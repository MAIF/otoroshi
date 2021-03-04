package otoroshi.ssl.pki

import java.io.{ByteArrayInputStream, StringReader}
import java.math.BigInteger
import java.security._
import java.security.cert.{CertificateFactory, X509Certificate}
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.util.ByteString
import otoroshi.env.Env
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509._
import org.bouncycastle.asn1.{
  x509,
  ASN1EncodableVector,
  ASN1Integer,
  ASN1ObjectIdentifier,
  ASN1Sequence,
  DERIA5String,
  DERSequence
}
import org.bouncycastle.cert.{X509ExtensionUtils, X509v3CertificateBuilder}
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.operator.bc.BcRSAContentSignerBuilder
import org.bouncycastle.operator.{DefaultDigestAlgorithmIdentifierFinder, DefaultSignatureAlgorithmIdentifierFinder}
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import org.bouncycastle.util.io.pem.PemReader
import otoroshi.ssl.pki.models._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import otoroshi.security.IdGenerator
import otoroshi.ssl.Cert
import otoroshi.ssl.SSLImplicits._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import org.bouncycastle.jce.ECNamedCurveTable
import org.jose4j.keys.X509Util
import org.bouncycastle.operator
import otoroshi.ssl.CertParentHelper
import otoroshi.utils.http.DN

object models {

  case class GenKeyPairQuery(algo: String = "rsa", size: Int = 2048) {
    def json: JsValue = GenKeyPairQuery.format.writes(this)
  }

  object GenKeyPairQuery {
    private val format                                           = Json.format[GenKeyPairQuery]
    def fromJson(json: JsValue): Either[String, GenKeyPairQuery] =
      format.reads(json).asEither match {
        case Left(errs) => Left("error while parsing json")
        case Right(q)   => Right(q)
      }
  }

  case class GenCsrQuery(
      hosts: Seq[String] = Seq.empty,
      key: GenKeyPairQuery = GenKeyPairQuery(),
      name: Map[String, String] = Map.empty,
      subject: Option[String] = None,
      client: Boolean = false,
      ca: Boolean = false,
      includeAIA: Boolean = false,
      duration: FiniteDuration = 365.days,
      signatureAlg: String = "SHA256WithRSAEncryption",
      digestAlg: String = "SHA-256",
      existingKeyPair: Option[KeyPair] = None,
      existingSerialNumber: Option[Long] = None
  )                  {
    def subj: String               = subject.getOrElse(name.map(t => s"${t._1}=${t._2}").mkString(","))
    def json: JsValue              = GenCsrQuery.format.writes(this)
    def hasDnsNameOrCName: Boolean =
      hosts.nonEmpty || subject.exists(_.toLowerCase().contains("cn=")) || name.contains("CN") || name.contains("cn")
  }
  object GenCsrQuery {
    private val format                                       = new Format[GenCsrQuery] {
      override def reads(json: JsValue): JsResult[GenCsrQuery] =
        Try {
          GenCsrQuery(
            hosts = (json \ "hosts").asOpt[Seq[String]].getOrElse(Seq.empty),
            key = (json \ "key")
              .asOpt[JsValue]
              .flatMap(v => GenKeyPairQuery.fromJson(v).toOption)
              .orElse(
                GenKeyPairQuery
                  .fromJson(
                    Json.obj(
                      "algo" -> (json \ "keyType").as[JsValue],
                      "size" -> (json \ "keySize").as[JsValue]
                    )
                  )
                  .toOption
              )
              .map(q => q.copy(algo = q.algo.toLowerCase()))
              .getOrElse(GenKeyPairQuery()),
            name = (json \ "name").asOpt[Map[String, String]].getOrElse(Map.empty),
            subject = (json \ "subject").asOpt[String],
            client = (json \ "client").asOpt[Boolean].getOrElse(false),
            ca = (json \ "ca").asOpt[Boolean].getOrElse(false),
            includeAIA = (json \ "includeAIA").asOpt[Boolean].getOrElse(false),
            duration = (json \ "duration").asOpt[Long].map(_.millis).getOrElse(365.days),
            signatureAlg = (json \ "signatureAlg").asOpt[String].getOrElse("SHA256WithRSAEncryption"),
            digestAlg = (json \ "digestAlg").asOpt[String].getOrElse("SHA-256")
          )
        } match {
          case Failure(e) => JsError(e.getMessage)
          case Success(s) => JsSuccess(s)
        }

      override def writes(o: GenCsrQuery): JsValue =
        Json.obj(
          "hosts"        -> o.hosts,
          "key"          -> o.key.json,
          "name"         -> o.name,
          "subject"      -> o.subject.map(JsString.apply).getOrElse(JsNull).as[JsValue],
          "client"       -> o.client,
          "ca"           -> o.ca,
          "duration"     -> o.duration.toMillis,
          "signatureAlg" -> o.signatureAlg,
          "digestAlg"    -> o.digestAlg
        )
    }
    def fromJson(json: JsValue): Either[String, GenCsrQuery] =
      format.reads(json).asEither match {
        case Left(errs) => Left("error while parsing json")
        case Right(q)   => Right(q)
      }
  }

