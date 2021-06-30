package otoroshi.ssl

import java.io._
import java.lang.reflect.Field
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.charset.StandardCharsets.US_ASCII
import java.security._
import java.security.cert._
import java.security.spec.{KeySpec, PKCS8EncodedKeySpec}
import java.util.concurrent.{Executors, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}
import java.util.regex.Pattern.CASE_INSENSITIVE
import java.util.regex.{Matcher, Pattern}
import java.util.{Base64, Date}
import otoroshi.actions.ApiAction
import akka.http.scaladsl.util.FastFuture
import akka.stream.{Materializer, TLSClientAuth}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.ByteString
import com.google.common.hash.Hashing
import com.typesafe.sslconfig.ssl.SSLConfigSettings
import otoroshi.env.Env
import otoroshi.events.{Alerts, CertExpiredAlert, CertRenewalAlert}
import otoroshi.gateway.Errors

import javax.crypto.Cipher.DECRYPT_MODE
import javax.crypto.spec.PBEKeySpec
import javax.crypto.{Cipher, EncryptedPrivateKeyInfo, SecretKey, SecretKeyFactory}
import javax.net.ssl._
import otoroshi.models._
import org.apache.commons.codec.binary.Hex
import org.apache.commons.codec.digest.DigestUtils
import org.bouncycastle.asn1.x509.{ExtendedKeyUsage, KeyPurposeId}
import org.bouncycastle.openssl.jcajce.{JcaPEMKeyConverter, JcePEMDecryptorProviderBuilder}
import org.bouncycastle.openssl.{PEMEncryptedKeyPair, PEMKeyPair, PEMParser}
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.bouncycastle.util.io.pem.PemReader
import org.joda.time.{DateTime, Interval}
import otoroshi.ssl.pki.models.{GenCertResponse, GenCsrQuery, GenKeyPairQuery}
import otoroshi.utils.letsencrypt.LetsEncryptHelper
import otoroshi.utils.{RegexPool, TypedMap}
import play.api.libs.json._
import play.api.libs.ws.WSProxyServer
import play.api.mvc._
import play.api.{Configuration, Logger}
import play.core.ApplicationProvider
import play.server.api.SSLEngineProvider
import redis.RedisClientMasterSlaves
import otoroshi.security.IdGenerator
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.http.DN
import otoroshi.utils.metrics.{FakeHasMetrics, HasMetrics}
import otoroshi.utils.metrics.FakeHasMetrics
import otoroshi.utils.syntax.implicits._

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._
import otoroshi.utils.syntax.implicits._

/**
 * git over http works with otoroshi
 * ssh and other => http tunneling like https://github.com/mathieuancelin/node-httptunnel or https://github.com/larsbrinkhoff/httptunnel or https://github.com/luizluca/bridge
 */
sealed trait ClientAuth {
  def name: String
  def toAkkaClientAuth: TLSClientAuth
}
object ClientAuth       {

  case object None extends ClientAuth {
    def name: String                    = "None"
    def toAkkaClientAuth: TLSClientAuth = TLSClientAuth.None
  }
  case object Want extends ClientAuth {
    def name: String                    = "Want"
    def toAkkaClientAuth: TLSClientAuth = TLSClientAuth.Want
  }
  case object Need extends ClientAuth {
    def name: String                    = "Need"
    def toAkkaClientAuth: TLSClientAuth = TLSClientAuth.Need
  }

  def values: Seq[ClientAuth] = Seq(None, Want, Need)
  def apply(name: String): Option[ClientAuth] = {
    name.toLowerCase match {
      case "None" => Some(None)
      case "none" => Some(None)
      case "Want" => Some(Want)
      case "want" => Some(Want)
      case "Need" => Some(Need)
      case "need" => Some(Need)
      case _      => scala.None
    }
  }
}

case class OCSPCertProjection(
    revoked: Boolean,
    valid: Boolean,
    expired: Boolean,
    revocationReason: String,
    from: Date,
    to: Date
)

case class Cert(
    id: String,
    name: String,
    description: String,
    chain: String,
    privateKey: String,
    caRef: Option[String],
    domain: String = "--",
    selfSigned: Boolean = false,
    ca: Boolean = false,
    valid: Boolean = false,
    exposed: Boolean = false,
    revoked: Boolean,
    autoRenew: Boolean = false,
    letsEncrypt: Boolean = false,
    client: Boolean = false,
    keypair: Boolean = false,
    subject: String = "--",
    from: DateTime = DateTime.now(),
    to: DateTime = DateTime.now(),
    sans: Seq[String] = Seq.empty,
    entityMetadata: Map[String, String] = Map.empty,
    tags: Seq[String] = Seq.empty,
    password: Option[String] = None,
    location: otoroshi.models.EntityLocation = otoroshi.models.EntityLocation()
) extends otoroshi.models.EntityLocationSupport {

  def json: JsValue                    = toJson
  def internalId: String               = id
  def theDescription: String           = description
  def theMetadata: Map[String, String] = entityMetadata
  def theName: String                  = name
  def theTags: Seq[String]             = tags

  lazy val certType = {
    if (client) "client"
    else if (ca) "ca"
    else if (letsEncrypt) "letsEncrypt"
    else if (keypair) "keypair"
    else if (selfSigned) "selfSigned"
    else "certificate"
  }

  lazy val notRevoked: Boolean = !revoked
  lazy val cacheKey: String    = s"$id###$contentHash"
  lazy val contentHash: String = Hashing.sha256().hashString(s"$chain:$privateKey", StandardCharsets.UTF_8).toString

  lazy val allDomains: Seq[String] = {
    val enriched = enrich()
    (Seq(enriched.domain) ++ enriched.sans).filter(_.trim.nonEmpty).filterNot(_ == "--").distinct
  }
  def signature: Option[String]                     = this.metadata.map(v => (v \ "signature").as[String])
  def serialNumber: Option[String]                  = this.metadata.map(v => (v \ "serialNumber").as[String])
  def serialNumberLng: Option[java.math.BigInteger] =
    this.metadata.map(v => (v \ "serialNumberLng").as[java.math.BigInteger])
  def matchesDomain(dom: String): Boolean           = allDomains.exists(d => RegexPool.apply(d).matches(dom))

  def renew(
      _duration: Option[FiniteDuration] = None
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Cert] = {
    import SSLImplicits._
    val duration = _duration.getOrElse(FiniteDuration(365, TimeUnit.DAYS))
    this match {
      case original if original.letsEncrypt => LetsEncryptHelper.renew(this)
      case _                                => {
        env.datastores.certificatesDataStore.findAll().map { certificates =>
          val cas = certificates.filter(cert => cert.ca)
          caRef.flatMap(ref => cas.find(_.id == ref)) match {
            case None if ca         =>
              val resp = FakeKeyStore
                .createCA(subject, duration, Some(cryptoKeyPair), certificate.map(_.getSerialNumber.longValue()))
              copy(chain = resp.cert.asPem, privateKey = resp.key.asPem).enrich()
            case None if selfSigned =>
              val resp = FakeKeyStore.createSelfSignedCertificate(
                domain,
                duration,
                Some(cryptoKeyPair),
                certificate.map(_.getSerialNumber.longValue())
              )
              copy(chain = resp.cert.asPem, privateKey = resp.key.asPem).enrich()
            case None if keypair    =>
              val resp = FakeKeyStore.createSelfSignedCertificate(
                domain,
                duration,
                Some(cryptoKeyPair),
                certificate.map(_.getSerialNumber.longValue())
              )
              copy(chain = resp.cert.asPem, privateKey = resp.key.asPem).enrich()
            case None               => // should not happens
              val resp = FakeKeyStore.createSelfSignedCertificate(
                domain,
                duration,
                Some(cryptoKeyPair),
                certificate.map(_.getSerialNumber.longValue())
              )
              copy(chain = resp.cert.asPem, privateKey = resp.key.asPem).enrich()
            case Some(caCert) if ca =>
              val resp = FakeKeyStore.createSubCa(
                domain,
                duration,
                Some(cryptoKeyPair),
                certificate.map(_.getSerialNumber.longValue()),
                caCert.certificate.get,
                caCert.certificates.tail,
                caCert.cryptoKeyPair
              )
              copy(chain = resp.cert.asPem + "\n" + caCert.chain, privateKey = resp.key.asPem).enrich()
            case Some(caCert)       =>
              val resp = FakeKeyStore.createCertificateFromCA(
                domain,
                duration,
                Some(cryptoKeyPair),
                certificate.map(_.getSerialNumber.longValue()),
                caCert.certificate.get,
                caCert.certificates.tail,
                caCert.cryptoKeyPair
              )
              copy(chain = resp.cert.asPem + "\n" + caCert.chain, privateKey = resp.key.asPem).enrich()
            case _                  =>
              // println("wait what ???")
              val resp = FakeKeyStore.createSelfSignedCertificate(
                domain,
                duration,
                Some(cryptoKeyPair),
                certificate.map(_.getSerialNumber.longValue())
              )
              copy(chain = resp.cert.asPem, privateKey = resp.key.asPem).enrich()
          }
        }
      }
    }
  }
  // def password: Option[String] = None
  def save()(implicit ec: ExecutionContext, env: Env) = {
    val current = this.enrich()
    env.datastores.certificatesDataStore.set(current)
  }
  lazy val notExpired: Boolean                          = from.isBefore(org.joda.time.DateTime.now()) && to.isAfter(org.joda.time.DateTime.now())
  lazy val expired: Boolean                             = !notExpired
  def enrich() = {
    val meta = this.metadata.get
    this.copy(
      domain = (meta \ "domain").asOpt[String].getOrElse("--"),
      selfSigned = (meta \ "selfSigned").asOpt[Boolean].getOrElse(false),
      ca = (meta \ "ca").asOpt[Boolean].getOrElse(false),
      // client = (meta \ "client").asOpt[Boolean].getOrElse(false),
      valid = this.isValid,
      subject = (meta \ "subjectDN").as[String],
      from = (meta \ "notBefore").asOpt[Long].map(v => new DateTime(v)).getOrElse(DateTime.now()),
      to = (meta \ "notAfter").asOpt[Long].map(v => new DateTime(v)).getOrElse(DateTime.now()),
      sans = (meta \ "subAltNames").asOpt[Seq[String]].getOrElse(Seq.empty)
    )
  }
  def delete()(implicit ec: ExecutionContext, env: Env) = env.datastores.certificatesDataStore.delete(this)
  def exists()(implicit ec: ExecutionContext, env: Env) = env.datastores.certificatesDataStore.exists(this)
  def toJson                                            = Cert.toJson(this)
  lazy val certificatesRaw: Seq[String]                 = Try {
    chain
      .split(PemHeaders.BeginCertificate)
      .toSeq
      .tail
      .map(_.replace(PemHeaders.EndCertificate, "").trim())
      .map(c => s"${PemHeaders.BeginCertificate}\n$c\n${PemHeaders.EndCertificate}")
  }.toOption.toSeq.flatten
  lazy val certificates: Seq[X509Certificate]           = certificatesChain.toSeq /*{
    val certificateFactory: CertificateFactory = CertificateFactory.getInstance("X.509")
    certificatesRaw
      .map(
        content =>
          Try(
            certificateFactory
              .generateCertificate(new ByteArrayInputStream(DynamicSSLEngineProvider.base64Decode(content)))
              .asInstanceOf[X509Certificate]
        )
      )
      .collect {
        case Success(cert) => cert
      }
  }*/

  lazy val certificatesChain: Array[X509Certificate] = { //certificates.toArray
    Try {
      chain
        .split(PemHeaders.BeginCertificate)
        .toSeq
        .map(_.trim)
        .filterNot(_.isEmpty)
        .map { content =>
          content.replace(PemHeaders.BeginCertificate, "").replace(PemHeaders.EndCertificate, "")
        }
        .map { content =>
          val certificateFactory: CertificateFactory = CertificateFactory.getInstance("X.509")
          certificateFactory
            .generateCertificate(new ByteArrayInputStream(DynamicSSLEngineProvider.base64Decode(content)))
            .asInstanceOf[X509Certificate]
        }
        .toArray
    }.getOrElse(Array.empty)
  }

  lazy val certificate: Option[X509Certificate] = Try {
    chain.split(PemHeaders.BeginCertificate).toSeq.tail.headOption.map { cert =>
      val content: String                        = cert.replace(PemHeaders.EndCertificate, "")
      val certificateFactory: CertificateFactory = CertificateFactory.getInstance("X.509")
      certificateFactory
        .generateCertificate(new ByteArrayInputStream(DynamicSSLEngineProvider.base64Decode(content)))
        .asInstanceOf[X509Certificate]
    }
  }.toOption.flatten

  lazy val caFromChain: Option[X509Certificate] = Try {
    chain.split(PemHeaders.BeginCertificate).toSeq.tail.lastOption.map { cert =>
      val content: String                        = cert.replace(PemHeaders.EndCertificate, "")
      val certificateFactory: CertificateFactory = CertificateFactory.getInstance("X.509")
      certificateFactory
        .generateCertificate(new ByteArrayInputStream(DynamicSSLEngineProvider.base64Decode(content)))
        .asInstanceOf[X509Certificate]
    }
  }.toOption.flatten
  lazy val metadata: Option[JsValue] = {
    chain.split(PemHeaders.BeginCertificate).toSeq.tail.headOption.map { cert =>
      val content: String = cert.replace(PemHeaders.EndCertificate, "")
      CertificateData(content)
    }
  }
  lazy val isValid: Boolean = {
    Try {
      val keyStore: KeyStore = KeyStore.getInstance("JKS")
      keyStore.load(null, null)
      DynamicSSLEngineProvider.readPrivateKeyUniversal(this.id, this.privateKey, this.password, false).toOption.exists {
        key: PrivateKey =>
          // val key: PrivateKey = DynamicSSLEngineProvider.readPrivateKey(encodedKeySpec) /*Try(KeyFactory.getInstance("RSA")).map(_.generatePrivate(encodedKeySpec))
          //   .orElse(Try(KeyFactory.getInstance("EC")).map(_.generatePrivate(encodedKeySpec)))
          //   .orElse(Try(KeyFactory.getInstance("DSA")).map(_.generatePrivate(encodedKeySpec)))
          //   .orElse(Try(KeyFactory.getInstance("DiffieHellman")).map(_.generatePrivate(encodedKeySpec)))
          //   .get*/
          val certificateChain: Seq[X509Certificate] =
            DynamicSSLEngineProvider.readCertificateChain(this.id, this.chain, false)
          if (certificateChain.isEmpty) {
            DynamicSSLEngineProvider.logger.error(s"[${this.id}] Certificate file does not contain any certificates :(")
            false
          } else {
            keyStore.setKeyEntry(
              this.id,
              key,
              this.password.getOrElse("").toCharArray,
              certificateChain.toArray[java.security.cert.Certificate]
            )
            true
          }
      }
    } recover { case e =>
      DynamicSSLEngineProvider.logger.error(s"Error while checking certificate validity (${name})")
      false
    } getOrElse false
  }
  lazy val cryptoKeyPair: KeyPair = {
    val privkey           = DynamicSSLEngineProvider.readPrivateKeyUniversal(id, privateKey, None).right.get
    //val privkey: PrivateKey = DynamicSSLEngineProvider.readPrivateKey(privkeySpec) /*Try(KeyFactory.getInstance("RSA"))
    //  .orElse(Try(KeyFactory.getInstance("DSA")))
    //  .map(_.generatePrivate(privkeySpec))
    //  .get
    val pubkey: PublicKey = certificate.get.getPublicKey
    new KeyPair(pubkey, privkey)
  }

  def toGenCertResponse(implicit env: Env): GenCertResponse = {
    val query = GenCsrQuery(
      hosts = Seq(domain),
      subject = Some(subject)
    )
    GenCertResponse(
      serial = serialNumberLng.get,
      cert = certificate.get,
      csr = Await
        .result(env.pki.genCsr(query, None)(env.otoroshiExecutionContext), 10.seconds)
        .right
        .get
        .csr,
      csrQuery = query.some,
      key = cryptoKeyPair.getPrivate,
      ca = caFromChain.get,
      caChain = certificates.tail
    )
  }
}

