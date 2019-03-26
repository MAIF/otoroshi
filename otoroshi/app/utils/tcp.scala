package utils.tcp

import java.net.{InetAddress, InetSocketAddress}
import java.util.concurrent.atomic.AtomicLong

import akka.actor.ActorSystem
import akka.http.scaladsl.settings.ServerSettings
import akka.http.scaladsl.util.FastFuture
import akka.stream.TLSProtocol.NegotiateNewSession
import akka.stream.scaladsl.{Flow, Keep, Sink, Source, Tcp}
import akka.stream.{ActorMaterializer, IgnoreComplete}
import akka.util.ByteString
import akka.{AwesomeIncomingConnection, Done, TcpUtils}
import com.typesafe.config.{Config, ConfigFactory}
import env.Env
import javax.net.ssl._
import play.api.Logger
import ssl.{ClientAuth, CustomSSLEngine, DynamicSSLEngineProvider}
import utils.RegexPool

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success
import scala.util.control.NonFatal

case class ServersBinding(servers: Future[Seq[Tcp.ServerBinding]]) {
  def stop()(implicit executionContext: ExecutionContext): Future[Unit] = {
    servers.flatMap { srvrs =>
      Future.sequence(srvrs.map(_.unbind())).map(_ => ())
    }
  }
}

case class SniSettings(enabled: Boolean, forwardIfNoMatch: Boolean, forwardsTo: TcpTarget = TcpTarget("127.0.0.1", None, 8080, false))
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
- [x] TCP service can match a sni domain for a same port (need to catch sni name per request)
- [x] TCP service can forward non matching request to local http server (only for sni request)
- [x] TCP service can be exposed over tls using dyn tls stuff
- [x] TCP service can passthrough tls
– [x] TCP service can specify if it needs or wants mtls
- [x] rules
  * if no sni matching, then only one Tcp service can exists with a specific port number
  * if sni matching, then multiple Tcp services can exists with a the port number
  * if sni matching, then all Tcp services using the same port number must have the same Tls mode
- [ ] We need a new datastore for tcp services
- [ ] We need to include tcp services in backup/restore
- [ ] We need a new admin api for tcp services
- [ ] We need to wire routexxx functions to the new datastore
- [ ] We need to generate access events
- [ ] A thread will request all tcp services with unique ports and stats tcp server. Servers will be shut down with otoroshi app
 */
