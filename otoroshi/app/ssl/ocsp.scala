package otoroshi.ssl

import java.security.cert.X509Certificate
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.Source
import akka.util.ByteString
import otoroshi.env.Env
import org.bouncycastle.asn1.ocsp.OCSPObjectIdentifiers
import org.bouncycastle.asn1.x509.{CRLReason, Extension, Extensions, SubjectPublicKeyInfo}
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.ocsp.{
  BasicOCSPRespBuilder,
  CertificateID,
  CertificateStatus,
  OCSPReq,
  OCSPResp,
  OCSPRespBuilder,
  Req,
  RespID,
  RevokedStatus,
  UnknownStatus
}
import org.bouncycastle.operator.{ContentSigner, DefaultDigestAlgorithmIdentifierFinder, DigestCalculatorProvider}
import org.bouncycastle.operator.jcajce.{
  JcaContentSignerBuilder,
  JcaContentVerifierProviderBuilder,
  JcaDigestCalculatorProviderBuilder
}
import play.api.mvc.{RequestHeader, Result, Results}
import play.api.libs.json.Json
import otoroshi.ssl._
import org.joda.time.DateTime
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import play.api.Logger

import java.util.Date
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import otoroshi.utils.http.DN
import otoroshi.utils.syntax.implicits._
import otoroshi.ssl.SSLImplicits.EnhancedX509Certificate

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object CertParentHelper {

  private val logger = Logger("otoroshi-cert-helper")

  private val cache: Cache[BigInt, Boolean] = Scaffeine()
    .recordStats()
    .expireAfterWrite(2.minutes)
    .maximumSize(1000)
    .build()

  def fromOtoroshiRootCa(cert: X509Certificate, level: Int = 0): Boolean = {
    logger.debug(s"fromOtoroshiRootCa: ${cert.getSerialNumber} - ${DN(cert.getSubjectDN.getName)}")
    if (level > 100) {
      logger.error(s"failed to find origin for cert ${cert.getSerialNumber} - ${DN(cert.getSubjectDN.getName)}")
      cache.put(cert.getSerialNumber, false)
      false
    } else {
      cache.getIfPresent(cert.getSerialNumber) match {
        case Some(res) =>
          logger.debug("success from cache")
          res
        case None      => {
          logger.debug("cache miss")
          DynamicSSLEngineProvider.certificates.values.find(_.id == Cert.OtoroshiCA) match {
            case None         =>
              logger.debug("ca not found")
              false
            case Some(caCert) => {
              logger.debug("ca found")
              val ca = caCert.certificate.get
              if (ca.getSerialNumber == cert.getSerialNumber) {
                cache.put(cert.getSerialNumber, true)
                true
              } else {
                val issuerDn = DN(cert.getIssuerDN.getName)
                logger.debug(s"searching for $issuerDn")
                DynamicSSLEngineProvider.certificates.values.find(
                  _.certificate.exists(c => DN(c.getSubjectDN.getName).isEqualsTo(issuerDn))
                ) match {
                  case None                                                                           =>
                    logger.debug("issuer not found")
                    cache.put(cert.getSerialNumber, false)
                    false
                  case Some(issuer) if cert.getSerialNumber == issuer.certificate.get.getSerialNumber =>
                    logger.debug("not from otoroshi")
                    cache.put(cert.getSerialNumber, false)
                    false
                  case Some(issuer) if cert.getSerialNumber != issuer.certificate.get.getSerialNumber =>
                    logger.debug("found issuer")
                    fromOtoroshiRootCa(issuer.certificate.get, level + 1)
                }
              }
            }
          }
        }
      }
    }
  }
}

object OcspResponder {
  def apply(env: Env, ec: ExecutionContext): OcspResponder = new OcspResponder(env, ec)
}

