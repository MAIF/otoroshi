package ssl

import java.io._
import java.lang.reflect.Field
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.US_ASCII
import java.security._
import java.security.cert.{Certificate => _, _}
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern.CASE_INSENSITIVE
import java.util.regex.{Matcher, Pattern}
import java.util.{Base64, Date}

import actions.ApiAction
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import com.google.common.base.Charsets
import com.typesafe.sslconfig.ssl.{KeyManagerConfig, KeyStoreConfig, SSLConfigSettings, TrustManagerConfig, TrustStoreConfig}
import env.Env
import gateway.Errors
import javax.crypto.Cipher.DECRYPT_MODE
import javax.crypto.spec.PBEKeySpec
import javax.crypto.{Cipher, EncryptedPrivateKeyInfo, SecretKey, SecretKeyFactory}
import javax.net.ssl._
import models._
import org.apache.commons.codec.binary.Hex
import org.joda.time.{DateTime, Interval}
import play.api.Logger
import play.api.libs.json._
import play.api.libs.ws.WSProxyServer
import play.api.mvc._
import play.core.ApplicationProvider
import play.server.api.SSLEngineProvider
import redis.RedisClientMasterSlaves
import security.IdGenerator
import ssl.DynamicSSLEngineProvider.certificates
import storage.redis.RedisStore
import storage.{BasicStore, RedisLike, RedisLikeStore}
import sun.security.util.{DerValue, ObjectIdentifier}
import sun.security.x509._

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
 * git over http works with otoroshi
 * ssh and other => http tunneling like https://github.com/mathieuancelin/node-httptunnel or https://github.com/larsbrinkhoff/httptunnel or https://github.com/luizluca/bridge
 */
sealed trait ClientAuth {
  def name: String
}
object ClientAuth {

  case object None extends ClientAuth {
    def name: String = "None"
  }
  case object Want extends ClientAuth {
    def name: String = "Want"
  }
  case object Need extends ClientAuth {
    def name: String = "Need"
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

case class Cert(
    id: String,
    chain: String,
    privateKey: String,
    caRef: Option[String],
    domain: String = "--",
    selfSigned: Boolean = false,
    ca: Boolean = false,
    valid: Boolean = false,
    autoRenew: Boolean = false,
    subject: String = "--",
    from: DateTime = DateTime.now(),
    to: DateTime = DateTime.now()
) {
  def renew(duration: FiniteDuration, caOpt: Option[Cert]): Cert = {
    this match {
      case original if original.ca && original.selfSigned => {
        val keyPair: KeyPair      = original.keyPair
        val cert: X509Certificate = FakeKeyStore.createCA(original.subject, duration, keyPair)
        val certificate: Cert     = Cert(cert, keyPair, None).enrich().copy(id = original.id)
        certificate
      }
      case original if original.selfSigned => {
        val keyPair: KeyPair      = original.keyPair
        val cert: X509Certificate = FakeKeyStore.createSelfSignedCertificate(original.domain, duration, keyPair)
        val certificate: Cert     = Cert(cert, keyPair, None).enrich().copy(id = original.id)
        certificate
      }
      case original if original.caRef.isDefined && caOpt.isDefined && caOpt.get.id == original.caRef.get => {
        val ca               = caOpt.get
        val keyPair: KeyPair = original.keyPair
        val cert: X509Certificate =
          FakeKeyStore.createCertificateFromCA(original.domain, duration, keyPair, ca.certificate.get, ca.keyPair)
        val certificate: Cert = Cert(cert, keyPair, None).enrich().copy(id = original.id)
        certificate
      }
      case _ => this
    }
  }
  def password: Option[String] = None
  def save()(implicit ec: ExecutionContext, env: Env) = {
    val current = this.enrich()
    env.datastores.certificatesDataStore.set(current)
  }
  def enrich() = {
    val meta = this.metadata.get
    this.copy(
      domain = (meta \ "domain").asOpt[String].getOrElse("--"),
      selfSigned = (meta \ "selfSigned").asOpt[Boolean].getOrElse(false),
      ca = (meta \ "ca").asOpt[Boolean].getOrElse(false),
      valid = this.isValid,
      subject = (meta \ "subjectDN").as[String],
      from = (meta \ "notBefore").asOpt[Long].map(v => new DateTime(v)).getOrElse(DateTime.now()),
      to = (meta \ "notAfter").asOpt[Long].map(v => new DateTime(v)).getOrElse(DateTime.now())
    )
  }
  def delete()(implicit ec: ExecutionContext, env: Env) = env.datastores.certificatesDataStore.delete(this)
  def exists()(implicit ec: ExecutionContext, env: Env) = env.datastores.certificatesDataStore.exists(this)
  def toJson                                            = Cert.toJson(this)
  lazy val certificate: Option[X509Certificate] = Try {
    chain.split(PemHeaders.BeginCertificate).toSeq.tail.headOption.map { cert =>
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
      DynamicSSLEngineProvider.readPrivateKey(this.id, this.privateKey, this.password, false).toOption.exists {
        encodedKeySpec: PKCS8EncodedKeySpec =>
          val key: PrivateKey = Try(KeyFactory.getInstance("RSA"))
            .orElse(Try(KeyFactory.getInstance("DSA")))
            .map(_.generatePrivate(encodedKeySpec))
            .get
          val certificateChain: Seq[X509Certificate] =
            DynamicSSLEngineProvider.readCertificateChain(this.id, this.chain, false)
          if (certificateChain.isEmpty) {
            DynamicSSLEngineProvider.logger.error(s"[${this.id}] Certificate file does not contain any certificates :(")
            false
          } else {
            keyStore.setKeyEntry(this.id,
                                 key,
                                 this.password.getOrElse("").toCharArray,
                                 certificateChain.toArray[java.security.cert.Certificate])
            true
          }
      }
    } recover {
      case e =>
        DynamicSSLEngineProvider.logger.error("Error while checking certificate validity", e)
        false
    } getOrElse false
  }
  lazy val keyPair: KeyPair = {
    val privkeySpec = DynamicSSLEngineProvider.readPrivateKey(id, privateKey, None).right.get
    val privkey: PrivateKey = Try(KeyFactory.getInstance("RSA"))
      .orElse(Try(KeyFactory.getInstance("DSA")))
      .map(_.generatePrivate(privkeySpec))
      .get
    val pubkey: PublicKey = certificate.get.getPublicKey
    new KeyPair(pubkey, privkey)
  }
}

object Cert {

  val OtoroshiCA = "otoroshi-ca"

  lazy val logger = Logger("otoroshi-cert")

  def apply(cert: X509Certificate, keyPair: KeyPair, caRef: Option[String]): Cert = {
    Cert(
      id = IdGenerator.token(32),
      chain =
        s"${PemHeaders.BeginCertificate}\n${Base64.getEncoder.encodeToString(cert.getEncoded)}\n${PemHeaders.EndCertificate}",
      privateKey =
        s"${PemHeaders.BeginPrivateKey}\n${Base64.getEncoder.encodeToString(keyPair.getPrivate.getEncoded)}\n${PemHeaders.EndPrivateKey}",
      caRef = caRef,
      autoRenew = false
    )
  }

  def apply(cert: X509Certificate, keyPair: KeyPair, ca: Cert): Cert = {
    Cert(
      id = IdGenerator.token(32),
      chain = s"${PemHeaders.BeginCertificate}\n${Base64.getEncoder
        .encodeToString(cert.getEncoded)}\n${PemHeaders.EndCertificate}\n${ca.chain}",
      privateKey =
        s"${PemHeaders.BeginPrivateKey}\n${Base64.getEncoder.encodeToString(keyPair.getPrivate.getEncoded)}\n${PemHeaders.EndPrivateKey}",
      caRef = Some(ca.id),
      autoRenew = false
    )
  }

  val _fmt: Format[Cert] = new Format[Cert] {
    override def writes(cert: Cert): JsValue = Json.obj(
      "id"         -> cert.id,
      "domain"     -> cert.domain,
      "chain"      -> cert.chain,
      "caRef"      -> cert.caRef,
      "privateKey" -> cert.privateKey,
      "selfSigned" -> cert.selfSigned,
      "ca"         -> cert.ca,
      "valid"      -> cert.valid,
      "autoRenew"  -> cert.autoRenew,
      "subject"    -> cert.subject,
      "from"       -> cert.from.getMillis,
      "to"         -> cert.to.getMillis,
    )
    override def reads(json: JsValue): JsResult[Cert] =
      Try {
        Cert(
          id = (json \ "id").as[String],
          domain = (json \ "domain").as[String],
          chain = (json \ "chain").as[String],
          caRef = (json \ "caRef").asOpt[String],
          privateKey = (json \ "privateKey").asOpt[String].getOrElse(""),
          selfSigned = (json \ "selfSigned").asOpt[Boolean].getOrElse(false),
          ca = (json \ "ca").asOpt[Boolean].getOrElse(false),
          valid = (json \ "valid").asOpt[Boolean].getOrElse(false),
          autoRenew = (json \ "autoRenew").asOpt[Boolean].getOrElse(false),
          subject = (json \ "subject").asOpt[String].getOrElse("--"),
          from = (json \ "from").asOpt[Long].map(v => new DateTime(v)).getOrElse(DateTime.now()),
          to = (json \ "to").asOpt[Long].map(v => new DateTime(v)).getOrElse(DateTime.now())
        )
      } map {
        case sd => JsSuccess(sd)
      } recover {
        case t =>
          logger.error("Error while reading Cert", t)
          JsError(t.getMessage)
      } get
  }
  def toJson(value: Cert): JsValue = _fmt.writes(value)
  def fromJsons(value: JsValue): Cert =
    try {
      _fmt.reads(value).get
    } catch {
      case e: Throwable => {
        logger.error(s"Try to deserialize ${Json.prettyPrint(value)}")
        throw e
      }
    }
  def fromJsonSafe(value: JsValue): JsResult[Cert] = _fmt.reads(value)
}

trait CertificateDataStore extends BasicStore[Cert] {