  case class GenKeyPairResponse(publicKey: PublicKey, privateKey: PrivateKey) {
    def json: JsValue    =
      Json.obj(
        "publicKey"  -> publicKey.asPem,
        "privateKey" -> privateKey.asPem
      )
    def chain: String    = s"${publicKey.asPem}\n${privateKey.asPem}"
    def keyPair: KeyPair = new KeyPair(publicKey, privateKey)
  }

  case class GenCsrResponse(csr: PKCS10CertificationRequest, publicKey: PublicKey, privateKey: PrivateKey) {
    def json: JsValue =
      Json.obj(
        "csr"        -> csr.asPem,
        "publicKey"  -> publicKey.asPem,
        "privateKey" -> privateKey.asPem
      )
    def chain: String = s"${csr.asPem}\n${privateKey.asPem}\n${publicKey.asPem}"
  }

  case class GenCertResponse(
      serial: java.math.BigInteger,
      cert: X509Certificate,
      csr: PKCS10CertificationRequest,
      csrQuery: Option[GenCsrQuery],
      key: PrivateKey,
      ca: X509Certificate,
      caChain: Seq[X509Certificate]
  ) {
    def json: JsValue        =
      Json.obj(
        "serial" -> serial,
        "cert"   -> cert.asPem,
        "csr"    -> csr.asPem,
        "key"    -> key.asPem,
        "ca"     -> ca.asPem,
        "query"  -> csrQuery.map(_.json).getOrElse(JsNull).as[JsValue]
      )
    def chain: String        = s"${key.asPem}\n${cert.asPem}\n${ca.asPem}"
    def chainWithCsr: String = s"${key.asPem}\n${cert.asPem}\n${ca.asPem}\n${csr.asPem}"
    def keyPair: KeyPair     = new KeyPair(cert.getPublicKey, key)
    def toCert: Cert = {
      Cert(
        id = IdGenerator.token(32),
        name = "none",
        description = "none",
        chain = if (cert == ca) {
          cert.asPem
        } else {
          cert.asPem + "\n" + ca.asPem + "\n" + caChain.map(_.asPem).mkString("\n")
        },
        privateKey = keyPair.getPrivate.asPem,
        caRef = None,
        entityMetadata = Map(
          "csr" -> csrQuery.map(_.json.stringify).getOrElse("--")
        ),
        exposed = false,
        revoked = false
      ).enrich()
    }
  }

  case class SignCertResponse(cert: X509Certificate, csr: PKCS10CertificationRequest, ca: Option[X509Certificate]) {
    def json: JsValue        =
      Json.obj(
        "cert" -> cert.asPem,
        "csr"  -> csr.asPem
      ) ++ ca.map(c => Json.obj("ca" -> c.asPem)).getOrElse(Json.obj())
    def chain: String        = s"${cert.asPem}\n${ca.map(_.asPem + "\n").getOrElse("")}"
    def chainWithCsr: String = s"${cert.asPem}\n${ca.map(_.asPem + "\n").getOrElse("")}${csr.asPem}"
  }
}

trait Pki {

  // genkeypair          generate a public key / private key pair
  def genKeyPair(query: ByteString)(implicit ec: ExecutionContext): Future[Either[String, GenKeyPairResponse]] =
    GenKeyPairQuery.fromJson(Json.parse(query.utf8String)) match {
      case Left(err) => Left(err).future
      case Right(q)  => genKeyPair(q)
    }

  // gencsr           generate a private key and a certificate request
  def genCsr(query: ByteString, caCert: Option[X509Certificate])(implicit
      ec: ExecutionContext
  ): Future[Either[String, GenCsrResponse]] =
    GenCsrQuery.fromJson(Json.parse(query.utf8String)) match {
      case Left(err) => Left(err).future
      case Right(q)  => genCsr(q, caCert)
    }

  // gencert          generate a private key and a certificate
  def genCert(query: ByteString, caCert: X509Certificate, caChain: Seq[X509Certificate], caKey: PrivateKey)(implicit
      ec: ExecutionContext
  ): Future[Either[String, GenCertResponse]] =
    GenCsrQuery.fromJson(Json.parse(query.utf8String)) match {
      case Left(err) => Left(err).future
      case Right(q)  => genCert(q, caCert, caChain, caKey)
    }