// check for inspiration: https://github.com/wdawson/revoker/blob/master/src/main/java/wdawson/samples/revoker/resources/OCSPResponderResource.java
// for testing: https://akshayranganath.github.io/OCSP-Validation-With-Openssl/
// test command: openssl ocsp -issuer chain.pem -cert certificate.pem -text -url http://otoroshi-api.oto.tools:9999/.well-known/otoroshi/ocsp -header "HOST" "otoroshi-api.oto.tools"
// test command: openssl ocsp -issuer "ca.cer" -cert "*.oto.tools.cer" -text -urDynamicSSLEngineProviderl http://otoroshi-api.oto.tools:9999/.well-known/otoroshi/ocsp -header "HOST" "otoroshi-api.oto.tools"
class OcspResponder(env: Env, implicit val ec: ExecutionContext) {

  private implicit val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-certificates-ocsp")

  val rejectUnknown         = true
  val nextUpdateOffset: Int = env.configuration.getOptional[Int]("app.ocsp.caching.seconds").getOrElse(3600)

  def aia(id: String, req: RequestHeader)(implicit ec: ExecutionContext): Future[Result] = {
    import scala.util._
    // DynamicSSLEngineProvider.certificates.values.find(c => c.certificate.get.getSerialNumber.toString == id && c.exposed && CertParentHelper.fromOtoroshiRootCa(c.certificate.get)) match {
    DynamicSSLEngineProvider.certificates.values.find { c =>
      Try {
        c.certificate.get.getSerialNumber.toString == id && c.exposed && CertParentHelper.fromOtoroshiRootCa(
          c.certificate.get
        )
      } match {
        case Failure(e) =>
          e.printStackTrace()
          false
        case Success(v) => v
      }
    } match {
      case None       => Results.NotFound("").as("application/pkix-cert").future
      case Some(cert) => Results.Ok(cert.certificate.get.asPem).as("application/pkix-cert").future
    }
  }

  def respond(req: RequestHeader, body: Source[ByteString, _])(implicit ec: ExecutionContext): Future[Result] = {
    body.runFold(ByteString.empty)(_ ++ _).flatMap { bs =>
      if (bs.isEmpty) {
        FastFuture.successful(
          Results.BadRequest(Json.obj("error" -> "Missing body"))
        )
      } else {
        val ocspReq = new OCSPReq(bs.toArray)

        if (ocspReq.isSigned && !isSignatureValid(ocspReq)) {
          Results.BadRequest(new OCSPRespBuilder().build(OCSPRespBuilder.MALFORMED_REQUEST, null).getEncoded).future
        } else {
          manageRequest(ocspReq).map { response =>
            Results.Ok(response.getEncoded)
          } recover {
            case e: Throwable =>
              logger.error("error while checking certificate", e)
              Results.BadRequest(new OCSPRespBuilder().build(OCSPRespBuilder.INTERNAL_ERROR, null).getEncoded)
          }
        }
      }
    }
  }

