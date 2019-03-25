package utils.tcp

import java.security.SecureRandom
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}

import akka.actor.ActorSystem
import akka.http.scaladsl.settings.ServerSettings
import akka.http.scaladsl.util.FastFuture
import akka.stream.TLSProtocol.NegotiateNewSession
import akka.stream.scaladsl.{Flow, Keep, Sink, Tcp}
import akka.stream.{ActorMaterializer, IgnoreComplete}
import akka.util.ByteString
import akka.{Done, TcpUtils}
import env.Env
import javax.net.ssl._
import play.api.Logger
import play.server.api.SSLEngineProvider
import ssl.{CustomSSLEngine, DynamicSSLEngineProvider}

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Success
import scala.util.control.NonFatal

case class SniSettings(enabled: Boolean, forwardIfNoMatch: Boolean)
case class TcpTarget(host: String, ip: Option[String], port: Int, tls: Boolean)
case class TcpRule(domain: String, targets: Seq[TcpTarget])
sealed trait TlsMode
object TlsMode {
  case object Disabled extends TlsMode
  case object Enabled extends TlsMode
  case object PassThrough extends TlsMode
}

/**
- [ ] TCP service can be disabled
- [ ] TCP service without sni is defined on a port and forwards to targets
- [ ] Target can define their own dns resolving
- [ ] TCP service can match a sni domain for a same port (need to catch sni name per request)
- [ ] TCP service can forward non matching request to local http server (only for sni request)
- [ ] TCP service can be exposed over tls using dyn tls stuff
- [ ] TCP service can passthrough tls
- [ ] rules
  * if no sni matching, then only one Tcp service can exists with a specific port number
  * if sni matching, then multiple Tcp services can exists with a the port number
  * if sni matching, then all Tcp services using the same port number must have the same Tls mode
- [ ] A thread will request all tcp services with unique ports and stats tcp server.  Servers will be shut down with otoroshi app
 */
case class TcpService(
  enabled: Boolean,
  tls: TlsMode,
  sni: SniSettings,
  port: Int,
  interface: String = "0.0.0.0",
  rules: Seq[TcpRule]
)

object TcpService {
  private val reqCounter = new AtomicLong(0L)
  private val log = Logger("tcp-proxy")
  def findByPort(port: Int)(implicit ec: ExecutionContext, env: Env): Future[Option[TcpService]] = FastFuture.successful(Some(TcpService(
    enabled = true,
    tls = TlsMode.Enabled,
    sni = SniSettings(false, false),
    port = 1234,
    interface = "0.0.0.0",
    rules = Seq(TcpRule(
      domain = "*",
      targets = Seq(TcpTarget(
        "localhost",
        None,
        1235,
        false
      ))
    ))
  )))
  def domainMatch(matchRule: String, domain: String): Boolean = true
  def route(incoming: Tcp.IncomingConnection, sni: Option[String], ref: Future[String])(implicit ec: ExecutionContext, env: Env, actorSystem: ActorSystem, materializer: ActorMaterializer): Future[Done] = {
    ref.map(s => log.info(s"routing on ${s}"))
    // val maybeSni = Option(ref).map(_.get())
    TcpService.findByPort(incoming.localAddress.getPort).flatMap {
      case Some(service) if service.enabled => {
        try {

          log.info(s"local: ${incoming.localAddress} ${incoming.localAddress.getHostName} ${incoming.localAddress.getHostString} ${incoming.localAddress.getAddress}")
          log.info(s"remote: ${incoming.remoteAddress} ${incoming.remoteAddress.getHostName} ${incoming.remoteAddress.getHostString} ${incoming.remoteAddress.getAddress}")
          val fullLayer: Flow[ByteString, ByteString, Future[Tcp.OutgoingConnection]] = service.sni.enabled match {
            case true if sni.isDefined => {
              val sniDomain = sni.get
              service.rules.find(r => domainMatch(r.domain, sniDomain)) match {
                case Some(rule) => {
                  val targets = rule.targets
                  val index = reqCounter.get() % (if (targets.nonEmpty) targets.size else 1)
                  val target = targets.apply(index.toInt)
                  target.tls match {
                    // TODO: test passthrough
                    case true => {
                      // TODO : be careful about domain name
                      // TODO : handle local resolve ?
                      Tcp().outgoingTlsConnection(target.host, target.port, DynamicSSLEngineProvider.current, NegotiateNewSession.withDefaults)
                    }
                    case false => {
                      // TODO : handle local resolve ?
                      Tcp().outgoingConnection(target.host, target.port)
                    }
                  }
                }
                case None => if (service.sni.forwardIfNoMatch) {
                  Tcp().outgoingConnection("127.0.0.1", env.port)
                } else {
                  throw new RuntimeException("no domain matches")
                }
              }
            }
            case _ => {
              val targets = service.rules.flatMap(_.targets)
              val index = reqCounter.get() % (if (targets.nonEmpty) targets.size else 1)
              val target = targets.apply(index.toInt)
              target.tls match {
                // TODO: test passthrough
                case true => {
                  // TODO : be careful about domain name
                  // TODO : handle local resolve ?
                  Tcp().outgoingTlsConnection(target.host, target.port, DynamicSSLEngineProvider.current, NegotiateNewSession.withDefaults)
                }
                case false => {
                  // TODO : handle local resolve ?
                  Tcp().outgoingConnection(target.host, target.port)
                }
              }
            }
          }
          fullLayer
            .map { bs =>
              log.info("[RESP]: " + bs.utf8String)
              bs
            }
            .joinMat(incoming.flow.map { bs =>
              log.info("[REQ]: " + bs.utf8String)
              bs
            })(Keep.left)
            .run()
            .map(_ => Done)
            .recover {
              case NonFatal(ex) => Done
            }
        } catch {
          case NonFatal(e) =>
            log.error(s"Could not materialize handling flow for {incoming}", e)
            throw e
        }
      }
      case _ => Future.failed[Done](new RuntimeException("No matching service !"))
    }
  }
}

