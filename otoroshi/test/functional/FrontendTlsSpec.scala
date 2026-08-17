package functional

import com.typesafe.config.ConfigFactory
import otoroshi.api.Otoroshi
import otoroshi.env.Env
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{NgHasClientCertMatchingValidator, NgHasClientCertMatchingValidatorConfig, NgHasClientCertValidator}
import otoroshi.security.IdGenerator
import otoroshi.ssl.{Cert, ClientAuth, DynamicSSLEngineProvider, FakeKeyStore}
import otoroshi.utils.syntax.implicits.*
import play.api.Configuration
import play.api.libs.json.JsObject
import play.core.server.ServerConfig

import java.io.{BufferedReader, InputStreamReader}
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.cert.{Certificate, X509Certificate}
import java.security.{KeyStore, PrivateKey, SecureRandom}
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.{KeyManager, KeyManagerFactory, SNIHostName, SSLContext, SSLSocket, TrustManager, X509TrustManager}
import scala.concurrent.duration.*
import scala.util.Try

/**
 * End-to-end validation of Otoroshi's FRONTEND TLS termination (client -> Otoroshi as TLS server),
 * exercised for real against BOTH server implementations over HTTP/1.1:
 *   - the standard Play/Pekko HTTPS listener (ServerConfig.sslPort)
 *   - the experimental Netty server (otoroshi.next.experimental.netty-server)
 * Both use the same DynamicSSLEngineProvider engine, so the same assertions run against both ports.
 *
 * Covered:
 *   - Part 1: dynamic server-cert selection by SNI (exact / wildcard / exact-beats-wildcard / no-match /
 *             randomIfNotFound / defaultDomain).
 *   - Part 2: client-auth modes None / Want / Need (flipped at runtime via globalConfig.tlsSettings.clientAuth).
 *   - Part 3: client-cert validation plugins (HasClientCert / HasClientCertMatching).
 *
 * The test client is a raw javax.net.ssl.SSLSocket: it sets the SNI explicitly, optionally presents a
 * client cert, reads back the served server certificate, and can send a minimal HTTP/1.1 GET to read the
 * status — so we observe the real handshake outcome (incl. handshake failures).
 */
class FrontendTlsSpec(configurationSpec: => Configuration) extends OtoroshiSpec {

  private val nettyHttpsPort: Int      = TargetService.freePort
  private val otoRef                   = new AtomicReference[Otoroshi]()
  private var otoEnv: Env              = _

  // domains for the plugin routes (covered by the *.foo.tools server cert)
  private val hasCertDomain   = "hasclientcert.foo.tools"
  private val matchCertDomain = "matchclientcert.foo.tools"
  private var matchingClient: (PrivateKey, Array[X509Certificate]) = _ // signed by test CA, CN=fe-client (matches the validator)
  private var otherClient: (PrivateKey, Array[X509Certificate])    = _ // signed by test CA, CN=fe-other (does not match)
  private var untrustedClient: (PrivateKey, Array[X509Certificate]) = _ // signed by a DIFFERENT (untrusted) CA

  override def getTestConfiguration(configuration: Configuration): Configuration = {
    Configuration(
      ConfigFactory
        .parseString(s"""
          |otoroshi.next.state-sync-interval = 5
          |otoroshi.ssl.trust.strictBackendServerValidation = "strict"
          |otoroshi.ssl.fromOutside.clientAuth = "Dynamic"
          |otoroshi.next.experimental.netty-server.enabled = true
          |otoroshi.next.experimental.netty-server.http-port = -1
          |otoroshi.next.experimental.netty-server.https-port = $nettyHttpsPort
          |otoroshi.next.experimental.netty-server.native.enabled = false
          |""".stripMargin)
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)
  }

  // -------------------------------------------------------------------------------------------------
  // startup (self-managed instance with both HTTPS listeners)
  // -------------------------------------------------------------------------------------------------

  private def startInstance(): Unit = {
    val otoroshi = Otoroshi(
      ServerConfig(
        address = "0.0.0.0",
        port = Some(port),
        sslPort = Some(httpsPort),
        rootDir = Files.createTempDirectory("otoroshi-frontend-tls").toFile
      ),
      getTestConfiguration(
        Configuration(ConfigFactory.parseString("").resolve())
      ).underlying
    )
    otoRef.set(otoroshi.startAndStopOnShutdown())
    otoEnv = otoroshi.env
    // wait for the admin http port to answer /health
    awaitCond(30.seconds) {
      Try(
        wsClient.url(s"http://127.0.0.1:$port/health").withRequestTimeout(1.second).get().futureValue.status == 200
      ).getOrElse(false)
    }
  }

