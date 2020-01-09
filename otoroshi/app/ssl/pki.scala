package otoroshi.ssl.pki

import java.io.{ByteArrayInputStream, StringReader}
import java.security._
import java.security.cert.{CertificateFactory, X509Certificate}

import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.util.ByteString
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509._
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.operator.bc.BcRSAContentSignerBuilder
import org.bouncycastle.operator.{DefaultDigestAlgorithmIdentifierFinder, DefaultSignatureAlgorithmIdentifierFinder}
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import org.bouncycastle.util.io.pem.PemReader
import otoroshi.ssl.pki.models._
import play.api.libs.json._
import security.IdGenerator
import ssl.Cert
import ssl.SSLImplicits._
import utils.future.Implicits._

import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object models {

  case class GenKeyPairQuery(algo: String = "rsa", size: Int = 2048) {
    def json: JsValue = GenKeyPairQuery.format.writes(this)
  }

  object GenKeyPairQuery {
    private val format = Json.format[GenKeyPairQuery]
    def fromJson(json: JsValue): Either[String, GenKeyPairQuery] = format.reads(json).asEither match {
      case Left(errs) => Left("error while parsing json")
      case Right(q) => Right(q)
    }
  }

  case class GenCsrQuery(
                          hosts: Seq[String] = Seq.empty,
                          key: GenKeyPairQuery = GenKeyPairQuery(),
                          name: Map[String, String] = Map.empty,
                          subject: Option[String] = None,
                          client: Boolean = false,
                          ca: Boolean = false,
                          duration: FiniteDuration = 365.days,
                          signatureAlg: String = "SHA256WithRSAEncryption",
                          digestAlg: String = "SHA-256",
                          existingKeyPair: Option[KeyPair] = None,
                          existingSerialNumber: Option[Long] = None
                        ) {
    def subj: String = subject.getOrElse(name.map(t => s"${t._1}=${t._2}").mkString(","))
    def json: JsValue = GenCsrQuery.format.writes(this)
    def hasDnsNameOrCName: Boolean = hosts.nonEmpty || subject.exists(_.toLowerCase().contains("cn=")) || name.contains("CN") || name.contains("cn")
  }
  object GenCsrQuery {
    private val format = new Format[GenCsrQuery] {
      override def reads(json: JsValue): JsResult[GenCsrQuery] = Try {
        GenCsrQuery(
          hosts = (json \ "hosts").asOpt[Seq[String]].getOrElse(Seq.empty),
          key = (json \ "key").asOpt[JsValue].flatMap(v => GenKeyPairQuery.fromJson(v).toOption).getOrElse(GenKeyPairQuery()),
          name = (json \ "name").asOpt[Map[String, String]].getOrElse(Map.empty),
          subject = (json \ "subject").asOpt[String],
          client = (json \ "client").asOpt[Boolean].getOrElse(false),
          ca = (json \ "ca").asOpt[Boolean].getOrElse(false),
          duration = (json \ "duration").asOpt[Long].map(_.millis).getOrElse(365.days),
          signatureAlg = (json \ "signatureAlg").asOpt[String].getOrElse("SHA256WithRSAEncryption"),
          digestAlg = (json \ "digestAlg").asOpt[String].getOrElse("SHA-256"),
        )
      } match {
        case Failure(e) => JsError(e.getMessage)
        case Success(s) => JsSuccess(s)
      }

      override def writes(o: GenCsrQuery): JsValue = Json.obj(
        "hosts" -> o.hosts,
        "key" -> o.key.json,
        "name" -> o.name,
        "subject" -> o.subject.map(JsString.apply).getOrElse(JsNull).as[JsValue],
        "client" -> o.client,
        "ca" -> o.ca,
        "duration" -> o.duration.toMillis,
        "signatureAlg" -> o.signatureAlg,
        "digestAlg" -> o.digestAlg,
      )
    }
    def fromJson(json: JsValue): Either[String, GenCsrQuery] = format.reads(json).asEither match {
      case Left(errs) => Left("error while parsing json")
      case Right(q) => Right(q)
    }
  }

  case class GenKeyPairResponse(publicKey: PublicKey, privateKey: PrivateKey) {
    def json: JsValue = Json.obj(
      "publicKey" -> publicKey.asPem,
      "privateKey" -> privateKey.asPem
    )
    def chain: String = s"${publicKey.asPem}\n${privateKey.asPem}"
    def keyPair: KeyPair = new KeyPair(publicKey, privateKey)
  }

  case class GenCsrResponse(csr: PKCS10CertificationRequest, publicKey: PublicKey, privateKey: PrivateKey) {
    def json: JsValue = Json.obj(
      "csr" -> csr.asPem,
      "publicKey" -> publicKey.asPem,
      "privateKey" -> privateKey.asPem,
    )
    def chain: String = s"${csr.asPem}\n${privateKey.asPem}\n${publicKey.asPem}"
  }

  case class GenCertResponse(serial: Long, cert: X509Certificate, csr: PKCS10CertificationRequest, key: PrivateKey, ca: X509Certificate) {
    def json: JsValue = Json.obj(
      "serial" -> serial,
      "cert" -> cert.asPem,
      "csr" -> csr.asPem,
      "key" -> key.asPem,
      "ca" -> ca.asPem
    )
    def chain: String = s"${key.asPem}\n${cert.asPem}\n${ca.asPem}"
    def chainWithCsr: String = s"${key.asPem}\n${cert.asPem}\n${ca.asPem}\n${csr.asPem}"
    def keyPair: KeyPair = new KeyPair(cert.getPublicKey, key)
    def toCert: Cert = Cert.apply(cert, keyPair, ca, false).enrich()
  }

  case class SignCertResponse(cert: X509Certificate, csr: PKCS10CertificationRequest, ca: Option[X509Certificate]) {
    def json: JsValue = Json.obj(
      "cert" -> cert.asPem,
      "csr" -> csr.asPem,
    ) ++ ca.map(c => Json.obj("ca" -> c.asPem)).getOrElse(Json.obj())
    def chain: String = s"${cert.asPem}\n${ca.map(_.asPem + "\n").getOrElse("")}"
    def chainWithCsr: String = s"${cert.asPem}\n${ca.map(_.asPem + "\n").getOrElse("")}${csr.asPem}"
  }
}