object Cert {

  import SSLImplicits._

  val OtoroshiCaDN             = s"CN=Otoroshi Default Root CA Certificate, OU=Otoroshi Certificates, O=Otoroshi"
  val OtoroshiCA               = "otoroshi-root-ca"
  val OtoroshiIntermediateCaDN =
    s"CN=Otoroshi Default Intermediate CA Certificate, OU=Otoroshi Certificates, O=Otoroshi"
  val OtoroshiIntermediateCA   = "otoroshi-intermediate-ca"
  val OtoroshiJwtSigningDn     = s"CN=Otoroshi Default Jwt Signing Keypair, OU=Otoroshi Certificates, O=Otoroshi"
  val OtoroshiJwtSigning       = "otoroshi-jwt-signing"
  val OtoroshiWildcard         = "otoroshi-wildcard"
  val OtoroshiClientDn         = s"CN=Otoroshi Default Client Certificate, OU=Otoroshi Certificates, O=Otoroshi"
  val OtoroshiClient           = "otoroshi-client"

  lazy val logger = Logger("otoroshi-cert")

  def apply(name: String, cert: String, privateKey: String): Cert = {
    Cert(
      id = IdGenerator.token(32),
      name = name,
      description = name,
      chain = cert,
      privateKey = privateKey,
      caRef = None,
      autoRenew = false,
      client = false,
      exposed = false,
      revoked = false
    ).enrich()
  }

  def apply(cert: X509Certificate, keyPair: KeyPair, caRef: Option[String], client: Boolean): Cert = {
    val c = Cert(
      id = IdGenerator.token(32),
      name = "none",
      description = "none",
      chain = cert.asPem,
      privateKey = keyPair.getPrivate.asPem,
      caRef = caRef,
      autoRenew = false,
      client = client,
      exposed = false,
      revoked = false
    ).enrich()
    c.copy(name = c.domain, description = s"Certificate for ${c.subject}")
  }

  def apply(cert: X509Certificate, keyPair: KeyPair, ca: Cert, client: Boolean): Cert = {
    val c = Cert(
      id = IdGenerator.token(32),
      name = "none",
      description = "none",
      chain = cert.asPem + "\n" + ca.chain,
      privateKey = keyPair.getPrivate.asPem,
      caRef = Some(ca.id),
      autoRenew = false,
      client = client,
      exposed = false,
      revoked = false
    ).enrich()
    c.copy(name = c.domain, description = s"Certificate for ${c.subject}")
  }

  def apply(cert: X509Certificate, keyPair: KeyPair, ca: X509Certificate, client: Boolean): Cert = {
    val c = Cert(
      id = IdGenerator.token(32),
      name = "none",
      description = "none",
      chain = cert.asPem + "\n" + ca.asPem,
      //s"${PemHeaders.BeginCertificate}\n${Base64.getEncoder
      //.encodeToString(cert.getEncoded)}\n${PemHeaders.EndCertificate}\n${PemHeaders.BeginCertificate}\n${Base64.getEncoder
      //.encodeToString(ca.getEncoded)}\n${PemHeaders.EndCertificate}\n",
      privateKey = keyPair.getPrivate.asPem,
      // s"${PemHeaders.BeginPrivateKey}\n${Base64.getEncoder.encodeToString(keyPair.getPrivate.getEncoded)}\n${PemHeaders.EndPrivateKey}",
      caRef = None,
      autoRenew = false,
      client = client,
      exposed = false,
      revoked = false
    ).enrich()
    c.copy(name = c.domain, description = s"Certificate for ${c.subject}")
  }

  val _fmt: Format[Cert]                           = new Format[Cert] {
    override def writes(cert: Cert): JsValue          =
      cert.location.jsonWithKey ++ Json.obj(
        "id"          -> cert.id,
        "domain"      -> cert.domain,
        "name"        -> cert.name,
        "description" -> cert.description,
        "chain"       -> cert.chain,
        "caRef"       -> cert.caRef,
        "privateKey"  -> cert.privateKey,
        "selfSigned"  -> cert.selfSigned,
        "ca"          -> cert.ca,
        "valid"       -> cert.valid,
        "exposed"     -> cert.exposed,
        "revoked"     -> cert.revoked,
        "autoRenew"   -> cert.autoRenew,
        "letsEncrypt" -> cert.letsEncrypt,
        "subject"     -> cert.subject,
        "from"        -> cert.from.getMillis,
        "to"          -> cert.to.getMillis,
        "client"      -> cert.client,
        "keypair"     -> cert.keypair,
        "sans"        -> JsArray(cert.sans.map(JsString.apply)),
        "certType"    -> cert.certType,
        "password"    -> cert.password,
        "metadata"    -> cert.entityMetadata,
        "tags"        -> JsArray(cert.tags.map(JsString.apply))
      )
    override def reads(json: JsValue): JsResult[Cert] =
      Try {
        Cert(
          location = otoroshi.models.EntityLocation.readFromKey(json),
          id = (json \ "id").as[String],
          name = (json \ "name").asOpt[String].orElse((json \ "domain").asOpt[String]).getOrElse("none"),
          description = (json \ "description")
            .asOpt[String]
            .orElse((json \ "domain").asOpt[String].map(v => s"Certificate for $v"))
            .getOrElse("none"),
          domain = (json \ "domain").as[String],
          sans = (json \ "sans").asOpt[Seq[String]].getOrElse(Seq.empty),
          chain = (json \ "chain").as[String],
          caRef = (json \ "caRef").asOpt[String],
          password = (json \ "password").asOpt[String].filter(_.trim.nonEmpty),
          privateKey = (json \ "privateKey").asOpt[String].getOrElse(""),
          selfSigned = (json \ "selfSigned").asOpt[Boolean].getOrElse(false),
          ca = (json \ "ca").asOpt[Boolean].getOrElse(false),
          client = (json \ "client").asOpt[Boolean].getOrElse(false),
          keypair = (json \ "keypair").asOpt[Boolean].getOrElse(false),
          valid = (json \ "valid").asOpt[Boolean].getOrElse(false),
          exposed = (json \ "exposed").asOpt[Boolean].getOrElse(false),
          revoked = (json \ "revoked").asOpt[Boolean].getOrElse(false),
          autoRenew = (json \ "autoRenew").asOpt[Boolean].getOrElse(false),
          letsEncrypt = (json \ "letsEncrypt").asOpt[Boolean].getOrElse(false),
          subject = (json \ "subject").asOpt[String].getOrElse("--"),
          from = (json \ "from").asOpt[Long].map(v => new DateTime(v)).getOrElse(DateTime.now()),
          to = (json \ "to").asOpt[Long].map(v => new DateTime(v)).getOrElse(DateTime.now()),
          entityMetadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
          tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String])
        )
      } map { case sd =>
        JsSuccess(sd)
      } recover { case t =>
        logger.error("Error while reading Cert", t)
        JsError(t.getMessage)
      } get
  }
  def toJson(value: Cert): JsValue                 = _fmt.writes(value)
  def fromJsons(value: JsValue): Cert              =
    try {
      _fmt.reads(value).get
    } catch {
      case e: Throwable => {
        logger.error(s"Try to deserialize ${Json.prettyPrint(value)}")
        throw e
      }
    }
  def fromJsonSafe(value: JsValue): JsResult[Cert] = _fmt.reads(value)

  def createFromServices()(implicit ec: ExecutionContext, env: Env, mat: Materializer): Future[Unit] = {
    env.datastores.certificatesDataStore.findAll().flatMap { certificates =>
      env.datastores.serviceDescriptorDataStore.findAll().flatMap { services =>
        val certs                    = certificates.filterNot(_.letsEncrypt)
        val letsEncryptServicesHosts = services
          .filter(_.issueCert)
          .flatMap(s => s.allHosts.map(h => (s, h)))
          .filterNot(s => certs.exists(c => RegexPool(c.domain).matches(s._2)))
        Source(letsEncryptServicesHosts.toList)
          .mapAsync(1) { case (service, host) =>
            env.datastores.rawDataStore.get(s"${env.storageRoot}:certs-issuer:local:create:$host").flatMap {
              case Some(_) =>
                logger.warn(s"Certificate already in creating process: $host")
                FastFuture.successful(())
              case None    => {
                env.datastores.rawDataStore
                  .set(
                    s"${env.storageRoot}:certs-issuer:local:create:$host",
                    ByteString("true"),
                    Some(1.minutes.toMillis)
                  )
                  .flatMap { _ =>
                    val cert = certs.find(c => RegexPool(c.domain).matches(host)).get
                    if (cert.autoRenew) {
                      cert.renew()
                    } else {
                      FastFuture.successful(cert)
                    }
                  }
                  .andThen { case _ =>
                    env.datastores.rawDataStore.del(Seq(s"${env.storageRoot}:certs-issuer:local:create:$host"))
                  }
              }
            }
          }
          .map {
            case (host, Left(err)) => logger.error(s"Error while creating certificate for $host. $err")
            case (host, Right(_))  => logger.info(s"Successfully created certificate for $host")
          }
          .runWith(Sink.ignore)
          .map(_ => ())
      }
    }
  }
}

trait CertificateDataStore extends BasicStore[Cert] {

  def nakedTemplate(env: Env): Cert = {
    Cert(
      id = IdGenerator.namedId("cert", env),
      name = "a new certificate",
      description = "a new certificate",
      chain = "",
      privateKey = "",
      caRef = None,
      revoked = false
    )
  }

  def template(implicit ec: ExecutionContext, env: Env): Future[Cert] = {
    env.pki
      .genSelfSignedCert(
        GenCsrQuery(
          hosts = Seq("www.oto.tools"),
          subject = Some("C=FR, OU=Foo, O=Bar")
        )
      )
      .map { c =>
        c.toOption.get.toCert.copy(
          id = IdGenerator.namedId("cert", env),
          name = "a new certificate",
          description = "a new certificate",
          chain = "",
          privateKey = ""
        )
      }
  }