  private def awaitCond(timeout: FiniteDuration)(cond: => Boolean): Unit = {
    val deadlineMs = System.currentTimeMillis() + timeout.toMillis
    while (!cond && System.currentTimeMillis() < deadlineMs) { await(300.millis) }
    if (!cond) throw new RuntimeException("condition not met within timeout")
  }

  // -------------------------------------------------------------------------------------------------
  // certificates
  // -------------------------------------------------------------------------------------------------

  private def serverCert(host: String)(using e: Env): Cert = {
    val ca = FrontendTlsSpec.testCa(e)
    FakeKeyStore
      .createCertificateFromCA(host, 3650.days, None, None, ca.cert, ca.caChain, ca.keyPair)(using e)
      .toCert
      .copy(id = s"fe-srv-${IdGenerator.token(8)}", name = host, description = host)
  }

  private def clientCert(cn: String)(using e: Env): (PrivateKey, Array[X509Certificate]) = {
    val ca   = FrontendTlsSpec.testCa(e)
    val resp = FakeKeyStore.createClientCertificateFromCA(s"CN=$cn, O=Otoroshi Test", 3650.days, None, None, ca.cert, ca.caChain, ca.keyPair)(using e)
    (resp.key, Array(resp.cert, resp.ca))
  }

  // -------------------------------------------------------------------------------------------------
  // raw SSLSocket probe
  // -------------------------------------------------------------------------------------------------

  private def clientSslContext(clientCert: Option[(PrivateKey, Array[X509Certificate])]): SSLContext = {
    val trustAll: Array[TrustManager] = Array(new X509TrustManager {
      def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit = ()
      def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit = ()
      def getAcceptedIssuers: Array[X509Certificate]                                = Array.empty
    })
    val kms: Array[KeyManager] = clientCert match {
      case None                => null
      case Some((key, chain))  =>
        val ks = KeyStore.getInstance("JKS")
        ks.load(null, null)
        ks.setKeyEntry("client", key, Array.emptyCharArray, chain.asInstanceOf[Array[Certificate]])
        val kmf = KeyManagerFactory.getInstance("SunX509")
        kmf.init(ks, Array.emptyCharArray)
        kmf.getKeyManagers
    }
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(kms, trustAll, new SecureRandom())
    ctx
  }

  /** result of a TLS probe: served server-cert DN (if handshake succeeded) + optional HTTP status */
  private case class Probe(handshake: Boolean, serverDn: Option[String], status: Option[Int])

  private def probe(
      port: Int,
      sni: Option[String],
      clientCert: Option[(PrivateKey, Array[X509Certificate])] = None,
      sendHttp: Boolean = false
  ): Probe = {
    val ctx    = clientSslContext(clientCert)
    val socket = ctx.getSocketFactory.createSocket().asInstanceOf[SSLSocket]
    try {
      socket.connect(new InetSocketAddress("127.0.0.1", port), 5000)
      socket.setSoTimeout(5000)
      // force TLS 1.2 so client-auth is enforced during the handshake (TLS 1.3 defers Need to post-handshake)
      socket.setEnabledProtocols(Array("TLSv1.2"))
      sni.foreach { s =>
        val params = socket.getSSLParameters
        params.setServerNames(java.util.List.of[javax.net.ssl.SNIServerName](new SNIHostName(s)))
        socket.setSSLParameters(params)
      }
      socket.startHandshake()
      val dn = socket.getSession.getPeerCertificates()(0).asInstanceOf[X509Certificate].getSubjectX500Principal.getName
      val status =
        if (sendHttp) {
          val host = sni.getOrElse("localhost")
          val out  = socket.getOutputStream
          out.write(s"GET /probe HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".getBytes("UTF-8"))
          out.flush()
          val in         = new BufferedReader(new InputStreamReader(socket.getInputStream))
          val statusLine = in.readLine() // e.g. "HTTP/1.1 200 OK"
          Option(statusLine).flatMap(l => Try(l.split(" ")(1).toInt).toOption)
        } else None
      Probe(handshake = true, serverDn = Some(dn), status = status)
    } catch {
      case _: Throwable => Probe(handshake = false, serverDn = None, status = None)
    } finally {
      Try(socket.close())
    }
  }

  private val servers = Seq("pekko" -> httpsPort, "netty" -> nettyHttpsPort)

  /** served cert DN for an SNI on a given port (None if handshake failed) */
  private def served(port: Int, sni: String): Option[String] = probe(port, Some(sni)).serverDn

  // -------------------------------------------------------------------------------------------------
  // fixtures (server certs) — registered directly into the running provider for both listeners
  // -------------------------------------------------------------------------------------------------

