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
 * Isolation note: Otoroshi caches the outgoing per-call SSLContext keyed ONLY by the client-cert id
 * (`app/utils/httpclient.scala`), so each distinct trust configuration uses its OWN client cert.
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

  private val clientAId  = "mtls-client-a"
  private val clientBId  = "mtls-client-b"
  private val clientCId  = "mtls-client-c"
  private val clientDId  = "mtls-client-d"
  private val clientHId  = "mtls-client-h"
  private val clientIId  = "mtls-client-i"
  private val rogueId    = "mtls-client-rogue"
  private val serverCaId = "mtls-server-ca"

  private var backend: MtlsBackend           = _
  private var plainBackend: PlainTlsBackend  = _

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

    def mkRoute(domain: String, hostname: String, backendPort: Int, tls: NgTlsConfig): Unit =
      createLocalRoute(
        rawDomain = Some(domain),
        target = Some(NgTarget(id = "mtls-backend", hostname = hostname, port = backendPort, tls = true, tlsConfig = tls)),
        id = IdGenerator.uuid
      ).futureValue

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