  def manageRequest(ocspReq: OCSPReq): Future[OCSPResp] = {
    for {
      optRootCA         <- env.datastores.certificatesDataStore.findById(Cert.OtoroshiCA)(ec, env)
      optIntermediateCA <- env.datastores.certificatesDataStore.findById(Cert.OtoroshiIntermediateCA)(ec, env)
    } yield {
      (optRootCA, optIntermediateCA) match {
        case (Some(rootCA), Some(intermediateCA)) if intermediateCA.caFromChain.isDefined =>
          val issuingCertificate = new JcaX509CertificateHolder(intermediateCA.caFromChain.get)

          val contentSigner = new JcaContentSignerBuilder("SHA256withRSA")
            .setProvider("BC")
            .build(rootCA.cryptoKeyPair.getPrivate)

          val digestCalculatorProvider = new JcaDigestCalculatorProviderBuilder()
            .setProvider("BC")
            .build()

          val responderID = new RespID(
            SubjectPublicKeyInfo.getInstance(rootCA.cryptoKeyPair.getPublic.getEncoded),
            digestCalculatorProvider.get(new DefaultDigestAlgorithmIdentifierFinder().find("SHA-1"))
          )

          val responseBuilder = new BasicOCSPRespBuilder(responderID)
          val nonceExtension  = ocspReq.getExtension(OCSPObjectIdentifiers.id_pkix_ocsp_nonce)

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
            addResponse(responseBuilder, request, issuingCertificate, digestCalculatorProvider)
          }

          val signingCertificateChain: Array[X509CertificateHolder] =
            rootCA.certificatesChain.map(new JcaX509CertificateHolder(_))

          new OCSPRespBuilder()
            .build(
              OCSPRespBuilder.SUCCESSFUL,
              responseBuilder.build(contentSigner, signingCertificateChain, new Date())
            )

        case (None, None)                                                                 => throw new RuntimeException(s"Missing root CA, intermediate CA or intermediate CA chain")
      }
    }
  }

  def addResponse(
      responseBuilder: BasicOCSPRespBuilder,
      request: Req,
      issuingCertificate: JcaX509CertificateHolder,
      digestCalculatorProvider: DigestCalculatorProvider
  ): Unit = {
    val certificateID = request.getCertID

    var extensions        = new Extensions(Array[Extension]())
    val requestExtensions = request.getSingleRequestExtensions

    if (requestExtensions != null) {
      val nonceExtension = requestExtensions.getExtension(OCSPObjectIdentifiers.id_pkix_ocsp_nonce)
      if (nonceExtension != null) {
        extensions = new Extensions(nonceExtension)
      }
    }

    val matchesIssuer = certificateID.matchesIssuer(issuingCertificate, digestCalculatorProvider)

    if (!matchesIssuer) {
      responseBuilder.addResponse(
        certificateID,
        getUnknownStatus,
        DateTime.now().toDate,
        DateTime.now().plusSeconds(nextUpdateOffset).toDate,
        extensions
      )

    } else {
      val certificateStatus = DynamicSSLEngineProvider._ocspProjectionCertificates.get(certificateID.getSerialNumber)

      getOCSPCertificateStatus(certificateStatus).foreach(value => {
        responseBuilder.addResponse(request.getCertID, value._1, value._2.toDate, value._3.toDate, extensions)
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

  def getOCSPCertificateStatus(
      certData: Option[OCSPCertProjection]
  ): Option[(CertificateStatus, DateTime, DateTime)] = {
    certData match {
      case None       => None
      case Some(cert) =>
        var status = getUnknownStatus
        if (cert.revoked)
          status = new RevokedStatus(cert.from, getCRLReason(cert.revocationReason))
        else if (cert.expired)
          status = new RevokedStatus(cert.to, getCRLReason(cert.revocationReason))
        else if (cert.valid)
          status = CertificateStatus.GOOD

        val updateTime = DateTime.now()

        Some((status, updateTime, updateTime.plusSeconds(nextUpdateOffset)))
    }
  }

  def getCRLReason(revocationReason: String): Int = {
    revocationReason match {
      case "UNSPECIFIED"            => CRLReason.unspecified
      case "KEY_COMPROMISE"         => CRLReason.keyCompromise
      case "CA_COMPROMISE"          => CRLReason.cACompromise
      case "AFFILIATION_CHANGED"    => CRLReason.affiliationChanged
      case "SUPERSEDED"             => CRLReason.superseded
      case "CESSATION_OF_OPERATION" => CRLReason.cessationOfOperation
      case "CERTIFICATE_HOLD"       => CRLReason.certificateHold
      case "REMOVE_FROM_CRL"        => CRLReason.removeFromCRL
      case "PRIVILEGE_WITH_DRAWN"   => CRLReason.privilegeWithdrawn
      case "AA_COMPROMISE"          => CRLReason.aACompromise
      case _                        => CRLReason.unspecified
    }
  }

  def isSignatureValid(ocspReq: OCSPReq): Boolean =
    ocspReq.isSignatureValid(
      new JcaContentVerifierProviderBuilder()
        .setProvider("BC")
        .build(ocspReq.getCerts.head)
    )
}