  def renewCertificates()(implicit ec: ExecutionContext, env: Env, mat: Materializer): Future[Unit] = {

    def willBeInvalidSoon(cert: Cert): Boolean = {
      val enriched         = cert.enrich()
      val globalInterval   = new Interval(enriched.from, enriched.to)
      val nowInterval      = new Interval(DateTime.now(), enriched.to)
      val percentage: Long = (nowInterval.toDurationMillis * 100) / globalInterval.toDurationMillis
      percentage < 20
    }

    def renewCAs(certificates: Seq[Cert]): Future[Unit] = {
      val renewableCas = certificates
        .filter(_.notRevoked)
        .filter(_.autoRenew)
        .filter(cert => cert.ca)
        .filter(willBeInvalidSoon)
        .filterNot(c =>
          c.entityMetadata.get("untilExpiration").contains("true") || c.name.startsWith("[UNTIL EXPIRATION] ")
        )
      Source(renewableCas.toList)
        .mapAsync(1) { case c =>
          c.renew()
            .flatMap(d =>
              c.copy(
                id = IdGenerator.token,
                name = "[UNTIL EXPIRATION] " + c.name,
                entityMetadata = c.entityMetadata ++ Map(
                  "untilExpiration" -> "true",
                  "nextCertificate" -> c.id
                )
              ).save()
                .map(_ => d)
            )
            .flatMap(c => c.save().map(_ => c))
        }
        .map { c =>
          Alerts.send(
            CertRenewalAlert(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              c
            )
          )
        }
        .runWith(Sink.ignore)
        .map(_ => ())
    }

    def renewNonCaCertificates(certificates: Seq[Cert]): Future[Unit] = {
      val renewableCertificates = certificates
        .filter(_.notRevoked)
        .filter(_.autoRenew)
        .filterNot(_.ca)
        .filter(willBeInvalidSoon) // TODO: fix
        .filterNot(c =>
          c.entityMetadata.get("untilExpiration").contains("true") || c.name.startsWith("[UNTIL EXPIRATION] ")
        )
      Source(renewableCertificates.toList)
        .mapAsync(1) { case c =>
          c.renew()
            .flatMap(d =>
              c.copy(
                id = IdGenerator.token,
                name = "[UNTIL EXPIRATION] " + c.name,
                entityMetadata = c.entityMetadata ++ Map(
                  "untilExpiration" -> "true",
                  "nextCertificate" -> c.id
                )
              ).save()
                .map(_ => d)
            )
            .flatMap(c => c.save().map(_ => c))
        }
        .map { c =>
          Alerts.send(
            CertRenewalAlert(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              c
            )
          )
        }
        .runWith(Sink.ignore)
        .map(_ => ())
    }

    def markExpiredCertsAsExpired(certificates: Seq[Cert]): Future[Unit] = {
      val expiredCertificates = certificates
        .filter(_.notRevoked)
        .filterNot { cert =>
          cert.from.isBefore(org.joda.time.DateTime.now()) && cert.to.isAfter(org.joda.time.DateTime.now())
        }
      Source(expiredCertificates.toList)
        .mapAsync(1) {
          case c if c.entityMetadata.get("expired").contains("true") || c.name.startsWith("[EXPIRED] ")    =>
            c.applyOn(d => d.save().map(_ => d))
          case c if !(c.entityMetadata.get("expired").contains("true") || c.name.startsWith("[EXPIRED] ")) =>
            c.copy(name = "[EXPIRED] " + c.name, entityMetadata = c.entityMetadata ++ Map("expired" -> "true"))
              .applyOn(d => d.save().map(_ => d))
        }
        .map { c =>
          Alerts.send(
            CertExpiredAlert(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              c
            )
          )
        }
        .runWith(Sink.ignore)
        .map(_ => ())
    }

    for {
      certificates   <- findAll()
      _              <- renewCAs(certificates)
      ncertificates  <- findAll()
      _              <- renewNonCaCertificates(ncertificates)
      nncertificates <- findAll()
      _              <- markExpiredCertsAsExpired(nncertificates)
    } yield ()
  }

  def readCertOrKey(conf: Configuration, path: String, env: Env): Option[String] = {
    conf.getOptionalWithFileSupport[String](path).flatMap { cacert =>
      if (
        (cacert.contains(PemHeaders.BeginCertificate) && cacert.contains(PemHeaders.EndCertificate)) ||
        (cacert.contains(PemHeaders.BeginPrivateKey) && cacert.contains(PemHeaders.EndPrivateKey)) ||
        (cacert.contains(PemHeaders.BeginPrivateECKey) && cacert.contains(PemHeaders.EndPrivateECKey)) ||
        (cacert.contains(PemHeaders.BeginPrivateRSAKey) && cacert.contains(PemHeaders.EndPrivateRSAKey))
      ) {
        Some(cacert)
      } else {
        val file = new File(cacert)
        if (file.exists()) {
          val content = new String(java.nio.file.Files.readAllBytes(file.toPath))
          if (
            (content.contains(PemHeaders.BeginCertificate) && content.contains(PemHeaders.EndCertificate)) ||
            (content.contains(PemHeaders.BeginPrivateKey) && content.contains(PemHeaders.EndPrivateKey)) ||
            (content.contains(PemHeaders.BeginPrivateECKey) && content.contains(PemHeaders.EndPrivateECKey)) ||
            (content.contains(PemHeaders.BeginPrivateRSAKey) && content.contains(PemHeaders.EndPrivateRSAKey))
          ) {
            Some(content)
          } else {
            None
          }
        } else {
          None
        }
      }
    }
  }

  def importOneCert(
      conf: Configuration,
      caPath: String,
      certPath: String,
      keyPath: String,
      logger: Logger,
      id: Option[String] = None
  )(implicit
      env: Env,
      ec: ExecutionContext
  ): Unit = {
    readCertOrKey(conf, caPath, env).foreach { cacert =>
      val _cert = Cert(
        id = IdGenerator.uuid,
        name = "none",
        description = "none",
        chain = cacert,
        privateKey = "",
        caRef = None,
        ca = true,
        client = false,
        exposed = false,
        revoked = false
      ).enrich()
      val cert  = _cert.copy(name = _cert.domain, description = s"Certificate for ${_cert.subject}")
      findAll().map { certs =>
        val found = certs
          .map(_.enrich())
          .exists(c =>
            (c.signature.isDefined && c.signature == cert.signature) && (c.serialNumber.isDefined && c.serialNumber == cert.serialNumber)
          )
        if (!found) {
          cert.save()(ec, env).andThen {
            case Success(e) => logger.info("Successful import of initial cacert !")
            case Failure(e) => logger.error("Error while storing initial cacert ...", e)
          }
        }
      }
    }
    for {
      certContent <- readCertOrKey(conf, certPath, env)
      keyContent  <- readCertOrKey(conf, keyPath, env)
    } yield {
      val _cert = Cert(
        id = IdGenerator.uuid,
        name = "none",
        description = "none",
        chain = certContent,
        privateKey = keyContent,
        caRef = None,
        client = false,
        exposed = false,
        revoked = false
      ).enrich()
      val cert  = _cert.copy(name = _cert.domain, description = s"Certificate for ${_cert.subject}")
      findAll().map { certs =>
        val found = certs
          .map(_.enrich())
          .exists(c =>
            (c.signature.isDefined && c.signature == cert.signature) && (c.serialNumber.isDefined && c.serialNumber == cert.serialNumber)
          )
        if (!found) {
          cert.save()(ec, env).andThen {
            case Success(e) => logger.info("Successful import of initial cert !")
            case Failure(e) => logger.error("Error while storing initial cert ...", e)
          }
        }
      }
    }
  }

  def importInitialCerts(logger: Logger)(implicit env: Env, ec: ExecutionContext) = {
    importOneCert(
      env.configuration,
      "otoroshi.ssl.rootCa.ca",
      "otoroshi.ssl.rootCa.cert",
      "otoroshi.ssl.rootCa.key",
      logger,
      Some(Cert.OtoroshiCA)
    )(env, ec)
    importOneCert(
      env.configuration,
      "otoroshi.ssl.initialCacert",
      "otoroshi.ssl.initialCert",
      "otoroshi.ssl.initialCertKey",
      logger
    )(env, ec)
    env.configuration
      .getOptionalWithFileSupport[Seq[Configuration]]("otoroshi.ssl.initialCerts")
      .getOrElse(Seq.empty[Configuration])
      .foreach { conf =>
        importOneCert(conf, "ca", "cert", "key", logger)(env, ec)
      }
  }

  def hasInitialCerts()(implicit env: Env, ec: ExecutionContext): Boolean = {
    val hasInitialCert  =
      env.configuration.has("otoroshi.ssl.initialCacert") &&
      env.configuration.has("otoroshi.ssl.initialCert") &&
      env.configuration.has("otoroshi.ssl.initialCertKey")
    val hasInitialCerts = env.configuration.has("otoroshi.ssl.initialCerts")
    val hasRootCA       = env.configuration.has("otoroshi.ssl.rootCa.cert") && env.configuration.has(
      "otoroshi.ssl.rootCa.key"
    )
    hasInitialCert || hasInitialCerts || hasRootCA
  }

  def autoGenerateCertificateForDomain(
      domain: String
  )(implicit env: Env, ec: ExecutionContext): Future[Option[Cert]] = {
    env.datastores.globalConfigDataStore.latestSafe match {
      case None         => FastFuture.successful(None)
      case Some(config) => {
        config.autoCert match {
          case AutoCert(true, Some(ref), allowed, notAllowed, replyNicely) => {
            env.datastores.certificatesDataStore.findById(ref).flatMap {
              case None       =>
                DynamicSSLEngineProvider.logger.error(s"CA cert not found to generate certificate for $domain")
                FastFuture.successful(None)
              case Some(cert) => {
                !notAllowed.exists(p => otoroshi.utils.RegexPool.apply(p).matches(domain)) && allowed
                  .exists(p => RegexPool.apply(p).matches(domain)) match {
                  case true                 => {
                    env.datastores.certificatesDataStore.findAll().flatMap { certificates =>
                      certificates.find(c => (c.sans :+ c.domain).contains(domain)) match {
                        case Some(_) => FastFuture.successful(None)
                        case _       => {
                          env.pki
                            .genCert(
                              GenCsrQuery(
                                hosts = Seq(domain),
                                subject = Some(
                                  s"CN=$domain,OU=Auto Generated Certificates, OU=Otoroshi Certificates, O=Otoroshi"
                                )
                              ),
                              cert.certificate.get,
                              cert.certificates.tail,
                              cert.cryptoKeyPair.getPrivate
                            )
                            .flatMap {
                              case Left(err)   =>
                                DynamicSSLEngineProvider.logger
                                  .error(s"error while generating certificate for $domain: $err")
                                FastFuture.successful(None)
                              case Right(resp) => {
                                val cert = resp.toCert
                                  .copy(
                                    name = s"Certificate for $domain",
                                    description = s"Auto Generated Certificate for $domain",
                                    autoRenew = true
                                  )
                                cert.save().map { _ =>
                                  Some(cert)
                                }
                              }
                            }
                        }
                      }
                    }
                  }
                  case false if replyNicely => {
                    env.pki
                      .genCert(
                        GenCsrQuery(
                          hosts = Seq(domain),
                          subject = Some(SSLSessionJavaHelper.BadDN)
                        ),
                        cert.certificate.get,
                        cert.certificates.tail,
                        cert.cryptoKeyPair.getPrivate
                      )
                      .flatMap {
                        case Left(err)   =>
                          DynamicSSLEngineProvider.logger.error(s"error while generating certificate for $domain: $err")
                          FastFuture.successful(None)
                        case Right(resp) => {
                          val cert = resp.toCert
                            .copy(
                              name = s"Certificate for $domain",
                              description = s"Auto Generated Certificate for $domain",
                              autoRenew = true
                            )
                          FastFuture.successful(Some(cert))
                        }
                      }
                  }
                  case _                    => FastFuture.successful(None)
                }
              }
            }
          }
          case _                                                           => FastFuture.successful(None)
        }
      }
    }
  }

  def jautoGenerateCertificateForDomain(domain: String, env: Env): Option[Cert] = {
    import scala.concurrent.duration._
    Try {
      // TODO: blocking ec
      implicit val ec = env.otoroshiExecutionContext
      implicit val ev = env
      Await.result(env.datastores.certificatesDataStore.autoGenerateCertificateForDomain(domain), 10.seconds)
    } match {
      case Failure(e)   => None
      case Success(opt) => opt
    }
  }
}

object DynamicSSLEngineProvider {

  import org.bouncycastle.jce.provider.BouncyCastleProvider
  import java.security.Security

  Security.addProvider(new BouncyCastleProvider())

  type KeyStoreError = String

  private val EMPTY_PASSWORD: Array[Char] = Array.emptyCharArray

  val logger = Logger("otoroshi-ssl-provider")

  private val CERT_PATTERN: Pattern = Pattern.compile(
    "-+BEGIN\\s+.*CERTIFICATE[^-]*-+(?:\\s|\\r|\\n)+" + // Header
    "([a-z0-9+/=\\r\\n]+)" +                            // Base64 text
    "-+END\\s+.*CERTIFICATE[^-]*-+", // Footer
    CASE_INSENSITIVE
  )

  val PRIVATE_KEY_PATTERN: Pattern = Pattern.compile(
    "-+BEGIN\\s+.*PRIVATE\\s+KEY[^-]*-+(?:\\s|\\r|\\n)+" + "([a-z0-9+/=\\r\\n]+)" + "-+END\\s+.*PRIVATE\\s+KEY[^-]*-+",
    CASE_INSENSITIVE
  )
  val PUBLIC_KEY_PATTERN: Pattern  = Pattern.compile(
    "-+BEGIN\\s+.*PUBLIC\\s+KEY[^-]*-+(?:\\s|\\r|\\n)+" + "([a-z0-9+/=\\r\\n]+)" + "-+END\\s+.*PUBLIC\\s+KEY[^-]*-+",
    CASE_INSENSITIVE
  )