  def renewCertificates()(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    def willBeInvalidSoon(cert: Cert): Boolean = {
      val enriched         = cert.enrich()
      val globalInterval   = new Interval(enriched.from, enriched.to)
      val nowInterval      = new Interval(DateTime.now(), enriched.to)
      val percentage: Long = (nowInterval.toDurationMillis * 100) / globalInterval.toDurationMillis
      percentage < 20
    }
    findAll().flatMap { certificates =>
      val renewFor = FiniteDuration(365, TimeUnit.DAYS)
      val renewableCas =
        certificates.filter(cert => cert.ca && cert.selfSigned).filter(willBeInvalidSoon).map(_.renew(renewFor, None))
      val renewableCertificates = certificates
        .filter { cert =>
          !cert.ca && (cert.selfSigned || cert.caRef.nonEmpty)
        }
        .filter(willBeInvalidSoon)
        .map(c => c.renew(renewFor, renewableCas.find(_.id == c.id)))
      val certs = renewableCas ++ renewableCertificates
      Future.sequence(certs.map(_.save())).map(_ => ())
    }
  }
}

object DynamicSSLEngineProvider {

  type KeyStoreError = String

  private val EMPTY_PASSWORD: Array[Char] = Array.emptyCharArray

  val logger = Logger("otoroshi-ssl-provider")

  private val CERT_PATTERN: Pattern = Pattern.compile(
    "-+BEGIN\\s+.*CERTIFICATE[^-]*-+(?:\\s|\\r|\\n)+" + // Header
    "([a-z0-9+/=\\r\\n]+)" +                            // Base64 text
    "-+END\\s+.*CERTIFICATE[^-]*-+", // Footer
    CASE_INSENSITIVE
  )

  private val KEY_PATTERN: Pattern = Pattern.compile(
    "-+BEGIN\\s+.*PRIVATE\\s+KEY[^-]*-+(?:\\s|\\r|\\n)+" + "([a-z0-9+/=\\r\\n]+)" + "-+END\\s+.*PRIVATE\\s+KEY[^-]*-+",
    CASE_INSENSITIVE
  )
  private val certificates = new TrieMap[String, Cert]()

  private lazy val currentContext           = new AtomicReference[SSLContext](setupContext())
  private lazy val currentSslConfigSettings = new AtomicReference[SSLConfigSettings](null)
  private val currentEnv                    = new AtomicReference[Env](null)
  private val defaultSslContext             = SSLContext.getDefault

  def setCurrentEnv(env: Env): Unit = {
    currentEnv.set(env)
  }