  private def setupCerts(): Unit = {
    implicit val e: Env = otoEnv
    val certs = Seq(
      // register the test CA so client certs signed by it are TRUSTED by the strict client validator
      FrontendTlsSpec.testCa(otoEnv).toCert.copy(id = "fe-ca", name = "fe-ca", description = "fe-ca", ca = true),
      serverCert("exact.foo.tools"),
      serverCert("api.foo.tools"),
      serverCert("*.foo.tools")
    )
    DynamicSSLEngineProvider.addCertificates(certs, otoEnv)
    // readiness: both HTTPS listeners must serve the exact cert
    servers.foreach { case (_, p) =>
      awaitCond(30.seconds)(served(p, "exact.foo.tools").exists(_.contains("exact.foo.tools")))
    }
  }

  private def setupRoutes(): Unit = {
    implicit val e: Env = otoEnv
    matchingClient = clientCert("fe-client")
    otherClient = clientCert("fe-other")
    // a client cert signed by a CA that is NOT registered in Otoroshi -> untrusted
    val untrustedCa = FakeKeyStore.createCA("CN=Untrusted CA, O=Otoroshi Test", 3650.days, None, None)(using e)
    val ucResp      = FakeKeyStore.createClientCertificateFromCA("CN=fe-untrusted, O=Otoroshi Test", 3650.days, None, None, untrustedCa.cert, untrustedCa.caChain, untrustedCa.keyPair)(using e)
    untrustedClient = (ucResp.key, Array(ucResp.cert, ucResp.ca))
    // route requiring ANY client cert
    createLocalRoute(
      rawDomain = Some(hasCertDomain),
      plugins = Seq(NgPluginInstance(plugin = NgPluginHelper.pluginId[NgHasClientCertValidator])),
      customOtoroshiPort = Some(port)
    ).futureValue
    // route requiring a client cert whose subject DN matches ".*fe-client.*"
    createLocalRoute(
      rawDomain = Some(matchCertDomain),
      plugins = Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[NgHasClientCertMatchingValidator],
          config = NgPluginInstanceConfig(
            NgHasClientCertMatchingValidatorConfig(regexSubjectDNs = Seq(".*fe-client.*")).json.as[JsObject]
          )
        )
      ),
      customOtoroshiPort = Some(port)
    ).futureValue
    await(2.seconds) // let the routes propagate
  }

  /** flip the frontend client-auth mode via the global config (read per-handshake because fromOutside=Dynamic) */
  private def setClientAuth(mode: ClientAuth): Unit = setTls(_.copy(clientAuth = mode))

  /** mutate the global config's TlsSettings at runtime */
  private def setTls(f: otoroshi.models.TlsSettings => otoroshi.models.TlsSettings): Unit = {
    val gc = getOtoroshiConfig(customPort = Some(port)).futureValue
    updateOtoroshiConfig(gc.copy(tlsSettings = f(gc.tlsSettings)), customPort = Some(port)).futureValue
    await(1.second)
  }

  "Otoroshi frontend TLS" should {

    "warm up" in {
      startInstance()
      setupCerts()
      setupRoutes()
    }

    "Part 1 - dynamic server-cert selection by SNI" in {
      servers.foreach { case (name, p) =>
        withClue(s"[$name] exact match: ") {
          served(p, "exact.foo.tools").get must include("exact.foo.tools")
        }
        withClue(s"[$name] wildcard match: ") {
          served(p, "anything.foo.tools").get must include("*.foo.tools")
        }
        withClue(s"[$name] exact beats wildcard: ") {
          served(p, "api.foo.tools").get must include("api.foo.tools")
        }
        withClue(s"[$name] wildcard excludes apex: ") {
          served(p, "foo.tools") mustBe None // no cert for the apex -> handshake fails
        }
        withClue(s"[$name] wildcard excludes 2 labels: ") {
          served(p, "a.b.foo.tools") mustBe None
        }
        withClue(s"[$name] no match -> handshake fails: ") {
          served(p, "nothing.bar.tools") mustBe None
        }
      }
    }

    "Part 1b - defaultDomain & randomIfNotFound fallbacks" in {
      // defaultDomain: an unmatched SNI falls back to the cert configured as tlsSettings.defaultDomain
      // (this exercises the fixed getServerCertificateForDomain fallback branch)
      setTls(_.copy(defaultDomain = Some("exact.foo.tools")))
      servers.foreach { case (name, p) =>
        withClue(s"[$name] defaultDomain fallback -> exact cert: ")(
          served(p, "unmatched-dd.other.tld").map(_.contains("exact.foo.tools")) mustBe Some(true)
        )
      }
      setTls(_.copy(defaultDomain = None))
      // randomIfNotFound: an unmatched SNI is served *some* cert instead of failing the handshake
      setTls(_.copy(randomIfNotFound = true))
      servers.foreach { case (name, p) =>
        withClue(s"[$name] randomIfNotFound serves a cert: ")(served(p, "unmatched-rnd.other.tld").isDefined mustBe true)
      }
      setTls(_.copy(randomIfNotFound = false))
    }

    // Both Want and Need VALIDATE the presented client cert against the trust store; they differ only in
    // presence semantics: Want = optional (no cert is allowed), Need = mandatory (no cert is rejected).
    // An UNTRUSTED client cert is rejected at the TLS handshake in both modes.
    "Part 2 - client-auth modes: both validate the cert; Want=optional, Need=mandatory" in {
      // None: no CertificateRequest -> even an offered (trusted) cert is not received
      setClientAuth(ClientAuth.None)
      servers.foreach { case (name, p) =>
        val r = probe(p, Some(hasCertDomain), clientCert = Some(matchingClient), sendHttp = true)
        withClue(s"[$name] None: handshake ok: ")(r.handshake mustBe true)
        withClue(s"[$name] None: offered cert not received: ")(r.status must not equal Some(200))
      }
      // Want: optional but validated -> trusted cert ok (200), no cert ok (denied at app), untrusted blocked
      setClientAuth(ClientAuth.Want)
      servers.foreach { case (name, p) =>
        withClue(s"[$name] Want + trusted cert -> 200: ")(
          probe(p, Some(hasCertDomain), Some(matchingClient), sendHttp = true).status mustBe Some(200)
        )
        withClue(s"[$name] Want + no cert -> handshake ok: ")(probe(p, Some(hasCertDomain)).handshake mustBe true)
        // Want ignores an invalid presented cert (handshake continues but the cert is not honored) -> not 200
        withClue(s"[$name] Want + untrusted cert -> not honored (not 200): ")(
          probe(p, Some(hasCertDomain), Some(untrustedClient), sendHttp = true).status must not equal Some(200)
        )
      }
      // Need: mandatory and validated -> trusted cert ok (200), no cert blocked, untrusted blocked
      setClientAuth(ClientAuth.Need)
      servers.foreach { case (name, p) =>
        withClue(s"[$name] Need + trusted cert -> 200: ")(
          probe(p, Some(hasCertDomain), Some(matchingClient), sendHttp = true).status mustBe Some(200)
        )
        withClue(s"[$name] Need + no cert -> handshake blocked: ")(probe(p, Some(hasCertDomain)).handshake mustBe false)
        withClue(s"[$name] Need + untrusted cert -> handshake blocked: ")(
          probe(p, Some(hasCertDomain), Some(untrustedClient)).handshake mustBe false
        )
      }
    }

    "Part 3 - client-cert validation plugins" in {
      setClientAuth(ClientAuth.Want) // handshake completes without a cert so the plugin can decide (403)
      servers.foreach { case (name, p) =>
        // NgHasClientCertValidator: any cert -> 200, none -> 403
        withClue(s"[$name] hasClientCert, with cert: ")(
          probe(p, Some(hasCertDomain), clientCert = Some(matchingClient), sendHttp = true).status mustBe Some(200)
        )
        withClue(s"[$name] hasClientCert, no cert: ")(
          probe(p, Some(hasCertDomain), sendHttp = true).status mustBe Some(403)
        )
        // NgHasClientCertMatchingValidator: matching cert -> 200, other cert -> 403, no cert -> 403
        withClue(s"[$name] matchClientCert, matching cert: ")(
          probe(p, Some(matchCertDomain), clientCert = Some(matchingClient), sendHttp = true).status mustBe Some(200)
        )
        withClue(s"[$name] matchClientCert, non-matching cert: ")(
          probe(p, Some(matchCertDomain), clientCert = Some(otherClient), sendHttp = true).status mustBe Some(403)
        )
        withClue(s"[$name] matchClientCert, no cert: ")(
          probe(p, Some(matchCertDomain), sendHttp = true).status mustBe Some(403)
        )
      }
    }

    "shutdown" in {
      Option(otoRef.get()).foreach(_.stop())
    }
  }
}

object FrontendTlsSpec {
  private val caRef = new AtomicReference[otoroshi.ssl.pki.models.GenCertResponse]()
  // a single shared test CA for the whole spec
  def testCa(env: Env): otoroshi.ssl.pki.models.GenCertResponse = {
    if (caRef.get() == null) {
      caRef.compareAndSet(null, FakeKeyStore.createCA("CN=Otoroshi Frontend Test CA, O=Otoroshi Test", 3650.days, None, None)(using env))
    }
    caRef.get()
  }
}