  val _certificates               = new TrieMap[String, Cert]()
  val _ocspProjectionCertificates = new TrieMap[java.math.BigInteger, OCSPCertProjection]()

  def certificates: TrieMap[String, Cert] = _certificates.filter(_._2.notRevoked)

  private lazy val firstSetupDone           = new AtomicBoolean(false)
  private lazy val currentContextServer           = new AtomicReference[SSLContext](setupContext(FakeHasMetrics, true))
  private lazy val currentContextClient           = new AtomicReference[SSLContext](setupContext(FakeHasMetrics, true))
  private lazy val currentSslConfigSettings = new AtomicReference[SSLConfigSettings](null)
  private val currentEnv                    = new AtomicReference[Env](null)
  private val defaultSslContext             = SSLContext.getDefault

  def isFirstSetupDone: Boolean = firstSetupDone.get()

  def setCurrentEnv(env: Env): Unit = {
    currentEnv.set(env)
  }

  def getCurrentEnv(): Env = {
    currentEnv.get()
  }

  private def setupContext(env: HasMetrics, includeJdkCa: Boolean): SSLContext =
    env.metrics.withTimer("otoroshi.core.tls.setup-global-context") {

      val certificates = _certificates.filter(_._2.notRevoked)

      val optEnv = Option(currentEnv.get)

      val trustAll: Boolean =
        optEnv
          .flatMap(e => e.configuration.getOptionalWithFileSupport[Boolean]("otoroshi.ssl.trust.all"))
          .getOrElse(false)

      val cacertPath = optEnv
        .flatMap(e => e.configuration.getOptionalWithFileSupport[String]("otoroshi.ssl.cacert.path"))
        .map(path =>
          path
            .replace("${JAVA_HOME}", System.getProperty("java.home"))
            .replace("$JAVA_HOME", System.getProperty("java.home"))
        )
        .getOrElse(System.getProperty("java.home") + "/lib/security/cacerts")

      val cacertPassword = optEnv
        .flatMap(e => e.configuration.getOptionalWithFileSupport[String]("otoroshi.ssl.cacert.password"))
        .getOrElse("changeit")

      val dumpPath: Option[String] =
        optEnv.flatMap(e => e.configuration.getOptionalWithFileSupport[String]("play.server.https.keyStoreDumpPath"))

      logger.debug("Setting up SSL Context ")
      val sslContext: SSLContext               = SSLContext.getInstance("TLS")
      val keyStore: KeyStore                   = createKeyStore(certificates.values.toSeq) //.filterNot(_.ca))
      dumpPath.foreach { path =>
        logger.debug(s"Dumping keystore at $dumpPath")
        keyStore.store(new FileOutputStream(path), EMPTY_PASSWORD)
      }
      val keyManagerFactory: KeyManagerFactory =
        Try(KeyManagerFactory.getInstance("X509")).orElse(Try(KeyManagerFactory.getInstance("SunX509"))).get
      keyManagerFactory.init(keyStore, EMPTY_PASSWORD)
      logger.debug("SSL Context init ...")
      val keyManagers: Array[KeyManager]       = keyManagerFactory.getKeyManagers.map(m =>
        KeyManagerCompatibility.keyManager(
          () => certificates.values.toSeq,
          false,
          m.asInstanceOf[X509KeyManager],
          optEnv.get
        ) // new X509KeyManagerSnitch(m.asInstanceOf[X509KeyManager]).asInstanceOf[KeyManager]
      )
      val tm: Array[TrustManager] =
        optEnv
          .flatMap(e =>
            e.configuration.getOptionalWithFileSupport[Boolean]("play.server.https.trustStore.noCaVerification")
          )
          .map {
            case true  => Array[TrustManager](noCATrustManager)
            case false if includeJdkCa  => createTrustStoreWithJdkCAs(keyStore, cacertPath, cacertPassword)
            case false if !includeJdkCa => createTrustStore(keyStore)
          } getOrElse {
          if (trustAll) {
            Array[TrustManager](
              new VeryNiceTrustManager(Seq.empty[X509TrustManager])
            )
          } else {
            if (includeJdkCa) {
              createTrustStoreWithJdkCAs(keyStore, cacertPath, cacertPassword)
            } else {
              createTrustStore(keyStore)
            }
          }
        }

      sslContext.init(keyManagers, tm, null)
      // dumpPath match {
      //   case Some(path) => {
      //     currentSslConfigSettings.set(
      //       SSLConfigSettings()
      //       //.withHostnameVerifierClass(classOf[OtoroshiHostnameVerifier])
      //         .withKeyManagerConfig(
      //           KeyManagerConfig().withKeyStoreConfigs(
      //             List(KeyStoreConfig(None, Some(path)).withPassword(Some(String.valueOf(EMPTY_PASSWORD))))
      //           )
      //         )
      //         .withTrustManagerConfig(
      //           TrustManagerConfig().withTrustStoreConfigs(
      //             certificates.values.toList.map(c => TrustStoreConfig(Option(c.chain).map(_.trim), None))
      //           )
      //         )
      //     )
      //   }
      //   case None => {
      //     currentSslConfigSettings.set(
      //       SSLConfigSettings()
      //       //.withHostnameVerifierClass(classOf[OtoroshiHostnameVerifier])
      //         .withKeyManagerConfig(
      //           KeyManagerConfig().withKeyStoreConfigs(
      //             certificates.values.toList.map(c => KeyStoreConfig(Option(c.chain).map(_.trim), None))
      //           )
      //         )
      //         .withTrustManagerConfig(
      //           TrustManagerConfig().withTrustStoreConfigs(
      //             certificates.values.toList.map(c => TrustStoreConfig(Option(c.chain).map(_.trim), None))
      //           )
      //         )
      //     )
      //   }
      // }
      logger.debug(s"SSL Context init done ! (${keyStore.size()})")
      SSLContext.setDefault(sslContext)
      sslContext
    }

  /*
  def setupSslContextFor(cert: Cert, env: Env): SSLContext =
    env.metrics.withTimer("otoroshi.core.tls.setup-single-context") {

      val optEnv = Option(currentEnv.get)

      val trustAll: Boolean =
        optEnv.flatMap(e => e.configuration.getOptionalWithFileSupport[Boolean]("otoroshi.ssl.trust.all")).getOrElse(false)

      val cacertPath = optEnv
        .flatMap(e => e.configuration.getOptionalWithFileSupport[String]("otoroshi.ssl.cacert.path"))
        .map(
          path =>
            path
              .replace("${JAVA_HOME}", System.getProperty("java.home"))
              .replace("$JAVA_HOME", System.getProperty("java.home"))
        )
        .getOrElse(System.getProperty("java.home") + "/lib/security/cacerts")

      val cacertPassword = optEnv
        .flatMap(e => e.configuration.getOptionalWithFileSupport[String]("otoroshi.ssl.cacert.password"))
        .getOrElse("changeit")

      logger.debug("Setting up SSL Context ")
      val sslContext: SSLContext = SSLContext.getInstance("TLS")
      val keyStore: KeyStore     = createKeyStore(Seq(cert))

      val keyManagerFactory: KeyManagerFactory =
        Try(KeyManagerFactory.getInstance("X509")).orElse(Try(KeyManagerFactory.getInstance("SunX509"))).get
      keyManagerFactory.init(keyStore, EMPTY_PASSWORD)
      logger.debug("SSL Context init ...")
      val keyManagers: Array[KeyManager] = keyManagerFactory.getKeyManagers.map(
        m => new X509KeyManagerSnitch(m.asInstanceOf[X509KeyManager]).asInstanceOf[KeyManager]
      )
      val tm: Array[TrustManager] =
      optEnv.flatMap(e => e.configuration.getOptionalWithFileSupport[Boolean]("play.server.https.trustStore.noCaVerification")).map {
        case true  => Array[TrustManager](noCATrustManager)
        case false => createTrustStore(keyStore, cacertPath, cacertPassword)
      } getOrElse {
        if (trustAll) {
          Array[TrustManager](
            new VeryNiceTrustManager(Seq.empty[X509TrustManager])
          )
        } else {
          createTrustStore(keyStore, cacertPath, cacertPassword)
        }
      }

      sslContext.init(keyManagers, tm, null)
      logger.debug(s"SSL Context init done ! (${keyStore.size()})")
      SSLContext.setDefault(sslContext)
      sslContext
    }
   */

  def setupSslContextFor(
      _certs: Seq[Cert],
      _trustedCerts: Seq[Cert],
      forceTrustAll: Boolean,
      client: Boolean,
      env: Env
  ): SSLContext =
    env.metrics.withTimer("otoroshi.core.tls.setup-single-context") {

      val includeJdkCa: Boolean = if (client) {
        env.datastores.globalConfigDataStore.latestSafe.map(_.tlsSettings.includeJdkCaClient).getOrElse(true)
      } else {
        env.datastores.globalConfigDataStore.latestSafe.map(_.tlsSettings.includeJdkCaServer).getOrElse(true)
      }

      val certs        = _certs.filter(_.notRevoked)
      val trustedCerts = _trustedCerts.filter(_.notRevoked)

      val optEnv = Option(env)

      val trustAll: Boolean =
        if (forceTrustAll) true
        else
          optEnv
            .flatMap(e => e.configuration.getOptionalWithFileSupport[Boolean]("otoroshi.ssl.trust.all"))
            .getOrElse(false)

      val cacertPath = optEnv
        .flatMap(e => e.configuration.getOptionalWithFileSupport[String]("otoroshi.ssl.cacert.path"))
        .map(path =>
          path
            .replace("${JAVA_HOME}", System.getProperty("java.home"))
            .replace("$JAVA_HOME", System.getProperty("java.home"))
        )
        .getOrElse(System.getProperty("java.home") + "/lib/security/cacerts")

      val cacertPassword = optEnv
        .flatMap(e => e.configuration.getOptionalWithFileSupport[String]("otoroshi.ssl.cacert.password"))
        .getOrElse("changeit")

      logger.debug("Setting up SSL Context ")
      val sslContext: SSLContext               = SSLContext.getInstance("TLS")
      val keyStore1: KeyStore                  = createKeyStore(certs)
      val keyManagerFactory: KeyManagerFactory =
        Try(KeyManagerFactory.getInstance("X509")).orElse(Try(KeyManagerFactory.getInstance("SunX509"))).get
      keyManagerFactory.init(keyStore1, EMPTY_PASSWORD)
      logger.debug("SSL Context init ...")
      val keyManagers: Array[KeyManager]       = keyManagerFactory.getKeyManagers.map(m =>
        KeyManagerCompatibility.keyManager(
          () => certs,
          client,
          m.asInstanceOf[X509KeyManager],
          optEnv.get
        ) // new X509KeyManagerSnitch(m.asInstanceOf[X509KeyManager]).asInstanceOf[KeyManager]
      )

      val keyStore2: KeyStore     = if (trustedCerts.nonEmpty) createKeyStore(trustedCerts) else keyStore1
      val tm: Array[TrustManager] =
        optEnv
          .flatMap(e =>
            e.configuration.getOptionalWithFileSupport[Boolean]("play.server.https.trustStore.noCaVerification")
          )
          .map {
            case true  => Array[TrustManager](noCATrustManager)
            case false if includeJdkCa  => createTrustStoreWithJdkCAs(keyStore2, cacertPath, cacertPassword)
            case false if !includeJdkCa => createTrustStore(keyStore2)
          } getOrElse {
            if (trustAll) {
              Array[TrustManager](
                new VeryNiceTrustManager(Seq.empty[X509TrustManager])
              )
            } else {
              if (includeJdkCa) {
                createTrustStoreWithJdkCAs(keyStore2, cacertPath, cacertPassword)
              } else {
                createTrustStore(keyStore2)
              }
            }
          }

      sslContext.init(keyManagers, tm, null)
      logger.debug(s"SSL Context init done ! (${keyStore1.size()} - ${keyStore2.size()})")
      SSLContext.setDefault(sslContext)
      sslContext
    }

  def currentServer = currentContextServer.get()
  def currentClient = currentContextClient.get()

  def sslConfigSettings: SSLConfigSettings = currentSslConfigSettings.get()

  def getHostNames(): Seq[String] = {
    _certificates.values.filter(_.notRevoked).map(_.domain).toSet.toSeq
  }

  def addCertificates(certs: Seq[Cert], env: Env): Unit = {
    firstSetupDone.compareAndSet(false, true)
    certs.filter(_.notRevoked).foreach(crt => _certificates.put(crt.id, crt))
    val ctxClient = setupContext(env, env.datastores.globalConfigDataStore.latestSafe.map(_.tlsSettings.includeJdkCaClient).getOrElse(true))
    val ctxServer = setupContext(env, env.datastores.globalConfigDataStore.latestSafe.map(_.tlsSettings.includeJdkCaServer).getOrElse(true))
    currentContextClient.set(ctxClient)
    currentContextServer.set(ctxServer)
  }

