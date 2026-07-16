package functional

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, HttpResponse, StatusCodes}
import org.apache.pekko.http.scaladsl.{ConnectionContext, Http, HttpsConnectionContext}
import otoroshi.env.Env
import otoroshi.next.models.{NgTarget, NgTlsConfig}
import otoroshi.security.IdGenerator
import otoroshi.ssl.{Cert, DynamicSSLEngineProvider, FakeKeyStore}
import play.api.Configuration
import play.api.libs.json._
import play.api.libs.ws.WSResponse

import java.net.Socket
import java.security.cert.X509Certificate
import java.security.{KeyStore, PrivateKey, SecureRandom}
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.{KeyManagerFactory, SSLContext, SSLEngine, TrustManagerFactory, X509ExtendedTrustManager}
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.jdk.CollectionConverters._
import scala.util.Failure

/**
 * End-to-end validation of Otoroshi → backend mutual TLS (Otoroshi presenting a client certificate).
 *
 * A local pekko-http backend serves TLS and REQUIRES a client certificate (`setNeedClientAuth(true)`),
 * trusting only our client CA. Its trust manager records the subject DN of every client cert it
 * accepts, so each positive case proves the mTLS handshake really happened with the expected identity.
 * Otoroshi is configured per route (target `tls_config`) with a client cert to present and the
 * `trustAll` / `trustedCerts` / `loose` levers.
 *
 * This can't be a unit test: the whole path needs `Env`, the cert registry and a real TLS socket, so we
 * boot a minimal in-memory Otoroshi once and drive the outgoing mТLS for real.
 *
 * NOTE on what is (and isn't) enforced on the OUTGOING path — verified against the code and empirically:
 *   - The CLIENT cert dimension IS enforced: the backend's `needClientAuth` rejects Otoroshi when it
 *     presents no cert (E) or one signed by a CA the backend doesn't trust (F).
 *   - The BACKEND-SERVER-cert dimension is NOT enforced by Otoroshi: `DynamicSSLEngineProvider`'s
 *     outgoing `FakeTrustManager.checkServerTrusted` does `managers.find(... isSuccess)` and never
 *     throws when none validate, so an untrusted / hostname-mismatched backend server cert is accepted
 *     regardless of `trustAll` / `trustedCerts` / `loose`. Those levers are therefore exercised here as
 *     plumbing (each combination must complete a working mTLS call) rather than as rejection cases.
 *
 * Isolation note: Otoroshi caches the outgoing per-call SSLContext keyed ONLY by the client-cert id
 * (`app/utils/httpclient.scala`), so each case below uses its OWN client cert.
 */
class BackendMtlsSpec(configurationSpec: => Configuration) extends OtoroshiSpec {

  override def getTestConfiguration(configuration: Configuration): Configuration = {
    Configuration(ConfigFactory.parseString("{}").resolve())
      .withFallback(configurationSpec)
      .withFallback(configuration)
  }

  private lazy val env: Env = otoroshiComponents.env

  // one route per case
  private val domainA = "mtls-trustall-loose.oto.tools" // client cert + trustAll + loose
  private val domainB = "mtls-trustone.oto.tools"       // client cert + trustedCerts (trust one)
  private val domainC = "mtls-loose.oto.tools"          // client cert + loose
  private val domainD = "mtls-trustall.oto.tools"       // client cert + trustAll
  private val domainE = "mtls-noclient.oto.tools"       // NO client cert              -> rejected
  private val domainF = "mtls-rogue.oto.tools"          // client cert backend rejects -> rejected

  // probe: an UNTRUSTED backend server cert (plain HTTPS backend, no client auth, self-signed & unregistered)
  private val domainP1 = "mtls-plain-default.oto.tools"  // tls=true, no tls_config (default path)
  private val domainP2 = "mtls-plain-percall.oto.tools"  // tls=true, enabled=true + client cert (per-call path)
  private val domainP3 = "mtls-plain-trustall.oto.tools" // tls=true, enabled=true + client cert + trustAll
  private val domainP4 = "mtls-plain-enabled-nocert.oto.tools" // tls=true, enabled=true, NO client cert, trustAll=false
  private var plainBackend: PlainTlsBackend = _