  private def setupContext(): SSLContext = {

    val optEnv = Option(currentEnv.get)

    val cacertPath = optEnv
      .flatMap(e => e.configuration.getOptional[String]("otoroshi.ssl.cacert.path"))
      .map(
        path =>
          path
            .replace("${JAVA_HOME}", System.getProperty("java.home"))
            .replace("$JAVA_HOME", System.getProperty("java.home"))
      )
      .getOrElse(System.getProperty("java.home") + "/lib/security/cacerts")

    val cacertPassword = optEnv
      .flatMap(e => e.configuration.getOptional[String]("otoroshi.ssl.cacert.password"))
      .getOrElse("changeit")

    val dumpPath: Option[String] =
      optEnv.flatMap(e => e.configuration.getOptional[String]("play.server.https.keyStoreDumpPath"))

    logger.debug("Setting up SSL Context ")
    val sslContext: SSLContext = SSLContext.getInstance("TLS")
    val keyStore: KeyStore     = createKeyStore(certificates.values.toSeq) //.filterNot(_.ca))
    dumpPath.foreach { path =>
      logger.debug(s"Dumping keystore at $dumpPath")
      keyStore.store(new FileOutputStream(path), EMPTY_PASSWORD)
    }
    val keyManagerFactory: KeyManagerFactory =
      Try(KeyManagerFactory.getInstance("X509")).orElse(Try(KeyManagerFactory.getInstance("SunX509"))).get
    keyManagerFactory.init(keyStore, EMPTY_PASSWORD)
    logger.debug("SSL Context init ...")
    val keyManagers: Array[KeyManager] = keyManagerFactory.getKeyManagers.map(
      m => new X509KeyManagerSnitch(m.asInstanceOf[X509KeyManager]).asInstanceOf[KeyManager]
    )
    val tm: Array[TrustManager] =
      optEnv.flatMap(e => e.configuration.getOptional[Boolean]("play.server.https.trustStore.noCaVerification")).map {
        case true  => Array[TrustManager](noCATrustManager)
        case false => createTrustStore(keyStore, cacertPath, cacertPassword)
      } orNull

    sslContext.init(keyManagers, tm, null)
    dumpPath match {
      case Some(path) => {
        currentSslConfigSettings.set(
          SSLConfigSettings()
          //.withHostnameVerifierClass(classOf[OtoroshiHostnameVerifier])
            .withKeyManagerConfig(
              KeyManagerConfig().withKeyStoreConfigs(
                List(KeyStoreConfig(None, Some(path)).withPassword(Some(String.valueOf(EMPTY_PASSWORD))))
              )
            )
            .withTrustManagerConfig(
              TrustManagerConfig().withTrustStoreConfigs(
                certificates.values.toList.map(c => TrustStoreConfig(Option(c.chain).map(_.trim), None))
              )
            )
        )
      }
      case None => {
        currentSslConfigSettings.set(
          SSLConfigSettings()
          //.withHostnameVerifierClass(classOf[OtoroshiHostnameVerifier])
            .withKeyManagerConfig(
              KeyManagerConfig().withKeyStoreConfigs(
                certificates.values.toList.map(c => KeyStoreConfig(Option(c.chain).map(_.trim), None))
              )
            )
            .withTrustManagerConfig(
              TrustManagerConfig().withTrustStoreConfigs(
                certificates.values.toList.map(c => TrustStoreConfig(Option(c.chain).map(_.trim), None))
              )
            )
        )
      }
    }
    logger.debug(s"SSL Context init done ! (${keyStore.size()})")
    SSLContext.setDefault(sslContext)
    sslContext
  }

  def current = currentContext.get()

  def sslConfigSettings: SSLConfigSettings = currentSslConfigSettings.get()

  def getHostNames(): Seq[String] = {
    certificates.values.map(_.domain).toSet.toSeq
  }

  def addCertificates(certs: Seq[Cert]): SSLContext = {
    certs.foreach(crt => certificates.put(crt.id, crt))
    val ctx = setupContext()
    currentContext.set(ctx)
    ctx
  }

  def setCertificates(certs: Seq[Cert]): SSLContext = {
    certificates.clear()
    certs.foreach(crt => certificates.put(crt.id, crt))
    val ctx = setupContext()
    currentContext.set(ctx)
    ctx
  }

  def createKeyStore(certificates: Seq[Cert]): KeyStore = {
    logger.debug(s"Creating keystore ...")
    val keyStore: KeyStore = KeyStore.getInstance("JKS")
    keyStore.load(null, null)
    certificates.foreach {
      case cert if cert.ca => {
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
          val domain = Try {
            certificateChain.head.getSubjectDN.getName
              .split(",")
              .map(_.trim)
              .find(_.toLowerCase().startsWith("cn="))
              .map(_.replace("CN=", "").replace("cn=", ""))
              .getOrElse(cert.domain)
          }.toOption.getOrElse(cert.domain)
          if (!keyStore.containsAlias(domain)) {
            keyStore.setCertificateEntry(domain, certificate)
          }
        }
      }
      case cert => {
        readPrivateKey(cert.domain, cert.privateKey, cert.password).foreach { encodedKeySpec: PKCS8EncodedKeySpec =>
          val key: PrivateKey = Try(KeyFactory.getInstance("RSA"))
            .orElse(Try(KeyFactory.getInstance("DSA")))
            .map(_.generatePrivate(encodedKeySpec))
            .get
          val certificateChain: Seq[X509Certificate] = readCertificateChain(cert.domain, cert.chain)
          if (certificateChain.isEmpty) {
            logger.error(s"[${cert.id}] Certificate file does not contain any certificates :(")
          } else {
            logger.debug(s"Adding entry for ${cert.domain} with chain of ${certificateChain.size}")
            val domain = Try {
              certificateChain.head.getSubjectDN.getName
                .split(",")
                .map(_.trim)
                .find(_.toLowerCase().startsWith("cn="))
                .map(_.replace("CN=", "").replace("cn=", ""))
                .getOrElse(cert.domain)
            }.toOption.getOrElse(cert.domain)
            keyStore.setKeyEntry(domain,
                                 key,
                                 cert.password.getOrElse("").toCharArray,
                                 certificateChain.toArray[java.security.cert.Certificate])
            certificateChain.tail.foreach { cert =>
              val id = "ca-" + cert.getSerialNumber.toString(16)
              if (!keyStore.containsAlias(id)) {
                keyStore.setCertificateEntry(id, cert)
              }
            }
          }
        }
      }
    }
    keyStore
  }