case class TcpService(
  enabled: Boolean,
  tls: TlsMode,
  sni: SniSettings,
  clientAuth: ClientAuth,
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
      clientAuth = ClientAuth.None,
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
      clientAuth = ClientAuth.None,
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
      clientAuth = ClientAuth.None,
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
      clientAuth = ClientAuth.None,
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

  def runServers(env: Env)(implicit executionContext: ExecutionContext, system: ActorSystem, materializer: ActorMaterializer): Future[ServersBinding] = {
    FastFuture.successful(ServersBinding(Future.sequence(services.map { service =>
      TcpProxy(service.interface, service.port, service.tls, service.sni.enabled, service.clientAuth).start(env)
    })))
  }

  def findByPort(port: Int)(implicit ec: ExecutionContext): Future[Option[TcpService]] = FastFuture.successful(services.find(_.port == port))

  def domainMatch(matchRule: String, domain: String): Boolean = {
    RegexPool(matchRule).matches(domain)
  }

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

  def routeWithSNI(incoming: AwesomeIncomingConnection, debugger: String => Sink[ByteString, Future[Done]])(implicit ec: ExecutionContext, actorSystem: ActorSystem, materializer: ActorMaterializer): Future[Done] = {
    TcpService.findByPort(incoming.localAddress.getPort).flatMap {
      case Some(service) if service.enabled && service.sni.enabled => {
        try {
          val fullLayer: Flow[ByteString, ByteString, Future[_]] = Flow.lazyInitAsync { () =>
            incoming.domain.map { sniDomain =>
              log.info(s"domain: $sniDomain, local: ${incoming.localAddress}, remote: ${incoming.remoteAddress}")
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
                  val target = service.sni.forwardsTo
                  val remoteAddress = target.ip match {
                    case Some(ip) => new InetSocketAddress(InetAddress.getByAddress(target.host, InetAddress.getByName(ip).getAddress), target.port)
                    case None => new InetSocketAddress(target.host, target.port)
                  }
                  Tcp().outgoingConnection(remoteAddress)
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

class TcpEngineProvider {

  def createSSLEngine(clientAuth: ClientAuth, env: Env): SSLEngine = {
    lazy val cipherSuites = env.configuration.getOptional[Seq[String]]("otoroshi.ssl.cipherSuites").filterNot(_.isEmpty)
    lazy val protocols = env.configuration.getOptional[Seq[String]]("otoroshi.ssl.protocols").filterNot(_.isEmpty)

    val context: SSLContext = DynamicSSLEngineProvider.current
    DynamicSSLEngineProvider.logger.debug(s"Create SSLEngine from: $context")
    val rawEngine              = context.createSSLEngine()
    val engine        = new CustomSSLEngine(rawEngine)
    val rawEnabledCipherSuites = rawEngine.getEnabledCipherSuites.toSeq
    val rawEnabledProtocols    = rawEngine.getEnabledProtocols.toSeq
    cipherSuites.foreach(s => rawEngine.setEnabledCipherSuites(s.toArray))
    protocols.foreach(p => rawEngine.setEnabledProtocols(p.toArray))
    val sslParameters = new SSLParameters
    val matchers      = new java.util.ArrayList[SNIMatcher]()
    clientAuth match {
      case ClientAuth.Want =>
        engine.setWantClientAuth(true)
        sslParameters.setWantClientAuth(true)
      case ClientAuth.Need =>
        engine.setNeedClientAuth(true)
        sslParameters.setNeedClientAuth(true)
      case _ =>
    }
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
    cipherSuites.orElse(Some(rawEnabledCipherSuites)).foreach(s => sslParameters.setCipherSuites(s.toArray))
    protocols.orElse(Some(rawEnabledProtocols)).foreach(p => sslParameters.setProtocols(p.toArray))
    engine.setSSLParameters(sslParameters)
    engine
  }
}

object TcpProxy {
  def apply(interface: String, port: Int, tls: TlsMode, sni: Boolean, clientAuth: ClientAuth, debug: Boolean = false)(implicit system: ActorSystem, mat: ActorMaterializer): TcpProxy = new TcpProxy(interface, port, tls, sni, clientAuth, debug)(system, mat)
}

class TcpProxy(interface: String, port: Int, tls: TlsMode, sni: Boolean, clientAuth: ClientAuth, debug: Boolean = false)(implicit system: ActorSystem, mat: ActorMaterializer) {

  private val log = Logger("tcp-proxy")
  private implicit val ec = system.dispatcher
  private val provider = new TcpEngineProvider()

  private def debugger(title: String): Sink[ByteString, Future[Done]] = debug match {
    case true => Sink.foreach[ByteString](bs => log.info(title + bs.utf8String))
    case false => Sink.ignore
  }

  private def tcpBindTlsAndSNI(settings: ServerSettings, env: Env): Future[Tcp.ServerBinding] = {
    TcpUtils.bindTlsWithSSLEngineAndSNI(
      interface = interface,
      port = port,
      createSSLEngine = () => {
        provider.createSSLEngine(clientAuth, env)
      },
      backlog = settings.backlog,
      options = settings.socketOptions,
      idleTimeout = Duration.Inf,
      verifySession = session => {
        Success(())
      },
      closing = IgnoreComplete
    ).mapAsyncUnordered(settings.maxConnections) { incoming =>
      TcpService.routeWithSNI(incoming, debugger)(ec, system, mat)
    }
    .to(Sink.ignore)
    .run()
  }

  private def tcpBindTls(settings: ServerSettings, env: Env): Future[Tcp.ServerBinding] = {
    TcpUtils.bindTlsWithSSLEngine(
      interface = interface,
      port = port,
      createSSLEngine = () => {
        new TcpEngineProvider().createSSLEngine(clientAuth, env)
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

  private def tcpBindNoTls(settings: ServerSettings, env: Env): Future[Tcp.ServerBinding] = {
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

  def start(env: Env): Future[Tcp.ServerBinding] = {
    val config = env.configuration.getOptional[Config]("akka.http.server").getOrElse(ConfigFactory.parseString("""max-connections = 2048
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
                                                                                                     |}""".stripMargin))
    val settings = ServerSettings(config)
    tls match {
      case TlsMode.Disabled => tcpBindNoTls(settings, env)
      case TlsMode.PassThrough => tcpBindNoTls(settings, env)
      case TlsMode.Enabled if !sni => tcpBindTls(settings, env)
      case TlsMode.Enabled if sni => tcpBindTlsAndSNI(settings, env)
    }
  }
}