  // cert ids referenced from the routes' tls_config
  private val clientAId  = "mtls-client-a"
  private val clientBId  = "mtls-client-b"
  private val clientCId  = "mtls-client-c"
  private val clientDId  = "mtls-client-d"
  private val rogueId    = "mtls-client-rogue"
  private val serverCaId = "mtls-server-ca"

  private var backend: MtlsBackend = _

  // -------------------------------------------------------------------------------------------------
  // fixtures
  // -------------------------------------------------------------------------------------------------

  private def setupFixtures(): Unit = {
    implicit val e: Env = env
    val ttl             = 3650.days

    // CAs: one for the client identity, one for the backend server identity, one "rogue" the backend won't trust
    val clientCa = FakeKeyStore.createCA("CN=Otoroshi Test Client CA, O=Otoroshi Test", ttl, None, None)(using e)
    val serverCa = FakeKeyStore.createCA("CN=Otoroshi Test Server CA, O=Otoroshi Test", ttl, None, None)(using e)
    val rogueCa  = FakeKeyStore.createCA("CN=Rogue CA, O=Otoroshi Test", ttl, None, None)(using e)

    // distinct client certs signed by the client CA (distinct ids => distinct outgoing-ctx cache keys)
    def clientCert(dn: String, id: String): Cert =
      FakeKeyStore
        .createClientCertificateFromCA(dn, ttl, None, None, clientCa.cert, clientCa.caChain, clientCa.keyPair)(using e)
        .toCert
        .copy(id = id, name = id, description = id, client = true)

    val clientCertA = clientCert("CN=otoroshi-client-a, O=Otoroshi Test", clientAId)
    val clientCertB = clientCert("CN=otoroshi-client-b, O=Otoroshi Test", clientBId)
    val clientCertC = clientCert("CN=otoroshi-client-c, O=Otoroshi Test", clientCId)
    val clientCertD = clientCert("CN=otoroshi-client-d, O=Otoroshi Test", clientDId)

    // a client cert signed by a CA the backend does NOT trust
    val rogueCert = FakeKeyStore
      .createClientCertificateFromCA("CN=rogue-client, O=Otoroshi Test", ttl, None, None, rogueCa.cert, rogueCa.caChain, rogueCa.keyPair)(using e)
      .toCert
      .copy(id = rogueId, name = rogueId, description = rogueId, client = true)

    // the backend server cert (SAN = localhost), signed by the server CA
    val serverResp   = FakeKeyStore.createCertificateFromCA("localhost", ttl, None, None, serverCa.cert, serverCa.caChain, serverCa.keyPair)(using e)
    val serverCaCert = serverCa.toCert.copy(id = serverCaId, name = serverCaId, description = serverCaId, ca = true)

    // make every cert resolvable by the running proxy immediately (no wait for state-sync)
    DynamicSSLEngineProvider.addCertificates(
      Seq(clientCertA, clientCertB, clientCertC, clientCertD, rogueCert, serverCaCert),
      env
    )
    Seq(clientAId, clientBId, clientCId, clientDId, rogueId, serverCaId).foreach { id =>
      DynamicSSLEngineProvider.certificates.contains(id) mustBe true
    }

    // local TLS backend: serves `serverResp`, trusts the client CA, REQUIRES a client cert
    backend = new MtlsBackend(serverResp.key, Array(serverResp.cert, serverResp.ca), clientCa.cert)

    def mkRouteTo(domain: String, backendPort: Int, tls: NgTlsConfig): Unit =
      createLocalRoute(
        rawDomain = Some(domain),
        target = Some(NgTarget(id = "mtls-backend", hostname = "localhost", port = backendPort, tls = true, tlsConfig = tls)),
        id = IdGenerator.uuid
      ).futureValue
    def mkRoute(domain: String, tls: NgTlsConfig): Unit = mkRouteTo(domain, backend.port, tls)

    mkRoute(domainA, NgTlsConfig(certs = Seq(clientAId), enabled = true, trustAll = true, loose = true))
    mkRoute(domainB, NgTlsConfig(certs = Seq(clientBId), trustedCerts = Seq(serverCaId), enabled = true))
    mkRoute(domainC, NgTlsConfig(certs = Seq(clientCId), enabled = true, loose = true))
    mkRoute(domainD, NgTlsConfig(certs = Seq(clientDId), enabled = true, trustAll = true))
    mkRoute(domainE, NgTlsConfig(certs = Seq.empty, trustedCerts = Seq(serverCaId), enabled = true))
    mkRoute(domainF, NgTlsConfig(certs = Seq(rogueId), enabled = true, trustAll = true))

    // plain HTTPS backend (no client auth) serving a self-signed cert that is NOT registered in Otoroshi
    val plainResp = FakeKeyStore.createSelfSignedCertificate("localhost", ttl, None, None)(using e)
    plainBackend = new PlainTlsBackend(plainResp.key, Array(plainResp.cert))
    mkRouteTo(domainP1, plainBackend.port, NgTlsConfig()) // default: mtls disabled
    mkRouteTo(domainP2, plainBackend.port, NgTlsConfig(certs = Seq(clientAId), enabled = true)) // per-call, no server trust
    mkRouteTo(domainP3, plainBackend.port, NgTlsConfig(certs = Seq(clientAId), enabled = true, trustAll = true))
    mkRouteTo(domainP4, plainBackend.port, NgTlsConfig(certs = Seq.empty, enabled = true, trustAll = false)) // enabled, NO client cert

    await(1.second)
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

    "A. present a client cert (trustAll + loose) — full mTLS round-trip" in {
      assertPresented(domainA, "otoroshi-client-a")
    }

    "B. present a client cert with trustedCerts (trust one)" in {
      assertPresented(domainB, "otoroshi-client-b")
    }

    "C. present a client cert with loose" in {
      assertPresented(domainC, "otoroshi-client-c")
    }

    "D. present a client cert with trustAll" in {
      assertPresented(domainD, "otoroshi-client-d")
    }

    "E. a backend requiring a client cert rejects Otoroshi when none is presented" in {
      call(domainE).status mustBe 502
    }

    "F. the backend rejects a client cert signed by a CA it does not trust" in {
      call(domainF).status mustBe 502
      backend.sawClient("rogue-client") mustBe false // never accepted by the backend trust manager
    }

    // Characterization of backend-server-cert validation against an UNTRUSTED (self-signed, unregistered)
    // backend. This documents a real asymmetry: the default HTTPS path validates the server cert, but the
    // mTLS path (tls_config.enabled=true, which switches Otoroshi to the pekko client whose FakeTrustManager
    // swallows validation failures) accepts any server cert. If the mTLS path is ever fixed to validate the
    // server cert, the `mustBe 200` assertions below will flip to 502 and flag this test for update.
    "G. an untrusted backend server cert is REJECTED on the default (non-mTLS) path" in {
      call(domainP1).status mustBe 502
    }

    // The leniency is triggered by `enabled=true` ALONE (which switches Otoroshi to the pekko client whose
    // FakeTrustManager never rejects a server cert): the untrusted self-signed backend is accepted in ALL
    // of these mTLS-path configs — with or without a client cert, with or without trustAll.
    "H. (known leniency) the mTLS path does NOT validate the backend server cert" in {
      call(domainP2).status mustBe 200 // enabled + client cert, no server trust configured
      call(domainP3).status mustBe 200 // enabled + client cert + trustAll
      call(domainP4).status mustBe 200 // enabled, NO client cert, trustAll=false  <-- the exact case in question
    }

    "shutdown" in {
      if (backend != null) backend.stop()
      if (plainBackend != null) plainBackend.stop()
      stopAll()
    }
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

  /** subject DNs of the client certs the backend accepted during handshakes */
  def acceptedClients: Set[String] = accepted.asScala.toSet

  /** did the backend accept a client cert whose subject DN contains `cn` */
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
      engine.setNeedClientAuth(true) // mandatory client cert -> handshake fails without one
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