  def setCertificates(certs: Seq[Cert], env: Env): Unit = {
    firstSetupDone.compareAndSet(false, true)
    _certificates.clear()
    certs.filter(_.notRevoked).foreach(crt => _certificates.put(crt.id, crt))
    certs
      .filter(r => r.serialNumberLng.isDefined && CertParentHelper.fromOtoroshiRootCa(r.certificate.get))
      .foreach(crt =>
        _ocspProjectionCertificates.put(
          crt.serialNumberLng.get,
          OCSPCertProjection(
            crt.revoked,
            crt.isValid,
            crt.expired,
            crt.entityMetadata.getOrElse("revocationReason", "VALID"),
            crt.from.toDate,
            crt.to.toDate
          )
        )
      )
    val ctxClient = setupContext(env, env.datastores.globalConfigDataStore.latestSafe.map(_.tlsSettings.includeJdkCaClient).getOrElse(true))
    val ctxServer = setupContext(env, env.datastores.globalConfigDataStore.latestSafe.map(_.tlsSettings.includeJdkCaServer).getOrElse(true))
    currentContextClient.set(ctxClient)
    currentContextServer.set(ctxServer)
  }

  def forceUpdate(env: Env): Unit = {
    firstSetupDone.compareAndSet(false, true)
    val ctxClient = setupContext(env, env.datastores.globalConfigDataStore.latestSafe.map(_.tlsSettings.includeJdkCaClient).getOrElse(true))
    val ctxServer = setupContext(env, env.datastores.globalConfigDataStore.latestSafe.map(_.tlsSettings.includeJdkCaServer).getOrElse(true))
    currentContextClient.set(ctxClient)
    currentContextServer.set(ctxServer)
  }

  def createKeyStore(certificates: Seq[Cert]): KeyStore = {

    import SSLImplicits._

    logger.debug(s"Creating keystore ...")
    val keyStore: KeyStore = KeyStore.getInstance("JKS")
    keyStore.load(null, null)
    certificates.foreach {
      case cert if cert.ca                      => {
        cert.certificate.foreach { certificate =>
          val id = "ca-" + certificate.getSerialNumber.toString(16)
          if (!keyStore.containsAlias(id)) {
            keyStore.setCertificateEntry(id, certificate)
          }
        }
      }
      case cert if cert.privateKey.trim.isEmpty => {
        cert.certificate.foreach { certificate =>
          val id                                     = "trusted-" + certificate.getSerialNumber.toString(16)
          val certificateChain: Seq[X509Certificate] = readCertificateChain(cert.domain, cert.chain)
          val domain                                 = Try {
            certificateChain.head.maybeDomain.getOrElse(cert.domain)
          }.toOption.getOrElse(cert.domain)
          // not sure it's actually needed
          if (!keyStore.containsAlias(domain)) {
            keyStore.setCertificateEntry(domain, certificate)
          }
          // Handle SANs, not sure it's actually needed
          cert.sans
            .filter(name => !keyStore.containsAlias(name))
            .foreach(name => {
              keyStore.setCertificateEntry(name, certificate)
            })
        }
      }
      case cert                                 => {
        cert.certificate.foreach { certificate =>
          Try {
            readPrivateKeyUniversal(cert.domain, cert.privateKey, cert.password).foreach { key: PrivateKey =>
              // val key: PrivateKey = readPrivateKey(encodedKeySpec)
              val certificateChain: Seq[X509Certificate] = readCertificateChain(cert.domain, cert.chain)
              if (certificateChain.isEmpty) {
                logger.error(s"[${cert.id}] Certificate file does not contain any certificates :(")
              } else {
                logger.debug(s"Adding entry for ${cert.domain} with chain of ${certificateChain.size}")
                val domain = Try {
                  certificateChain.head.maybeDomain.getOrElse(cert.domain)
                }.toOption.getOrElse(cert.domain)
                keyStore.setKeyEntry(
                  if (cert.client) "client-cert-" + certificate.getSerialNumber.toString(16) else domain,
                  key,
                  cert.password.getOrElse("").toCharArray,
                  certificateChain.toArray[java.security.cert.Certificate]
                )

                // Handle SANs
                if (!cert.client) {
                  cert.sans
                    .filter(name => !keyStore.containsAlias(name))
                    .foreach { name =>
                      keyStore.setKeyEntry(
                        name,
                        key,
                        cert.password.getOrElse("").toCharArray,
                        certificateChain.toArray[java.security.cert.Certificate]
                      )
                    }
                }

                certificateChain.tail.foreach { cert =>
                  val id = "ca-" + cert.getSerialNumber.toString(16)
                  if (!keyStore.containsAlias(id)) {
                    keyStore.setCertificateEntry(id, cert)
                  }
                }
              }
            }
          } match {
            case Failure(e) => logger.error(s"Error while handling certificate: ${cert.name}: " + e.getMessage)
            case Success(e) =>
          }
        }
      }
    }
    keyStore
  }

  def createTrustStoreWithJdkCAs(keyStore: KeyStore, cacertPath: String, cacertPassword: String): Array[TrustManager] = {
    logger.debug(s"Creating truststore ...")
    val tmf    = TrustManagerFactory.getInstance("SunX509")
    tmf.init(keyStore)
    val javaKs = KeyStore.getInstance("JKS")
    // TODO: optimize here: avoid reading file all the time
    javaKs.load(new FileInputStream(cacertPath), cacertPassword.toCharArray)
    val tmf2   = TrustManagerFactory.getInstance("SunX509")
    tmf2.init(javaKs)
    Array[TrustManager](
      new FakeTrustManager((tmf.getTrustManagers ++ tmf2.getTrustManagers).map(_.asInstanceOf[X509TrustManager]).toSeq)
    )
  }

  def createTrustStore(keyStore: KeyStore): Array[TrustManager] = {
    logger.debug(s"Creating truststore ...")
    val tmf    = TrustManagerFactory.getInstance("SunX509")
    tmf.init(keyStore)
    Array[TrustManager](
      new FakeTrustManager(tmf.getTrustManagers.map(_.asInstanceOf[X509TrustManager]).toSeq)
    )
  }

  def readCertificateChain(id: String, certificateChain: String, log: Boolean = true): Seq[X509Certificate] = {
    if (log) logger.debug(s"Reading cert chain for $id")
    val matcher: Matcher                       = CERT_PATTERN.matcher(certificateChain)
    val certificateFactory: CertificateFactory = CertificateFactory.getInstance("X.509")
    var certificates                           = Seq.empty[X509Certificate]
    var start                                  = 0
    while ({ matcher.find(start) }) {
      val buffer: Array[Byte] = base64Decode(matcher.group(1))
      certificates = certificates :+ certificateFactory
        .generateCertificate(new ByteArrayInputStream(buffer))
        .asInstanceOf[X509Certificate]
      start = matcher.end
    }
    certificates
  }

  def _readPrivateKey(encodedKeySpec: KeySpec): Try[PrivateKey] = {
    Try(KeyFactory.getInstance("RSA").generatePrivate(encodedKeySpec))
      .orElse(Try(KeyFactory.getInstance("EC").generatePrivate(encodedKeySpec)))
      .orElse(Try(KeyFactory.getInstance("DSA").generatePrivate(encodedKeySpec)))
    //.orElse(Try(KeyFactory.getInstance("DiffieHellman")).map(_.generatePrivate(encodedKeySpec)))
    //.get
  }

  def _readPrivateKeySpec(
      id: String,
      content: String,
      keyPassword: Option[String],
      log: Boolean = true
  ): Either[KeyStoreError, KeySpec] = {
    if (log) logger.debug(s"Reading private key for $id")
    val matcher: Matcher = PRIVATE_KEY_PATTERN.matcher(content)
    if (!matcher.find) {
      logger.debug(s"[$id] Found no private key :(")
      Left(s"[$id] Found no private key")
    } else {
      val encodedKey: Array[Byte] = base64Decode(matcher.group(1))
      keyPassword
        .map { kpv =>
          val encryptedPrivateKeyInfo      = new EncryptedPrivateKeyInfo(encodedKey)
          val keyFactory: SecretKeyFactory = SecretKeyFactory.getInstance(encryptedPrivateKeyInfo.getAlgName)
          val secretKey: SecretKey         = keyFactory.generateSecret(new PBEKeySpec(kpv.toCharArray))
          val cipher: Cipher               = Cipher.getInstance(encryptedPrivateKeyInfo.getAlgName)
          cipher.init(DECRYPT_MODE, secretKey, encryptedPrivateKeyInfo.getAlgParameters)
          Right(encryptedPrivateKeyInfo.getKeySpec(cipher))
        }
        .getOrElse {
          Right(new PKCS8EncodedKeySpec(encodedKey))
        }
    }
  }

  def readPrivateKeyUniversal(
      id: String,
      content: String,
      keyPassword: Option[String],
      log: Boolean = true
  ): Either[KeyStoreError, PrivateKey] = {
    if (log) logger.debug(s"Reading private key for $id")
    val matcher: Matcher = PRIVATE_KEY_PATTERN.matcher(content)
    if (!matcher.find) {
      logger.debug(s"[$id] Found no private key :(")
      Left(s"[$id] Found no private key")
    } else {
      import otoroshi.utils.syntax.implicits._
      Try {
        // val reader = new PemReader(new StringReader(privateKey))
        val parser    = new PEMParser(new StringReader(content))
        val converter = new JcaPEMKeyConverter().setProvider("BC")
        parser.readObject() match {
          case ckp: PEMEncryptedKeyPair if keyPassword.isEmpty   => None
          case ckp: PEMEncryptedKeyPair if keyPassword.isDefined =>
            val decProv = new JcePEMDecryptorProviderBuilder().build(keyPassword.get.toCharArray)
            val kp      = converter.getKeyPair(ckp.decryptKeyPair(decProv))
            kp.getPrivate.some
          case ukp: PEMKeyPair                                   =>
            val kp = converter.getKeyPair(ukp)
            kp.getPrivate.some
          case _                                                 => None
        }
      } match {
        case Failure(e)         =>
          // Left(s"[$id] error while reading private key: ${e.getMessage}".debugPrintln)
          _readPrivateKeySpec(id, content, keyPassword, log).flatMap(ks =>
            _readPrivateKey(ks).toEither match {
              case Left(r)  => Left(r.getMessage)
              case Right(r) => Right(r)
            }
          )
        case Success(None)      =>
          // Left(s"[$id] no valid key found".debugPrintln)
          _readPrivateKeySpec(id, content, keyPassword, log).flatMap(ks =>
            _readPrivateKey(ks).toEither match {
              case Left(r)  => Left(r.getMessage)
              case Right(r) => Right(r)
            }
          )
        case Success(Some(key)) => Right(key)
      }
    }
  }

  def isSelfSigned(cert: X509Certificate): Boolean = {
    Try { // Try to verify certificate signature with its own public key
      val key: PublicKey = cert.getPublicKey
      cert.verify(key)
      true
    } recover { case e =>
      false
    } get
  }

  def base64Decode(base64: String): Array[Byte] = Base64.getMimeDecoder.decode(base64.getBytes(US_ASCII))

  def createSSLEngine(
      clientAuth: ClientAuth,
      cipherSuites: Option[Seq[String]],
      protocols: Option[Seq[String]]
  ): SSLEngine = {
    val context: SSLContext    = DynamicSSLEngineProvider.currentServer
    DynamicSSLEngineProvider.logger.debug(s"Create SSLEngine from: $context")
    val rawEngine              = context.createSSLEngine()
    val rawEnabledCipherSuites = rawEngine.getEnabledCipherSuites.toSeq
    val rawEnabledProtocols    = rawEngine.getEnabledProtocols.toSeq
    cipherSuites.foreach(s => rawEngine.setEnabledCipherSuites(s.toArray))
    protocols.foreach(p => rawEngine.setEnabledProtocols(p.toArray))
    val engine                 = new CustomSSLEngine(rawEngine)
    val sslParameters          = new SSLParameters
    val matchers               = new java.util.ArrayList[SNIMatcher]()

    clientAuth match {
      case ClientAuth.Want =>
        engine.setWantClientAuth(true)
        sslParameters.setWantClientAuth(true)
      case ClientAuth.Need =>
        engine.setNeedClientAuth(true)
        sslParameters.setNeedClientAuth(true)
      case _               =>
    }

    matchers.add(new SNIMatcher(0) {
      override def matches(sniServerName: SNIServerName): Boolean = {
        sniServerName match {
          case hn: SNIHostName =>
            val hostName = hn.getAsciiName
            DynamicSSLEngineProvider.logger.debug(s"createSSLEngine - for $hostName")
            engine.setEngineHostName(hostName)
          case _               =>
            DynamicSSLEngineProvider.logger.debug(s"Not a hostname :( $sniServerName")
        }
        true
      }
    })
    sslParameters.setSNIMatchers(matchers)
    cipherSuites.orElse(Some(rawEnabledCipherSuites)).foreach(s => sslParameters.setCipherSuites(s.toArray))
    protocols.orElse(Some(rawEnabledProtocols)).foreach(p => sslParameters.setProtocols(p.toArray))
    engine.setSSLParameters(sslParameters)
    engine
  }
}

/*class OtoroshiHostnameVerifier() extends HostnameVerifier {
  override def verify(s: String, sslSession: SSLSession): Boolean = {
    true
  }
}*/

class DynamicSSLEngineProvider(appProvider: ApplicationProvider) extends SSLEngineProvider {

