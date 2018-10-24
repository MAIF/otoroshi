package ssl

import java.io._
import java.lang.reflect.Field
import java.math.BigInteger
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

import akka.util.ByteString
import com.google.common.base.Charsets
import env.Env
import javax.crypto.Cipher.DECRYPT_MODE
import javax.crypto.spec.PBEKeySpec
import javax.crypto.{Cipher, EncryptedPrivateKeyInfo, SecretKey, SecretKeyFactory}
import javax.net.ssl._
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json._
import play.core.ApplicationProvider
import play.server.api.SSLEngineProvider
import security.IdGenerator
import storage.BasicStore
import sun.security.util.ObjectIdentifier
import sun.security.x509._

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

// TODO: doc + swagger
case class Cert(
    id: String,
    chain: String,
    privateKey: String,
    caRef: Option[String],
    domain: String = "--",
    selfSigned: Boolean = false,
    ca: Boolean = false,
    valid: Boolean = false,
    subject: String = "--",
    from: DateTime = DateTime.now(),
    to: DateTime = DateTime.now()
) {
  def password: Option[String]                          = None
  def save()(implicit ec: ExecutionContext, env: Env)   = {
    val current = this.enrich()
    env.datastores.certificatesDataStore.set(current)
  }
  def enrich()(implicit ec: ExecutionContext, env: Env)   = {
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
      val content: String = cert.replace(PemHeaders.EndCertificate, "")
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
      chain = s"${PemHeaders.BeginCertificate}\n${Base64.getEncoder.encodeToString(cert.getEncoded)}\n${PemHeaders.EndCertificate}",
      privateKey = s"${PemHeaders.BeginPrivateKey}\n${Base64.getEncoder.encodeToString(keyPair.getPrivate.getEncoded)}\n${PemHeaders.EndPrivateKey}",
      caRef = caRef
    )
  }

  def apply(cert: X509Certificate, keyPair: KeyPair, ca: Cert): Cert = {
    Cert(
      id = IdGenerator.token(32),
      chain = s"${PemHeaders.BeginCertificate}\n${Base64.getEncoder.encodeToString(cert.getEncoded)}\n${PemHeaders.EndCertificate}\n${ca.chain}",
      privateKey = s"${PemHeaders.BeginPrivateKey}\n${Base64.getEncoder.encodeToString(keyPair.getPrivate.getEncoded)}\n${PemHeaders.EndPrivateKey}",
      caRef = Some(ca.id)
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
          privateKey = (json \ "privateKey").as[String],
          selfSigned = (json \ "selfSigned").asOpt[Boolean].getOrElse(false),
          ca = (json \ "ca").asOpt[Boolean].getOrElse(false),
          valid = (json \ "valid").asOpt[Boolean].getOrElse(false),
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

trait CertificateDataStore extends BasicStore[Cert]

object DynamicSSLEngineProvider {

  type KeyStoreError = String

  private val EMPTY_PASSWORD = Array.emptyCharArray

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

  private lazy val currentContext = new AtomicReference[SSLContext](setupContext())
  private val currentEnv          = new AtomicReference[Env](null)

  def setCurrentEnv(env: Env): Unit = {
    currentEnv.set(env)
  }

  private def setupContext(): SSLContext = {

    val optEnv = Option(currentEnv.get)

    val tm: Array[TrustManager] =
      optEnv.flatMap(e => e.configuration.getOptional[Boolean]("play.server.https.trustStore.noCaVerification")).map {
        case true  => Array[TrustManager](noCATrustManager)
        case false => null
      } orNull

    val dumpPath: Option[String] =
      optEnv.flatMap(e => e.configuration.getOptional[String]("play.server.https.keyStoreDumpPath"))

    logger.debug("Setting up SSL Context ")
    val sslContext: SSLContext = SSLContext.getInstance("TLS")
    val keyStore: KeyStore     = createKeyStore(certificates.values.toSeq)//.filterNot(_.ca))
    dumpPath.foreach { path =>
      logger.warn(s"Dumping keystore at $dumpPath")
      keyStore.store(new FileOutputStream(path), EMPTY_PASSWORD)
    }
    val keyManagerFactory: KeyManagerFactory =
      Try(KeyManagerFactory.getInstance("X509")).orElse(Try(KeyManagerFactory.getInstance("SunX509"))).get
    keyManagerFactory.init(keyStore, EMPTY_PASSWORD)
    logger.debug("SSL Context init ...")
    val keyManagers: Array[KeyManager] = keyManagerFactory.getKeyManagers.map(
      m => new X509KeyManagerSnitch(m.asInstanceOf[X509KeyManager]).asInstanceOf[KeyManager]
    )
    sslContext.init(keyManagers, tm, null)
    logger.debug(s"SSL Context init done ! (${keyStore.size()})")
    SSLContext.setDefault(sslContext)
    sslContext
  }

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
              certificateChain.head
                .getSubjectDN.getName
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

class DynamicSSLEngineProvider(appProvider: ApplicationProvider) extends SSLEngineProvider {

  override def createSSLEngine(): SSLEngine = {
    val context: SSLContext = DynamicSSLEngineProvider.currentContext.get()
    DynamicSSLEngineProvider.logger.debug(s"Create SSLEngine from: $context")
    val engine = new CustomSSLEngine(context.createSSLEngine())
    val sslParameters = new SSLParameters
    val matchers = new java.util.ArrayList[SNIMatcher]()
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

  private val encoder                                = Base64.getEncoder
  private val certificateFactory: CertificateFactory = CertificateFactory.getInstance("X.509")

  private def base64Decode(base64: String): Array[Byte] = Base64.getMimeDecoder.decode(base64.getBytes(US_ASCII))

  def apply(pemContent: String): JsValue = {
    val buffer = base64Decode(
      pemContent.replace(PemHeaders.BeginCertificate, "").replace(PemHeaders.EndCertificate, "")
    )
    val cert = certificateFactory.generateCertificate(new ByteArrayInputStream(buffer)).asInstanceOf[X509Certificate]
    val domain: String = Option(cert.getSubjectDN.getName).flatMap(_.split(",").toSeq.map(_.trim).find(_.startsWith("CN="))).map(_.replace("CN=", "")).getOrElse(cert.getSubjectDN.getName)
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
      "version"      -> cert.getVersion,
      "type"         -> cert.getType,
      "publicKey"    -> new String(encoder.encode(cert.getPublicKey.getEncoded)),
      "selfSigned"   -> DynamicSSLEngineProvider.isSelfSigned(cert),
      "constraints"  -> cert.getBasicConstraints,
      "ca"           -> (cert.getBasicConstraints != -1),
      "cExtensions"  -> JsArray(Option(cert.getCriticalExtensionOIDs).map(_.asScala.toSeq).getOrElse(Seq.empty[String]).map { oid =>
        val ext: String = Option(cert.getExtensionValue(oid)).map(bytes => ByteString(bytes).utf8String).getOrElse("--")
        Json.obj(
          "oid" -> oid,
          "value" -> ext
        )
      }),
      "ncExtensions" -> JsArray(Option(cert.getNonCriticalExtensionOIDs).map(_.asScala.toSeq).getOrElse(Seq.empty[String]).map { oid =>
        val ext: String = Option(cert.getExtensionValue(oid)).map(bytes => ByteString(bytes).utf8String).getOrElse("--")
        Json.obj(
          "oid" -> oid,
          "value" -> ext
        )
      })
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
      privateKey =
        s"${PemHeaders.BeginPrivateKey}\n${new String(encoder.encode(keyPair.getPrivate.getEncoded), Charsets.UTF_8)}\n${PemHeaders.EndPrivateKey}",
      caRef = None
    )
  }

  def createSelfSignedCertificate(host: String, duration: FiniteDuration, keyPair: KeyPair): X509Certificate = {
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

  def createCertificateFromCA(host: String, duration: FiniteDuration, kp: KeyPair, ca: X509Certificate, caKeyPair: KeyPair): X509Certificate = {
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

    // Create a new certificate and sign it
    val cert = new X509CertImpl(certInfo)
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
    val bce = new BasicConstraintsExtension(true, -1)
    exts.set(BasicConstraintsExtension.NAME, new BasicConstraintsExtension(false, bce.getExtensionValue))
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

  override def wrap(byteBuffers: Array[ByteBuffer], i: Int, i1: Int, byteBuffer: ByteBuffer): SSLEngineResult = delegate.wrap(byteBuffers, i, i1, byteBuffer)

  override def unwrap(byteBuffer: ByteBuffer, byteBuffers: Array[ByteBuffer], i: Int, i1: Int): SSLEngineResult = delegate.unwrap(byteBuffer, byteBuffers, i, i1)

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