trait Pki {

  import utils.future.Implicits._

  // genkeypair          generate a public key / private key pair
  def genKeyPair(query: ByteString)(implicit ec: ExecutionContext): Future[Either[String, GenKeyPairResponse]] = GenKeyPairQuery.fromJson(Json.parse(query.utf8String)) match {
    case Left(err) => Left(err).future
    case Right(q)  => genKeyPair(q)
  }

  // gencsr           generate a private key and a certificate request
  def genCsr(query: ByteString, caCert: Option[X509Certificate])(implicit ec: ExecutionContext): Future[Either[String, GenCsrResponse]] = GenCsrQuery.fromJson(Json.parse(query.utf8String)) match {
    case Left(err) => Left(err).future
    case Right(q)  => genCsr(q, caCert)
  }

  // gencert          generate a private key and a certificate
  def genCert(query: ByteString, caCert: X509Certificate, caKey: PrivateKey)(implicit ec: ExecutionContext): Future[Either[String, GenCertResponse]] = GenCsrQuery.fromJson(Json.parse(query.utf8String)) match {
    case Left(err) => Left(err).future
    case Right(q)  => genCert(q, caCert, caKey)
  }

  // sign             signs a certificate
  def signCert(csr: ByteString, validity: FiniteDuration, caCert: X509Certificate, caKey: PrivateKey, existingSerialNumber: Option[Long])(implicit ec: ExecutionContext): Future[Either[String, SignCertResponse]] = {
    val pemReader = new PemReader(new StringReader(csr.utf8String))
    val pemObject = pemReader.readPemObject()
    val _csr = new PKCS10CertificationRequest(pemObject.getContent)
    pemReader.close()
    signCert(_csr, validity, caCert, caKey, existingSerialNumber)
  }

  // selfsign         generates a self-signed certificate
  def genSelfSignedCert(query: ByteString)(implicit ec: ExecutionContext): Future[Either[String, GenCertResponse]] = GenCsrQuery.fromJson(Json.parse(query.utf8String)) match {
    case Left(err) => Left(err).future
    case Right(q)  => genSelfSignedCert(q)
  }

  def genSelfSignedCA(query: ByteString)(implicit ec: ExecutionContext, mat: Materializer): Future[Either[String, GenCertResponse]] = GenCsrQuery.fromJson(Json.parse(query.utf8String)) match {
    case Left(err) => Left(err).future
    case Right(q)  => genSelfSignedCA(q)
  }

