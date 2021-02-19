package otoroshi.ssl

import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.Source
import akka.util.ByteString
import env.Env
import org.bouncycastle.asn1.ocsp.OCSPObjectIdentifiers
import org.bouncycastle.asn1.x509.{CRLReason, Extension, Extensions, SubjectPublicKeyInfo}
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.ocsp.{BasicOCSPRespBuilder, CertificateID, CertificateStatus, OCSPReq, OCSPResp, OCSPRespBuilder, Req, RespID, RevokedStatus, UnknownStatus}
import org.bouncycastle.operator.{ContentSigner, DefaultDigestAlgorithmIdentifierFinder, DigestCalculatorProvider}
import org.bouncycastle.operator.jcajce.{JcaContentSignerBuilder, JcaContentVerifierProviderBuilder, JcaDigestCalculatorProviderBuilder}
import play.api.mvc.{RequestHeader, Result, Results}
import play.api.Logger
import play.api.libs.json.Json
import ssl.Cert
import org.joda.time.DateTime
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder

import java.util.Date
import scala.concurrent.{ExecutionContext, Future}

object OcspResponder {
  def apply(env: Env, ec: ExecutionContext): OcspResponder = new OcspResponder(env, ec)
}

case class OCSPCertificateStatusWrapper (
                                          certificateStatus: CertificateStatus,
                                          thisUpdate: DateTime,
                                          nextUpdate: DateTime)

// check for inspiration: https://github.com/wdawson/revoker/blob/master/src/main/java/wdawson/samples/revoker/resources/OCSPResponderResource.java
// for testing: https://akshayranganath.github.io/OCSP-Validation-With-Openssl/
// test command: openssl ocsp -issuer chain.pem -cert certificate.pem -text -url http://otoroshi-api.oto.tools:9999/.well-known/otoroshi/ocsp -header "HOST" "otoroshi-api.oto.tools"
// test command: openssl ocsp -issuer "ca.cer" -cert "*.oto.tools.cer" -text -url http://otoroshi-api.oto.tools:9999/.well-known/otoroshi/ocsp -header "HOST" "otoroshi-api.oto.tools"
class OcspResponder(env: Env, implicit val ec: ExecutionContext) {

  private val logger = Logger("otoroshi-ocsp-responder")
  private implicit val mat = env.otoroshiMaterializer

  val rejectUnknown = false

  var issuingCertificate: X509CertificateHolder = _
  var contentSigner: ContentSigner = _
  var digestCalculatorProvider: DigestCalculatorProvider = _
  var signingCertificateChain: Array[X509CertificateHolder] = _
  var responderID: RespID = _

  for {
    certificates <- env.datastores.certificatesDataStore.findAll()(ec, env)
    optRootCA <- FastFuture.successful(certificates.find(p => p.id == "otoroshi-root-ca"))
    optIntermediateCA <- FastFuture.successful(certificates.find(p => p.id == "otoroshi-intermediate-ca"))
  } yield {
    (optRootCA, optIntermediateCA) match {
      case (Some(rootCA), Some(intermediateCA)) if intermediateCA.caFromChain.isDefined =>
        issuingCertificate = new JcaX509CertificateHolder(intermediateCA.caFromChain.get) // TODO - faire mieux que le get sur un option

        contentSigner = new JcaContentSignerBuilder ("SHA256withRSA")
          .setProvider ("BC")
          .build (rootCA.cryptoKeyPair.getPrivate)

        digestCalculatorProvider = new JcaDigestCalculatorProviderBuilder ()
          .setProvider ("BC")
          .build ()

        signingCertificateChain = rootCA.certificatesChain.map (c => new JcaX509CertificateHolder (c) )

        responderID = new RespID (
          SubjectPublicKeyInfo.getInstance (rootCA.cryptoKeyPair.getPublic.getEncoded),
          digestCalculatorProvider.get (new DefaultDigestAlgorithmIdentifierFinder ().find ("SHA-1") )
        )

      case (None, None) => throw new RuntimeException(s"Missing root CA, intermediate CA or intermediate CA chain")
    }
  }