  lazy val cipherSuites =
    appProvider.get.get.configuration
      .getOptionalWithFileSupport[Seq[String]]("otoroshi.ssl.cipherSuites")
      .filterNot(_.isEmpty)
  lazy val protocols    =
    appProvider.get.get.configuration
      .getOptionalWithFileSupport[Seq[String]]("otoroshi.ssl.protocols")
      .filterNot(_.isEmpty)
  lazy val clientAuth = {
    val auth = appProvider.get.get.configuration
      .getOptionalWithFileSupport[String]("otoroshi.ssl.fromOutside.clientAuth")
      .flatMap(ClientAuth.apply)
      .getOrElse(ClientAuth.None)
    DynamicSSLEngineProvider.logger.debug(s"Otoroshi client auth: ${auth}")
    auth
  }

  override def createSSLEngine(): SSLEngine = {
    DynamicSSLEngineProvider.createSSLEngine(clientAuth, cipherSuites, protocols)
  }

  private def setupSslContext(): SSLContext = {
    new SSLContext(
      new SSLContextSpi() {
        override def engineCreateSSLEngine(): SSLEngine                     = createSSLEngine()
        override def engineCreateSSLEngine(s: String, i: Int): SSLEngine    = engineCreateSSLEngine()
        override def engineInit(
            keyManagers: Array[KeyManager],
            trustManagers: Array[TrustManager],
            secureRandom: SecureRandom
        ): Unit                                                             = ()
        override def engineGetClientSessionContext(): SSLSessionContext     =
          DynamicSSLEngineProvider.currentServer.getClientSessionContext
        override def engineGetServerSessionContext(): SSLSessionContext     =
          DynamicSSLEngineProvider.currentServer.getServerSessionContext
        override def engineGetSocketFactory(): SSLSocketFactory             =
          DynamicSSLEngineProvider.currentServer.getSocketFactory
        override def engineGetServerSocketFactory(): SSLServerSocketFactory =
          DynamicSSLEngineProvider.currentServer.getServerSocketFactory
      },
      new Provider(
        "Otoroshi SSlEngineProvider delegate",
        1d,
        "A provider that delegates calls to otoroshi dynamic one"
      )                   {},
      "Otoroshi SSLEngineProvider delegate"
    ) {}
  }

  override def sslContext(): SSLContext = setupSslContext()
}

object noCATrustManager extends X509TrustManager {
  val nullArray            = Array[X509Certificate]()
  def checkClientTrusted(x509Certificates: Array[X509Certificate], s: String): Unit = {}
  def checkServerTrusted(x509Certificates: Array[X509Certificate], s: String): Unit = {}
  def getAcceptedIssuers() = nullArray
}

object CertificateData {

  import otoroshi.ssl.SSLImplicits._

  import collection.JavaConverters._

  private val logger                                 = Logger("otoroshi-cert-data")
  private val encoder                                = Base64.getEncoder
  private val certificateFactory: CertificateFactory = CertificateFactory.getInstance("X.509")

  private def base64Decode(base64: String): Array[Byte] = Base64.getMimeDecoder.decode(base64.getBytes(US_ASCII))

  def apply(pemContent: String): JsValue = {
    val finContent                  = PemHeaders.BeginCertificate + "\n" + pemContent + "\n" + PemHeaders.EndCertificate
    val buffer                      = base64Decode(
      pemContent.replace(PemHeaders.BeginCertificate, "").replace(PemHeaders.EndCertificate, "")
    )
    val cert                        = certificateFactory.generateCertificate(new ByteArrayInputStream(buffer)).asInstanceOf[X509Certificate]
    val altNames                    = cert.altNames
    val rawDomain                   = cert.rawDomain
    val domain: String              = cert.domain
    val pemReader                   = new org.bouncycastle.openssl.PEMParser(new StringReader(finContent))
    val holder                      = pemReader.readObject().asInstanceOf[org.bouncycastle.cert.X509CertificateHolder]
    pemReader.close()
    val usages: Array[KeyPurposeId] = Option(holder.getExtensions)
      .flatMap(exts => Option(ExtendedKeyUsage.fromExtensions(exts)))
      .map(_.getUsages)
      .getOrElse(Array.empty)
    val client: Boolean             = usages.contains(KeyPurposeId.id_kp_clientAuth)
    // val client: Boolean = Try(cert.getExtensionValue("2.5.29.37")) match {
    Json.obj(
      "issuerDN"        -> DN(cert.getIssuerDN.getName).stringify,
      "notAfter"        -> cert.getNotAfter.getTime,
      "notBefore"       -> cert.getNotBefore.getTime,
      "serialNumber"    -> cert.getSerialNumber.toString(16),
      "serialNumberLng" -> cert.getSerialNumber,
      "sigAlgName"      -> cert.getSigAlgName,
      "sigAlgOID"       -> cert.getSigAlgOID,
      "_signature"      -> new String(encoder.encode(cert.getSignature)),
      "signature"       -> DigestUtils.sha256Hex(cert.getSignature).toUpperCase().grouped(2).mkString(":"),
      "subjectDN"       -> DN(cert.getSubjectDN.getName).stringify,
      "domain"          -> domain,
      "rawDomain"       -> rawDomain.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "version"         -> cert.getVersion,
      "type"            -> cert.getType,
      "publicKey"       -> cert.getPublicKey.asPem, // new String(encoder.encode(cert.getPublicKey.getEncoded)),
      "selfSigned"      -> DynamicSSLEngineProvider.isSelfSigned(cert),
      "constraints"     -> cert.getBasicConstraints,
      "ca"              -> (cert.getBasicConstraints != -1),
      "client"          -> client,
      "subAltNames"     -> JsArray(altNames.map(JsString.apply)),
      "cExtensions"     -> JsArray(
        Option(cert.getCriticalExtensionOIDs).map(_.asScala.toSeq).getOrElse(Seq.empty[String]).map { oid =>
          val ext: String =
            Option(cert.getExtensionValue(oid)).map(bytes => ByteString(bytes).utf8String).getOrElse("--")
          Json.obj(
            "oid"   -> oid,
            "value" -> ext
          )
        }
      ),
      "ncExtensions"    -> JsArray(
        Option(cert.getNonCriticalExtensionOIDs).map(_.asScala.toSeq).getOrElse(Seq.empty[String]).map { oid =>
          val ext: String =
            Option(cert.getExtensionValue(oid)).map(bytes => ByteString(bytes).utf8String).getOrElse("--")
          Json.obj(
            "oid"   -> oid,
            "value" -> ext
          )
        }
      )
    )
  }
}

object PemHeaders {
  val BeginCertificate        = "-----BEGIN CERTIFICATE-----"
  val EndCertificate          = "-----END CERTIFICATE-----"
  val BeginPublicKey          = "-----BEGIN PUBLIC KEY-----"
  val EndPublicKey            = "-----END PUBLIC KEY-----"
  val BeginPrivateKey         = "-----BEGIN PRIVATE KEY-----"
  val EndPrivateKey           = "-----END PRIVATE KEY-----"
  val BeginPrivateRSAKey      = "-----BEGIN RSA PRIVATE KEY-----"
  val BeginPrivateECKey       = "-----BEGIN EC PRIVATE KEY-----"
  val EndPrivateRSAKey        = "-----END RSA PRIVATE KEY-----"
  val EndPrivateECKey         = "-----END EC PRIVATE KEY-----"
  val BeginCertificateRequest = "-----BEGIN CERTIFICATE REQUEST-----"
  val EndCertificateRequest   = "-----END CERTIFICATE REQUEST-----"
}

object FakeKeyStore {

  import otoroshi.ssl.SSLImplicits._

  private val EMPTY_PASSWORD = Array.emptyCharArray
  private val encoder        = Base64.getEncoder

  object SelfSigned {

    object Alias {
      val trustedCertEntry = "otoroshi-selfsigned-trust"
      val PrivateKeyEntry  = "otoroshi-selfsigned"
    }

    def DistinguishedName(host: String) = s"CN=$host, OU=Otoroshi Certificates, O=Otoroshi"
    def SubDN(host: String)             = s"CN=$host"
  }

  object KeystoreSettings {
    val SignatureAlgorithmName = "SHA256withRSA"
    val KeyPairAlgorithmName   = "RSA"
    val KeyPairKeyLength       = 2048 // 2048 is the NIST acceptable key length until 2030
    val KeystoreType           = "JKS"
  }

  private implicit val ec = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(4))

  def generateKeyStore(host: String)(implicit env: Env): KeyStore = {
    val keyStore: KeyStore = KeyStore.getInstance(KeystoreSettings.KeystoreType)
    val (cert, keyPair)    = generateX509Certificate(host)
    keyStore.load(null, EMPTY_PASSWORD)
    keyStore.setKeyEntry(SelfSigned.Alias.PrivateKeyEntry, keyPair.getPrivate, EMPTY_PASSWORD, Array(cert))
    keyStore.setCertificateEntry(SelfSigned.Alias.trustedCertEntry, cert)
    keyStore
  }

  def generateX509Certificate(host: String)(implicit env: Env): (X509Certificate, KeyPair) = {
    val resp = createSelfSignedCertificate(host, 365.days, None, None)
    (resp.cert, resp.keyPair)
  }

  def generateCert(host: String)(implicit env: Env): Cert = {
    val (cert, keyPair) = generateX509Certificate(host)
    Cert(
      id = IdGenerator.token(32),
      name = host,
      description = s"Certificate for $host",
      domain = host,
      chain = cert.asPem,
      privateKey = keyPair.getPrivate.asPem,
      caRef = None,
      autoRenew = false,
      client = false,
      exposed = false,
      revoked = false
    )
  }

  def createSelfSignedCertificate(host: String, duration: FiniteDuration, kp: Option[KeyPair], serial: Option[Long])(
      implicit env: Env
  ): GenCertResponse = {

    val f = env.pki.genSelfSignedCert(
      GenCsrQuery(
        hosts = Seq(host),
        key = GenKeyPairQuery(KeystoreSettings.KeyPairAlgorithmName, KeystoreSettings.KeyPairKeyLength),
        name = Map("CN" -> host),
        duration = duration,
        existingKeyPair = kp,
        existingSerialNumber = serial
      )
    )

    val resp = Await.result(f, 30.seconds)

    resp.right.get
  }

  def createClientCertificateFromCA(
      dn: String,
      duration: FiniteDuration,
      kp: Option[KeyPair],
      serial: Option[Long],
      ca: X509Certificate,
      caChain: Seq[X509Certificate],
      caKeyPair: KeyPair
  )(implicit env: Env): GenCertResponse = {

    val f = env.pki.genCert(
      GenCsrQuery(
        hosts = Seq.empty,
        key = GenKeyPairQuery(KeystoreSettings.KeyPairAlgorithmName, KeystoreSettings.KeyPairKeyLength),
        subject = Some(dn),
        duration = duration,
        existingKeyPair = kp,
        existingSerialNumber = serial,
        client = true
      ),
      ca,
      caChain,
      caKeyPair.getPrivate
    )

    val resp = Await.result(f, 30.seconds)

    resp.right.get
  }

  def createSelfSignedClientCertificate(
      dn: String,
      duration: FiniteDuration,
      kp: Option[KeyPair],
      serial: Option[Long]
  )(implicit env: Env): GenCertResponse = {

    val f = env.pki.genSelfSignedCert(
      GenCsrQuery(
        hosts = Seq.empty,
        key = GenKeyPairQuery(KeystoreSettings.KeyPairAlgorithmName, KeystoreSettings.KeyPairKeyLength),
        subject = Some(dn),
        duration = duration,
        existingKeyPair = kp,
        existingSerialNumber = serial,
        client = true
      )
    )

    val resp = Await.result(f, 30.seconds)

    resp.right.get
  }

  def createCertificateFromCA(
      host: String,
      duration: FiniteDuration,
      kp: Option[KeyPair],
      serial: Option[Long],
      ca: X509Certificate,
      caChain: Seq[X509Certificate],
      caKeyPair: KeyPair
  )(implicit env: Env): GenCertResponse = {

    val f = env.pki.genCert(
      GenCsrQuery(
        hosts = Seq(host),
        key = GenKeyPairQuery(KeystoreSettings.KeyPairAlgorithmName, KeystoreSettings.KeyPairKeyLength),
        name = Map("CN" -> host),
        duration = duration,
        existingKeyPair = kp,
        existingSerialNumber = serial
      ),
      ca,
      caChain,
      caKeyPair.getPrivate
    )

    val resp = Await.result(f, 30.seconds)

    resp.right.get
  }

  def createSubCa(
      cn: String,
      duration: FiniteDuration,
      kp: Option[KeyPair],
      serial: Option[Long],
      ca: X509Certificate,
      caChain: Seq[X509Certificate],
      caKeyPair: KeyPair
  )(implicit env: Env): GenCertResponse = {

    val f = env.pki.genSubCA(
      GenCsrQuery(
        hosts = Seq.empty,
        key = GenKeyPairQuery(KeystoreSettings.KeyPairAlgorithmName, KeystoreSettings.KeyPairKeyLength),
        subject = Some(cn),
        duration = duration,
        existingKeyPair = kp,
        existingSerialNumber = serial,
        ca = true
      ),
      ca,
      caChain,
      caKeyPair.getPrivate
    )

    val resp = Await.result(f, 30.seconds)

    resp.right.get
  }

  def createCA(cn: String, duration: FiniteDuration, kp: Option[KeyPair], serial: Option[Long])(implicit
      env: Env
  ): GenCertResponse = {

    val f = env.pki.genSelfSignedCA(
      GenCsrQuery(
        hosts = Seq.empty,
        key = GenKeyPairQuery(KeystoreSettings.KeyPairAlgorithmName, KeystoreSettings.KeyPairKeyLength),
        subject = Some(cn),
        duration = duration,
        existingKeyPair = kp,
        existingSerialNumber = serial,
        ca = true
      )
    )

    val resp = Await.result(f, 30.seconds)

    resp.right.get
  }
}