  def genSubCA(query: ByteString, caCert: X509Certificate, caKey: PrivateKey)(implicit ec: ExecutionContext): Future[Either[String, GenCertResponse]] = GenCsrQuery.fromJson(Json.parse(query.utf8String)) match {
    case Left(err) => Left(err).future
    case Right(q)  => genSubCA(q, caCert, caKey)
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // actual implementation
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  // genkeypair          generate a public key / private key pair
  def genKeyPair(query: GenKeyPairQuery)(implicit ec: ExecutionContext): Future[Either[String, GenKeyPairResponse]]

  // gencsr           generate a private key and a certificate request
  def genCsr(query: GenCsrQuery, caCert: Option[X509Certificate])(implicit ec: ExecutionContext): Future[Either[String, GenCsrResponse]]

  // gencert          generate a private key and a certificate
  def genCert(query: GenCsrQuery, caCert: X509Certificate, caKey: PrivateKey)(implicit ec: ExecutionContext): Future[Either[String, GenCertResponse]]

  // sign             signs a certificate
  def signCert(csr: PKCS10CertificationRequest, validity: FiniteDuration, caCert: X509Certificate, caKey: PrivateKey, existingSerialNumber: Option[Long])(implicit ec: ExecutionContext): Future[Either[String, SignCertResponse]]

  def genSelfSignedCA(query: GenCsrQuery)(implicit ec: ExecutionContext): Future[Either[String, GenCertResponse]]

  def genSelfSignedCert(query: GenCsrQuery)(implicit ec: ExecutionContext): Future[Either[String, GenCertResponse]]

  def genSubCA(query: GenCsrQuery, caCert: X509Certificate, caKey: PrivateKey)(implicit ec: ExecutionContext): Future[Either[String, GenCertResponse]]
}

class BouncyCastlePki(generator: IdGenerator) extends Pki {

  // genkeypair          generate a public key / private key pair
  override def genKeyPair(query: GenKeyPairQuery)(implicit ec: ExecutionContext): Future[Either[String, GenKeyPairResponse]] = {
    Try {
      val keyPairGenerator = KeyPairGenerator.getInstance(query.algo.toUpperCase())
      keyPairGenerator.initialize(query.size, new SecureRandom())
      keyPairGenerator.generateKeyPair()
    } match {
      case Failure(e) => Left(e.getMessage).future
      case Success(keyPair) => Right(GenKeyPairResponse(keyPair.getPublic, keyPair.getPrivate)).future
    }
  }

