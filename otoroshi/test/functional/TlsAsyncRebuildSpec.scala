package functional

import com.typesafe.config.ConfigFactory
import otoroshi.api.Otoroshi
import otoroshi.env.Env
import otoroshi.models.{EntityLocation, RoundRobin}
import otoroshi.next.models.*
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{StaticResponse, StaticResponseConfig}
import otoroshi.next.proxy.NgProxyStateLoaderJob
import otoroshi.security.IdGenerator
import otoroshi.ssl.{Cert, DynamicKeyManager, DynamicSSLEngineProvider, FakeKeyStore}
import otoroshi.utils.syntax.implicits.*
import play.api.Configuration
import play.api.libs.json.JsObject
import play.core.server.ServerConfig

import java.io.{BufferedReader, InputStreamReader}
import java.lang.management.ManagementFactory
import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.KeyPair
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import javax.net.ssl.{SNIHostName, SSLContext, SSLSocket, TrustManager, X509TrustManager}
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * Load probe for the frontend TLS handshake: it measures what a client actually sees (connect, handshake,
 * first byte) while the proxy state loader job runs, and checks that serving TLS stays insensitive to the
 * lifecycle of the certificate contexts.
 *
 * Two properties are at stake. Indexing the certificates of a context (`DynamicKeyManager`) re-parses every
 * chain and every private key, so that cost must stay where the context is built and never reach a
 * handshake. And a context must only be rebuilt when its inputs moved, so an instance whose certificates
 * are steady rebuilds nothing at all.
 *
 * What this spec does, on a self-managed instance with `certCount` certificates in the datastore:
 *   - Part 0: measures `DynamicKeyManager.validCertificatesByDomains` directly, to get a cost per certificate
 *             that can be extrapolated to a bigger fleet.
 *   - Part 1b: saves a certificate for a domain that was not served before, and requires the contexts to
 *              be rebuilt on their own and the new domain to be served: the counterpart of rebuilding only
 *              on change.
 *   - Part 1: `clients` threads open a NEW TLS connection each, time connect / handshake / request, for
 *             `runSeconds`, while the state loader job keeps running. A watcher thread timestamps every
 *             context swap (by watching `DynamicSSLEngineProvider.currentServer`) and every GC pause, so a
 *             slow handshake can be attributed to a rebuild rather than to noise. A plain HTTP loop runs
 *             alongside as a no-TLS baseline.
 *   - Part 2: same load with the state loader job unregistered, so no rebuild can happen at all: the control.
 *   - Part 3: prints the report and asserts the two properties.
 *
 * The client is a raw SSLSocket, the only way to get the handshake duration alone, and sessions are
 * invalidated so every iteration is a full handshake. The backend is the `StaticResponse` plugin: no second
 * server, so what we measure is Otoroshi and nothing else.
 *
 * Absolute timings are NOT asserted (the client runs in the same JVM as the server and competes for CPU),
 * and neither are percentiles: under heavy load the tail induced by the load alone reaches the cost of an
 * index computation, so it stops discriminating. What is asserted are the two signals that hold at any load:
 * while nothing changes the contexts must not be rebuilt at all, and a rebuild - if one happens - must not
 * delay the handshakes in flight. Part 1b asserts the other direction, that a real certificate change is
 * still picked up on its own. A context indexed inside the handshakes instead would show up as one delayed
 * handshake per concurrent client per rebuild, with a mean of about half an index computation.
 *
 * Note on the jdk session cache: it plays no part here. The server issues stateless session tickets, so its
 * `SSLSessionContext` holds 0 sessions whatever the traffic - measured, capped and uncapped. A long lived
 * context does not accumulate anything there.
 *
 * Knobs (env): TLS_PERF_CERTS, TLS_PERF_CLIENTS, TLS_PERF_DURATION, TLS_PERF_PAUSE_MS,
 * TLS_PERF_SYNC_INTERVAL_MS, TLS_PERF_WARMUP, TLS_PERF_WINDOW_MS, TLS_PERF_LINGER0. Emulate a small pod with
 * OTOROSHI_PEKKO_DISPATCHER_PARALLELISM_{FACTOR,MIN,MAX}.
 */
