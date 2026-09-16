package functional

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, HttpResponse, StatusCodes}
import org.apache.pekko.http.scaladsl.{ConnectionContext, Http, HttpsConnectionContext}
import otoroshi.env.Env
import otoroshi.next.models.{NgTarget, NgTlsConfig}
import otoroshi.plugins.jobs.kubernetes.{KubernetesClient, KubernetesConfig}
import otoroshi.security.IdGenerator
import otoroshi.ssl.SSLImplicits.*
import otoroshi.ssl.pki.models.GenCertResponse
import otoroshi.ssl.{Cert, DynamicSSLEngineProvider, FakeKeyStore, NewFakeTrustManager}
import play.api.Configuration
import play.api.libs.json.*
import play.api.libs.ws.WSResponse

import java.io.FileInputStream
import java.net.Socket
import java.security.cert.{CertificateException, X509Certificate}
import java.security.{KeyStore, PrivateKey, SecureRandom}
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.{
  KeyManagerFactory,
  SSLContext,
  SSLEngine,
  TrustManagerFactory,
  X509ExtendedTrustManager,
  X509TrustManager
}
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}
import scala.jdk.CollectionConverters.*
import scala.util.Failure

/**
 * End-to-end validation of Otoroshi → backend mutual TLS.
 *
 * A local pekko-http backend serves TLS and REQUIRES a client certificate (`setNeedClientAuth(true)`),
 * trusting only our client CA; its trust manager records the subject DN of every client cert it accepts.
 * Otoroshi is configured per route (target `tls_config`) with a client cert to present and the
 * `trustAll` / `trustedCerts` / `loose` levers. A second plain HTTPS backend (self-signed, unregistered)
 * is used to exercise backend-server-cert validation.
 *
 * This can't be a unit test: the whole path needs `Env`, the cert registry and a real TLS socket, so we
 * boot a minimal in-memory Otoroshi once and drive the outgoing mТLS for real. Each lever is isolated as
 * a real accept(200)/reject(502) pair.
 *
 * Isolation note: Otoroshi caches the outgoing per-call SSLContext (`app/utils/httpclient.scala`). Its key
 * used to be the client-cert ids only, hence one client cert per trust configuration in cases A to I.
 * Cases K and L deliberately reuse the client certs of I and H with another trust configuration to check
 * that the key now covers the whole trust configuration.
 *
 * Cases N to R trust the backend server through a PEM without private key used as `trustedCerts`, like the
 * kubernetes client does with the service account `ca.crt`, which can hold several CAs.
 */
class BackendMtlsSpec(configurationSpec: => Configuration) extends OtoroshiSpec {