  // gencsr           generate a private key and a certificate request
  override def genCsr(query: GenCsrQuery, caCert: Option[X509Certificate])(implicit ec: ExecutionContext): Future[Either[String, GenCsrResponse]] = {
    genKeyPair(query.key).flatMap {
      case Left(e) => Left(e).future
      case Right(_kpr) => {
        val kpr = query.existingKeyPair.map(v => GenKeyPairResponse(v.getPublic, v.getPrivate)).getOrElse(_kpr)
        Try {
          val privateKey = PrivateKeyFactory.createKey(kpr.privateKey.getEncoded)
          val signatureAlgorithm = new DefaultSignatureAlgorithmIdentifierFinder().find(query.signatureAlg)
          val digestAlgorithm = new DefaultDigestAlgorithmIdentifierFinder().find(query.digestAlg)
          val signer = new BcRSAContentSignerBuilder(signatureAlgorithm, digestAlgorithm).build(privateKey)
          val names = new GeneralNames(query.hosts.map(name => new GeneralName(GeneralName.dNSName, name)).toArray)
          val csrBuilder = new JcaPKCS10CertificationRequestBuilder(new X500Name(query.subj), kpr.publicKey)
          val extensionsGenerator = new ExtensionsGenerator
          extensionsGenerator.addExtension(Extension.basicConstraints, true, new BasicConstraints(query.ca))
          if (!query.ca) {
            extensionsGenerator.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment))
            // extensionsGenerator.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(Seq(KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth).toArray))
            if (query.client && query.hasDnsNameOrCName) {
              extensionsGenerator.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(Seq(KeyPurposeId.id_kp_clientAuth, KeyPurposeId.id_kp_serverAuth).toArray))
            } else if (query.client) {
              extensionsGenerator.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(Seq(KeyPurposeId.id_kp_clientAuth).toArray))
            } else {
              extensionsGenerator.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(Seq(KeyPurposeId.id_kp_serverAuth).toArray))
            }
            caCert.foreach(ca => extensionsGenerator.addExtension(Extension.authorityKeyIdentifier, false, new AuthorityKeyIdentifier(new GeneralNames(new GeneralName(new X509Name(ca.getSubjectX500Principal.getName))), ca.getSerialNumber)))
            extensionsGenerator.addExtension(Extension.subjectAlternativeName, false, names)
          } else {
            extensionsGenerator.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign  | KeyUsage.cRLSign))
          }
          csrBuilder.addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest /* x509Certificate */ , extensionsGenerator.generate)
          csrBuilder.build(signer)
        } match {
          case Failure(e) => Left(e.getMessage).future
          case Success(csr) => Right(GenCsrResponse(csr, kpr.publicKey, kpr.privateKey)).future
        }
      }
    }
  }

  // gencert          generate a private key and a certificate
  override def genCert(query: GenCsrQuery, caCert: X509Certificate, caKey: PrivateKey)(implicit ec: ExecutionContext): Future[Either[String, GenCertResponse]] = {
    (for {
      csr <- genCsr(query, Some(caCert))
      cert <- csr match {
        case Left(err) => FastFuture.successful(Left(err))
        case Right(_csr) => signCert(_csr.csr, query.duration, caCert, caKey, query.existingSerialNumber)
      }
    } yield cert match {
      case Left(err) => Left(err)
      case Right(resp) =>
        Right(GenCertResponse(resp.cert.getSerialNumber.longValue(), resp.cert, resp.csr, csr.right.get.privateKey, caCert))
    }).transformWith {
      case Failure(e) => Left(e.getMessage).future
      case Success(Left(e)) => Left(e).future
      case Success(Right(response)) => Right(response).future
    }
  }

  // sign             signs a certificate
  override def signCert(csr: PKCS10CertificationRequest, validity: FiniteDuration, caCert: X509Certificate, caKey: PrivateKey, existingSerialNumber: Option[Long])(implicit ec: ExecutionContext): Future[Either[String, SignCertResponse]] = {
    generator.nextIdSafe().map { _serial =>
      val issuer = new X500Name(caCert.getSubjectX500Principal.getName)
      val serial = java.math.BigInteger.valueOf(existingSerialNumber.getOrElse(_serial)) // new java.math.BigInteger(32, new SecureRandom)
      val from = new java.util.Date
      val to = new java.util.Date(System.currentTimeMillis + validity.toMillis)
      val certgen = new X509v3CertificateBuilder(issuer, serial, from, to, csr.getSubject, csr.getSubjectPublicKeyInfo)
      csr.getAttributes.foreach(attr => {
        attr.getAttributeValues.collect {
          case exts: Extensions => {
            exts.getExtensionOIDs.map(id => exts.getExtension(id)).filter(_ != null).foreach { ext =>
              certgen.addExtension(ext.getExtnId, ext.isCritical, ext.getParsedValue)
            }
          }
        }
      })
      // val signatureAlgorithm = new DefaultSignatureAlgorithmIdentifierFinder().find("SHA256WithRSAEncryption")
      val digestAlgorithm = new DefaultDigestAlgorithmIdentifierFinder().find("SHA-256")
      val signer = new BcRSAContentSignerBuilder(csr.getSignatureAlgorithm, digestAlgorithm).build(PrivateKeyFactory.createKey(caKey.getEncoded))
      val holder = certgen.build(signer)
      val certencoded = holder.toASN1Structure.getEncoded
      val certificateFactory: CertificateFactory = CertificateFactory.getInstance("X.509")
      val cert = certificateFactory
        .generateCertificate(new ByteArrayInputStream(certencoded))
        .asInstanceOf[X509Certificate]
      cert
    } match {
      case Failure(err) => Left(err.getMessage).future
      case Success(cert) => Right(SignCertResponse(cert, csr, Some(caCert))).future
    }
  }

  override def genSelfSignedCert(query: GenCsrQuery)(implicit ec: ExecutionContext): Future[Either[String, GenCertResponse]] = {
    if (query.ca) {
      genSelfSignedCA(query)
    } else {
      genKeyPair(query.key).flatMap {
        case Left(e) => Left(e).future
        case Right(_kpr) =>
          val kpr = query.existingKeyPair.map(v => GenKeyPairResponse(v.getPublic, v.getPrivate)).getOrElse(_kpr)
          generator.nextIdSafe().map { _serial =>
            val privateKey = PrivateKeyFactory.createKey(kpr.privateKey.getEncoded)
            val signatureAlgorithm = new DefaultSignatureAlgorithmIdentifierFinder().find(query.signatureAlg)
            val digestAlgorithm = new DefaultDigestAlgorithmIdentifierFinder().find(query.digestAlg)
            val signer = new BcRSAContentSignerBuilder(signatureAlgorithm, digestAlgorithm).build(privateKey)
            val names = new GeneralNames(query.hosts.map(name => new GeneralName(GeneralName.dNSName, name)).toArray)
            val csrBuilder = new JcaPKCS10CertificationRequestBuilder(new X500Name(query.subj), kpr.publicKey)
            val extensionsGenerator = new ExtensionsGenerator
            extensionsGenerator.addExtension(Extension.basicConstraints, true, new BasicConstraints(query.ca))
            if (!query.ca) {
              extensionsGenerator.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment))
              // extensionsGenerator.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(Seq(KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth).toArray))
              if (query.client && query.hasDnsNameOrCName) {
                extensionsGenerator.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(Seq(KeyPurposeId.id_kp_clientAuth, KeyPurposeId.id_kp_serverAuth).toArray))
              } else if (query.client) {
                extensionsGenerator.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(Seq(KeyPurposeId.id_kp_clientAuth).toArray))
              } else {
                extensionsGenerator.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(Seq(KeyPurposeId.id_kp_serverAuth).toArray))
              }
              extensionsGenerator.addExtension(Extension.subjectAlternativeName, false, names)
            } else {
              extensionsGenerator.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign))
            }
            csrBuilder.addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest /* x509Certificate */ , extensionsGenerator.generate)
            val csr = csrBuilder.build(signer)
            val issuer = csr.getSubject
            val serial = java.math.BigInteger.valueOf(query.existingSerialNumber.getOrElse(_serial)) // new java.math.BigInteger(32, new SecureRandom)
            val from = new java.util.Date
            val to = new java.util.Date(System.currentTimeMillis + query.duration.toMillis)
            val certgen = new X509v3CertificateBuilder(issuer, serial, from, to, csr.getSubject, csr.getSubjectPublicKeyInfo)
            csr.getAttributes.foreach(attr => {
              attr.getAttributeValues.collect {
                case exts: Extensions => {
                  exts.getExtensionOIDs.map(id => exts.getExtension(id)).filter(_ != null).foreach { ext =>
                    certgen.addExtension(ext.getExtnId, ext.isCritical, ext.getParsedValue)
                  }
                }
              }
            })
            // val signatureAlgorithm = new DefaultSignatureAlgorithmIdentifierFinder().find("SHA256WithRSAEncryption")
            val holder = certgen.build(signer)
            val certencoded = holder.toASN1Structure.getEncoded
            val certificateFactory: CertificateFactory = CertificateFactory.getInstance("X.509")
            val cert = certificateFactory
              .generateCertificate(new ByteArrayInputStream(certencoded))
              .asInstanceOf[X509Certificate]
            Right(GenCertResponse(_serial, cert, csr, kpr.privateKey, cert))
          } match {
            case Failure(e) => Left(e.getMessage).future
            case Success(either) => either.future
          }
      }
    }
  }

  override def genSelfSignedCA(query: GenCsrQuery)(implicit ec: ExecutionContext): Future[Either[String, GenCertResponse]] = {
    genKeyPair(query.key).flatMap {
      case Left(e) => Left(e).future
      case Right(_kpr) =>
        val kpr = query.existingKeyPair.map(v => GenKeyPairResponse(v.getPublic, v.getPrivate)).getOrElse(_kpr)
        generator.nextIdSafe().map { _serial =>
          val privateKey = PrivateKeyFactory.createKey(kpr.privateKey.getEncoded)
          val signatureAlgorithm = new DefaultSignatureAlgorithmIdentifierFinder().find(query.signatureAlg)
          val digestAlgorithm = new DefaultDigestAlgorithmIdentifierFinder().find(query.digestAlg)
          val signer = new BcRSAContentSignerBuilder(signatureAlgorithm, digestAlgorithm).build(privateKey)
          val names = new GeneralNames(query.hosts.map(name => new GeneralName(GeneralName.dNSName, name)).toArray)
          val csrBuilder = new JcaPKCS10CertificationRequestBuilder(new X500Name(query.subj), kpr.publicKey)
          val extensionsGenerator = new ExtensionsGenerator
          extensionsGenerator.addExtension(Extension.basicConstraints, true, new BasicConstraints(true))
          extensionsGenerator.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign))
          csrBuilder.addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest /* x509Certificate */ , extensionsGenerator.generate)
          val csr = csrBuilder.build(signer)
          val issuer = csr.getSubject
          val serial = java.math.BigInteger.valueOf(query.existingSerialNumber.getOrElse(_serial)) // new java.math.BigInteger(32, new SecureRandom)
          val from = new java.util.Date
          val to = new java.util.Date(System.currentTimeMillis + query.duration.toMillis)
          val certgen = new X509v3CertificateBuilder(issuer, serial, from, to, csr.getSubject, csr.getSubjectPublicKeyInfo)
          csr.getAttributes.foreach(attr => {
            attr.getAttributeValues.collect {
              case exts: Extensions => {
                exts.getExtensionOIDs.map(id => exts.getExtension(id)).filter(_ != null).foreach { ext =>
                  certgen.addExtension(ext.getExtnId, ext.isCritical, ext.getParsedValue)
                }
              }
            }
          })
          val holder = certgen.build(signer)
          val certencoded = holder.toASN1Structure.getEncoded
          val certificateFactory: CertificateFactory = CertificateFactory.getInstance("X.509")
          val cert = certificateFactory
            .generateCertificate(new ByteArrayInputStream(certencoded))
            .asInstanceOf[X509Certificate]
          Right(GenCertResponse(_serial, cert, csr, kpr.privateKey, cert))
        } match {
          case Failure(e) => Left(e.getMessage).future
          case Success(either) => either.future
        }
    }
  }

  override def genSubCA(query: GenCsrQuery, caCert: X509Certificate, caKey: PrivateKey)(implicit ec: ExecutionContext): Future[Either[String, GenCertResponse]] = {
    genKeyPair(query.key).flatMap {
      case Left(e) => Left(e).future
      case Right(_kpr) =>
        val kpr = query.existingKeyPair.map(v => GenKeyPairResponse(v.getPublic, v.getPrivate)).getOrElse(_kpr)
        generator.nextIdSafe().map { _serial =>
          val privateKey = PrivateKeyFactory.createKey(kpr.privateKey.getEncoded)
          val signatureAlgorithm = new DefaultSignatureAlgorithmIdentifierFinder().find(query.signatureAlg)
          val digestAlgorithm = new DefaultDigestAlgorithmIdentifierFinder().find(query.digestAlg)
          val signer = new BcRSAContentSignerBuilder(signatureAlgorithm, digestAlgorithm).build(privateKey)
          val names = new GeneralNames(query.hosts.map(name => new GeneralName(GeneralName.dNSName, name)).toArray)
          val csrBuilder = new JcaPKCS10CertificationRequestBuilder(new X500Name(query.subj), kpr.publicKey)
          val extensionsGenerator = new ExtensionsGenerator
          extensionsGenerator.addExtension(Extension.basicConstraints, true, new BasicConstraints(true))
          extensionsGenerator.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign))
          extensionsGenerator.addExtension(Extension.authorityKeyIdentifier, false, new AuthorityKeyIdentifier(new GeneralNames(new GeneralName(new X509Name(caCert.getSubjectX500Principal.getName))), caCert.getSerialNumber))
          csrBuilder.addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest /* x509Certificate */ , extensionsGenerator.generate)
          val csr = csrBuilder.build(signer)
          val issuer = csr.getSubject
          val serial = java.math.BigInteger.valueOf(query.existingSerialNumber.getOrElse(_serial)) // new java.math.BigInteger(32, new SecureRandom)
          val from = new java.util.Date
          val to = new java.util.Date(System.currentTimeMillis + query.duration.toMillis)
          val certgen = new X509v3CertificateBuilder(issuer, serial, from, to, csr.getSubject, csr.getSubjectPublicKeyInfo)
          csr.getAttributes.foreach(attr => {
            attr.getAttributeValues.collect {
              case exts: Extensions => {
                exts.getExtensionOIDs.map(id => exts.getExtension(id)).filter(_ != null).foreach { ext =>
                  certgen.addExtension(ext.getExtnId, ext.isCritical, ext.getParsedValue)
                }
              }
            }
          })
          val certsigner = new BcRSAContentSignerBuilder(csr.getSignatureAlgorithm, digestAlgorithm).build(PrivateKeyFactory.createKey(caKey.getEncoded))
          val holder = certgen.build(certsigner)
          val certencoded = holder.toASN1Structure.getEncoded
          val certificateFactory: CertificateFactory = CertificateFactory.getInstance("X.509")
          val cert = certificateFactory
            .generateCertificate(new ByteArrayInputStream(certencoded))
            .asInstanceOf[X509Certificate]
          Right(GenCertResponse(_serial, cert, csr, kpr.privateKey, cert))
        } match {
          case Failure(e) => Left(e.getMessage).future
          case Success(either) => either.future
        }
    }
  }
}
