package ssl

import java.io._
import java.math.BigInteger
import java.nio.charset.StandardCharsets.US_ASCII
import java.security._
import java.security.cert.{Certificate => _, _}
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern.CASE_INSENSITIVE
import java.util.regex.{Matcher, Pattern}
import java.util.{Base64, Date}

import com.google.common.base.Charsets
import env.Env
import javax.crypto.Cipher.DECRYPT_MODE
import javax.crypto.spec.PBEKeySpec
import javax.crypto.{Cipher, EncryptedPrivateKeyInfo, SecretKey, SecretKeyFactory}
import javax.net.ssl._
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
import scala.util.Try

// TODO: doc + swagger
case class Cert(id: String, domain: String, chain: String, privateKey: String) {
  def password: Option[String] = None
  def save()(implicit ec: ExecutionContext, env: Env)   = env.datastores.certificatesDataStore.set(this)
  def delete()(implicit ec: ExecutionContext, env: Env) = env.datastores.certificatesDataStore.delete(this)
  def exists()(implicit ec: ExecutionContext, env: Env) = env.datastores.certificatesDataStore.exists(this)
  def toJson                                            = Cert.toJson(this)
  def isValid(): Boolean = Try {
    val keyStore: KeyStore = KeyStore.getInstance("JKS")
    keyStore.load(null, null)
    DynamicSSLEngineProvider.readPrivateKey(this.id, this.privateKey, this.password, false).toOption.exists { encodedKeySpec: PKCS8EncodedKeySpec =>
      val key: PrivateKey = Try(KeyFactory.getInstance("RSA")).orElse(Try(KeyFactory.getInstance("DSA"))).map(_.generatePrivate(encodedKeySpec)).get
      val certificateChain: Seq[X509Certificate] = DynamicSSLEngineProvider.readCertificateChain(this.id, this.chain, false)
      if (certificateChain.isEmpty) {
        DynamicSSLEngineProvider.logger.error(s"[${this.id}] Certificate file does not contain any certificates :(")
        false
      } else {
        keyStore.setKeyEntry(this.id, key, this.password.getOrElse("").toCharArray, certificateChain.toArray[java.security.cert.Certificate])
        true
      }
    }
  } recover {
    case e =>
      e.printStackTrace
      false
  } getOrElse false
}

object Cert {

  lazy val logger = Logger("otoroshi-cert")