  override def getTestConfiguration(configuration: Configuration): Configuration = {
    Configuration(
      ConfigFactory
        .parseString("""otoroshi.ssl.trust.strictBackendServerValidation = "strict"""")
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)
  }

  private lazy val env: Env = otoroshiComponents.env

  // one route per case
  private val domainA  = "mtls-trustall.oto.tools"      // client cert + trustAll                 -> 200
  private val domainB  = "mtls-trustone.oto.tools"      // client cert + trustedCerts (trust one) -> 200
  private val domainC  = "mtls-notrust.oto.tools"       // client cert, no trust config            -> 502 (server untrusted)
  private val domainD1 = "mtls-loose-off.oto.tools"     // trusted server, wrong hostname, loose=off -> 502
  private val domainD2 = "mtls-loose-on.oto.tools"      // trusted server, wrong hostname, loose=on  -> 200
  private val domainE  = "mtls-noclient.oto.tools"      // NO client cert                          -> 502
  private val domainF  = "mtls-rogue.oto.tools"         // rogue client cert                       -> 502
  private val domainG  = "mtls-plain-default.oto.tools" // untrusted server, default (non-mTLS)    -> 502
  private val domainH  = "mtls-plain-notrust.oto.tools" // untrusted server, mTLS, no trust config -> 502
  private val domainI  = "mtls-plain-trustall.oto.tools"// untrusted server, mTLS, trustAll        -> 200
  private val domainJ  = "mtls-plain-trustall-nocert.oto.tools" // untrusted server, mTLS, no cert at all, trustAll -> 200
  private val domainK  = "mtls-plain-notrust-cert-i.oto.tools"  // untrusted server, mTLS, client cert of I, no trustAll -> 502
  private val domainL  = "mtls-plain-trustall-cert-h.oto.tools" // untrusted server, mTLS, client cert of H, trustAll -> 200

  private val clientAId  = "mtls-client-a"
  private val clientBId  = "mtls-client-b"
  private val clientCId  = "mtls-client-c"
  private val clientDId  = "mtls-client-d"
  private val clientHId  = "mtls-client-h"
  private val clientIId  = "mtls-client-i"
  private val rogueId    = "mtls-client-rogue"
  private val serverCaId = "mtls-server-ca"

  private var backend: MtlsBackend           = scala.compiletime.uninitialized
  private var plainBackend: PlainTlsBackend  = scala.compiletime.uninitialized

  // -------------------------------------------------------------------------------------------------
  // fixtures
  // -------------------------------------------------------------------------------------------------

  private def setupFixtures(): Unit = {
    implicit val e: Env = env
    val ttl             = 3650.days

    val clientCa = FakeKeyStore.createCA("CN=Otoroshi Test Client CA, O=Otoroshi Test", ttl, None, None)(using e)
    val serverCa = FakeKeyStore.createCA("CN=Otoroshi Test Server CA, O=Otoroshi Test", ttl, None, None)(using e)
    val rogueCa  = FakeKeyStore.createCA("CN=Rogue CA, O=Otoroshi Test", ttl, None, None)(using e)

    def clientCert(cn: String, id: String): Cert =
      FakeKeyStore
        .createClientCertificateFromCA(s"CN=$cn, O=Otoroshi Test", ttl, None, None, clientCa.cert, clientCa.caChain, clientCa.keyPair)(using e)
        .toCert
        .copy(id = id, name = id, description = id, client = true)

    // distinct client certs signed by the client CA (distinct ids => distinct outgoing-ctx cache keys)
    val cA = clientCert("otoroshi-client-a", clientAId)
    val cB = clientCert("otoroshi-client-b", clientBId)
    val cC = clientCert("otoroshi-client-c", clientCId)
    val cD = clientCert("otoroshi-client-d", clientDId)
    val cH = clientCert("otoroshi-client-h", clientHId)
    val cI = clientCert("otoroshi-client-i", clientIId)

    // a client cert signed by a CA the backend does NOT trust
    val rogue = FakeKeyStore
      .createClientCertificateFromCA("CN=rogue-client, O=Otoroshi Test", ttl, None, None, rogueCa.cert, rogueCa.caChain, rogueCa.keyPair)(using e)
      .toCert
      .copy(id = rogueId, name = rogueId, description = rogueId, client = true)

    // the mTLS backend server cert (SAN = localhost), signed by the server CA + the server CA (for trustedCerts)
    val serverResp   = FakeKeyStore.createCertificateFromCA("localhost", ttl, None, None, serverCa.cert, serverCa.caChain, serverCa.keyPair)(using e)
    val serverCaCert = serverCa.toCert.copy(id = serverCaId, name = serverCaId, description = serverCaId, ca = true)

    DynamicSSLEngineProvider.addCertificates(Seq(cA, cB, cC, cD, cH, cI, rogue, serverCaCert), env)
    Seq(clientAId, clientBId, clientCId, clientDId, clientHId, clientIId, rogueId, serverCaId).foreach { id =>
      DynamicSSLEngineProvider.certificates.contains(id) mustBe true
    }

    // mTLS backend: serves `serverResp`, trusts the client CA, REQUIRES a client cert
    backend = new MtlsBackend(serverResp.key, Array(serverResp.cert, serverResp.ca), clientCa.cert)
    // plain HTTPS backend (no client auth) serving a self-signed cert that is NOT registered in Otoroshi
    val plainResp = FakeKeyStore.createSelfSignedCertificate("localhost", ttl, None, None)(using e)
    plainBackend = new PlainTlsBackend(plainResp.key, Array(plainResp.cert))

    val bp = backend.port
    val pp = plainBackend.port

    mkRoute(domainA, "localhost", bp, NgTlsConfig(certs = Seq(clientAId), enabled = true, trustAll = true))
    mkRoute(domainB, "localhost", bp, NgTlsConfig(certs = Seq(clientBId), trustedCerts = Seq(serverCaId), enabled = true))
    mkRoute(domainC, "localhost", bp, NgTlsConfig(certs = Seq(clientCId), enabled = true))
    mkRoute(domainD1, "127.0.0.1", bp, NgTlsConfig(certs = Seq(clientDId), trustedCerts = Seq(serverCaId), enabled = true, loose = false))
    mkRoute(domainD2, "127.0.0.1", bp, NgTlsConfig(certs = Seq(clientDId), trustedCerts = Seq(serverCaId), enabled = true, loose = true))
    mkRoute(domainE, "localhost", bp, NgTlsConfig(certs = Seq.empty, trustedCerts = Seq(serverCaId), enabled = true))
    mkRoute(domainF, "localhost", bp, NgTlsConfig(certs = Seq(rogueId), enabled = true, trustAll = true))
    mkRoute(domainG, "localhost", pp, NgTlsConfig()) // default (mTLS disabled)
    mkRoute(domainH, "localhost", pp, NgTlsConfig(certs = Seq(clientHId), enabled = true))
    mkRoute(domainI, "localhost", pp, NgTlsConfig(certs = Seq(clientIId), enabled = true, trustAll = true))
    mkRoute(domainJ, "localhost", pp, NgTlsConfig(certs = Seq.empty, enabled = true, trustAll = true))
    mkRoute(domainK, "localhost", pp, NgTlsConfig(certs = Seq(clientIId), enabled = true))
    mkRoute(domainL, "localhost", pp, NgTlsConfig(certs = Seq(clientHId), enabled = true, trustAll = true))

    await(1.second)
  }

  private def mkRoute(domain: String, hostname: String, backendPort: Int, tls: NgTlsConfig): Unit =
    createLocalRoute(
      rawDomain = Some(domain),
      target = Some(NgTarget(id = "mtls-backend", hostname = hostname, port = backendPort, tls = true, tlsConfig = tls)),
      id = IdGenerator.uuid
    ).futureValue

  private def createCA(cn: String, serial: Option[Long] = None): GenCertResponse =
    FakeKeyStore.createCA(s"CN=$cn, O=Otoroshi Test", 3650.days, None, serial)(using env)

  private def createServerCert(host: String, ca: GenCertResponse): GenCertResponse =
    FakeKeyStore.createCertificateFromCA(host, 3650.days, None, None, ca.cert, ca.caChain, ca.keyPair)(using env)

  /**
   * What the kubernetes client does with the service account `ca.crt`: the whole PEM content, without any private
   * key, becomes ONE otoroshi certificate (`Cert(name, pem, "")`) used as `trustedCerts`. Calls a backend serving
   * `server` (without its chain) through a route trusting that certificate only, and returns the response status.
   */
  private def callTrustingPem(name: String, pem: String, expectedCa: Boolean, server: GenCertResponse): Int = {
    val trusted = Cert(name, pem, "").copy(id = name)
    withClue(s"[$name] ca flag of the trusted certificate ") {
      trusted.ca mustBe expectedCa
    }
    DynamicSSLEngineProvider.addCertificates(Seq(trusted), env)
    val serverBackend = new PlainTlsBackend(server.key, Array(server.cert))
    try {
      val domain = s"$name.oto.tools"
      mkRoute(domain, "localhost", serverBackend.port, NgTlsConfig(trustedCerts = Seq(name), enabled = true))
      await(1.second)
      call(domain).status
    } finally {
      serverBackend.stop()
    }
  }

  private def call(domain: String): WSResponse =
    wsClient
      .url(s"http://127.0.0.1:$port/mtls")
      .withHttpHeaders("Host" -> domain)
      .withRequestTimeout(30.seconds)
      .withFollowRedirects(false)
      .get()
      .futureValue

  /** a positive case: the mTLS call succeeds AND the backend accepted the expected client identity */
  private def assertPresented(domain: String, expectedClient: String): Unit = {
    val resp = call(domain)
    withClue(s"[$domain] status=${resp.status} seen=${backend.acceptedClients} ") {
      resp.status mustBe 200
      backend.sawClient(expectedClient) mustBe true
    }
  }

  "Otoroshi backend mTLS" should {

    "warm up and set up fixtures" in {
      startOtoroshi()
      getOtoroshiServices().andThen { case Failure(ex) => ex.printStackTrace() }.futureValue
      setupFixtures()
    }

    "A. present a client cert + trustAll (server validation bypassed)" in {
      assertPresented(domainA, "otoroshi-client-a")
    }

    "B. present a client cert + trustedCerts (trust one: server cert validated against the CA)" in {
      assertPresented(domainB, "otoroshi-client-b")
    }

    "C. reject when the backend server cert is not trusted (no trustAll / trustedCerts)" in {
      call(domainC).status mustBe 502
    }

    "D. loose disables backend hostname verification" in {
      call(domainD1).status mustBe 502 // trusted CA but wrong hostname, loose off -> rejected
      assertPresented(domainD2, "otoroshi-client-d") // same, loose on -> accepted
    }

    "E. reject when no client cert is presented (backend requires one)" in {
      call(domainE).status mustBe 502
    }

    "F. reject a client cert signed by a CA the backend does not trust" in {
      call(domainF).status mustBe 502
      backend.sawClient("rogue-client") mustBe false
    }

    "G. an untrusted backend server cert is rejected on the default (non-mTLS) path" in {
      call(domainG).status mustBe 502
    }

    "H. an untrusted backend server cert is rejected on the mTLS path (no trustAll)" in {
      call(domainH).status mustBe 502
    }

    "I. trustAll accepts an untrusted backend server cert on the mTLS path" in {
      call(domainI).status mustBe 200
    }

    // what a cluster worker does when calling its leader with the default helm values
    // (mtls.enabled + mtls.trustAll, no cert): E already cached a context without any client cert
    "J. trustAll accepts an untrusted backend server cert on the mTLS path without any cert" in {
      call(domainJ).status mustBe 200
    }

    "K. a client cert shared with a trustAll target does not inherit its trustAll context" in {
      call(domainI).status mustBe 200
      call(domainK).status mustBe 502
    }

    "L. a client cert shared with a non trustAll target does not inherit its context" in {
      call(domainH).status mustBe 502
      call(domainL).status mustBe 200
    }

    // the kubernetes jobs call the api server right after building their client, while the api server CA (read
    // from the service account ca.crt and saved as the `kubernetes-ca-cert` certificate) is not yet in the proxy state
    "M. the kubernetes client trusts the api server CA from its very first call" in {
      implicit val e: Env = env
      val ttl             = 3650.days
      // a CA unknown to otoroshi, like the one of a real cluster
      val kubeCa          = FakeKeyStore.createCA("CN=Otoroshi Test Kubernetes CA, O=Otoroshi Test", ttl, None, None)(using e)
      val apiServerCert   = FakeKeyStore.createCertificateFromCA("localhost", ttl, None, None, kubeCa.cert, kubeCa.caChain, kubeCa.keyPair)(using e)
      val apiServer       = new PlainTlsBackend(apiServerCert.key, Array(apiServerCert.cert))
      try {
        val endpoint = s"https://localhost:${apiServer.port}"
        val config   = KubernetesConfig
          .theConfig(Json.obj("endpoint" -> endpoint))(using e, e.otoroshiExecutionContext)
          .copy(
            endpoint = endpoint,
            caCert = Some(kubeCa.cert.asPem),
            trust = false,
            token = Some("token"),
            userPassword = None,
            clientCert = None,
            clientCertKey = None
          )
        val client   = new KubernetesClient(config, e)
        client.fetchConfigMap("kube-system", "coredns").futureValue mustBe defined
      } finally {
        apiServer.stop()
      }
    }

    "N. trustedCerts trust a CA given as a PEM without private key" in {
      val ca = createCA("Otoroshi Test Pem CA")
      callTrustingPem("mtls-pem-single-ca", ca.cert.asPem, expectedCa = true, createServerCert("localhost", ca)) mustBe 200
    }

    // a service account `ca.crt` can hold several CAs, e.g. during a cluster CA rotation
    "O. trustedCerts trust every CA of a PEM bundle without private key, not only the first one" in {
      val first  = createCA("Otoroshi Test Pem Bundle First CA")
      val second = createCA("Otoroshi Test Pem Bundle Second CA")
      val pem    = first.cert.asPem + second.cert.asPem
      callTrustingPem("mtls-pem-bundle-second-ca", pem, expectedCa = true, createServerCert("localhost", second)) mustBe 200
    }

    // older kubeadm / client-go versions created every cluster CA with the serial number 0
    "P. trustedCerts trust every CA of a PEM bundle without private key, even when the CAs share a serial number" in {
      val first  = createCA("Otoroshi Test Pem Bundle Same Serial First CA", serial = Some(0L))
      val second = createCA("Otoroshi Test Pem Bundle Same Serial Second CA", serial = Some(0L))
      first.cert.getSerialNumber mustBe second.cert.getSerialNumber
      val pem    = first.cert.asPem + second.cert.asPem
      callTrustingPem("mtls-pem-bundle-same-serial", pem, expectedCa = true, createServerCert("localhost", second)) mustBe 200
    }

    "Q. trustedCerts trust every certificate of a PEM without private key starting with a non CA certificate" in {
      val leaf = createServerCert("pem-leaf.oto.tools", createCA("Otoroshi Test Pem Leaf Issuer CA"))
      val ca   = createCA("Otoroshi Test Pem After Leaf CA")
      val pem  = leaf.cert.asPem + ca.cert.asPem
      callTrustingPem("mtls-pem-leaf-then-ca", pem, expectedCa = false, createServerCert("localhost", ca)) mustBe 200
    }

    "R. trustedCerts built from a PEM bundle without private key do not trust a CA outside the bundle" in {
      val first   = createCA("Otoroshi Test Pem Bundle Outside First CA")
      val second  = createCA("Otoroshi Test Pem Bundle Outside Second CA")
      val outside = createCA("Otoroshi Test Pem Bundle Outside CA")
      val pem     = first.cert.asPem + second.cert.asPem
      callTrustingPem("mtls-pem-bundle-outside-ca", pem, expectedCa = true, createServerCert("localhost", outside)) mustBe 502
    }

    // the otoroshi truststore comes before the jdk one, whose error ("No trusted certificate found") used to be the
    // only one reported, hiding why the truststore holding the expected CA rejected the chain
    "S. a rejected server chain reports the error of every trust manager, the otoroshi truststore one first" in {
      // same subject as the trusted CA but another key: the otoroshi truststore finds the issuer but rejects the signature
      val trustedCa   = createCA("Otoroshi Test Rotated CA")
      val rotatedCa   = createCA("Otoroshi Test Rotated CA")
      val chain       = Array(createServerCert("localhost", rotatedCa).cert)
      val keyStore    = DynamicSSLEngineProvider.createKeyStore(Seq(Cert("mtls-rotated-ca", trustedCa.cert.asPem, "")))
      val cacertsPath = System.getProperty("java.home") + "/lib/security/cacerts"
      val cacerts     = KeyStore.getInstance("JKS")
      cacerts.load(new FileInputStream(cacertsPath), "changeit".toCharArray)

      def errorOf(ks: KeyStore): String = {
        val tmf = TrustManagerFactory.getInstance("SunX509")
        tmf.init(ks)
        val tm  = tmf.getTrustManagers.collectFirst { case m: X509TrustManager => m }.get
        intercept[CertificateException](tm.checkServerTrusted(chain, "UNKNOWN")).getMessage
      }
      val truststoreError = errorOf(keyStore)
      val jdkError        = errorOf(cacerts)
      truststoreError must not be jdkError

      val manager = DynamicSSLEngineProvider.createTrustStoreWithJdkCAs(keyStore, cacertsPath, "changeit").head
      manager mustBe a[NewFakeTrustManager]
      val error   = intercept[CertificateException](manager.asInstanceOf[X509TrustManager].checkServerTrusted(chain, "UNKNOWN"))
      withClue(s"error: $error, cause: ${error.getCause}, suppressed: ${error.getSuppressed.toSeq} ") {
        error.getCause must not be null
        error.getCause.getMessage mustBe truststoreError
        error.getSuppressed.toSeq.map(_.getMessage) mustBe Seq(jdkError)
        error.getMessage must include(truststoreError)
      }
    }

    "shutdown" in {
      if (backend != null) backend.stop()
      if (plainBackend != null) plainBackend.stop()
      stopAll()
    }
  }
}

/**
 * A minimal local pekko-http backend that serves TLS and REQUIRES client authentication.
 * Its trust manager (a) enforces that the client cert chains to our client CA and (b) records the
 * subject DN of every accepted client cert, so the test can assert which identity Otoroshi presented.
 */
private class MtlsBackend(serverKey: PrivateKey, serverChain: Array[X509Certificate], clientCa: X509Certificate) {