class TcpEngineProvider(ref: Promise[String]) extends SSLEngineProvider {

  override def createSSLEngine(): SSLEngine = {
    val context: SSLContext = DynamicSSLEngineProvider.current
    DynamicSSLEngineProvider.logger.debug(s"Create SSLEngine from: $context")
    val rawEngine              = context.createSSLEngine()
    val engine        = new CustomSSLEngine(rawEngine)
    val sslParameters = new SSLParameters
    val matchers      = new java.util.ArrayList[SNIMatcher]()
    engine.setUseClientMode(false)
    engine.setNeedClientAuth(false)
    engine.setWantClientAuth(false)
    sslParameters.setNeedClientAuth(false)
    sslParameters.setWantClientAuth(false)
    matchers.add(new SNIMatcher(0) {
      override def matches(sniServerName: SNIServerName): Boolean = {
        sniServerName match {
          case hn: SNIHostName =>
            val hostName = hn.getAsciiName
            Option(ref).map(_.trySuccess(hostName))
            println(s"hostName $hostName - ${Thread.currentThread().getName}")
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

object TcpProxy {
  def apply(interface: String, port: Int, passThrough: Boolean, env: Env): TcpProxy = new TcpProxy(interface, port, env)
}

class TcpProxy(interface: String, port: Int, env: Env) {

  private val log = Logger("tcp-proxy")
  private implicit val system = ActorSystem("tcp-proxy")
  private implicit val ec = system.dispatcher
  private implicit val mat = ActorMaterializer.create(system)

  private val tl = new ThreadLocal[AtomicReference[Promise[String]]]()

  private def tcpBind(settings: ServerSettings): Future[Tcp.ServerBinding] = {
    TcpUtils.bindTlsWithSSLEngine(
      interface = interface,
      port = port,
      createSSLEngine = () => {
        val ref = tl.get()
        println(s"createEnginee ($ref) ${Thread.currentThread().getName}")
        new TcpEngineProvider(ref.get()).createSSLEngine()
      },
      backlog = settings.backlog,
      options = settings.socketOptions,
      idleTimeout = Duration.Inf,
      verifySession = session => {
        println(s"verify session ${session} ${Thread.currentThread().getName}")
        Success(())
      },
      closing = IgnoreComplete
    ).mapAsyncUnordered(settings.maxConnections) { incoming =>
      val prom = Promise[String]
      val ref = new AtomicReference[Promise[String]](prom)
      tl.set(ref)
      println(s"route ${Thread.currentThread().getName}")
      val f = TcpService.route(incoming, None, prom.future)(ec, env, system, mat)
      prom.future.andThen {
        case _ =>
          println("cleanup")
          tl.remove()
      }
      f
    }
    .to(Sink.foreach { w =>
      println(s"done $w ${Thread.currentThread().getName}")
    })
    .run()
  }

  def start(): Future[Tcp.ServerBinding] = {
    tcpBind(ServerSettings(
      """
        |max-connections = 2048
        |remote-address-header = on
        |raw-request-uri-header = on
        |pipelining-limit = 64
        |backlog = 512
        |socket-options {
        |  so-receive-buffer-size = undefined
        |  so-send-buffer-size = undefined
        |  so-reuse-address = undefined
        |  so-traffic-class = undefined
        |  tcp-keep-alive = true
        |  tcp-oob-inline = undefined
        |  tcp-no-delay = undefined
        |}
      """.stripMargin))
  }
}
// try {
//   log.info(s"local: ${incoming.localAddress} ${incoming.localAddress.getHostName} ${incoming.localAddress.getHostString} ${incoming.localAddress.getAddress}")
//   log.info(s"remote: ${incoming.remoteAddress} ${incoming.remoteAddress.getHostName} ${incoming.remoteAddress.getHostString} ${incoming.remoteAddress.getAddress}")
//   val fullLayer: Flow[ByteString, ByteString, Future[Tcp.OutgoingConnection]] = Tcp().outgoingConnection("127.0.0.1", 1235)
//   val f: Future[Serializable] = fullLayer
//     .map { bs =>
//       log.info("[RESP]: " + bs.utf8String)
//       bs
//     }
//     .joinMat(incoming.flow.map { bs =>
//       log.info("[REQ]: " + bs.utf8String)
//       bs
//     })(Keep.left)
//     .run()
//     .recover {
//       case NonFatal(ex) => Done
//     }
//   f
// } catch {
//   case NonFatal(e) =>
//     log.error(s"Could not materialize handling flow for {incoming}", e)
//     throw e
// }
// Tcp().bindTls(
//   interface = interface,
//   port = port,
//   sslContext = mockSslContext(),
//   negotiateNewSession = NegotiateNewSession(
//     enabledCipherSuites = None,
//     enabledProtocols = None,
//     clientAuth = Some(TLSClientAuth.none),
//     sslParameters = None
//   ),
//   backlog = settings.backlog,
//   options = settings.socketOptions,
//   idleTimeout = Duration.Inf
// ).mapAsyncUnordered(settings.maxConnections) { incoming =>

//private def mockSslContext(): SSLContext = new SSLContext(
//  new SSLContextSpi() {
//    private lazy val sslEngineProvider = new TcpEngineProvider()
//    override def engineCreateSSLEngine(): SSLEngine  = sslEngineProvider.createSSLEngine()
//    override def engineCreateSSLEngine(s: String, i: Int): SSLEngine = {
//      log.info(s"engineCreateSSLEngine($s, $i)")
//      engineCreateSSLEngine()
//    }
//    override def engineInit(keyManagers: Array[KeyManager], trustManagers: Array[TrustManager], secureRandom: SecureRandom): Unit = ()
//    override def engineGetClientSessionContext(): SSLSessionContext = SSLContext.getDefault.getClientSessionContext
//    override def engineGetServerSessionContext(): SSLSessionContext = SSLContext.getDefault.getServerSessionContext
//    override def engineGetSocketFactory(): SSLSocketFactory = SSLSocketFactory.getDefault.asInstanceOf[SSLSocketFactory]
//    override def engineGetServerSocketFactory(): SSLServerSocketFactory =  SSLServerSocketFactory.getDefault.asInstanceOf[SSLServerSocketFactory]
//  },
//  new java.security.Provider(
//    "Play SSlEngineProvider delegate",
//    1d,
//    "A provider that only implements the creation of SSL engines, and delegates to Play's SSLEngineProvider"
//  ) {},
//  "Play SSLEngineProvider delegate"
//) {}