  // sign             signs a certificate
  def signCert(
      csr: ByteString,
      validity: FiniteDuration,
      caCert: X509Certificate,
      caKey: PrivateKey,
      existingSerialNumber: Option[Long]
  )(implicit ec: ExecutionContext): Future[Either[String, SignCertResponse]] = {
    val pemReader = new PemReader(new StringReader(csr.utf8String))
    val pemObject = pemReader.readPemObject()
    val _csr      = new PKCS10CertificationRequest(pemObject.getContent)
    pemReader.close()
    signCert(_csr, validity, caCert, caKey, existingSerialNumber, None)
  }

  // selfsign         generates a self-signed certificate
  def genSelfSignedCert(query: ByteString)(implicit ec: ExecutionContext): Future[Either[String, GenCertResponse]] =
    GenCsrQuery.fromJson(Json.parse(query.utf8String)) match {
      case Left(err) => Left(err).future
      case Right(q)  => genSelfSignedCert(q)
    }

  def genSelfSignedCA(
      query: ByteString
  )(implicit ec: ExecutionContext, mat: Materializer): Future[Either[String, GenCertResponse]] =
    GenCsrQuery.fromJson(Json.parse(query.utf8String)) match {
      case Left(err) => Left(err).future
      case Right(q)  => genSelfSignedCA(q)
    }

  def genSubCA(query: ByteString, caCert: X509Certificate, caChain: Seq[X509Certificate], caKey: PrivateKey)(implicit
      ec: ExecutionContext
  ): Future[Either[String, GenCertResponse]] =
    GenCsrQuery.fromJson(Json.parse(query.utf8String)) match {
      case Left(err) => Left(err).future
      case Right(q)  => genSubCA(q, caCert, caChain, caKey)
    }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // actual implementation
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  // genkeypair          generate a public key / private key pair
  def genKeyPair(query: GenKeyPairQuery)(implicit ec: ExecutionContext): Future[Either[String, GenKeyPairResponse]]

  // gencsr           generate a private key and a certificate request
  def genCsr(query: GenCsrQuery, caCert: Option[X509Certificate])(implicit
      ec: ExecutionContext
  ): Future[Either[String, GenCsrResponse]]

  // gencert          generate a private key and a certificate
  def genCert(query: GenCsrQuery, caCert: X509Certificate, caChain: Seq[X509Certificate], caKey: PrivateKey)(implicit
      ec: ExecutionContext
  ): Future[Either[String, GenCertResponse]]

  // sign             signs a certificate
  def signCert(
      csr: PKCS10CertificationRequest,
      validity: FiniteDuration,
      caCert: X509Certificate,
      caKey: PrivateKey,
      existingSerialNumber: Option[Long],
      originalQuery: Option[GenCsrQuery]
  )(implicit ec: ExecutionContext): Future[Either[String, SignCertResponse]]

  def genSelfSignedCA(query: GenCsrQuery)(implicit ec: ExecutionContext): Future[Either[String, GenCertResponse]]

  def genSelfSignedCert(query: GenCsrQuery)(implicit ec: ExecutionContext): Future[Either[String, GenCertResponse]]

  def genSubCA(query: GenCsrQuery, caCert: X509Certificate, caChain: Seq[X509Certificate], caKey: PrivateKey)(implicit
      ec: ExecutionContext
  ): Future[Either[String, GenCertResponse]]
}

class BouncyCastlePki(generator: IdGenerator, env: Env) extends Pki {

  private val random = new SecureRandom()

  private def generateSerial(): Try[java.math.BigInteger] = {
    Try(new java.math.BigInteger(64, random))
  }

  // genkeypair          generate a public key / private key pair
  override def genKeyPair(
      query: GenKeyPairQuery
  )(implicit ec: ExecutionContext): Future[Either[String, GenKeyPairResponse]] = {
    Try {
      if (query.algo == "ecdsa") {
        val curve            = query.size match {
          case 256 => "secp256r1"
          case 384 => "secp384r1"
          case 521 => "secp521r1"
          case _   => "secp256r1"
        }
        val ecSpec           = ECNamedCurveTable.getParameterSpec(curve);
        val keyPairGenerator = KeyPairGenerator.getInstance(query.algo.toUpperCase(), "BC")
        keyPairGenerator.initialize(ecSpec, new SecureRandom())
        keyPairGenerator.generateKeyPair()
      } else {
        val keyPairGenerator = KeyPairGenerator.getInstance(query.algo.toUpperCase(), "BC")
        keyPairGenerator.initialize(query.size, new SecureRandom())
        keyPairGenerator.generateKeyPair()
      }
    } match {
      case Failure(e)       => Left(e.getMessage).future
      case Success(keyPair) => Right(GenKeyPairResponse(keyPair.getPublic, keyPair.getPrivate)).future
    }
  }