  implicit val system: ActorSystem = ActorSystem(s"mtls-backend-${IdGenerator.token(6)}")
  import system.dispatcher

  val port: Int = TargetService.freePort

  private val accepted = ConcurrentHashMap.newKeySet[String]()

  def acceptedClients: Set[String]   = accepted.asScala.toSet
  def sawClient(cn: String): Boolean = accepted.asScala.exists(_.contains(cn))

  private val sslContext: SSLContext = {
    val pwd = Array.emptyCharArray

    val keyStore = KeyStore.getInstance("JKS")
    keyStore.load(null, null)
    keyStore.setKeyEntry("server", serverKey, pwd, serverChain.asInstanceOf[Array[java.security.cert.Certificate]])
    val kmf = KeyManagerFactory.getInstance("SunX509")
    kmf.init(keyStore, pwd)

    val trustStore = KeyStore.getInstance("JKS")
    trustStore.load(null, null)
    trustStore.setCertificateEntry("client-ca", clientCa)
    val tmf = TrustManagerFactory.getInstance("SunX509")
    tmf.init(trustStore)
    val delegate = tmf.getTrustManagers.collectFirst { case m: X509ExtendedTrustManager => m }.get

    val ctx = SSLContext.getInstance("TLS")
    ctx.init(kmf.getKeyManagers, Array(new RecordingTrustManager(delegate, accepted)), new SecureRandom())
    ctx
  }