class TlsAsyncRebuildSpec(configurationSpec: => Configuration) extends OtoroshiSpec {

  private def intEnv(name: String, default: Int): Int =
    Option(System.getenv(name)).flatMap(v => Try(v.trim.toInt).toOption).getOrElse(default)

  // how many certificates sit in the datastore. 500 is the interesting order of magnitude: big enough to make
  // the index cost obvious, small enough to set up in a few seconds
  private val certCount   = intEnv("TLS_PERF_CERTS", 500)
  private val clients     = intEnv("TLS_PERF_CLIENTS", 10)
  private val runSeconds  = intEnv("TLS_PERF_DURATION", 60)
  // a pause between two iterations of a client: concurrency is what matters here, not raw throughput
  private val pauseMillis = intEnv("TLS_PERF_PAUSE_MS", 50)
  // prod default is 10000; 5000 puts twice as many state syncs inside a 60s run
  private val syncMillis  = intEnv("TLS_PERF_SYNC_INTERVAL_MS", 5000)
  // samples of the first seconds of a run are dropped (class loading, JIT)
  private val warmupSecs  = intEnv("TLS_PERF_WARMUP", 5)
  // a handshake is attributed to a rebuild when a context swap falls in [handshakeStart - window, handshakeEnd]
  private val windowMillis = intEnv("TLS_PERF_WINDOW_MS", 50)
  // abortive close (SO_LINGER 0) once the response has been read, so the connection leaves no TIME_WAIT.
  // That is what caps the connection rate of a load run on macos: ~16k ephemeral ports over 30s of
  // TIME_WAIT is ~550 conn/s, above which connect() starts failing. Off by default: the normal run stays
  // realistic, the high traffic runs turn it on.
  private val linger0          = Option(System.getenv("TLS_PERF_LINGER0")).contains("true")

  private val trafficDomain = "perf.oto.tools"

  private val otoRef      = new AtomicReference[Otoroshi]()
  private var otoEnv: Env = scala.compiletime.uninitialized
  private var keyPair: KeyPair = scala.compiletime.uninitialized

  private val report = new ConcurrentLinkedQueue[String]()
  private def line(str: String): Unit = {
    report.add(str)
    println(str)
  }

  // the instance is started here rather than by startOtoroshi, the helpers poll its proxy state
  override def proxyStateEnv: Option[Env] = Option(otoEnv)

  override def getTestConfiguration(configuration: Configuration): Configuration = {
    Configuration(
      ConfigFactory
        .parseString(s"""
          |otoroshi.next.state-sync-interval = $syncMillis
          |otoroshi.ssl.fromOutside.clientAuth = "None"
          |""".stripMargin)
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)
  }

  // -------------------------------------------------------------------------------------------------
  // startup
  // -------------------------------------------------------------------------------------------------