  val _fmt: Format[Cert] = new Format[Cert] {
    override def writes(cert: Cert): JsValue = Json.obj(
      "id"       -> cert.id,
      "domain"     -> cert.domain,
      "chain"      -> cert.chain,
      "privateKey" -> cert.privateKey
    )
    override def reads(json: JsValue): JsResult[Cert] =
      Try {
        Cert(
          id = (json \ "id").as[String],
          domain = (json \ "domain").as[String],
          chain = (json \ "chain").as[String],
          privateKey = (json \ "privateKey").as[String]
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

  val logger = Logger("otoroshi-ssl-provider")

  private val CERT_PATTERN: Pattern = Pattern.compile("-+BEGIN\\s+.*CERTIFICATE[^-]*-+(?:\\s|\\r|\\n)+" + // Header
    "([a-z0-9+/=\\r\\n]+)" + // Base64 text
    "-+END\\s+.*CERTIFICATE[^-]*-+", // Footer
    CASE_INSENSITIVE)

  private val KEY_PATTERN: Pattern = Pattern.compile("-+BEGIN\\s+.*PRIVATE\\s+KEY[^-]*-+(?:\\s|\\r|\\n)+" + "([a-z0-9+/=\\r\\n]+)" + "-+END\\s+.*PRIVATE\\s+KEY[^-]*-+", CASE_INSENSITIVE)
  private val certificates = new TrieMap[String, Cert]()

  private lazy val currentContext = new AtomicReference[SSLContext](null) //setupContext())
  private val currentEnv = new AtomicReference[Env](null)

  def setCurrentEnv(env: Env): Unit = {
    currentEnv.set(env)
  }

  private def setupContext(): SSLContext = {

    val optEnv = Option(currentEnv.get)

    val tm: Array[TrustManager] = optEnv.flatMap(e => e.configuration.getOptional[Boolean]("play.server.https.trustStore.noCaVerification")).map {
      case true => Array[TrustManager](noCATrustManager)
      case false => null
    } orNull

    val dumpPath: Option[String] = optEnv.flatMap(e => e.configuration.getOptional[String]("play.server.https.keyStoreDumpPath"))

    logger.debug("Setting up SSL Context ")
    val sslContext: SSLContext = SSLContext.getInstance("TLS")
    val keyStore: KeyStore = createKeyStore(certificates.values.toSeq)
    dumpPath.foreach(path => keyStore.store(new FileOutputStream(path), "".toCharArray))
    val keyManagerFactory: KeyManagerFactory = Try(KeyManagerFactory.getInstance("X509")).orElse(Try(KeyManagerFactory.getInstance("SunX509"))).get
    keyManagerFactory.init(keyStore, "".toCharArray)
    logger.debug("SSL Context init ...")
    val keyManagers: Array[KeyManager] = keyManagerFactory.getKeyManagers.map(m => new X509KeyManagerSnitch(m.asInstanceOf[X509KeyManager]).asInstanceOf[KeyManager])
    sslContext.init(keyManagers, tm, null)
    logger.debug(s"SSL Context init done ! (${keyStore.size()})")
    SSLContext.setDefault(sslContext)
    sslContext
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
    certificates.foreach { cert =>
      readPrivateKey(cert.domain, cert.privateKey, cert.password).foreach { encodedKeySpec: PKCS8EncodedKeySpec =>
        val key: PrivateKey = Try(KeyFactory.getInstance("RSA")).orElse(Try(KeyFactory.getInstance("DSA"))).map(_.generatePrivate(encodedKeySpec)).get
        val certificateChain: Seq[X509Certificate] = readCertificateChain(cert.domain, cert.chain)
        if (certificateChain.isEmpty) {
          logger.error(s"[${cert.id}] Certificate file does not contain any certificates :(")
        } else {
          logger.debug(s"Adding entry for ${cert.domain} with chain of ${certificateChain.size}")
          keyStore.setKeyEntry(cert.domain, key, cert.password.getOrElse("").toCharArray, certificateChain.toArray[java.security.cert.Certificate])
          certificateChain.tail.foreach { cert =>
            val id = cert.getSerialNumber.toString(16)
            if (!keyStore.containsAlias(id)) {
              keyStore.setCertificateEntry(s"ca-$id", cert)
            }
          }
        }
      }
    }
    keyStore
  }

  def readCertificateChain(id: String, certificateChain: String, log: Boolean = true): Seq[X509Certificate] = {
    if (log) logger.debug(s"Reading cert chain for $id")
    val matcher: Matcher = CERT_PATTERN.matcher(certificateChain)
    val certificateFactory: CertificateFactory = CertificateFactory.getInstance("X.509")
    var certificates = Seq.empty[X509Certificate]
    var start = 0
    while ({  matcher.find(start) }) {
      val buffer: Array[Byte] = base64Decode(matcher.group(1))
      certificates = certificates :+ certificateFactory.generateCertificate(new ByteArrayInputStream(buffer)).asInstanceOf[X509Certificate]
      start = matcher.end
    }
    certificates
  }

  def readPrivateKey(id: String, content: String, keyPassword: Option[String], log: Boolean = true): Either[KeyStoreError, PKCS8EncodedKeySpec] = {
    if (log) logger.debug(s"Reading private key for $id")
    val matcher: Matcher = KEY_PATTERN.matcher(content)
    if (!matcher.find) {
      logger.error(s"[$id] Found no private key :(")
      Left(s"[$id] Found no private key")
    } else {
      val encodedKey: Array[Byte] = base64Decode(matcher.group(1))
      keyPassword.map { _ =>
        val encryptedPrivateKeyInfo = new EncryptedPrivateKeyInfo(encodedKey)
        val keyFactory: SecretKeyFactory = SecretKeyFactory.getInstance(encryptedPrivateKeyInfo.getAlgName)
        val secretKey: SecretKey = keyFactory.generateSecret(new PBEKeySpec(keyPassword.get.toCharArray))
        val cipher: Cipher = Cipher.getInstance(encryptedPrivateKeyInfo.getAlgName)
        cipher.init(DECRYPT_MODE, secretKey, encryptedPrivateKeyInfo.getAlgParameters)
        Right(encryptedPrivateKeyInfo.getKeySpec(cipher))
      }.getOrElse {
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

  private def base64Decode(base64: String): Array[Byte] = Base64.getMimeDecoder.decode(base64.getBytes(US_ASCII))
}

object noCATrustManager extends X509TrustManager {
  val nullArray = Array[X509Certificate]()
  def checkClientTrusted(x509Certificates: Array[X509Certificate], s: String): Unit = {}
  def checkServerTrusted(x509Certificates: Array[X509Certificate], s: String): Unit = {}
  def getAcceptedIssuers() = nullArray
}

class DynamicSSLEngineProvider(appProvider: ApplicationProvider) extends SSLEngineProvider {

  override def createSSLEngine(): SSLEngine = {
    val context: SSLContext = DynamicSSLEngineProvider.currentContext.get()
    DynamicSSLEngineProvider.logger.debug(s"Create SSLEngine from: $context")
    context.createSSLEngine()
  }
}

object CertificateData {

  private val encoder = Base64.getEncoder
  private val certificateFactory: CertificateFactory = CertificateFactory.getInstance("X.509")

  private def base64Decode(base64: String): Array[Byte] = Base64.getMimeDecoder.decode(base64.getBytes(US_ASCII))

  def apply(pemContent: String): JsValue = {
    val buffer = base64Decode(pemContent.replace(PemHeaders.BeginCertificate, "").replace(PemHeaders.EndCertificate, ""))
    val cert = certificateFactory.generateCertificate(new ByteArrayInputStream(buffer)).asInstanceOf[X509Certificate]
    Json.obj(
      "issuerDN" -> cert.getIssuerDN.getName,
      "notAfter" -> cert.getNotAfter.getTime,
      "notBefore" -> cert.getNotBefore.getTime,
      "serialNumber" -> cert.getSerialNumber.toString(16),
      "sigAlgName" -> cert.getSigAlgName,
      "sigAlgOID" -> cert.getSigAlgOID,
      "signature" -> new String(encoder.encode(cert.getSignature)),
      "subjectDN" -> cert.getSubjectDN.getName,
      "version" -> cert.getVersion,
      "type" -> cert.getType,
      "publicKey" -> new String(encoder.encode(cert.getPublicKey.getEncoded)),
      "selfSigned" -> DynamicSSLEngineProvider.isSelfSigned(cert)
    )
  }
}

object PemHeaders {
  val BeginCertificate = "-----BEGIN CERTIFICATE-----"
  val EndCertificate = "-----END CERTIFICATE-----"
  val BeginPublicKey = "-----BEGIN PUBLIC KEY-----"
  val EndPublicKey = "-----END PUBLIC KEY-----"
  val BeginPrivateKey = "-----BEGIN PRIVATE KEY-----"
  val EndPrivateKey = "-----END PRIVATE KEY-----"
}

object FakeKeyStore {

  private val EMPTY_PASSWORD = Array.emptyCharArray
  private val encoder = Base64.getEncoder

  object SelfSigned {

    object Alias {
      val trustedCertEntry = "otoroshi-selfsigned-trust"
      val PrivateKeyEntry = "otoroshi-selfsigned"
    }

    def DistinguishedName(host: String) = s"CN=$host, OU=Otoroshi Testing (self-signed), O=Otoroshi, C=FR"
    def SubDN(host: String) = s"CN=$host"
  }

  object KeystoreSettings {
    val SignatureAlgorithmName = "SHA256withRSA"
    val KeyPairAlgorithmName = "RSA"
    val KeyPairKeyLength = 2048 // 2048 is the NIST acceptable key length until 2030
    val KeystoreType = "JKS"
    val SignatureAlgorithmOID: ObjectIdentifier = AlgorithmId.sha256WithRSAEncryption_oid
  }

  def generateKeyStore(host: String): KeyStore = {
    val keyStore: KeyStore = KeyStore.getInstance(KeystoreSettings.KeystoreType)
    val (cert, keyPair) = generateX509Certificate(host)
    keyStore.load(null, EMPTY_PASSWORD)
    keyStore.setKeyEntry(SelfSigned.Alias.PrivateKeyEntry, keyPair.getPrivate, EMPTY_PASSWORD, Array(cert))
    keyStore.setCertificateEntry(SelfSigned.Alias.trustedCertEntry, cert)
    keyStore
  }

  def generateX509Certificate(host: String): (X509Certificate, KeyPair) = {
    val keyPairGenerator = KeyPairGenerator.getInstance(KeystoreSettings.KeyPairAlgorithmName)
    keyPairGenerator.initialize(KeystoreSettings.KeyPairKeyLength)
    val keyPair = keyPairGenerator.generateKeyPair()
    val cert = createSelfSignedCertificate(host, keyPair)
    (cert, keyPair)
  }

  def generateCert(host: String): Cert = {
    val (cert, keyPair) = generateX509Certificate(host)
    Cert(
      id = IdGenerator.token(32),
      domain = host,
      chain = s"${PemHeaders.BeginCertificate}\n${new String(encoder.encode(cert.getEncoded), Charsets.UTF_8)}\n${PemHeaders.EndCertificate}",
      privateKey = s"${PemHeaders.BeginPrivateKey}\n${new String(encoder.encode(keyPair.getPrivate.getEncoded), Charsets.UTF_8)}\n${PemHeaders.EndPrivateKey}"
    )
  }

  def createSelfSignedCertificate(host: String, keyPair: KeyPair): X509Certificate = {
    val certInfo = new X509CertInfo()

    // Serial number and version
    certInfo.set(X509CertInfo.SERIAL_NUMBER, new CertificateSerialNumber(new BigInteger(64, new SecureRandom())))
    certInfo.set(X509CertInfo.VERSION, new CertificateVersion(CertificateVersion.V3))

    // Validity
    val validFrom = new Date()
    val validTo = new Date(validFrom.getTime + 50l * 365l * 24l * 60l * 60l * 1000l)
    val validity = new CertificateValidity(validFrom, validTo)
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

}

/*
object Test {

  implicit val system = ActorSystem()
  implicit val ec     = system.dispatcher
  implicit val mat    = ActorMaterializer.create(system)
  implicit val http   = Http(system)

  def handler(request: HttpRequest): Future[HttpResponse] = {
    FastFuture.successful(HttpResponse(
      200,
      entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, "hello")
    ))
  }

  val httpsContext: ConnectionContext = {
    val ks: KeyStore = KeyStore.getInstance("JKS")
    val keystore: InputStream = new FileInputStream(new File("/Users/mathieuancelin/Desktop/keystore.jks"))
    require(keystore != null, "Keystore required!")
    ks.load(keystore, "".toCharArray)
    val keyManagerFactory: KeyManagerFactory = KeyManagerFactory.getInstance("SunX509")
    keyManagerFactory.init(ks, "".toCharArray)
    val tmf: TrustManagerFactory = TrustManagerFactory.getInstance("SunX509")
    tmf.init(ks)
    val sslContext: SSLContext = SSLContext.getInstance("TLS")
    sslContext.init(keyManagerFactory.getKeyManagers, tmf.getTrustManagers, new SecureRandom)
    ConnectionContext.https(sslContext)
  }

  val bound = http.bindAndHandleAsync(
    handler = handler,
    interface = "0.0.0.0",
    port = 8443,
    connectionContext = httpsContext
  )
}*/