  private val httpsContext: HttpsConnectionContext =
    ConnectionContext.httpsServer { () =>
      val engine: SSLEngine = sslContext.createSSLEngine()
      engine.setUseClientMode(false)
      engine.setNeedClientAuth(true)
      engine
    }

  private def handler(req: HttpRequest): Future[HttpResponse] = {
    req.discardEntityBytes()
    Future.successful(HttpResponse(StatusCodes.OK, entity = HttpEntity(ContentTypes.`application/json`, """{"ok":true}""")))
  }

  private val binding =
    Await.result(Http().newServerAt("127.0.0.1", port).enableHttps(httpsContext).bind(handler), 30.seconds)

  def stop(): Unit = {
    Await.result(binding.unbind(), 10.seconds)
    Await.result(system.terminate(), 10.seconds)
  }
}

/** a minimal local pekko-http backend that serves TLS but does NOT require client authentication */
private class PlainTlsBackend(serverKey: PrivateKey, serverChain: Array[X509Certificate]) {

  implicit val system: ActorSystem = ActorSystem(s"plain-tls-backend-${IdGenerator.token(6)}")
  import system.dispatcher

  val port: Int = TargetService.freePort

  private val sslContext: SSLContext = {
    val pwd      = Array.emptyCharArray
    val keyStore = KeyStore.getInstance("JKS")
    keyStore.load(null, null)
    keyStore.setKeyEntry("server", serverKey, pwd, serverChain.asInstanceOf[Array[java.security.cert.Certificate]])
    val kmf = KeyManagerFactory.getInstance("SunX509")
    kmf.init(keyStore, pwd)
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(kmf.getKeyManagers, null, new SecureRandom())
    ctx
  }