  private def startInstance(): Unit = {
    val otoroshi = Otoroshi(
      ServerConfig(
        address = "0.0.0.0",
        port = Some(port),
        sslPort = Some(httpsPort),
        rootDir = Files.createTempDirectory("otoroshi-tls-perf").toFile
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

  // -------------------------------------------------------------------------------------------------
  // fixtures
  // -------------------------------------------------------------------------------------------------

  /**
   * All certificates are signed by the test CA and share a single key pair: generating `certCount` RSA key
   * pairs would take minutes for nothing, and what is measured here is parsing, not key generation. Being
   * CA-signed matters though: `CertificateData` runs an `isSelfSigned` check that throws for every
   * CA-signed certificate, and that is part of the real cost.
   */
  private def certFor(host: String)(using e: Env): Cert = {
    val ca = TlsAsyncRebuildSpec.testCa(e)
    FakeKeyStore
      .createCertificateFromCA(host, 3650.days, Option(keyPair), None, ca.cert, ca.caChain, ca.keyPair)(using e)
      .toCert
      .copy(id = s"tls-perf-${IdGenerator.token(8)}", name = host, description = host)
  }

  private def setupCerts(): Unit = {
    implicit val e: Env = otoEnv
    val ca              = TlsAsyncRebuildSpec.testCa(otoEnv)
    keyPair = ca.keyPair // reuse the CA key pair for every leaf: cheap and irrelevant to what we measure
    val started         = System.currentTimeMillis()
    val caCert          = ca.toCert.copy(id = "tls-perf-ca", name = "tls-perf-ca", description = "tls-perf-ca", ca = true)
    val traffic         = certFor(trafficDomain)
    // the filler certificates are never served: they are there to make the index as heavy as a real fleet
    val fillers         = (1 to certCount - 1).map(i => certFor(s"filler-$i.tls-perf.tools"))
    val all             = Seq(caCert, traffic) ++ fillers
    Future.sequence(all.map(_.save())).futureValue
    line(s"setup: ${all.size} certificates created and saved in ${System.currentTimeMillis() - started} ms")
    // the certificates must have reached the proxy state (that is where the contexts read them from)
    awaitCond(120.seconds)(otoEnv.proxyState.allCertificates().size >= all.size)
  }

  private def setupRoute(): Unit = {
    val route = NgRoute(
      location = EntityLocation.default,
      id = "route_tls-perf",
      name = "tls-perf",
      description = "tls-perf",
      enabled = true,
      debugFlow = false,
      capture = false,
      exportReporting = false,
      frontend = NgFrontend(
        domains = Seq(NgDomainAndPath(trafficDomain)),
        headers = Map.empty,
        cookies = Map.empty,
        query = Map.empty,
        methods = Seq.empty,
        stripPath = true,
        exact = false
      ),
      backend = NgBackend(
        // never called: StaticResponse is a backend call plugin, so no second server is needed
        targets = Seq(NgTarget(hostname = "127.0.0.1", port = 1, id = "unused", tls = false)),
        root = "/",
        rewrite = false,
        loadBalancing = RoundRobin,
        client = NgClientConfig.default
      ),
      plugins = NgPlugins(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[StaticResponse],
            config = NgPluginInstanceConfig(
              StaticResponseConfig(status = 200, body = "ok").json.as[JsObject]
            )
          )
        )
      ),
      tags = Seq.empty,
      metadata = Map.empty
    )
    createOtoroshiRoute(route, Some(port)).futureValue
    awaitCond(60.seconds)(probe().exists(_.status == 200))
  }

  // -------------------------------------------------------------------------------------------------
  // clients
  // -------------------------------------------------------------------------------------------------

  private case class Sample(hsStartMs: Long, hsEndMs: Long, connectMicros: Long, handshakeMicros: Long, requestMicros: Long, status: Int)

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

  /** one TLS connection: connect, handshake, GET, close. Timings are in microseconds. */
  private def probe(ctx: SSLContext = clientSslContext()): Either[String, Sample] = {
    val socket = ctx.getSocketFactory.createSocket().asInstanceOf[SSLSocket]
    try {
      val t0 = System.nanoTime()
      socket.connect(new InetSocketAddress("127.0.0.1", httpsPort), 10000)
      socket.setSoTimeout(30000)
      val t1     = System.nanoTime()
      // TLS 1.2, like FrontendTlsSpec: no post-handshake steps to blur the handshake duration
      socket.setEnabledProtocols(Array("TLSv1.2"))
      val params = socket.getSSLParameters
      params.setServerNames(java.util.List.of[javax.net.ssl.SNIServerName](new SNIHostName(trafficDomain)))
      socket.setSSLParameters(params)
      val hsStart = System.currentTimeMillis()
      socket.startHandshake()
      val t2      = System.nanoTime()
      val hsEnd   = System.currentTimeMillis()
      // no session reuse: a resumed handshake never reaches the key manager, which is exactly what we measure
      socket.getSession.invalidate()
      val out = socket.getOutputStream
      out.write(s"GET /perf HTTP/1.1\r\nHost: $trafficDomain\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8))
      out.flush()
      val in         = new BufferedReader(new InputStreamReader(socket.getInputStream))
      val statusLine = in.readLine()
      val t3         = System.nanoTime()
      val status     = Option(statusLine).flatMap(l => Try(l.split(" ")(1).toInt).toOption).getOrElse(-1)
      // drain the rest of the response (not measured, the timing above is time to first byte): an abortive
      // close with a body still in flight would discard it and have the server log a reset
      while (in.readLine() != null) ()
      if (linger0) socket.setSoLinger(true, 0)
      Right(Sample(hsStart, hsEnd, (t1 - t0) / 1000, (t2 - t1) / 1000, (t3 - t2) / 1000, status))
    } catch {
      case e: Throwable => Left(s"${e.getClass.getSimpleName}: ${e.getMessage}")
    } finally {
      Try(socket.close())
    }
  }

  /** the subject DN of the certificate served for an SNI, None when the handshake fails */
  private def servedCertDn(sni: String): Option[String] = {
    val socket = clientSslContext().getSocketFactory.createSocket().asInstanceOf[SSLSocket]
    try {
      socket.connect(new InetSocketAddress("127.0.0.1", httpsPort), 10000)
      socket.setSoTimeout(10000)
      socket.setEnabledProtocols(Array("TLSv1.2"))
      val params = socket.getSSLParameters
      params.setServerNames(java.util.List.of[javax.net.ssl.SNIServerName](new SNIHostName(sni)))
      socket.setSSLParameters(params)
      socket.startHandshake()
      Some(socket.getSession.getPeerCertificates()(0).asInstanceOf[X509Certificate].getSubjectX500Principal.getName)
    } catch {
      case _: Throwable => None
    } finally {
      Try(socket.close())
    }
  }

  /** the same thing without TLS, on the http port: the no-TLS baseline */
  private def plainProbe(): Either[String, Long] = {
    val socket = new Socket()
    try {
      val t0 = System.nanoTime()
      socket.connect(new InetSocketAddress("127.0.0.1", port), 10000)
      socket.setSoTimeout(30000)
      val out = socket.getOutputStream
      out.write(s"GET /perf HTTP/1.1\r\nHost: $trafficDomain\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8))
      out.flush()
      val in         = new BufferedReader(new InputStreamReader(socket.getInputStream))
      val statusLine = in.readLine()
      val t1         = System.nanoTime()
      if (statusLine == null) Left("no response") else Right((t1 - t0) / 1000)
    } catch {
      case e: Throwable => Left(s"${e.getClass.getSimpleName}: ${e.getMessage}")
    } finally {
      Try(socket.close())
    }
  }

  // -------------------------------------------------------------------------------------------------
  // one run
  // -------------------------------------------------------------------------------------------------

  private case class PhaseResult(
      name: String,
      samples: Seq[Sample],
      failures: Seq[String],
      swaps: Seq[Long],
      gcPauses: Seq[(Long, Long)], // (timestamp, pause ms)
      plain: Seq[Long],
      plainFailures: Int
  ) {
    private val handshakes                = samples.map(_.handshakeMicros).sorted.toArray
    private val connects                  = samples.map(_.connectMicros).sorted.toArray
    private val requests                  = samples.map(_.requestMicros).sorted.toArray
    private val plainSorted               = plain.sorted.toArray
    def gcTotalMs: Long                   = gcPauses.map(_._2).sum
    def hs(p: Double): Double             = TlsAsyncRebuildSpec.percentile(handshakes, p)
    def req(p: Double): Double            = TlsAsyncRebuildSpec.percentile(requests, p)
    def conn(p: Double): Double           = TlsAsyncRebuildSpec.percentile(connects, p)
    /** connections per second actually offered during the measured window */
    def rate: Double                      = samples.size.toDouble / math.max(1, runSeconds - warmupSecs)
    def plainPct(p: Double): Double       = TlsAsyncRebuildSpec.percentile(plainSorted, p)
    def maxHandshakeMs: Double            = hs(100)
    /** a handshake is attributed to a rebuild when a context swap falls in [start - window, end] */
    def attributed: Seq[Sample]           =
      samples.filter(s => swaps.exists(ts => ts >= s.hsStartMs - windowMillis && ts <= s.hsEndMs))
    def others: Seq[Sample]               = {
      val att = attributed.toSet
      samples.filterNot(att.contains)
    }
    def meanMs(in: Seq[Sample]): Double   = if (in.isEmpty) 0.0 else in.map(_.handshakeMicros).sum / in.size / 1000.0
    def slowest: Option[Sample]           = samples.sortBy(-_.handshakeMicros).headOption
    def slowestAttributed: Boolean        = slowest.exists(s => attributed.contains(s))
    def slowestNearGc: Boolean            =
      slowest.exists(s => gcPauses.exists { case (ts, _) => ts >= s.hsStartMs - 100 && ts <= s.hsEndMs + 100 })
  }

  private def runPhase(name: String, withPlainBaseline: Boolean): PhaseResult = {
    val samples       = new ConcurrentLinkedQueue[Sample]()
    val failures      = new ConcurrentLinkedQueue[String]()
    val swaps         = new ConcurrentLinkedQueue[Long]()
    val gcPauses      = new ConcurrentLinkedQueue[(Long, Long)]()
    val plain         = new ConcurrentLinkedQueue[Long]()
    val plainFailures = new AtomicInteger(0)
    val stop          = new AtomicBoolean(false)
    val startedAt     = System.currentTimeMillis()
    val deadline      = startedAt + (runSeconds * 1000L)

    // watcher: timestamps every SSLContext swap and every GC pause, so an outlier can be attributed
    val watcher = new Thread(
      () => {
        var lastCtx = DynamicSSLEngineProvider.currentServer
        val gcBeans = ManagementFactory.getGarbageCollectorMXBeans.asScala.toSeq
        var lastGcs = gcBeans.map(b => b.getName -> (b.getCollectionCount, b.getCollectionTime)).toMap
        while (!stop.get()) {
          val ctx = DynamicSSLEngineProvider.currentServer
          if (ctx ne lastCtx) {
            swaps.add(System.currentTimeMillis())
            lastCtx = ctx
          }
          gcBeans.foreach { bean =>
            val (count, time) = lastGcs.getOrElse(bean.getName, (0L, 0L))
            val newCount      = bean.getCollectionCount
            val newTime       = bean.getCollectionTime
            if (newCount > count) {
              gcPauses.add((System.currentTimeMillis(), newTime - time))
              lastGcs = lastGcs + (bean.getName -> (newCount, newTime))
            }
          }
          Thread.sleep(2L)
        }
      },
      s"tls-perf-watcher-$name"
    )
    watcher.setDaemon(true)
    watcher.start()

    val threads = (1 to clients).map { i =>
      val t = new Thread(
        () => {
          val ctx = clientSslContext()
          while (System.currentTimeMillis() < deadline) {
            probe(ctx) match {
              case Right(sample) => samples.add(sample)
              case Left(err)     => failures.add(err)
            }
            if (pauseMillis > 0) Thread.sleep(pauseMillis.toLong)
          }
        },
        s"tls-perf-client-$name-$i"
      )
      t.setDaemon(true)
      t
    }

    val plainThread = Option.when(withPlainBaseline) {
      val t = new Thread(
        () => {
          while (System.currentTimeMillis() < deadline) {
            plainProbe() match {
              case Right(micros) => plain.add(micros)
              case Left(_)       => plainFailures.incrementAndGet()
            }
            if (pauseMillis > 0) Thread.sleep(pauseMillis.toLong)
          }
        },
        s"tls-perf-plain-$name"
      )
      t.setDaemon(true)
      t
    }

    threads.foreach(_.start())
    plainThread.foreach(_.start())
    threads.foreach(_.join())
    plainThread.foreach(_.join())
    stop.set(true)
    watcher.join(5000)

    // drop the warm-up window (class loading, JIT) from the measurements
    val warmupUntil = startedAt + (warmupSecs * 1000L)
    PhaseResult(
      name = name,
      samples = samples.asScala.toSeq.filter(_.hsStartMs >= warmupUntil),
      failures = failures.asScala.toSeq,
      swaps = swaps.asScala.toSeq.filter(_ >= warmupUntil).sorted,
      gcPauses = gcPauses.asScala.toSeq.filter(_._1 >= warmupUntil),
      plain = plain.asScala.toSeq,
      plainFailures = plainFailures.get()
    )
  }

  // -------------------------------------------------------------------------------------------------
  // report
  // -------------------------------------------------------------------------------------------------

  // the report is printed with a fixed locale: it ends up pasted in english notes and in the issue
  private def d1(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)
  private def d2(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
  private def d0(v: Double): String = String.format(java.util.Locale.US, "%.0f", v)
  private def n(v: Long): String    = String.format(java.util.Locale.US, "%,d", v)

  private def printPhase(res: PhaseResult, title: String): Unit = {
    line("")
    line(title)
    line(s"  samples: ${n(res.samples.size)}   failures: ${n(res.failures.size)}   context swaps: ${n(res.swaps.size)}   gc pauses: ${n(res.gcPauses.size)} (${n(res.gcTotalMs)} ms total)")
    line(s"  handshake ms: p50=${d1(res.hs(50))} p90=${d1(res.hs(90))} p99=${d1(res.hs(99))} p99.9=${d1(res.hs(99.9))} max=${d1(res.hs(100))}")
    line(s"  request ms:   p50=${d1(res.req(50))} p90=${d1(res.req(90))} p99=${d1(res.req(99))} max=${d1(res.req(100))}")
    line(s"  connect ms:   p50=${d1(res.conn(50))} p90=${d1(res.conn(90))} p99=${d1(res.conn(99))} max=${d1(res.conn(100))}")
    line(s"  offered load: ${d1(res.rate)} new tls connections/s over ${clients} concurrent clients")
    if (res.failures.nonEmpty) {
      val top = res.failures.groupBy(identity).view.mapValues(_.size).toSeq.sortBy(-_._2).take(3)
      line(s"  top failures: ${top.map { case (reason, count) => s"${n(count)} x $reason" }.mkString("  |  ")}")
    }
    if (res.swaps.nonEmpty) {
      val att   = res.attributed
      val oth   = res.others
      val ratio = if (res.meanMs(oth) > 0) res.meanMs(att) / res.meanMs(oth) else 0.0
      line(s"  handshakes attributed to a rebuild: ${n(att.size)} / ${n(res.samples.size)} (${n(res.swaps.size)} rebuilds x $clients concurrent clients)")
      line(s"    mean handshake in that set: ${d1(res.meanMs(att))} ms   mean outside: ${d1(res.meanMs(oth))} ms   ratio: x${d1(ratio)}")
    }
    res.slowest.foreach { s =>
      line(s"  slowest handshake: ${d1(s.handshakeMicros / 1000.0)} ms   attributed to a rebuild: ${res.slowestAttributed}   gc in the same window: ${res.slowestNearGc}")
    }
    if (res.plain.nonEmpty) {
      line(s"  plain HTTP baseline (no TLS): ${n(res.plain.size)} samples, failures: ${n(res.plainFailures)}, p50=${d1(res.plainPct(50))} p99=${d1(res.plainPct(99))} max=${d1(res.plainPct(100))} ms")
    }
  }

  // -------------------------------------------------------------------------------------------------
  // the spec
  // -------------------------------------------------------------------------------------------------

  private val withRebuildsRef = new AtomicReference[PhaseResult]()
  private val noRebuildRef    = new AtomicReference[PhaseResult]()
  private val indexCostRef    = new AtomicReference[Seq[Long]](Seq.empty)

  "Otoroshi frontend TLS handshake under concurrency" should {

    "warm up" in {
      startInstance()
      setupCerts()
      setupRoute()
    }

    "Part 0 - cost of the certificate index, measured directly" in {
      // fresh copies: a `copy()` resets every lazy val, so this measures a cold index like a rebuild does,
      // without warming the instances the running key manager shares
      val runs = (1 to 3).map { _ =>
        val certs   = otoEnv.proxyState.allCertificates().map(_.copy())
        val started = System.nanoTime()
        val (valid, byDomain) = DynamicKeyManager.validCertificatesByDomains(certs)
        val elapsed = (System.nanoTime() - started) / 1000000L
        valid.size must be > 0
        byDomain.size must be > 0
        elapsed
      }
      indexCostRef.set(runs)
    }

    // the counterpart of rebuilding only on change: a real change must still be picked up on its own, with
    // no manual action
    "Part 1b - a real certificate change is still picked up, with no manual action" in {
      implicit val e: Env = otoEnv
      val hotDomain       = "hotreload.tls-perf.tools"
      withClue("the domain must not be served before its certificate exists: ")(servedCertDn(hotDomain) mustBe None)
      val contextBefore = DynamicSSLEngineProvider.currentServer
      certFor(hotDomain).save().futureValue
      awaitCond(60.seconds)(DynamicSSLEngineProvider.currentServer ne contextBefore)
      awaitCond(60.seconds)(servedCertDn(hotDomain).exists(_.contains(hotDomain)))
      // let the dust settle so the load phase starts from a quiet state
      await(3.seconds)
    }

    "Part 1 - load while the state loader job runs (it must not rebuild anything)" in {
      withRebuildsRef.set(runPhase("with-rebuilds", withPlainBaseline = true))
    }

    "Part 2 - same load with no rebuild at all (control)" in {
      // unregistering the state loader job stops the periodic rebuild. Nothing writes a certificate during
      // the run, so the 2s lastUpdatedKey loop of the certificate datastore stays quiet too: the control
      // asserts `swaps == 0`, which is what proves the job was really the only source of rebuilds.
      otoEnv.jobManager.unregisterJob(new NgProxyStateLoaderJob())
      await(2.seconds)
      noRebuildRef.set(runPhase("no-rebuild", withPlainBaseline = false))
    }

    "Part 3 - report and conclusions" in {
      val withRebuilds = withRebuildsRef.get()
      val noRebuild    = noRebuildRef.get()
      val indexCosts   = indexCostRef.get()
      val certs        = otoEnv.proxyState.allCertificates().size
      val perCertMs    = indexCosts.sum.toDouble / indexCosts.size / certs
      val indexCostMs  = indexCosts.sum.toDouble / indexCosts.size

      line("")
      line("=" * 100)
      line("TLS HANDSHAKE LATENCY REPORT")
      line("=" * 100)
      line(s"certificates in the datastore: ${n(certs)}   clients: $clients   run: ${runSeconds}s   pause between iterations: ${pauseMillis}ms")
      line(s"state-sync-interval: ${syncMillis}ms (prod default: 10000ms)   warm-up dropped: ${warmupSecs}s   attribution window: ${windowMillis}ms")
      line(s"index cost (DynamicKeyManager.validCertificatesByDomains over ${n(certs)} certs): ${indexCosts.mkString(" ms / ")} ms  ->  ${d0(perCertMs * 1000)} us per certificate")

      printPhase(withRebuilds, "phase 1 - state loader job running, state synced every ~" + syncMillis + "ms")
      printPhase(noRebuild, "phase 2 - state loader job unregistered, no rebuild possible (control)")

      line("")
      line("conclusions")
      line(s"  * state loader job running:      p99=${d1(withRebuilds.hs(99))} ms  p99.9=${d1(withRebuilds.hs(99.9))} ms  max=${d1(withRebuilds.maxHandshakeMs)} ms")
      line(s"  * state loader job unregistered: p99=${d1(noRebuild.hs(99))} ms  p99.9=${d1(noRebuild.hs(99.9))} ms  max=${d1(noRebuild.maxHandshakeMs)} ms")
      val att = withRebuilds.attributed
      val oth = withRebuilds.others
      if (att.nonEmpty && oth.nonEmpty) {
        line(s"  * handshakes right after a rebuild are ${d1(withRebuilds.meanMs(att) / withRebuilds.meanMs(oth))}x slower on average (${d1(withRebuilds.meanMs(att))} ms vs ${d1(withRebuilds.meanMs(oth))} ms)")
      }
      line(s"  * ${n(withRebuilds.swaps.size)} context rebuilds in ${runSeconds - warmupSecs}s, ${n(att.size)} of the ${n(withRebuilds.samples.size)} handshakes of the run in their window")
      line(s"  * extrapolation at ${d2(perCertMs)} ms per certificate: 500 certs -> ~${d0(perCertMs * 500)} ms per rebuild, 2000 -> ~${d0(perCertMs * 2000)} ms, 5000 -> ~${d0(perCertMs * 5000)} ms")
      // not an assertion: under heavy load the tail induced by the load alone reaches the cost of an index,
      // so this only discriminates on a machine that is not saturated. The rebuild count and the attributed
      // set are the signals that hold at any load.
      line(s"  * phase 1 tail vs one index computation: p99.9=${d1(withRebuilds.hs(99.9))} ms for an index at ${d1(indexCostMs)} ms")
      line(s"  * gc during phase 1: ${n(withRebuilds.gcPauses.size)} pauses, ${n(withRebuilds.gcTotalMs)} ms total (to rule out gc as the cause of the outliers)")
      if (withRebuilds.plain.nonEmpty) {
        line(s"  * plain HTTP during phase 1: p99=${d1(withRebuilds.plainPct(99))} ms max=${d1(withRebuilds.plainPct(100))} ms (no TLS, so no index to rebuild)")
      }
      line("=" * 100)

      // Neither absolute timings nor percentiles are asserted: the client shares the CPU with the server,
      // and under heavy load the tail induced by the load alone reaches the cost of an index computation,
      // so it stops telling a rebuild from the background noise. Only the two load independent signals are
      // asserted: how many rebuilds happened, and what a rebuild did to the handshakes in flight.
      // A load run can legitimately lose a connection (ephemeral port exhaustion on the client side), so
      // failures are a ratio, and the reasons are printed above
      withClue(s"phase 1 failure rate must stay marginal (${withRebuilds.failures.size} failures): ") {
        withRebuilds.failures.size.toDouble must be < (0.01 * math.max(1, withRebuilds.samples.size))
      }
      withClue(s"phase 2 failure rate must stay marginal (${noRebuild.failures.size} failures): ") {
        noRebuild.failures.size.toDouble must be < (0.01 * math.max(1, noRebuild.samples.size))
      }
      withClue("no context swap once the state loader job is gone: ")(noRebuild.swaps mustBe empty)
      // nothing changed during the run, so the contexts must not be rebuilt at all. One stray rebuild is
      // tolerated: a background job may legitimately write a certificate while the load runs.
      withClue(s"the state loader job must not rebuild the contexts while nothing changes (${withRebuilds.swaps.size} swaps): ") {
        withRebuilds.swaps.size must be <= 1
      }
      if (att.nonEmpty && oth.nonEmpty) {
        withClue("a rebuild, when it happens, must not delay the handshakes in flight: ") {
          withRebuilds.meanMs(att) must be < (3 * withRebuilds.meanMs(oth))
        }
      }
    }

    "shutdown" in {
      Option(otoRef.get()).foreach(_.stop())
    }
  }
}

object TlsAsyncRebuildSpec {

  private val caRef = new AtomicReference[otoroshi.ssl.pki.models.GenCertResponse]()

  /** a single shared test CA for the whole spec */
  def testCa(env: Env): otoroshi.ssl.pki.models.GenCertResponse = {
    if (caRef.get() == null) {
      caRef.compareAndSet(
        null,
        FakeKeyStore.createCA("CN=Otoroshi TLS Perf Test CA, O=Otoroshi Test", 3650.days, None, None)(using env)
      )
    }
    caRef.get()
  }

  /** `p` in [0, 100], values in microseconds, result in milliseconds */
  def percentile(sorted: Array[Long], p: Double): Double = {
    if (sorted.isEmpty) 0.0
    else {
      val rank = math.ceil(p / 100.0 * sorted.length).toInt - 1
      val idx  = math.min(sorted.length - 1, math.max(0, rank))
      sorted(idx) / 1000.0
    }
  }
}
