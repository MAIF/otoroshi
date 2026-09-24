package functional

import com.typesafe.config.ConfigFactory
import otoroshi.api.Otoroshi
import otoroshi.env.Env
import otoroshi.models.AutoCert
import otoroshi.ssl.{Cert, DynamicSSLEngineProvider, FakeKeyStore}
import otoroshi.utils.syntax.implicits.*
import play.api.Configuration
import play.core.server.ServerConfig

import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.cert.X509Certificate
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch}
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.{SNIHostName, SSLContext, SSLSocket, TrustManager, X509TrustManager}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * autoCert (globalConfig.autoCert): a certificate is generated during the handshake for an allowed domain
 * that has none yet. What matters here is that a burst of handshakes on a brand new domain produces ONE
 * certificate: the generation is deduplicated per domain, so the concurrent handshakes share the one in
 * flight instead of each running its own key generation and saving its own entity.
 *
 * The client is a raw SSLSocket, so each thread does a real handshake with the domain as SNI and reads back
 * the certificate it was served. The threads are released together by a latch, which is what puts them all
 * inside the generation window.
 */
class AutoCertSpec(configurationSpec: => Configuration) extends OtoroshiSpec {

  private val otoRef      = new AtomicReference[Otoroshi]()
  private var otoEnv: Env = scala.compiletime.uninitialized

  private val caId          = "autocert-test-ca"
  private val allowedDomain = "first.autocert.oto.tools"
  private val deniedDomain  = "nope.other.oto.tools"
  private val concurrency   = 20

  override def proxyStateEnv: Option[Env] = Option(otoEnv)

  override def getTestConfiguration(configuration: Configuration): Configuration = {
    Configuration(
      ConfigFactory
        .parseString(s"""
          |otoroshi.next.state-sync-interval = 1000
          |otoroshi.ssl.fromOutside.clientAuth = "None"
          |""".stripMargin)
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)
  }

  private def startInstance(): Unit = {
    val otoroshi = Otoroshi(
      ServerConfig(
        address = "0.0.0.0",
        port = Some(port),
        sslPort = Some(httpsPort),
        rootDir = Files.createTempDirectory("otoroshi-autocert").toFile
      ),
      getTestConfiguration(Configuration(ConfigFactory.parseString("").resolve())).underlying
    )
    otoRef.set(otoroshi.startAndStopOnShutdown())
    otoEnv = otoroshi.env
    awaitCond(60.seconds) {
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

  private def clientSslContext(): SSLContext = {
    val trustAll: Array[TrustManager] = Array(new X509TrustManager {
      def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit = ()
      def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit = ()
      def getAcceptedIssuers: Array[X509Certificate]                                = Array.empty
    })
    val ctx                           = SSLContext.getInstance("TLS")
    ctx.init(null, trustAll, new java.security.SecureRandom())
    ctx
  }

  /** handshakes with `sni` and returns the serial number of the certificate served, None on failure */
  private def servedSerial(sni: String): Option[String] = {
    val socket = clientSslContext().getSocketFactory.createSocket().asInstanceOf[SSLSocket]
    try {
      socket.connect(new InetSocketAddress("127.0.0.1", httpsPort), 15000)
      socket.setSoTimeout(15000)
      socket.setEnabledProtocols(Array("TLSv1.2"))
      val params = socket.getSSLParameters
      params.setServerNames(java.util.List.of[javax.net.ssl.SNIServerName](new SNIHostName(sni)))
      socket.setSSLParameters(params)
      socket.startHandshake()
      val served = socket.getSession.getPeerCertificates()(0).asInstanceOf[X509Certificate]
      Some(served.getSerialNumber.toString(16))
    } catch {
      case _: Throwable => None
    } finally {
      Try(socket.close())
    }
  }

  private def certsForDomain(domain: String): Seq[Cert] = {
    otoEnv.datastores.certificatesDataStore
      .findAll()(using otoEnv.otoroshiExecutionContext, otoEnv)
      .futureValue
      .filter(c => (c.sans :+ c.domain).contains(domain))
  }

  "Otoroshi autoCert" should {

    "warm up with a CA and autoCert enabled" in {
      startInstance()
      implicit val e: Env = otoEnv
      val ca              = FakeKeyStore.createCA("CN=Otoroshi AutoCert Test CA, O=Otoroshi Test", 3650.days, None, None)
      ca.toCert
        .copy(id = caId, name = caId, description = caId, ca = true)
        .save()(using otoEnv.otoroshiExecutionContext, otoEnv)
        .futureValue
      val gc              = getOtoroshiConfig(customPort = Some(port)).futureValue
      updateOtoroshiConfig(
        gc.copy(autoCert = AutoCert(enabled = true, caRef = Some(caId), allowed = Seq("*.autocert.oto.tools"))),
        customPort = Some(port)
      ).futureValue
      // the config and the CA must have reached the instance before the burst
      awaitCond(30.seconds)(
        otoEnv.datastores.globalConfigDataStore.latestSafe.exists(_.autoCert.enabled) &&
        otoEnv.proxyState.certificate(caId).isDefined
      )
      certsForDomain(allowedDomain) mustBe empty
    }

    "generate one certificate only, for a burst of handshakes on a new domain" in {
      val serials = new ConcurrentLinkedQueue[Option[String]]()
      val ready   = new CountDownLatch(concurrency)
      val go      = new CountDownLatch(1)
      val threads = (1 to concurrency).map { i =>
        val t = new Thread(
          () => {
            ready.countDown()
            go.await()
            serials.add(servedSerial(allowedDomain))
            ()
          },
          s"autocert-burst-$i"
        )
        t.setDaemon(true)
        t
      }
      threads.foreach(_.start())
      ready.await()
      go.countDown() // every thread handshakes at the same time, inside the generation window
      threads.foreach(_.join(60000))

      val results = serials.asScala.toSeq
      withClue(s"every handshake of the burst must succeed: ")(results.count(_.isEmpty) mustBe 0)
      withClue(s"every handshake must be served the same certificate: ")(results.flatten.distinct.size mustBe 1)
      // the real assertion: one generation, so one entity. Without the dedup, each concurrent handshake
      // generates its own key pair and saves its own certificate for the same domain.
      withClue(s"exactly one certificate must have been persisted for $allowedDomain: ") {
        certsForDomain(allowedDomain).size mustBe 1
      }
    }

    "serve that certificate without generating again" in {
      val before = certsForDomain(allowedDomain).map(_.id).toSet
      servedSerial(allowedDomain).isDefined mustBe true
      certsForDomain(allowedDomain).map(_.id).toSet mustBe before
    }

    "not generate anything for a domain outside the allowed list" in {
      servedSerial(deniedDomain) mustBe None
      certsForDomain(deniedDomain) mustBe empty
    }

    "shutdown" in {
      Option(otoRef.get()).foreach(_.stop())
    }
  }
}