class CustomSSLEngine(delegate: SSLEngine) extends SSLEngine {

  // println(delegate.getClass.getName)
  // sun.security.ssl.SSLEngineImpl
  // sun.security.ssl.X509TrustManagerImpl
  // javax.net.ssl.X509ExtendedTrustManager
  private val hostnameHolder = new AtomicReference[String]()

  // TODO: add try to avoid future issue ?
  private lazy val field: Field = {
    val f = Option(classOf[SSLEngine].getDeclaredField("peerHost")).getOrElse(classOf[SSLEngine].getField("peerHost"))
    f.setAccessible(true)
    f
  }

  def setEngineHostName(hostName: String): Unit = {
    DynamicSSLEngineProvider.logger.debug(s"Setting current session hostname to $hostName")
    hostnameHolder.set(hostName)
    // TODO: add try to avoid future issue ?
    field.set(this, hostName)
    field.set(delegate, hostName)
  }

  override def getPeerHost: String = Option(hostnameHolder.get()).getOrElse(delegate.getPeerHost)

  override def getPeerPort: Int = delegate.getPeerPort

  override def wrap(byteBuffers: Array[ByteBuffer], i: Int, i1: Int, byteBuffer: ByteBuffer): SSLEngineResult =
    delegate.wrap(byteBuffers, i, i1, byteBuffer)

  override def unwrap(byteBuffer: ByteBuffer, byteBuffers: Array[ByteBuffer], i: Int, i1: Int): SSLEngineResult =
    delegate.unwrap(byteBuffer, byteBuffers, i, i1)

  override def getDelegatedTask: Runnable = delegate.getDelegatedTask

  override def closeInbound(): Unit = delegate.closeInbound()

  override def isInboundDone: Boolean = delegate.isInboundDone

  override def closeOutbound(): Unit = delegate.closeOutbound()

  override def isOutboundDone: Boolean = delegate.isOutboundDone

  override def getSupportedCipherSuites: Array[String] = delegate.getSupportedCipherSuites

  override def getEnabledCipherSuites: Array[String] = delegate.getEnabledCipherSuites

  override def setEnabledCipherSuites(strings: Array[String]): Unit = delegate.setEnabledCipherSuites(strings)

  override def getSupportedProtocols: Array[String] = delegate.getSupportedProtocols

  override def getEnabledProtocols: Array[String] = delegate.getEnabledProtocols

  override def setEnabledProtocols(strings: Array[String]): Unit = delegate.setEnabledProtocols(strings)

  override def getSession: SSLSession = delegate.getSession

  override def beginHandshake(): Unit = delegate.beginHandshake()

  override def getHandshakeStatus: SSLEngineResult.HandshakeStatus = delegate.getHandshakeStatus

  override def setUseClientMode(b: Boolean): Unit = delegate.setUseClientMode(b)

  override def getUseClientMode: Boolean = delegate.getUseClientMode

  override def setNeedClientAuth(b: Boolean): Unit = delegate.setNeedClientAuth(b)

  override def getNeedClientAuth: Boolean = delegate.getNeedClientAuth

  override def setWantClientAuth(b: Boolean): Unit = delegate.setWantClientAuth(b)

  override def getWantClientAuth: Boolean = delegate.getWantClientAuth

  override def setEnableSessionCreation(b: Boolean): Unit = delegate.setNeedClientAuth(b)

  override def getEnableSessionCreation: Boolean = delegate.getEnableSessionCreation

  override def wrap(var1: ByteBuffer, var2: ByteBuffer): SSLEngineResult = delegate.wrap(var1, var2)

  override def wrap(var1: Array[ByteBuffer], var2: ByteBuffer): SSLEngineResult = delegate.wrap(var1, var2)

  override def unwrap(var1: ByteBuffer, var2: ByteBuffer): SSLEngineResult = delegate.unwrap(var1, var2)

  override def unwrap(var1: ByteBuffer, var2: Array[ByteBuffer]): SSLEngineResult = delegate.unwrap(var1, var2)

  override def getHandshakeSession: SSLSession = delegate.getHandshakeSession

  override def getSSLParameters: SSLParameters = delegate.getSSLParameters

  override def setSSLParameters(var1: SSLParameters): Unit = delegate.setSSLParameters(var1)
}

sealed trait ClientCertificateValidationDataStore extends BasicStore[ClientCertificateValidator] {
  def getValidation(key: String)(implicit ec: ExecutionContext, env: Env): Future[Option[Boolean]]
  def setValidation(key: String, value: Boolean, ttl: Long)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def removeValidation(key: String)(implicit ec: ExecutionContext, env: Env): Future[Long]
  def template: ClientCertificateValidator = {
    ClientCertificateValidator(
      id = IdGenerator.token,
      name = "validator",
      description = "A client certificate validator",
      url = "https://validator.oto.tools:8443",
      host = "validator.oto.tools",
      noCache = false,
      alwaysValid = false,
      proxy = None
    )
  }
}

class KvClientCertificateValidationDataStore(redisCli: RedisLike, env: Env)
    extends ClientCertificateValidationDataStore
    with RedisLikeStore[ClientCertificateValidator] {

  def dsKey(k: String)(implicit env: Env): String                                                           = s"${env.storageRoot}:certificates:clients:$k"
  override def getValidation(key: String)(implicit ec: ExecutionContext, env: Env): Future[Option[Boolean]] =
    redisCli.get(dsKey(key)).map(_.map(_.utf8String.toBoolean))
  override def setValidation(key: String, value: Boolean, ttl: Long)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Boolean]                                                                                        =
    redisCli.set(dsKey(key), value.toString, pxMilliseconds = Some(ttl))
  def removeValidation(key: String)(implicit ec: ExecutionContext, env: Env): Future[Long]                  = redisCli.del(dsKey(key))

  override def fmt: Format[ClientCertificateValidator]              = ClientCertificateValidator.fmt
  override def redisLike(implicit env: Env): RedisLike              = redisCli
  override def key(id: String): otoroshi.models.Key                 =
    otoroshi.models.Key(s"${env.storageRoot}:certificates:validators:$id")
  override def extractId(value: ClientCertificateValidator): String = value.id
}

// https://tools.ietf.org/html/rfc5280
// https://tools.ietf.org/html/rfc2585
// https://tools.ietf.org/html/rfc2560
// https://en.wikipedia.org/wiki/Public_key_infrastructure
// https://en.wikipedia.org/wiki/Validation_authority
// https://en.wikipedia.org/wiki/Online_Certificate_Status_Protocol
object ClientCertificateValidator {
  val logger   = Logger("otoroshi-client-cert-validator")
  val digester = MessageDigest.getInstance("SHA-1")
  val fmt      = new Format[ClientCertificateValidator] {

    override def reads(json: JsValue): JsResult[ClientCertificateValidator] =
      Try {
        JsSuccess(
          ClientCertificateValidator(
            location = otoroshi.models.EntityLocation.readFromKey(json),
            id = (json \ "id").as[String],
            name = (json \ "name").as[String],
            description = (json \ "description").asOpt[String].getOrElse("--"),
            url = (json \ "url").as[String],
            host = (json \ "host").as[String],
            goodTtl = (json \ "goodTtl").asOpt[Long].getOrElse(10 * 60000L),
            badTtl = (json \ "badTtl").asOpt[Long].getOrElse(1 * 60000L),
            method = (json \ "method").asOpt[String].getOrElse("POST"),
            path = (json \ "path").asOpt[String].getOrElse("/certificates/_validate"),
            timeout = (json \ "timeout").asOpt[Long].getOrElse(10000L),
            noCache = (json \ "noCache").asOpt[Boolean].getOrElse(false),
            alwaysValid = (json \ "alwaysValid").asOpt[Boolean].getOrElse(false),
            headers = (json \ "headers").asOpt[Map[String, String]].getOrElse(Map.empty),
            proxy = (json \ "proxy").asOpt[JsValue].flatMap(p => WSProxyServerJson.proxyFromJson(p)),
            metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
            tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String])
          )
        )
      } recover { case e =>
        JsError(e.getMessage)
      } get

    override def writes(o: ClientCertificateValidator): JsValue =
      o.location.jsonWithKey ++ Json.obj(
        "id"          -> o.id,
        "name"        -> o.name,
        "description" -> o.description,
        "url"         -> o.url,
        "host"        -> o.host,
        "goodTtl"     -> o.goodTtl,
        "badTtl"      -> o.badTtl,
        "method"      -> o.method,
        "path"        -> o.path,
        "timeout"     -> o.timeout,
        "noCache"     -> o.noCache,
        "alwaysValid" -> o.alwaysValid,
        "headers"     -> o.headers,
        "proxy"       -> WSProxyServerJson.maybeProxyToJson(o.proxy),
        "metadata"    -> o.metadata,
        "tags"        -> JsArray(o.tags.map(JsString.apply))
      )
  }

  def fromJson(json: JsValue): Either[Seq[(JsPath, Seq[JsonValidationError])], ClientCertificateValidator] =
    ClientCertificateValidator.fmt.reads(json).asEither

  def fromJsons(value: JsValue): ClientCertificateValidator =
    try {
      fmt.reads(value).get
    } catch {
      case e: Throwable => {
        logger.error(s"Try to deserialize ${Json.prettyPrint(value)}")
        throw e
      }
    }
}