  def createTrustStore(keyStore: KeyStore, cacertPath: String, cacertPassword: String): Array[TrustManager] = {
    logger.debug(s"Creating truststore ...")
    val tmf = TrustManagerFactory.getInstance("SunX509")
    tmf.init(keyStore)
    val javaKs = KeyStore.getInstance("JKS")
    javaKs.load(new FileInputStream(cacertPath), cacertPassword.toCharArray)
    val tmf2 = TrustManagerFactory.getInstance("SunX509")
    tmf2.init(javaKs)
    Array[TrustManager](
      new FakeTrustManager((tmf.getTrustManagers ++ tmf2.getTrustManagers).map(_.asInstanceOf[X509TrustManager]).toSeq)
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

  def readPrivateKey(id: String,
                     content: String,
                     keyPassword: Option[String],
                     log: Boolean = true): Either[KeyStoreError, PKCS8EncodedKeySpec] = {
    if (log) logger.debug(s"Reading private key for $id")
    val matcher: Matcher = KEY_PATTERN.matcher(content)
    if (!matcher.find) {
      logger.error(s"[$id] Found no private key :(")
      Left(s"[$id] Found no private key")
    } else {
      val encodedKey: Array[Byte] = base64Decode(matcher.group(1))
      keyPassword
        .map { _ =>
          val encryptedPrivateKeyInfo      = new EncryptedPrivateKeyInfo(encodedKey)
          val keyFactory: SecretKeyFactory = SecretKeyFactory.getInstance(encryptedPrivateKeyInfo.getAlgName)
          val secretKey: SecretKey         = keyFactory.generateSecret(new PBEKeySpec(keyPassword.get.toCharArray))
          val cipher: Cipher               = Cipher.getInstance(encryptedPrivateKeyInfo.getAlgName)
          cipher.init(DECRYPT_MODE, secretKey, encryptedPrivateKeyInfo.getAlgParameters)
          Right(encryptedPrivateKeyInfo.getKeySpec(cipher))
        }
        .getOrElse {
          Right(new PKCS8EncodedKeySpec(encodedKey))
        }
    }
  }

  def isSelfSigned(cert: X509Certificate): Boolean = {
    Try { // Try to verify certificate signature with its own public key
      val key: PublicKey = cert.getPublicKey
      cert.verify(key)
      true
    } recover {
      case e => false
    } get
  }

  def base64Decode(base64: String): Array[Byte] = Base64.getMimeDecoder.decode(base64.getBytes(US_ASCII))
}

class OtoroshiHostnameVerifier() extends HostnameVerifier {
  override def verify(s: String, sslSession: SSLSession): Boolean = {
    true
  }
}

class DynamicSSLEngineProvider(appProvider: ApplicationProvider) extends SSLEngineProvider {

  lazy val cipherSuites =
    appProvider.get.get.configuration.getOptional[Seq[String]]("otoroshi.ssl.cipherSuites").filterNot(_.isEmpty)
  lazy val protocols =
    appProvider.get.get.configuration.getOptional[Seq[String]]("otoroshi.ssl.protocols").filterNot(_.isEmpty)
  lazy val clientAuth = {
    val auth = appProvider.get.get.configuration
      .getOptional[String]("otoroshi.ssl.fromOutside.clientAuth")
      .flatMap(ClientAuth.apply)
      .getOrElse(ClientAuth.None)
    DynamicSSLEngineProvider.logger.debug(s"Otoroshi client auth: ${auth}")
    auth
  }

  override def createSSLEngine(): SSLEngine = {
    val context: SSLContext = DynamicSSLEngineProvider.currentContext.get()
    DynamicSSLEngineProvider.logger.debug(s"Create SSLEngine from: $context")
    val rawEngine              = context.createSSLEngine()
    val rawEnabledCipherSuites = rawEngine.getEnabledCipherSuites.toSeq
    val rawEnabledProtocols    = rawEngine.getEnabledProtocols.toSeq
    cipherSuites.foreach(s => rawEngine.setEnabledCipherSuites(s.toArray))
    protocols.foreach(p => rawEngine.setEnabledProtocols(p.toArray))
    val engine        = new CustomSSLEngine(rawEngine)
    val sslParameters = new SSLParameters
    val matchers      = new java.util.ArrayList[SNIMatcher]()

    clientAuth match {
      case ClientAuth.Want =>
        engine.setWantClientAuth(true)
        sslParameters.setWantClientAuth(true)
      case ClientAuth.Need =>
        engine.setNeedClientAuth(true)
        sslParameters.setNeedClientAuth(true)
      case _ =>
    }

    matchers.add(new SNIMatcher(0) {
      override def matches(sniServerName: SNIServerName): Boolean = {
        sniServerName match {
          case hn: SNIHostName =>
            val hostName = hn.getAsciiName
            DynamicSSLEngineProvider.logger.debug(s"createSSLEngine - for $hostName")
            engine.setEngineHostName(hostName)
          case _ =>
            DynamicSSLEngineProvider.logger.debug(s"Not a hostname :( ${sniServerName.toString}")
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

object noCATrustManager extends X509TrustManager {
  val nullArray                                                                     = Array[X509Certificate]()
  def checkClientTrusted(x509Certificates: Array[X509Certificate], s: String): Unit = {}
  def checkServerTrusted(x509Certificates: Array[X509Certificate], s: String): Unit = {}
  def getAcceptedIssuers()                                                          = nullArray
}

object CertificateData {

  import collection.JavaConverters._

  private val logger                                 = Logger("otoroshi-cert-data")
  private val encoder                                = Base64.getEncoder
  private val certificateFactory: CertificateFactory = CertificateFactory.getInstance("X.509")

  private def base64Decode(base64: String): Array[Byte] = Base64.getMimeDecoder.decode(base64.getBytes(US_ASCII))

  def apply(pemContent: String): JsValue = {
    val buffer = base64Decode(
      pemContent.replace(PemHeaders.BeginCertificate, "").replace(PemHeaders.EndCertificate, "")
    )
    val cert     = certificateFactory.generateCertificate(new ByteArrayInputStream(buffer)).asInstanceOf[X509Certificate]
    val altNames = CertInfo.getSubjectAlternativeNames(cert, logger).asScala.toSeq
    val rawDomain: String = Option(cert.getSubjectDN.getName)
      .flatMap(_.split(",").toSeq.map(_.trim).find(_.startsWith("CN=")))
      .map(_.replace("CN=", ""))
      .getOrElse(cert.getSubjectDN.getName)
    val domain: String = altNames.headOption.getOrElse(rawDomain)
    Json.obj(
      "issuerDN"     -> cert.getIssuerDN.getName,
      "notAfter"     -> cert.getNotAfter.getTime,
      "notBefore"    -> cert.getNotBefore.getTime,
      "serialNumber" -> cert.getSerialNumber.toString(16),
      "sigAlgName"   -> cert.getSigAlgName,
      "sigAlgOID"    -> cert.getSigAlgOID,
      "signature"    -> new String(encoder.encode(cert.getSignature)),
      "subjectDN"    -> cert.getSubjectDN.getName,
      "domain"       -> domain,
      "rawDomain"    -> rawDomain,
      "version"      -> cert.getVersion,
      "type"         -> cert.getType,
      "publicKey"    -> new String(encoder.encode(cert.getPublicKey.getEncoded)),
      "selfSigned"   -> DynamicSSLEngineProvider.isSelfSigned(cert),
      "constraints"  -> cert.getBasicConstraints,
      "ca"           -> (cert.getBasicConstraints != -1),
      "subAltNames"  -> JsArray(altNames.map(JsString.apply)),
      "cExtensions" -> JsArray(
        Option(cert.getCriticalExtensionOIDs).map(_.asScala.toSeq).getOrElse(Seq.empty[String]).map { oid =>
          val ext: String =
            Option(cert.getExtensionValue(oid)).map(bytes => ByteString(bytes).utf8String).getOrElse("--")
          Json.obj(
            "oid"   -> oid,
            "value" -> ext
          )
        }
      ),
      "ncExtensions" -> JsArray(
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
  val BeginCertificate = "-----BEGIN CERTIFICATE-----"
  val EndCertificate   = "-----END CERTIFICATE-----"
  val BeginPublicKey   = "-----BEGIN PUBLIC KEY-----"
  val EndPublicKey     = "-----END PUBLIC KEY-----"
  val BeginPrivateKey  = "-----BEGIN PRIVATE KEY-----"
  val EndPrivateKey    = "-----END PRIVATE KEY-----"
}

object FakeKeyStore {

  private val EMPTY_PASSWORD = Array.emptyCharArray
  private val encoder        = Base64.getEncoder

  object SelfSigned {

    object Alias {
      val trustedCertEntry = "otoroshi-selfsigned-trust"
      val PrivateKeyEntry  = "otoroshi-selfsigned"
    }

    def DistinguishedName(host: String) = s"CN=$host, OU=Otoroshi Certificates, O=Otoroshi, C=FR"
    def SubDN(host: String)             = s"CN=$host"
  }

  object KeystoreSettings {
    val SignatureAlgorithmName                  = "SHA256withRSA"
    val KeyPairAlgorithmName                    = "RSA"
    val KeyPairKeyLength                        = 2048 // 2048 is the NIST acceptable key length until 2030
    val KeystoreType                            = "JKS"
    val SignatureAlgorithmOID: ObjectIdentifier = AlgorithmId.sha256WithRSAEncryption_oid
  }

  def generateKeyStore(host: String): KeyStore = {
    val keyStore: KeyStore = KeyStore.getInstance(KeystoreSettings.KeystoreType)
    val (cert, keyPair)    = generateX509Certificate(host)
    keyStore.load(null, EMPTY_PASSWORD)
    keyStore.setKeyEntry(SelfSigned.Alias.PrivateKeyEntry, keyPair.getPrivate, EMPTY_PASSWORD, Array(cert))
    keyStore.setCertificateEntry(SelfSigned.Alias.trustedCertEntry, cert)
    keyStore
  }

  def generateX509Certificate(host: String): (X509Certificate, KeyPair) = {
    val keyPairGenerator = KeyPairGenerator.getInstance(KeystoreSettings.KeyPairAlgorithmName)
    keyPairGenerator.initialize(KeystoreSettings.KeyPairKeyLength)
    val keyPair = keyPairGenerator.generateKeyPair()
    val cert    = createSelfSignedCertificate(host, FiniteDuration(365, TimeUnit.DAYS), keyPair)
    (cert, keyPair)
  }

  def generateCert(host: String): Cert = {
    val (cert, keyPair) = generateX509Certificate(host)
    Cert(
      id = IdGenerator.token(32),
      domain = host,
      chain =
        s"${PemHeaders.BeginCertificate}\n${new String(encoder.encode(cert.getEncoded), Charsets.UTF_8)}\n${PemHeaders.EndCertificate}",
      privateKey = s"${PemHeaders.BeginPrivateKey}\n${new String(encoder.encode(keyPair.getPrivate.getEncoded),
                                                                 Charsets.UTF_8)}\n${PemHeaders.EndPrivateKey}",
      caRef = None,
      autoRenew = false
    )
  }

  def createSelfSignedCertificate(host: String, duration: FiniteDuration, keyPair: KeyPair): X509Certificate = {

    import sun.security.x509.DNSName
    import sun.security.x509.GeneralNameInterface

    val certInfo = new X509CertInfo()

    // Serial number and version
    certInfo.set(X509CertInfo.SERIAL_NUMBER, new CertificateSerialNumber(new BigInteger(64, new SecureRandom())))
    certInfo.set(X509CertInfo.VERSION, new CertificateVersion(CertificateVersion.V3))

    // Validity
    val validFrom = new Date()
    val validTo   = new Date(validFrom.getTime + duration.toMillis)
    val validity  = new CertificateValidity(validFrom, validTo)
    certInfo.set(X509CertInfo.VALIDITY, validity)

    // Subject and issuer
    val owner = new X500Name(SelfSigned.DistinguishedName(host))
    certInfo.set(X509CertInfo.SUBJECT, owner)
    certInfo.set(X509CertInfo.ISSUER, owner)

    // Key and algorithm
    certInfo.set(X509CertInfo.KEY, new CertificateX509Key(keyPair.getPublic))
    val algorithm = new AlgorithmId(KeystoreSettings.SignatureAlgorithmOID)
    certInfo.set(X509CertInfo.ALGORITHM_ID, new CertificateAlgorithmId(algorithm))

    if (!host.contains("*")) {
      val extensions = new CertificateExtensions()
      val generalNames = new GeneralNames()
      generalNames.add(new GeneralName(new DNSName(host)))
      extensions.set(SubjectAlternativeNameExtension.NAME, new SubjectAlternativeNameExtension(false, generalNames))
      certInfo.set(X509CertInfo.EXTENSIONS, extensions)
    }

    // Create a new certificate and sign it
    val cert = new X509CertImpl(certInfo)
    cert.sign(keyPair.getPrivate, KeystoreSettings.SignatureAlgorithmName)

    // Since the signature provider may have a different algorithm ID to what we think it should be,
    // we need to reset the algorithm ID, and resign the certificate
    val actualAlgorithm = cert.get(X509CertImpl.SIG_ALG).asInstanceOf[AlgorithmId]
    certInfo.set(CertificateAlgorithmId.NAME + "." + CertificateAlgorithmId.ALGORITHM, actualAlgorithm)
    val newCert = new X509CertImpl(certInfo)
    newCert.sign(keyPair.getPrivate, KeystoreSettings.SignatureAlgorithmName)
    newCert
  }

  def createCertificateFromCA(host: String,
                              duration: FiniteDuration,
                              kp: KeyPair,
                              ca: X509Certificate,
                              caKeyPair: KeyPair): X509Certificate = {
    val certInfo = new X509CertInfo()

    // Serial number and version
    certInfo.set(X509CertInfo.SERIAL_NUMBER, new CertificateSerialNumber(new BigInteger(64, new SecureRandom())))
    certInfo.set(X509CertInfo.VERSION, new CertificateVersion(CertificateVersion.V3))

    // Validity
    val validFrom = new Date()
    val validTo   = new Date(validFrom.getTime + duration.toMillis)
    val validity  = new CertificateValidity(validFrom, validTo)
    certInfo.set(X509CertInfo.VALIDITY, validity)

    // Subject and issuer
    val owner = new X500Name(s"CN=$host")
    certInfo.set(X509CertInfo.SUBJECT, owner)
    certInfo.set(X509CertInfo.ISSUER, ca.getSubjectDN)

    // Key and algorithm
    certInfo.set(X509CertInfo.KEY, new CertificateX509Key(kp.getPublic))
    val algorithm = new AlgorithmId(KeystoreSettings.SignatureAlgorithmOID)
    certInfo.set(X509CertInfo.ALGORITHM_ID, new CertificateAlgorithmId(algorithm))

    if (!host.contains("*")) {
      val extensions = new CertificateExtensions()
      val generalNames = new GeneralNames()
      generalNames.add(new GeneralName(new DNSName(host)))
      extensions.set(SubjectAlternativeNameExtension.NAME, new SubjectAlternativeNameExtension(false, generalNames))
      certInfo.set(X509CertInfo.EXTENSIONS, extensions)
    }

    // Create a new certificate and sign it
    val cert                 = new X509CertImpl(certInfo)
    val issuerSigAlg: String = ca.getSigAlgName
    cert.sign(caKeyPair.getPrivate, issuerSigAlg)

    // Since the signature provider may have a different algorithm ID to what we think it should be,
    // we need to reset the algorithm ID, and resign the certificate
    val actualAlgorithm = cert.get(X509CertImpl.SIG_ALG).asInstanceOf[AlgorithmId]
    certInfo.set(CertificateAlgorithmId.NAME + "." + CertificateAlgorithmId.ALGORITHM, actualAlgorithm)
    val newCert = new X509CertImpl(certInfo)
    newCert.sign(caKeyPair.getPrivate, issuerSigAlg)
    newCert
  }

  def createCA(cn: String, duration: FiniteDuration, keyPair: KeyPair): X509Certificate = {
    val certInfo = new X509CertInfo()

    // Serial number and version
    certInfo.set(X509CertInfo.SERIAL_NUMBER, new CertificateSerialNumber(new BigInteger(64, new SecureRandom())))
    certInfo.set(X509CertInfo.VERSION, new CertificateVersion(CertificateVersion.V3))

    // Validity
    val validFrom = new Date()
    val validTo   = new Date(validFrom.getTime + duration.toMillis)
    val validity  = new CertificateValidity(validFrom, validTo)
    certInfo.set(X509CertInfo.VALIDITY, validity)

    // Subject and issuer
    val owner = new X500Name(cn)
    certInfo.set(X509CertInfo.SUBJECT, owner)
    certInfo.set(X509CertInfo.ISSUER, owner)

    // Key and algorithm
    certInfo.set(X509CertInfo.KEY, new CertificateX509Key(keyPair.getPublic))
    val algorithm = new AlgorithmId(KeystoreSettings.SignatureAlgorithmOID)
    certInfo.set(X509CertInfo.ALGORITHM_ID, new CertificateAlgorithmId(algorithm))

    val exts = new CertificateExtensions
    exts.set(BasicConstraintsExtension.NAME, new BasicConstraintsExtension(true, -1))
    certInfo.set(X509CertInfo.EXTENSIONS, exts)

    // Create a new certificate and sign it
    val cert = new X509CertImpl(certInfo)
    cert.sign(keyPair.getPrivate, KeystoreSettings.SignatureAlgorithmName)

    // Since the signature provider may have a different algorithm ID to what we think it should be,
    // we need to reset the algorithm ID, and resign the certificate
    val actualAlgorithm = cert.get(X509CertImpl.SIG_ALG).asInstanceOf[AlgorithmId]
    certInfo.set(CertificateAlgorithmId.NAME + "." + CertificateAlgorithmId.ALGORITHM, actualAlgorithm)
    val newCert = new X509CertImpl(certInfo)
    newCert.sign(keyPair.getPrivate, KeystoreSettings.SignatureAlgorithmName)
    newCert
  }

}

class CustomSSLEngine(delegate: SSLEngine) extends SSLEngine {

  // println(delegate.getClass.getName)
  // sun.security.ssl.SSLEngineImpl
  // sun.security.ssl.X509TrustManagerImpl
  // javax.net.ssl.X509ExtendedTrustManager
  private val hostnameHolder = new AtomicReference[String]()

  private lazy val field: Field = {
    val f = Option(classOf[SSLEngine].getDeclaredField("peerHost")).getOrElse(classOf[SSLEngine].getField("peerHost"))
    f.setAccessible(true)
    f
  }

  def setEngineHostName(hostName: String): Unit = {
    DynamicSSLEngineProvider.logger.debug(s"Setting current session hostname to $hostName")
    hostnameHolder.set(hostName)
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
}

class InMemoryClientCertificateValidationDataStore(redisCli: RedisLike, env: Env)
    extends ClientCertificateValidationDataStore
    with RedisLikeStore[ClientCertificateValidator] {

  def dsKey(k: String)(implicit env: Env): String = s"${env.storageRoot}:certificates:clients:$k"
  override def getValidation(key: String)(implicit ec: ExecutionContext, env: Env): Future[Option[Boolean]] =
    redisCli.get(dsKey(key)).map(_.map(_.utf8String.toBoolean))
  override def setValidation(key: String, value: Boolean, ttl: Long)(implicit ec: ExecutionContext,
                                                                     env: Env): Future[Boolean] =
    redisCli.set(dsKey(key), value.toString, pxMilliseconds = Some(ttl))
  def removeValidation(key: String)(implicit ec: ExecutionContext, env: Env): Future[Long] = redisCli.del(dsKey(key))

  override def fmt: Format[ClientCertificateValidator]              = ClientCertificateValidator.fmt
  override def redisLike(implicit env: Env): RedisLike              = redisCli
  override def key(id: String): models.Key                          = models.Key(s"${env.storageRoot}:certificates:validators:$id")
  override def extractId(value: ClientCertificateValidator): String = value.id
}

class RedisClientCertificateValidationDataStore(redisCli: RedisClientMasterSlaves, env: Env)
    extends ClientCertificateValidationDataStore
    with RedisStore[ClientCertificateValidator] {
  def dsKey(k: String)(implicit env: Env): String = s"${env.storageRoot}:certificates:clients:$k"
  override def getValidation(key: String)(implicit ec: ExecutionContext, env: Env): Future[Option[Boolean]] =
    redisCli.get(dsKey(key)).map(_.map(_.utf8String.toBoolean))
  override def setValidation(key: String, value: Boolean, ttl: Long)(implicit ec: ExecutionContext,
                                                                     env: Env): Future[Boolean] =
    redisCli.set(dsKey(key), value.toString, pxMilliseconds = Some(ttl))
  def removeValidation(key: String)(implicit ec: ExecutionContext, env: Env): Future[Long] = redisCli.del(dsKey(key))

  override def _redis(implicit env: Env): RedisClientMasterSlaves   = redisCli
  override def fmt: Format[ClientCertificateValidator]              = ClientCertificateValidator.fmt
  override def key(id: String): models.Key                          = models.Key(s"${env.storageRoot}:certificates:validators:$id")
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
  val fmt = new Format[ClientCertificateValidator] {

    override def reads(json: JsValue): JsResult[ClientCertificateValidator] =
      Try {
        JsSuccess(
          ClientCertificateValidator(
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
            proxy = (json \ "proxy").asOpt[JsValue].flatMap(p => WSProxyServerJson.proxyFromJson(p))
          )
        )
      } recover {
        case e => JsError(e.getMessage)
      } get

    override def writes(o: ClientCertificateValidator): JsValue = Json.obj(
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
      "proxy"       -> WSProxyServerJson.maybeProxyToJson(o.proxy)
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
    proxy: Option[WSProxyServer]
) {

  import utils.http.Implicits._

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
    const identity = req.body.apikey || req.body.user || { email: 'nobody@foo.bar' };
    const cert = x509.parseCert(req.body.chain);
    console.log(identity, service, cert);
    if (cert.subject.emailAddress === 'john.doe@foo.bar') {
      res.send({ status: "good" });
    } else {
      res.send({ status: "revoked" });
    }
  });

  app.listen(3000, () => console.log('certificate validation server'));
   */

  import play.api.http.websocket.{Message => PlayWSMessage}

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
    val certPayload = chain
      .map { cert =>
        s"${PemHeaders.BeginCertificate}\n${Base64.getEncoder.encodeToString(cert.getEncoded)}\n${PemHeaders.EndCertificate}"
      }
      .mkString("\n")
    val payload = Json.obj(
      "apikey" -> apikey.map(_.toJson.as[JsObject] - "clientSecret").getOrElse(JsNull).as[JsValue],
      "user"   -> user.map(_.toJson).getOrElse(JsNull).as[JsValue],
      "service" -> Json.obj(
        "id"        -> desc.id,
        "name"      -> desc.name,
        "groupId"   -> desc.groupId,
        "domain"    -> desc.domain,
        "env"       -> desc.env,
        "subdomain" -> desc.subdomain,
        "root"      -> desc.root,
        "metadata"  -> desc.metadata
      ),
      "chain"        -> certPayload,
      "fingerprints" -> JsArray(chain.map(computeFingerPrint).map(JsString.apply))
    )
    val finalHeaders: Seq[(String, String)] = headers.toSeq ++ Seq("Host" -> host,
                                                                   "Content-Type" -> "application/json",
                                                                   "Accept"       -> "application/json")
    env.Ws
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
          case _ => None
        }
      }
      .recover {
        case e =>
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
        case None => {
          validateCertificateChain(chain, desc, apikey, user, config).flatMap {
            case Some(false) => setBadLocalValidation(key).map(_ => false)
            case Some(true)  => setGoodLocalValidation(key).map(_ => true)
            case None        => setBadLocalValidation(key).map(_ => false)
          }
        }
      }
    }
  }

  private def internalValidateClientCertificates[A](request: RequestHeader,
                                                    desc: ServiceDescriptor,
                                                    apikey: Option[ApiKey] = None,
                                                    user: Option[PrivateAppsUser] = None,
                                                    config: GlobalConfig)(
      f: => Future[A]
  )(implicit ec: ExecutionContext, env: Env): Future[Either[Result, A]] = {
    request.clientCertificateChain match {
      case Some(chain) if alwaysValid => f.map(Right.apply)
      case Some(chain) =>
        isCertificateChainValid(chain, desc, apikey, user, config).flatMap {
          case true => f.map(Right.apply)
          case false =>
            Errors
              .craftResponseResult(
                "You're not authorized here !",
                Results.Forbidden,
                request,
                None,
                None
              )
              .map(Left.apply)
        }
      case None =>
        Errors
          .craftResponseResult(
            "You're not authorized here !!!",
            Results.Forbidden,
            request,
            None,
            None
          )
          .map(Left.apply)
    }
  }

  def validateClientCertificates(
      req: RequestHeader,
      desc: ServiceDescriptor,
      apikey: Option[ApiKey] = None,
      user: Option[PrivateAppsUser] = None,
      config: GlobalConfig
  )(f: => Future[Result])(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    internalValidateClientCertificates(req, desc, apikey, user, config)(f).map {
      case Left(badResult)   => badResult
      case Right(goodResult) => goodResult
    }
  }

  def wsValidateClientCertificates(req: RequestHeader,
                                   desc: ServiceDescriptor,
                                   apikey: Option[ApiKey] = None,
                                   user: Option[PrivateAppsUser] = None,
                                   config: GlobalConfig)(
      f: => Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]]
  )(implicit ec: ExecutionContext, env: Env): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] = {
    internalValidateClientCertificates(req, desc, apikey, user, config)(f).map {
      case Left(badResult)   => Left[Result, Flow[PlayWSMessage, PlayWSMessage, _]](badResult)
      case Right(goodResult) => goodResult
    }
  }
}

class ClientValidatorsController(ApiAction: ApiAction, cc: ControllerComponents)(
    implicit env: Env
) extends AbstractController(cc) {

  import gnieh.diffson.playJson._
  import utils.future.Implicits._

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  def findAllClientValidators() = ApiAction.async { ctx =>
    env.datastores.clientCertificateValidationDataStore.findAll().map(all => Ok(JsArray(all.map(_.asJson))))
  }

  def findClientValidatorById(id: String) = ApiAction.async { ctx =>
    env.datastores.clientCertificateValidationDataStore.findById(id).map {
      case Some(verifier) => Ok(verifier.asJson)
      case None =>
        NotFound(
          Json.obj("error" -> s"ClientCertificateValidator with id $id not found")
        )
    }
  }

  def createClientValidator() = ApiAction.async(parse.json) { ctx =>
    ClientCertificateValidator.fromJson(ctx.request.body) match {
      case Left(_) => BadRequest(Json.obj("error" -> "Bad ClientValidator format")).asFuture
      case Right(newVerifier) =>
        env.datastores.clientCertificateValidationDataStore.set(newVerifier).map(_ => Ok(newVerifier.asJson))
    }
  }

  def updateClientValidator(id: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.clientCertificateValidationDataStore.findById(id).flatMap {
      case None =>
        NotFound(
          Json.obj("error" -> s"ClientValidator with id $id not found")
        ).asFuture
      case Some(verifier) => {
        ClientCertificateValidator.fromJson(ctx.request.body) match {
          case Left(_) => BadRequest(Json.obj("error" -> "Bad ClientValidator format")).asFuture
          case Right(newVerifier) => {
            env.datastores.clientCertificateValidationDataStore.set(newVerifier).map(_ => Ok(newVerifier.asJson))
          }
        }
      }
    }
  }

  def patchClientValidator(id: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.clientCertificateValidationDataStore.findById(id).flatMap {
      case None =>
        NotFound(
          Json.obj("error" -> s"ClientValidator with id $id not found")
        ).asFuture
      case Some(verifier) => {
        val currentJson = verifier.asJson
        val patch       = JsonPatch(ctx.request.body)
        val newVerifier = patch(currentJson)
        ClientCertificateValidator.fromJson(newVerifier) match {
          case Left(_) => BadRequest(Json.obj("error" -> "Bad ClientValidator format")).asFuture
          case Right(newVerifier) => {
            env.datastores.clientCertificateValidationDataStore.set(newVerifier).map(_ => Ok(newVerifier.asJson))
          }
        }
      }
    }
  }

  def deleteClientValidator(id: String) = ApiAction.async { ctx =>
    env.datastores.clientCertificateValidationDataStore.delete(id).map(_ => Ok(Json.obj("done" -> true)))
  }

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

/**
FROM ubuntu:18.04

RUN apt-get update && apt-get install -y --no-install-recommends \
  autoconf \
  bison \
  build-essential \
  ca-certificates \
  curl \
  gzip \
  libreadline-dev \
  patch \
  pkg-config \
  sed \
  zlib1g-dev

RUN mkdir -p /build/openssl
RUN mkdir -p /build/curl

RUN curl -s https://www.openssl.org/source/openssl-1.1.1a.tar.gz | tar -C /build/openssl -xzf - && \
    cd /build/openssl/openssl-1.1.1a && \
    ./Configure \
      --prefix=/opt/openssl/openssl-1.1.1 \
      enable-crypto-mdebug enable-crypto-mdebug-backtrace \
      linux-x86_64 && \
    make && make install_sw

ENV LD_LIBRARY_PATH /opt/openssl/openssl-1.1.1/lib

RUN curl -s https://curl.haxx.se/download/curl-7.62.0.tar.gz | tar -C /build/curl -xzf - && \
    cd /build/curl/curl-7.62.0 && \
    env PKG_CONFIG_PATH=/opt/openssl/openssl-1.1.1/lib/pkgconfig ./configure --with-ssl --prefix=/opt/curl/curl-7.62 && make && make install

CMD ["/opt/curl/curl-7.62/bin/curl", "--tlsv1.3", "https://enabled.tls13.com/"]
**/

/**
# https://blog.codeship.com/how-to-set-up-mutual-tls-authentication/
openssl genrsa -aes256 -out ca/ca.key 4096
# chmod 400 ca/ca.key
# with CN=CA-ROOT
openssl req -new -x509 -sha256 -days 730 -key ca/ca.key -out ca/ca.crt
# chmod 444 ca/ca.crt
openssl genrsa -out server/app.foo.bar.key 2048
# chmod 400 server/app.foo.bar.key
# with CN=*.foo.bar
openssl req -new -key server/app.foo.bar.key -sha256 -out server/app.foo.bar.csr
openssl x509 -req -days 365 -sha256 -in server/app.foo.bar.csr -CA ca/ca.crt -CAkey ca/ca.key -set_serial 1 -out server/app.foo.bar.crt
# chmod 444 server/app.foo.bar.crt
openssl verify -CAfile ca/ca.crt server/app.foo.bar.crt
openssl genrsa -out client/client.key 2048
# with CN and other stuff about the actual device/user
openssl req -new -key client/client.key -out client/client.csr
openssl x509 -req -days 365 -sha256 -in client/client.csr -CA ca/ca.crt -CAkey ca/ca.key -set_serial 2 -out client/client.crt
openssl pkcs12 -export -clcerts -in client/client.crt -inkey client/client.key -out client/client.p12
**/
