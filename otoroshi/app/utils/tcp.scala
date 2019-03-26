package utils.tcp

import java.net.{InetAddress, InetSocketAddress}
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

import akka.actor.ActorSystem
import akka.http.scaladsl.settings.ServerSettings
import akka.http.scaladsl.util.FastFuture
import akka.stream.TLSProtocol.NegotiateNewSession
import akka.stream.scaladsl.{Flow, Keep, Sink, Source, Tcp}
import akka.stream.{ActorMaterializer, IgnoreComplete}
import akka.util.ByteString
import akka.{Done, TcpUtils}
import javax.net.ssl._
import play.api.Logger
import play.server.api.SSLEngineProvider
import ssl.{CustomSSLEngine, DynamicSSLEngineProvider}

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
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
- [x] TCP service can be disabled
- [x] TCP service without sni is defined on a port and forwards to targets
- [x] Target can define their own dns resolving
- [ ] TCP service can match a sni domain for a same port (need to catch sni name per request)
- [x] TCP service can forward non matching request to local http server (only for sni request)
- [x] TCP service can be exposed over tls using dyn tls stuff
- [x] TCP service can passthrough tls
- [x] rules
  * if no sni matching, then only one Tcp service can exists with a specific port number
  * if sni matching, then multiple Tcp services can exists with a the port number
  * if sni matching, then all Tcp services using the same port number must have the same Tls mode