  private def handler(req: HttpRequest): Future[HttpResponse] = {
    req.discardEntityBytes()
    Future.successful(HttpResponse(StatusCodes.OK, entity = HttpEntity(ContentTypes.`application/json`, """{"ok":true}""")))
  }

  private val binding = Await.result(
    Http().newServerAt("127.0.0.1", port).enableHttps(ConnectionContext.httpsServer(sslContext)).bind(handler),
    30.seconds
  )

  def stop(): Unit = {
    Await.result(binding.unbind(), 10.seconds)
    Await.result(system.terminate(), 10.seconds)
  }
}

/** delegates client/server trust to a real manager, and records the subject DN of accepted client certs */
private class RecordingTrustManager(delegate: X509ExtendedTrustManager, accepted: java.util.Set[String])
    extends X509ExtendedTrustManager {

  private def record(chain: Array[X509Certificate]): Unit =
    if (chain != null && chain.nonEmpty) accepted.add(chain(0).getSubjectX500Principal.getName)

  def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit = {
    delegate.checkClientTrusted(chain, authType); record(chain)
  }
  def checkClientTrusted(chain: Array[X509Certificate], authType: String, socket: Socket): Unit = {
    delegate.checkClientTrusted(chain, authType, socket); record(chain)
  }
  def checkClientTrusted(chain: Array[X509Certificate], authType: String, engine: SSLEngine): Unit = {
    delegate.checkClientTrusted(chain, authType, engine); record(chain)
  }

  def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit =
    delegate.checkServerTrusted(chain, authType)
  def checkServerTrusted(chain: Array[X509Certificate], authType: String, socket: Socket): Unit =
    delegate.checkServerTrusted(chain, authType, socket)
  def checkServerTrusted(chain: Array[X509Certificate], authType: String, engine: SSLEngine): Unit =
    delegate.checkServerTrusted(chain, authType, engine)

  def getAcceptedIssuers: Array[X509Certificate] = delegate.getAcceptedIssuers
}