case class ClientCertificateValidator(
    id: String,
    name: String,
    description: String,
    url: String,
    host: String,
    goodTtl: Long = 10L * 60000L,
    badTtl: Long = 1L * 60000L,
    method: String = "POST",
    path: String = "/certificates/_validate",
    timeout: Long = 10000L,
    noCache: Boolean,
    alwaysValid: Boolean,
    headers: Map[String, String] = Map.empty,
    proxy: Option[WSProxyServer],
    location: otoroshi.models.EntityLocation = otoroshi.models.EntityLocation(),
    tags: Seq[String] = Seq.empty,
    metadata: Map[String, String] = Map.empty
) extends otoroshi.models.EntityLocationSupport {

  def json: JsValue                    = asJson
  def internalId: String               = id
  def theDescription: String           = description
  def theMetadata: Map[String, String] = metadata
  def theName: String                  = name
  def theTags: Seq[String]             = tags

  import otoroshi.utils.http.Implicits._

  /*
  TEST CODE

  const express = require('express');
  const bodyParser = require('body-parser');
  const x509 = require('x509');

  const app = express();

  app.use(bodyParser.text());
  app.use(bodyParser.json());

  app.post('/certificates/_validate', (req, res) => {
    console.log('need to validate the following certificate chain');
    const service = req.body.service;
    const identity = req.body.apikey || req.body.user || { email: 'nobody@oto.tools' };
    const cert = x509.parseCert(req.body.chain);
    console.log(identity, service, cert);
    if (cert.subject.emailAddress === 'john.doe@oto.tools') {
      res.send({ status: "good" });
    } else {
      res.send({ status: "revoked" });
    }
  });

  app.listen(3000, () => console.log('certificate validation server'));
   */

  import play.api.http.websocket.{Message => PlayWSMessage}
  import otoroshi.ssl.SSLImplicits._

  import scala.concurrent.duration._

  def save()(implicit ec: ExecutionContext, env: Env) = env.datastores.clientCertificateValidationDataStore.set(this)

  def asJson: JsValue = ClientCertificateValidator.fmt.writes(this)

  private def validateCertificateChain(
      chain: Seq[X509Certificate],
      desc: ServiceDescriptor,
      apikey: Option[ApiKey] = None,
      user: Option[PrivateAppsUser] = None,
      config: GlobalConfig
  )(implicit ec: ExecutionContext, env: Env): Future[Option[Boolean]] = {
    val certPayload                         = chain
      .map { cert =>
        cert.asPem
      // s"${PemHeaders.BeginCertificate}\n${Base64.getEncoder.encodeToString(cert.getEncoded)}\n${PemHeaders.EndCertificate}"
      }
      .mkString("\n")
    val payload                             = Json.obj(
      "apikey"       -> apikey.map(_.toJson.as[JsObject] - "clientSecret").getOrElse(JsNull).as[JsValue],
      "user"         -> user.map(_.toJson).getOrElse(JsNull).as[JsValue],
      "service"      -> Json.obj(
        "id"        -> desc.id,
        "name"      -> desc.name,
        "groups"    -> desc.groups,
        "domain"    -> desc.domain,
        "env"       -> desc.env,
        "subdomain" -> desc.subdomain,
        "root"      -> desc.root,
        "metadata"  -> desc.metadata
      ),
      "chain"        -> certPayload,
      "fingerprints" -> JsArray(chain.map(computeFingerPrint).map(JsString.apply))
    )
    val finalHeaders: Seq[(String, String)] =
      headers.toSeq ++ Seq("Host" -> host, "Content-Type" -> "application/json", "Accept" -> "application/json")
    env.Ws // no need for mtls here
      .url(url + path)
      .withHttpHeaders(finalHeaders: _*)
      .withMethod(method)
      .withBody(payload)
      .withRequestTimeout(Duration(timeout, TimeUnit.MILLISECONDS))
      .withMaybeProxyServer(proxy.orElse(config.proxies.authority))
      .execute()
      .map { resp =>
        resp.status match { // TODO: can be good | revoked | unknown
          case 200 =>
            (resp.json.as[JsObject] \ "status")
              .asOpt[String]
              .map(_.toLowerCase == "good") // TODO: return custom message, also device identification for logging
          case _   =>
            resp.ignore()(env.otoroshiMaterializer)
            None
        }
      }
      .recover { case e =>
        ClientCertificateValidator.logger.error("Error while validating client certificate chain", e)
        None
      }
  }

  private def getLocalValidation(key: String)(implicit ec: ExecutionContext, env: Env): Future[Option[Boolean]] = {
    env.datastores.clientCertificateValidationDataStore.getValidation(key)
  }

  private def setGoodLocalValidation(key: String)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    env.datastores.clientCertificateValidationDataStore.setValidation(key, true, goodTtl).map(_ => ())
  }

  private def setBadLocalValidation(key: String)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    env.datastores.clientCertificateValidationDataStore.setValidation(key, false, badTtl).map(_ => ())
  }

  private def computeFingerPrint(cert: X509Certificate): String = {
    Hex.encodeHexString(ClientCertificateValidator.digester.digest(cert.getEncoded())).toLowerCase()
  }

  private def computeKeyFromChain(chain: Seq[X509Certificate]): String = {
    chain.map(computeFingerPrint).mkString("-")
  }

  private def isCertificateChainValid(
      chain: Seq[X509Certificate],
      desc: ServiceDescriptor,
      apikey: Option[ApiKey] = None,
      user: Option[PrivateAppsUser] = None,
      config: GlobalConfig
  )(implicit ec: ExecutionContext, env: Env): Future[Boolean] = {
    val key = computeKeyFromChain(chain) + "-" + apikey
      .map(_.clientId)
      .orElse(user.map(_.randomId))
      .getOrElse("none") + "-" + desc.id
    if (noCache) {
      validateCertificateChain(chain, desc, apikey, user, config).map {
        case Some(bool) => bool
        case None       => false
      }
    } else {
      getLocalValidation(key).flatMap {
        case Some(true)  => FastFuture.successful(true)
        case Some(false) => FastFuture.successful(false)
        case None        => {
          validateCertificateChain(chain, desc, apikey, user, config).flatMap {
            case Some(false) => setBadLocalValidation(key).map(_ => false)
            case Some(true)  => setGoodLocalValidation(key).map(_ => true)
            case None        => setBadLocalValidation(key).map(_ => false)
          }
        }
      }
    }
  }

  private def internalValidateClientCertificates[A](
      request: RequestHeader,
      desc: ServiceDescriptor,
      apikey: Option[ApiKey] = None,
      user: Option[PrivateAppsUser] = None,
      config: GlobalConfig,
      attrs: TypedMap
  )(
      f: => Future[A]
  )(implicit ec: ExecutionContext, env: Env): Future[Either[Result, A]] = {
    request.clientCertificateChain match {
      case Some(chain) if alwaysValid => f.map(Right.apply)
      case Some(chain)                =>
        isCertificateChainValid(chain, desc, apikey, user, config).flatMap {
          case true  => f.map(Right.apply)
          case false =>
            Errors
              .craftResponseResult(
                "You're not authorized here !",
                Results.Forbidden,
                request,
                None,
                None,
                attrs = attrs
              )
              .map(Left.apply)
        }
      case None                       =>
        Errors
          .craftResponseResult(
            "You're not authorized here !!!",
            Results.Forbidden,
            request,
            None,
            None,
            attrs = attrs
          )
          .map(Left.apply)
    }
  }

  def validateClientCertificates(
      req: RequestHeader,
      desc: ServiceDescriptor,
      apikey: Option[ApiKey] = None,
      user: Option[PrivateAppsUser] = None,
      config: GlobalConfig,
      attrs: TypedMap
  )(f: => Future[Result])(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    internalValidateClientCertificates(req, desc, apikey, user, config, attrs)(f).map {
      case Left(badResult)   => badResult
      case Right(goodResult) => goodResult
    }
  }

  def wsValidateClientCertificates(
      req: RequestHeader,
      desc: ServiceDescriptor,
      apikey: Option[ApiKey] = None,
      user: Option[PrivateAppsUser] = None,
      config: GlobalConfig,
      attrs: TypedMap
  )(
      f: => Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]]
  )(implicit ec: ExecutionContext, env: Env): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] = {
    internalValidateClientCertificates(req, desc, apikey, user, config, attrs)(f).map {
      case Left(badResult)   => Left[Result, Flow[PlayWSMessage, PlayWSMessage, _]](badResult)
      case Right(goodResult) => goodResult
    }
  }

  def validateClientCertificatesGen[A](
      req: RequestHeader,
      desc: ServiceDescriptor,
      apikey: Option[ApiKey] = None,
      user: Option[PrivateAppsUser] = None,
      config: GlobalConfig,
      attrs: TypedMap
  )(
      f: => Future[Either[Result, A]]
  )(implicit ec: ExecutionContext, env: Env): Future[Either[Result, A]] = {
    internalValidateClientCertificates(req, desc, apikey, user, config, attrs)(f).map {
      case Left(badResult)   => Left[Result, A](badResult)
      case Right(goodResult) => goodResult
    }
  }
}

class VeryNiceTrustManager(managers: Seq[X509TrustManager]) extends X509ExtendedTrustManager {

  def checkClientTrusted(var1: Array[X509Certificate], var2: String): Unit = ()

  def checkServerTrusted(var1: Array[X509Certificate], var2: String): Unit = ()

  def getAcceptedIssuers: Array[X509Certificate] = managers.flatMap(_.getAcceptedIssuers).toArray

  def checkClientTrusted(var1: Array[X509Certificate], var2: String, var3: Socket): Unit = ()

  def checkServerTrusted(var1: Array[X509Certificate], var2: String, var3: Socket): Unit = ()

  def checkClientTrusted(var1: Array[X509Certificate], var2: String, var3: SSLEngine): Unit = ()

  def checkServerTrusted(var1: Array[X509Certificate], var2: String, var3: SSLEngine): Unit = ()
}

class FakeTrustManager(managers: Seq[X509TrustManager]) extends X509ExtendedTrustManager {

  def checkClientTrusted(var1: Array[X509Certificate], var2: String): Unit = {
    managers.find(m => Try(m.checkClientTrusted(var1, var2)).isSuccess)
  }

  def checkServerTrusted(var1: Array[X509Certificate], var2: String): Unit = {
    managers.find(m => Try(m.checkServerTrusted(var1, var2)).isSuccess)
  }

  def getAcceptedIssuers: Array[X509Certificate] = managers.flatMap(_.getAcceptedIssuers).toArray

  def checkClientTrusted(var1: Array[X509Certificate], var2: String, var3: Socket): Unit = {
    managers.find {
      case m: X509ExtendedTrustManager => Try(m.checkClientTrusted(var1, var2, var3)).isSuccess
      case m: X509TrustManager         => Try(m.checkClientTrusted(var1, var2)).isSuccess
    }
  }

  def checkServerTrusted(var1: Array[X509Certificate], var2: String, var3: Socket): Unit = {
    managers.find {
      case m: X509ExtendedTrustManager => Try(m.checkServerTrusted(var1, var2, var3)).isSuccess
      case m: X509TrustManager         => Try(m.checkServerTrusted(var1, var2)).isSuccess
    }
  }

  def checkClientTrusted(var1: Array[X509Certificate], var2: String, var3: SSLEngine): Unit = {
    managers.find {
      case m: X509ExtendedTrustManager => Try(m.checkClientTrusted(var1, var2, var3)).isSuccess
      case m: X509TrustManager         => Try(m.checkClientTrusted(var1, var2)).isSuccess
    }
  }

  def checkServerTrusted(var1: Array[X509Certificate], var2: String, var3: SSLEngine): Unit = {
    managers.find {
      case m: X509ExtendedTrustManager => Try(m.checkServerTrusted(var1, var2, var3)).isSuccess
      case m: X509TrustManager         => Try(m.checkServerTrusted(var1, var2)).isSuccess
    }
  }
}

object SSLImplicits {

  import collection.JavaConverters._

  private val logger = Logger("otoroshi-ssl-implicits")

  implicit class EnhancedCertificate(val cert: java.security.cert.Certificate)           extends AnyVal {
    def asPem: String =
      s"${PemHeaders.BeginCertificate}\n${Base64.getEncoder.encodeToString(cert.getEncoded).grouped(64).mkString("\n")}\n${PemHeaders.EndCertificate}\n"
  }
  implicit class EnhancedX509Certificate(val cert: X509Certificate)                      extends AnyVal {
    def asPem: String               =
      s"${PemHeaders.BeginCertificate}\n${Base64.getEncoder.encodeToString(cert.getEncoded).grouped(64).mkString("\n")}\n${PemHeaders.EndCertificate}\n"
    def altNames: Seq[String]       = CertInfo.getSubjectAlternativeNames(cert, logger).asScala.toSeq
    def rawDomain: Option[String] = {
      Option(DN(cert.getSubjectDN.getName).stringify)
        .flatMap(_.split(",").toSeq.map(_.trim).find(_.toLowerCase.startsWith("cn=")))
        .map(_.replace("CN=", "").replace("cn=", ""))
    }
    def maybeDomain: Option[String] = domains.headOption
    def domain: String              = domains.headOption.getOrElse(cert.getSubjectDN.getName)
    def domains: Seq[String]        = (rawDomain ++ altNames).toSeq
    def asJson: JsObject            =
      Json.obj(
        "subjectDN"    -> DN(cert.getSubjectDN.getName).stringify,
        "issuerDN"     -> DN(cert.getIssuerDN.getName).stringify,
        "notAfter"     -> cert.getNotAfter.getTime,
        "notBefore"    -> cert.getNotBefore.getTime,
        "serialNumber" -> cert.getSerialNumber.toString(16),
        "subjectCN"    -> Option(DN(cert.getSubjectDN.getName).stringify)
          .flatMap(_.split(",").toSeq.map(_.trim).find(_.startsWith("CN=")))
          .map(_.replace("CN=", ""))
          .getOrElse(DN(cert.getSubjectDN.getName).stringify)
          .asInstanceOf[String],
        "issuerCN"     -> Option(DN(cert.getIssuerDN.getName).stringify)
          .flatMap(_.split(",").toSeq.map(_.trim).find(_.startsWith("CN=")))
          .map(_.replace("CN=", ""))
          .getOrElse(DN(cert.getIssuerDN.getName).stringify)
          .asInstanceOf[String]
      )
  }
  implicit class EnhancedKey(val key: java.security.Key)                                 extends AnyVal {
    def asPublicKeyPem: String  =
      s"${PemHeaders.BeginPublicKey}\n${Base64.getEncoder.encodeToString(key.getEncoded).grouped(64).mkString("\n")}\n${PemHeaders.EndPublicKey}\n"
    def asPrivateKeyPem: String =
      s"${PemHeaders.BeginPrivateKey}\n${Base64.getEncoder.encodeToString(key.getEncoded).grouped(64).mkString("\n")}\n${PemHeaders.EndPrivateKey}\n"
  }
  implicit class EnhancedPublicKey(val key: PublicKey)                                   extends AnyVal {
    def asPem: String =
      s"${PemHeaders.BeginPublicKey}\n${Base64.getEncoder.encodeToString(key.getEncoded).grouped(64).mkString("\n")}\n${PemHeaders.EndPublicKey}\n"
  }
  implicit class EnhancedPrivateKey(val key: PrivateKey)                                 extends AnyVal {
    def asPem: String =
      s"${PemHeaders.BeginPrivateKey}\n${Base64.getEncoder.encodeToString(key.getEncoded).grouped(64).mkString("\n")}\n${PemHeaders.EndPrivateKey}\n"
  }
  implicit class EnhancedPKCS10CertificationRequest(val csr: PKCS10CertificationRequest) extends AnyVal {
    def asPem: String =
      s"${PemHeaders.BeginCertificateRequest}\n${Base64.getEncoder.encodeToString(csr.getEncoded).grouped(64).mkString("\n")}\n${PemHeaders.EndCertificateRequest}\n"
  }
}

object SSLSessionJavaHelper {

  val NotAllowed = "CN=NotAllowedCert"
  val BadDN      = s"$NotAllowed, OU=Auto Generated Certs, OU=Otoroshi Certificates, O=Otoroshi"

  def computeKey(session: SSLSession): Option[String] = {
    computeKey(session.toString)
  }

  def computeKey(session: String): Option[String] = {
    Try(session.split(",")(0).replace("[", "")).toOption.map { header =>
      val idAndAlg = header.replace("Session(", "").replace(")", "")
      idAndAlg.contains("|") match {
        case true  => idAndAlg.split('|').toSeq.head
        case false => idAndAlg
      }
    }
  }
}

import scala.util.control.NoStackTrace

case class NoCertificateFoundException(hostname: String)
    extends RuntimeException(s"No certificate found for: $hostname !")
    with NoStackTrace
case class NoHostFoundException()     extends RuntimeException(s"No hostname or aliases found !") with NoStackTrace
case class NoAliasesFoundException()  extends RuntimeException(s"No aliases found in SSLContext !") with NoStackTrace
case class NoHostnameFoundException() extends RuntimeException(s"No hostname found in SSLContext !") with NoStackTrace