- [ ] We need a new datastore for tcp services
- [ ] We need a new admin api for tcp services
- [ ] We need to wire routexxx functions to the new datastore
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
  private val services = Seq(
    TcpService(
      enabled = true,
      tls = TlsMode.Disabled,
      sni = SniSettings(false, false),
      port = 1201,
      rules = Seq(TcpRule(
        domain = "*",
        targets = Seq(
          TcpTarget(
            "localhost",
            None,
            1301,
            false
          ),
          TcpTarget(
            "localhost",
            None,
            1302,
            false
          )
        )
      ))
    ),
    TcpService(
      enabled = true,
      tls = TlsMode.PassThrough,
      sni = SniSettings(false, false),
      port = 1202,
      rules = Seq(TcpRule(
        domain = "*",
        targets = Seq(
          TcpTarget(
            "ssl.ancelin.org",
            Some("127.0.0.1"),
            1303,
            false
          ),
          TcpTarget(
            "ssl.ancelin.org",
            Some("127.0.0.1"),
            1304,
            false
          )
        )
      ))
    ),
    TcpService(
      enabled = true,
      tls = TlsMode.Enabled,
      sni = SniSettings(false, false),
      port = 1203,
      rules = Seq(TcpRule(
        domain = "*",
        targets = Seq(
          TcpTarget(
            "localhost",
            None,
            1301,
            false
          ),
          TcpTarget(
            "localhost",
            None,
            1302,
            false
          )
        )
      ))
    ),
    TcpService(
      enabled = true,
      tls = TlsMode.Enabled,
      sni = SniSettings(true, false),
      port = 1204,
      rules = Seq(
        TcpRule(
          domain = "ssl.ancelin.org",
          targets = Seq(
            TcpTarget(
              "localhost",
              None,
              1301,
              false
            )
          )
        ),
        TcpRule(
          domain = "ssl2.ancelin.org",
          targets = Seq(
            TcpTarget(
              "localhost",
              None,
              1302,
              false
            )
          )
        )
      )
    )
  )

  def findByPort(port: Int)(implicit ec: ExecutionContext): Future[Option[TcpService]] = FastFuture.successful(services.find(_.port == port))

  // TODO: handle *
  def domainMatch(matchRule: String, domain: String): Boolean = matchRule == domain

  def routeWithoutSNI(incoming: Tcp.IncomingConnection, debugger: String => Sink[ByteString, Future[Done]])(implicit ec: ExecutionContext, actorSystem: ActorSystem, materializer: ActorMaterializer): Future[Done] = {
    TcpService.findByPort(incoming.localAddress.getPort).flatMap {
      case Some(service) if service.enabled => {
        try {
          log.info(s"local: ${incoming.localAddress}, remote: ${incoming.remoteAddress}")
          val fullLayer: Flow[ByteString, ByteString, Future[Tcp.OutgoingConnection]] = {
            val targets = service.rules.flatMap(_.targets)
            val index = reqCounter.incrementAndGet() % (if (targets.nonEmpty) targets.size else 1)
            val target = targets.apply(index.toInt)
            target.tls match {
              case true => {
                val remoteAddress = target.ip match {
                  case Some(ip) => new InetSocketAddress(InetAddress.getByAddress(target.host, InetAddress.getByName(ip).getAddress), target.port)
                  case None => new InetSocketAddress(target.host, target.port)
                }
                Tcp().outgoingTlsConnection(remoteAddress, DynamicSSLEngineProvider.current, NegotiateNewSession.withDefaults)
              }
              case false => {
                val remoteAddress = target.ip match {
                  case Some(ip) => new InetSocketAddress(InetAddress.getByAddress(target.host, InetAddress.getByName(ip).getAddress), target.port)
                  case None => new InetSocketAddress(target.host, target.port)
                }
                Tcp().outgoingConnection(remoteAddress)
              }
            }
          }
          fullLayer.alsoTo(debugger("[RESP]: "))
            .joinMat(incoming.flow.alsoTo(debugger("[REQ]: ")))(Keep.left)
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

  def routeWithSNI(incoming: Tcp.IncomingConnection, ref: ConcurrentLinkedQueue[String], debugger: String => Sink[ByteString, Future[Done]])(implicit ec: ExecutionContext, actorSystem: ActorSystem, materializer: ActorMaterializer): Future[Done] = {
    TcpService.findByPort(incoming.localAddress.getPort).flatMap {
      case Some(service) if service.enabled && service.sni.enabled => {
        try {
          log.info(s"local: ${incoming.localAddress}, remote: ${incoming.remoteAddress}")
          val fullLayer: Flow[ByteString, ByteString, Future[_]] = Flow.lazyInitAsync { () =>
            val fu = FastFuture.successful(ref.poll())
            fu.map { sniDomain =>
              service.rules.find(r => domainMatch(r.domain, sniDomain)) match {
                case Some(rule) => {
                  val targets = rule.targets
                  val index = reqCounter.incrementAndGet() % (if (targets.nonEmpty) targets.size else 1)
                  val target = targets.apply(index.toInt)
                  target.tls match {
                    case true => {
                      val remoteAddress = target.ip match {
                        case Some(ip) => new InetSocketAddress(InetAddress.getByAddress(target.host, InetAddress.getByName(ip).getAddress), target.port)
                        case None => new InetSocketAddress(target.host, target.port)
                      }
                      Tcp().outgoingTlsConnection(remoteAddress, DynamicSSLEngineProvider.current, NegotiateNewSession.withDefaults)
                    }
                    case false => {
                      val remoteAddress = target.ip match {
                        case Some(ip) => new InetSocketAddress(InetAddress.getByAddress(target.host, InetAddress.getByName(ip).getAddress), target.port)
                        case None => new InetSocketAddress(target.host, target.port)
                      }
                      Tcp().outgoingConnection(remoteAddress)
                    }
                  }
                }
                case None if service.sni.forwardIfNoMatch => {
                  // TODO: use actual port from env
                  // TODO: check for TLS ? maybe from config
                  Tcp().outgoingConnection("127.0.0.1", 9999)
                }
                case None => {
                  Flow[ByteString].flatMapConcat(_ => Source.failed(new RuntimeException("No domain matches")))
                }
              }
            } recover {
              case e =>
                log.error("SNI failed", e)
                Flow[ByteString].flatMapConcat(_ => Source.failed(e))
            }
          }
          fullLayer.alsoTo(debugger("[RESP]: "))
            .joinMat(incoming.flow.alsoTo(debugger("[REQ]: ")))(Keep.left)
            .run()
            .map(_ => Done)
            .recover {
              case NonFatal(ex) => Done
            }
        } catch {
          case NonFatal(e) =>
            log.error(s"Could not materialize handling flow for ${incoming}", e)
            throw e
        }
      }
      case _ => Future.failed[Done](new RuntimeException("No matching service !"))
    }
  }
}

class TcpEngineProvider(ref: ConcurrentLinkedQueue[String]) extends SSLEngineProvider {

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
            Option(ref).map(_.offer(hostName))
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
  def apply(interface: String, port: Int, tls: TlsMode, sni: Boolean, debug: Boolean = false)(implicit system: ActorSystem, mat: ActorMaterializer): TcpProxy = new TcpProxy(interface, port, tls, sni, debug)(system, mat)
}

class TcpProxy(interface: String, port: Int, tls: TlsMode, sni: Boolean, debug: Boolean = false)(implicit system: ActorSystem, mat: ActorMaterializer) {

  private val log = Logger("tcp-proxy")
  private implicit val ec = system.dispatcher
  private val queue = new ConcurrentLinkedQueue[String]()

  private def debugger(title: String): Sink[ByteString, Future[Done]] = debug match {
    case true => Sink.foreach[ByteString](bs => log.info(title + bs.utf8String))
    case false => Sink.ignore
  }

  private def tcpBindTlsAndSNI(settings: ServerSettings): Future[Tcp.ServerBinding] = {
    TcpUtils.bindTlsWithSSLEngine(
      interface = interface,
      port = port,
      createSSLEngine = () => {
        new TcpEngineProvider(queue).createSSLEngine()
      },
      backlog = settings.backlog,
      options = settings.socketOptions,
      idleTimeout = Duration.Inf,
      verifySession = session => {
        Success(())
      },
      closing = IgnoreComplete
    ).mapAsyncUnordered(settings.maxConnections) { incoming =>
      TcpService.routeWithSNI(incoming, queue, debugger)(ec, system, mat)
    }
    .to(Sink.ignore)
    .run()
  }

  private def tcpBindTls(settings: ServerSettings): Future[Tcp.ServerBinding] = {
    TcpUtils.bindTlsWithSSLEngine(
      interface = interface,
      port = port,
      createSSLEngine = () => {
        new TcpEngineProvider(null).createSSLEngine()
      },
      backlog = settings.backlog,
      options = settings.socketOptions,
      idleTimeout = Duration.Inf,
      verifySession = session => {
        Success(())
      },
      closing = IgnoreComplete
    ).mapAsyncUnordered(settings.maxConnections) { incoming =>
      TcpService.routeWithoutSNI(incoming, debugger)(ec, system, mat)
    }.to(Sink.ignore).run()
  }

  private def tcpBindNoTls(settings: ServerSettings): Future[Tcp.ServerBinding] = {
    Tcp().bind(
      interface = interface,
      port = port,
      halfClose = false,
      backlog = settings.backlog,
      options = settings.socketOptions,
      idleTimeout = Duration.Inf
    ).mapAsyncUnordered(settings.maxConnections) { incoming =>
      TcpService.routeWithoutSNI(incoming, debugger)(ec, system, mat)
    }.to(Sink.ignore).run()
  }

  def start(): Future[Tcp.ServerBinding] = {

    // TODO: fetch from play config
    val settings = ServerSettings(
      """max-connections = 2048
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
        |}""".stripMargin)

    tls match {
      case TlsMode.Disabled => tcpBindNoTls(settings)
      case TlsMode.PassThrough => tcpBindNoTls(settings)
      case TlsMode.Enabled if !sni => tcpBindTls(settings)
      case TlsMode.Enabled if sni => tcpBindTlsAndSNI(settings)
    }
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