  def respond(req: RequestHeader, body: Source[ByteString, _])(implicit ec: ExecutionContext): Future[Result] = {
    body.runFold(ByteString.empty)(_ ++ _).map { bs =>
      val body = bs.utf8String
      if (body.isEmpty) {
        Results.BadRequest(Json.obj("error" -> "Missing body"))
      } else {
        val ocspReq = new OCSPReq(bs.toArray)

        if (ocspReq.isSigned && !isSignatureValid(ocspReq)) {
          Results.BadRequest(new OCSPRespBuilder().build(OCSPRespBuilder.MALFORMED_REQUEST, null).getEncoded)
        } else {
          val responseBuilder = new BasicOCSPRespBuilder(responderID)
          val nonceExtension = ocspReq.getExtension(OCSPObjectIdentifiers.id_pkix_ocsp_nonce)

          var responseExtensions = List[Extension]()
          if (nonceExtension != null)
            responseExtensions = responseExtensions :+ nonceExtension

          if (rejectUnknown)
            responseExtensions = responseExtensions :+ new Extension(
              OCSPObjectIdentifiers.id_pkix_ocsp_extended_revoke,
              false,
              Array[Byte]()
            )

          responseBuilder.setResponseExtensions(new Extensions(responseExtensions.toArray))

          // Check that each request is valid and put the appropriate response in the builder
          val requests = ocspReq.getRequestList
          requests.foreach { request =>
            addResponse(responseBuilder, request)
          }

          Results.Ok(buildAndSignResponse(responseBuilder).getEncoded)
        }
      }
    }
  }

  def addResponse(responseBuilder: BasicOCSPRespBuilder, request: Req): Any = {
    val certificateID = request.getCertID

    var extensions = new Extensions(Array[Extension]())
    val requestExtensions = request.getSingleRequestExtensions

    if (requestExtensions != null) {
      val nonceExtension = requestExtensions.getExtension(OCSPObjectIdentifiers.id_pkix_ocsp_nonce)
      if (nonceExtension != null) {
        extensions = new Extensions(nonceExtension)
      }
    }

    val matchesIssuer = certificateID.matchesIssuer(issuingCertificate, digestCalculatorProvider)

    if (!matchesIssuer) {
      addResponseForCertificateRequest(responseBuilder,
        certificateID,
        OCSPCertificateStatusWrapper(getUnknownStatus,
          DateTime.now(),
          DateTime.now().plusSeconds(10)), // TODO - voir ce qu'on met ici
        extensions)

    } else {
      env.datastores.certificatesDataStore.findAll()(ec, env)
        .map(certificates => {
          val certificateSummary = certificates
              .filter(f => f.serialNumberLng.isDefined)
              .find(f => f.serialNumberLng.get == certificateID.getSerialNumber)

          getOCSPCertificateStatus(certificateSummary).map(value => {
            addResponseForCertificateRequest(responseBuilder,
              request.getCertID,
              value,
              extensions)
          })
        })
    }
  }

  def getUnknownStatus: CertificateStatus = {
    if (rejectUnknown) {
      new RevokedStatus(DateTime.now().toDate, CRLReason.unspecified)
    } else {
      new UnknownStatus()
    }
  }

  def getOCSPCertificateStatus(certData: Option[Cert]): Option[OCSPCertificateStatusWrapper] = {
    certData match {
      case None => None
      case Some(cert) =>
        var status = getUnknownStatus
        if(cert.revoked)
            status = new RevokedStatus(cert.from.toDate, CRLReason.unspecified) // TODO - voir si on met un date de revocation et la raison dans les metadatas
        else if (cert.expired)
            status = new RevokedStatus(cert.to.toDate, CRLReason.superseded)
        else if (cert.isValid)
          status = CertificateStatus.GOOD

        val updateTime = DateTime.now()

        Some(OCSPCertificateStatusWrapper(status,
          updateTime,
          updateTime.plusSeconds(10) // TODO - voir ce qu'on met ici
        ))
    }
  }

  def addResponseForCertificateRequest(responseBuilder: BasicOCSPRespBuilder,
    certificateID: CertificateID, status: OCSPCertificateStatusWrapper, extensions: Extensions) {
    responseBuilder.addResponse(certificateID,
      status.certificateStatus,
      status.thisUpdate.toDate,
      status.nextUpdate.toDate,
      extensions)
  }

  def buildAndSignResponse(responseBuilder: BasicOCSPRespBuilder): OCSPResp = {
    val basicResponse = responseBuilder.build(contentSigner, signingCertificateChain, new Date())
    new OCSPRespBuilder().build(OCSPRespBuilder.SUCCESSFUL, basicResponse)
  }

  def isSignatureValid(ocspReq: OCSPReq): Boolean =
    ocspReq.isSignatureValid(
      new JcaContentVerifierProviderBuilder()
        .setProvider("BC")
        .build(ocspReq.getCerts.head)
    )
}