  // gencsr           generate a private key and a certificate request
  override def genCsr(query: GenCsrQuery, caCert: Option[X509Certificate])(implicit
      ec: ExecutionContext
  ): Future[Either[String, GenCsrResponse]] = {
    genKeyPair(query.key).flatMap {
      case Left(e)     => Left(e).future
      case Right(_kpr) => {
        val kpr = query.existingKeyPair.map(v => GenKeyPairResponse(v.getPublic, v.getPrivate)).getOrElse(_kpr)
        Try {
          val privateKey          = PrivateKeyFactory.createKey(kpr.privateKey.getEncoded)
          val signatureAlgorithm  = new DefaultSignatureAlgorithmIdentifierFinder().find(query.signatureAlg)
          val digestAlgorithm     = new DefaultDigestAlgorithmIdentifierFinder().find(query.digestAlg)
          val signer              = contentSigner(signatureAlgorithm, digestAlgorithm, privateKey)
          val names               = new GeneralNames(query.hosts.map(name => new GeneralName(GeneralName.dNSName, name)).toArray)
          val csrBuilder          = new JcaPKCS10CertificationRequestBuilder(new X500Name(query.subj), kpr.publicKey)
          val extensionsGenerator = new ExtensionsGenerator
          extensionsGenerator.addExtension(Extension.basicConstraints, true, new BasicConstraints(query.ca))
          if (!query.ca) {
            extensionsGenerator.addExtension(
              Extension.keyUsage,
              true,
              new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment)
            )
            // extensionsGenerator.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(Seq(KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth).toArray))
            if (query.client && query.hasDnsNameOrCName) {
              extensionsGenerator.addExtension(
                Extension.extendedKeyUsage,
                false,
                new ExtendedKeyUsage(Seq(KeyPurposeId.id_kp_clientAuth, KeyPurposeId.id_kp_serverAuth).toArray)
              )
            } else if (query.client) {
              extensionsGenerator.addExtension(
                Extension.extendedKeyUsage,
                false,
                new ExtendedKeyUsage(Seq(KeyPurposeId.id_kp_clientAuth, KeyPurposeId.id_kp_serverAuth).toArray)
              )
            } else {
              extensionsGenerator.addExtension(
                Extension.extendedKeyUsage,
                false,
                new ExtendedKeyUsage(Seq(KeyPurposeId.id_kp_serverAuth).toArray)
              )
            }
            caCert.foreach(ca =>
              extensionsGenerator.addExtension(
                Extension.authorityKeyIdentifier,
                false,
                new JcaX509ExtensionUtils().createAuthorityKeyIdentifier(ca.getPublicKey).getEncoded
                // new AuthorityKeyIdentifier(new ASN1Integer(ca.getSerialNumber).getEncoded)
              )
            // TODO: subjectkeyidentifier ????
            )
            extensionsGenerator.addExtension(Extension.subjectAlternativeName, false, names)
          } else {
            extensionsGenerator.addExtension(
              Extension.keyUsage,
              true,
              new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign)
            )
          }
          csrBuilder.addAttribute(
            PKCSObjectIdentifiers.pkcs_9_at_extensionRequest /* x509Certificate */,
            extensionsGenerator.generate
          )
          csrBuilder.build(signer)
        } match {
          case Failure(e)   => Left(e.getMessage).future
          case Success(csr) => Right(GenCsrResponse(csr, kpr.publicKey, kpr.privateKey)).future
        }
      }
    }
  }

  import org.bouncycastle.operator._
  import org.bouncycastle.operator.bc._
  import org.bouncycastle.crypto.params._

  def contentSigner(
      signatureAlg: AlgorithmIdentifier,
      digestAlg: AlgorithmIdentifier,
      pkey: AsymmetricKeyParameter
  ): ContentSigner = {
    val asn1Oid = signatureAlg.getAlgorithm.toString
    val ec      = asn1Oid == "1.2.840.10045.4.3.2" || asn1Oid == "1.2.840.10045.4.3.3" || asn1Oid == "1.2.840.10045.4.3.4"
    if (!ec) {
      new BcRSAContentSignerBuilder(signatureAlg, digestAlg).build(pkey)
    } else {
      new BcECContentSignerBuilder(signatureAlg, digestAlg).build(pkey)
    }
  }

  // sign             signs a certificate
  override def signCert(
      csr: PKCS10CertificationRequest,
      validity: FiniteDuration,
      caCert: X509Certificate,
      caKey: PrivateKey,
      existingSerialNumber: Option[Long],
      originalQuery: Option[GenCsrQuery]
  )(implicit ec: ExecutionContext): Future[Either[String, SignCertResponse]] = {
    //generator.nextIdSafe().map { _serial =>
    generateSerial().map { _serial =>
      // val __issuer = new X500Name(caCert.getSubjectX500Principal.getName)
      val issuer = X500Name.getInstance(ASN1Sequence.getInstance(caCert.getSubjectX500Principal.getEncoded))
      val serial =
        existingSerialNumber
          .map(java.math.BigInteger.valueOf)
          .getOrElse(_serial) // new java.math.BigInteger(32, new SecureRandom)
      val from    = new java.util.Date
      val to      = new java.util.Date(System.currentTimeMillis + validity.toMillis)
      val certgen = new X509v3CertificateBuilder(issuer, serial, from, to, csr.getSubject, csr.getSubjectPublicKeyInfo)
      csr.getAttributes.foreach(attr => {
        attr.getAttributeValues.collect { case exts: Extensions =>
          exts.getExtensionOIDs.map(id => exts.getExtension(id)).filter(_ != null).foreach { ext =>
            certgen.addExtension(ext.getExtnId, ext.isCritical, ext.getParsedValue)
          }
        }
      })

      if (originalQuery.exists(_.includeAIA) && CertParentHelper.fromOtoroshiRootCa(caCert)) {
        val access = buildAuthorityInfoAccess(serial)
        certgen.addExtension(access._1, access._2, access._3)
      }

      val digestAlgorithm                        = originalQuery
        .map(c => new DefaultDigestAlgorithmIdentifierFinder().find(c.digestAlg))
        .getOrElse(new DefaultDigestAlgorithmIdentifierFinder().find("SHA-256"))
      val parentSignatureAlg                     = new DefaultSignatureAlgorithmIdentifierFinder().find(caCert.getSigAlgName)
      val signer                                 = contentSigner(
        parentSignatureAlg /*csr.getSignatureAlg*/,
        digestAlgorithm,
        PrivateKeyFactory.createKey(caKey.getEncoded)
      )
      val holder                                 = certgen.build(signer)
      val certencoded                            = holder.toASN1Structure.getEncoded
      val certificateFactory: CertificateFactory = CertificateFactory.getInstance("X.509")
      val cert                                   = certificateFactory
        .generateCertificate(new ByteArrayInputStream(certencoded))
        .asInstanceOf[X509Certificate]
      cert
    } match {
      case Failure(err)  => Left(err.getMessage).future
      case Success(cert) => Right(SignCertResponse(cert, csr, Some(caCert))).future
    }
  }

  private def buildAuthorityInfoAccess(certId: BigInteger): (ASN1ObjectIdentifier, Boolean, DERSequence) = {

    val caIssuers1 = new AccessDescription(
      AccessDescription.id_ad_caIssuers,
      new GeneralName(
        GeneralName.uniformResourceIdentifier,
        new DERIA5String(
          s"http://${env.backOfficeHost}${env.exposedHttpPort}/.well-known/otoroshi/certificates/$certId"
        )
      )
    )

    val caIssuers2 = new AccessDescription(
      AccessDescription.id_ad_caIssuers,
      new GeneralName(
        GeneralName.uniformResourceIdentifier,
        new DERIA5String(
          s"http://${env.adminApiExposedHost}${env.exposedHttpPort}/.well-known/otoroshi/certificates/$certId"
        )
      )
    )

    val ocsp1 = new AccessDescription(
      AccessDescription.id_ad_ocsp,
      new GeneralName(
        GeneralName.uniformResourceIdentifier,
        new DERIA5String(s"http://${env.backOfficeHost}${env.exposedHttpPort}/.well-known/otoroshi/ocsp")
      )
    )

    val ocsp2 = new AccessDescription(
      AccessDescription.id_ad_ocsp,
      new GeneralName(
        GeneralName.uniformResourceIdentifier,
        new DERIA5String(s"http://${env.adminApiExposedHost}${env.exposedHttpPort}/.well-known/otoroshi/ocsp")
      )
    )

    val aia_ASN = new ASN1EncodableVector()
    //aia_ASN.add(caIssuers1)
    aia_ASN.add(caIssuers2)
    //aia_ASN.add(ocsp1)
    aia_ASN.add(ocsp2)

    (Extension.authorityInfoAccess, false, new DERSequence(aia_ASN))
  }

  // gencert          generate a private key and a certificate
  override def genCert(query: GenCsrQuery, caCert: X509Certificate, caChain: Seq[X509Certificate], caKey: PrivateKey)(
      implicit ec: ExecutionContext
  ): Future[Either[String, GenCertResponse]] = {
    (for {
      csr  <- genCsr(query, Some(caCert))
      cert <- csr match {
                case Left(err)   => FastFuture.successful(Left(err))
                case Right(_csr) =>
                  signCert(_csr.csr, query.duration, caCert, caKey, query.existingSerialNumber, query.some)
              }
    } yield cert match {
      case Left(err)   => Left(err)
      case Right(resp) =>
        Right(
          GenCertResponse(
            resp.cert.getSerialNumber,
            resp.cert,
            resp.csr,
            query.some,
            csr.right.get.privateKey,
            caCert,
            caChain
          )
        )
    }).transformWith {
      case Failure(e)               => Left(e.getMessage).future
      case Success(Left(e))         => Left(e).future
      case Success(Right(response)) => Right(response).future
    }
  }

  override def genSelfSignedCert(
      query: GenCsrQuery
  )(implicit ec: ExecutionContext): Future[Either[String, GenCertResponse]] = {
    if (query.ca) {
      genSelfSignedCA(query)
    } else {
      genKeyPair(query.key).flatMap {
        case Left(e)     => Left(e).future
        case Right(_kpr) =>
          val kpr = query.existingKeyPair.map(v => GenKeyPairResponse(v.getPublic, v.getPrivate)).getOrElse(_kpr)
          // generator.nextIdSafe().map { _serial =>
          generateSerial().map { _serial =>
            val serial =
              query.existingSerialNumber
                .map(java.math.BigInteger.valueOf)
                .getOrElse(_serial) // new java.math.BigInteger(32, new SecureRandom)
            val privateKey          = PrivateKeyFactory.createKey(kpr.privateKey.getEncoded)
            val signatureAlgorithm  = new DefaultSignatureAlgorithmIdentifierFinder().find(query.signatureAlg)
            val digestAlgorithm     = new DefaultDigestAlgorithmIdentifierFinder().find(query.digestAlg)
            val signer              = contentSigner(signatureAlgorithm, digestAlgorithm, privateKey)
            val names               = new GeneralNames(query.hosts.map(name => new GeneralName(GeneralName.dNSName, name)).toArray)
            val csrBuilder          = new JcaPKCS10CertificationRequestBuilder(new X500Name(query.subj), kpr.publicKey)
            //val x500Name            = X500Name.getInstance(ASN1Sequence.getInstance(new X500Principal(query.subj).getEncoded))
            //val csrBuilder          = new JcaPKCS10CertificationRequestBuilder(x500Name, kpr.publicKey)
            val extensionsGenerator = new ExtensionsGenerator
            extensionsGenerator.addExtension(Extension.basicConstraints, true, new BasicConstraints(query.ca))
            if (!query.ca) {
              extensionsGenerator.addExtension(
                Extension.keyUsage,
                true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment)
              )
              extensionsGenerator.addExtension(
                Extension.subjectKeyIdentifier,
                false,
                new JcaX509ExtensionUtils().createSubjectKeyIdentifier(kpr.publicKey).getEncoded
                // new SubjectKeyIdentifier(new ASN1Integer(serial).getEncoded)
              )
              // extensionsGenerator.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(Seq(KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth).toArray))
              if (query.client && query.hasDnsNameOrCName) {
                extensionsGenerator.addExtension(
                  Extension.extendedKeyUsage,
                  false,
                  new ExtendedKeyUsage(Seq(KeyPurposeId.id_kp_clientAuth, KeyPurposeId.id_kp_serverAuth).toArray)
                )
              } else if (query.client) {
                extensionsGenerator.addExtension(
                  Extension.extendedKeyUsage,
                  false,
                  new ExtendedKeyUsage(Seq(KeyPurposeId.id_kp_clientAuth, KeyPurposeId.id_kp_serverAuth).toArray)
                )
              } else {
                extensionsGenerator.addExtension(
                  Extension.extendedKeyUsage,
                  false,
                  new ExtendedKeyUsage(Seq(KeyPurposeId.id_kp_serverAuth).toArray)
                )
              }
              extensionsGenerator.addExtension(Extension.subjectAlternativeName, false, names)
            } else {
              extensionsGenerator.addExtension(
                Extension.keyUsage,
                true,
                new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign)
              )
            }
            csrBuilder.addAttribute(
              PKCSObjectIdentifiers.pkcs_9_at_extensionRequest /* x509Certificate */,
              extensionsGenerator.generate
            )
            val csr                 = csrBuilder.build(signer)
            val issuer              = csr.getSubject
            val from                = new java.util.Date
            val to                  = new java.util.Date(System.currentTimeMillis + query.duration.toMillis)
            val certgen             =
              new X509v3CertificateBuilder(issuer, serial, from, to, csr.getSubject, csr.getSubjectPublicKeyInfo)
            csr.getAttributes.foreach(attr => {
              attr.getAttributeValues.collect {
                case exts: Extensions => {
                  exts.getExtensionOIDs.map(id => exts.getExtension(id)).filter(_ != null).foreach { ext =>
                    certgen.addExtension(ext.getExtnId, ext.isCritical, ext.getParsedValue)
                  }
                }
              }
            })

            //val access = buildAuthorityInfoAccess()
            //certgen.addExtension(access._1, access._2, access._3)

            // val signatureAlgorithm = new DefaultSignatureAlgorithmIdentifierFinder().find("SHA256WithRSAEncryption")
            val holder                                 = certgen.build(signer)
            val certencoded                            = holder.toASN1Structure.getEncoded
            val certificateFactory: CertificateFactory = CertificateFactory.getInstance("X.509")
            val cert                                   = certificateFactory
              .generateCertificate(new ByteArrayInputStream(certencoded))
              .asInstanceOf[X509Certificate]
            Right(GenCertResponse(_serial, cert, csr, query.some, kpr.privateKey, cert, Seq.empty))
          } match {
            case Failure(e)      => Left(e.getMessage).future
            case Success(either) => either.future
          }
      }
    }
  }

  override def genSelfSignedCA(
      query: GenCsrQuery
  )(implicit ec: ExecutionContext): Future[Either[String, GenCertResponse]] = {
    genKeyPair(query.key).flatMap {
      case Left(e)     => Left(e).future
      case Right(_kpr) =>
        val kpr = query.existingKeyPair.map(v => GenKeyPairResponse(v.getPublic, v.getPrivate)).getOrElse(_kpr)
        //generator.nextIdSafe().map { _serial =>
        generateSerial().map { _serial =>
          val serial =
            query.existingSerialNumber
              .map(java.math.BigInteger.valueOf)
              .getOrElse(_serial) // new java.math.BigInteger(32, new SecureRandom)
          val privateKey          = PrivateKeyFactory.createKey(kpr.privateKey.getEncoded)
          val signatureAlgorithm  = new DefaultSignatureAlgorithmIdentifierFinder().find(query.signatureAlg)
          val digestAlgorithm     = new DefaultDigestAlgorithmIdentifierFinder().find(query.digestAlg)
          val signer              = contentSigner(signatureAlgorithm, digestAlgorithm, privateKey)
          val names               = new GeneralNames(query.hosts.map(name => new GeneralName(GeneralName.dNSName, name)).toArray)
          val csrBuilder          = new JcaPKCS10CertificationRequestBuilder(new X500Name(query.subj), kpr.publicKey)
          //val x500Name            = X500Name.getInstance(ASN1Sequence.getInstance(new X500Principal(query.subj).getEncoded))
          //val csrBuilder          = new JcaPKCS10CertificationRequestBuilder(x500Name, kpr.publicKey)
          val extensionsGenerator = new ExtensionsGenerator
          extensionsGenerator.addExtension(Extension.basicConstraints, true, new BasicConstraints(true))
          extensionsGenerator.addExtension(
            Extension.keyUsage,
            true,
            new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign)
          )
          extensionsGenerator.addExtension(
            Extension.authorityKeyIdentifier,
            false,
            new JcaX509ExtensionUtils().createAuthorityKeyIdentifier(kpr.publicKey).getEncoded
            // new AuthorityKeyIdentifier(new ASN1Integer(serial).getEncoded)
          )
          extensionsGenerator.addExtension(
            Extension.subjectKeyIdentifier,
            false,
            new JcaX509ExtensionUtils().createSubjectKeyIdentifier(kpr.publicKey).getEncoded
            // new SubjectKeyIdentifier(new ASN1Integer(serial).getEncoded)
          )
          csrBuilder.addAttribute(
            PKCSObjectIdentifiers.pkcs_9_at_extensionRequest /* x509Certificate */,
            extensionsGenerator.generate
          )
          val csr                 = csrBuilder.build(signer)
          val issuer              = csr.getSubject
          val from                = new java.util.Date
          val to                  = new java.util.Date(System.currentTimeMillis + query.duration.toMillis)
          val certgen             =
            new X509v3CertificateBuilder(issuer, serial, from, to, csr.getSubject, csr.getSubjectPublicKeyInfo)
          csr.getAttributes.foreach(attr => {
            attr.getAttributeValues.collect {
              case exts: Extensions => {
                exts.getExtensionOIDs.map(id => exts.getExtension(id)).filter(_ != null).foreach { ext =>
                  certgen.addExtension(ext.getExtnId, ext.isCritical, ext.getParsedValue)
                }
              }
            }
          })

          if (query.includeAIA && DN(Cert.OtoroshiCaDN).isEqualsTo(DN(csr.getSubject.toString))) {
            val access = buildAuthorityInfoAccess(serial)
            certgen.addExtension(access._1, access._2, access._3)
          }

          val holder                                 = certgen.build(signer)
          val certencoded                            = holder.toASN1Structure.getEncoded
          val certificateFactory: CertificateFactory = CertificateFactory.getInstance("X.509")
          val cert                                   = certificateFactory
            .generateCertificate(new ByteArrayInputStream(certencoded))
            .asInstanceOf[X509Certificate]
          Right(GenCertResponse(_serial, cert, csr, query.some, kpr.privateKey, cert, Seq.empty))
        } match {
          case Failure(e)      => Left(e.getMessage).future
          case Success(either) => either.future
        }
    }
  }

  override def genSubCA(query: GenCsrQuery, caCert: X509Certificate, caChain: Seq[X509Certificate], caKey: PrivateKey)(
      implicit ec: ExecutionContext
  ): Future[Either[String, GenCertResponse]] = {
    genKeyPair(query.key).flatMap {
      case Left(e)     => Left(e).future
      case Right(_kpr) =>
        val kpr = query.existingKeyPair.map(v => GenKeyPairResponse(v.getPublic, v.getPrivate)).getOrElse(_kpr)
        // generator.nextIdSafe().map { _serial =>
        generateSerial().map { _serial =>
          val serial =
            query.existingSerialNumber
              .map(java.math.BigInteger.valueOf)
              .getOrElse(_serial) // new java.math.BigInteger(32, new SecureRandom)
          val privateKey          = PrivateKeyFactory.createKey(kpr.privateKey.getEncoded)
          val signatureAlgorithm  = new DefaultSignatureAlgorithmIdentifierFinder().find(query.signatureAlg)
          val digestAlgorithm     = new DefaultDigestAlgorithmIdentifierFinder().find(query.digestAlg)
          val signer              = contentSigner(signatureAlgorithm, digestAlgorithm, PrivateKeyFactory.createKey(caKey.getEncoded))
          val names               = new GeneralNames(query.hosts.map(name => new GeneralName(GeneralName.dNSName, name)).toArray)
          val csrBuilder          = new JcaPKCS10CertificationRequestBuilder(new X500Name(query.subj), kpr.publicKey)
          //val x500Name            = X500Name.getInstance(ASN1Sequence.getInstance(new X500Principal(query.subj).getEncoded))
          //val csrBuilder          = new JcaPKCS10CertificationRequestBuilder(x500Name, kpr.publicKey)
          val extensionsGenerator = new ExtensionsGenerator
          extensionsGenerator.addExtension(Extension.basicConstraints, true, new BasicConstraints(0))
          extensionsGenerator.addExtension(
            Extension.keyUsage,
            true,
            new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign | KeyUsage.digitalSignature)
          )
          extensionsGenerator.addExtension(
            Extension.authorityKeyIdentifier,
            false,
            new JcaX509ExtensionUtils().createAuthorityKeyIdentifier(caCert.getPublicKey).getEncoded
            // new AuthorityKeyIdentifier(new ASN1Integer(caCert.getSerialNumber).getEncoded)
          )
          extensionsGenerator.addExtension(
            Extension.subjectKeyIdentifier,
            false,
            new JcaX509ExtensionUtils().createSubjectKeyIdentifier(kpr.publicKey).getEncoded
            // new SubjectKeyIdentifier(new ASN1Integer(serial).getEncoded)
          )
          csrBuilder.addAttribute(
            PKCSObjectIdentifiers.pkcs_9_at_extensionRequest /* x509Certificate */,
            extensionsGenerator.generate
          )
          val csr                 = csrBuilder.build(signer)
          // val issuer = csr.getSubject
          // val __issuer = new X500Name(caCert.getSubjectX500Principal.getName)
          val issuer              = X500Name.getInstance(ASN1Sequence.getInstance(caCert.getSubjectX500Principal.getEncoded))
          val from                = new java.util.Date
          val to                  = new java.util.Date(System.currentTimeMillis + query.duration.toMillis)
          val certgen             =
            new X509v3CertificateBuilder(issuer, serial, from, to, csr.getSubject, csr.getSubjectPublicKeyInfo)
          csr.getAttributes.foreach(attr => {
            attr.getAttributeValues.collect {
              case exts: Extensions => {
                exts.getExtensionOIDs.map(id => exts.getExtension(id)).filter(_ != null).foreach { ext =>
                  certgen.addExtension(ext.getExtnId, ext.isCritical, ext.getParsedValue)
                }
              }
            }
          })

          if (query.includeAIA && CertParentHelper.fromOtoroshiRootCa(caCert)) {
            val access = buildAuthorityInfoAccess(serial)
            certgen.addExtension(access._1, access._2, access._3)
          }

          val parentSignatureAlg                     = new DefaultSignatureAlgorithmIdentifierFinder().find(caCert.getSigAlgName)
          val certsigner                             = contentSigner(
            parentSignatureAlg /*csr.getSignatureAlg*/,
            digestAlgorithm,
            PrivateKeyFactory.createKey(caKey.getEncoded)
          )
          val holder                                 = certgen.build(certsigner)
          val certencoded                            = holder.toASN1Structure.getEncoded
          val certificateFactory: CertificateFactory = CertificateFactory.getInstance("X.509")
          val cert                                   = certificateFactory
            .generateCertificate(new ByteArrayInputStream(certencoded))
            .asInstanceOf[X509Certificate]
          Right(GenCertResponse(_serial, cert, csr, query.some, kpr.privateKey, caCert, caChain))
        } match {
          case Failure(e)      => Left(e.getMessage).future
          case Success(either) => either.future
        }
    }
  }
}
