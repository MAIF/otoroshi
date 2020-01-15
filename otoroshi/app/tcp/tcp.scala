package otoroshi.tcp

import java.net.{InetAddress, InetSocketAddress}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}
import java.util.regex.MatchResult

import actions.ApiAction
import akka.actor.{ActorSystem, Cancellable}
import akka.http.scaladsl.settings.ServerSettings
import akka.http.scaladsl.util.FastFuture
import akka.stream.TLSProtocol.NegotiateNewSession
import akka.stream.scaladsl.{Flow, Keep, Sink, Source, Tcp}
import akka.stream.{ActorMaterializer, IgnoreComplete}
import akka.util.ByteString
import akka.{AwesomeIncomingConnection, Done, TcpUtils}
import env.Env
import events.{DataInOut, Location, TcpEvent}
import javax.net.ssl._
import models.IpFiltering
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{AbstractController, ControllerComponents}
import redis.RedisClientMasterSlaves
import security.IdGenerator
import ssl.{ClientAuth, CustomSSLEngine, DynamicSSLEngineProvider}
import storage.redis.RedisStore
import storage.{BasicStore, RedisLike, RedisLikeStore}
import utils.RegexPool

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/**
- [x] TCP service can be disabled
- [x] TCP service without sni is defined on a port and forwards to targets
- [x] Target can define their own dns resolving
- [x] TCP service can match a sni domain for a same port (need to catch sni name per request)
- [x] TCP service can forward non matching request to local http server (only for sni request)
- [x] TCP service can be exposed over tls using dyn tls stuff
- [x] TCP service can passthrough tls
– [x] TCP service can specify if it needs or wants mtls
– [x] Passthrough + SNI
- [x] rules
 * if no sni matching, then only one Tcp service can exists with a specific port number
 * if sni matching, then multiple Tcp services can exists with a the port number
 * if sni matching, then all Tcp services using the same port number must have the same Tls mode
- [x] We need a new datastore for tcp services
- [x] We need to include tcp services in backup/restore
- [x] We need a new admin api for tcp services
- [x] We need a new UI for tcp services
- [x] We need to wire routexxx functions to the new datastore
- [x] We need to generate access events
- [x] A job will request all tcp services with unique ports and stats tcp server. Servers will be shut down with otoroshi app
- [ ] add api in swagger when feature is ready
- [ ] support ClientConfig for tcp
- [ ] support ClientValidator for tcp
- [ ] support IpFiltering
- [ ] support healthCheck for tcp (+UI)
- [ ] support snowMonkey for tcp
- [ ] support live metrics (+UI)
- [ ] support analytics in UI (metrics + events)
 */
case class TcpService(
    id: String = IdGenerator.token,
    name: String = "A TCP Proxy",
    enabled: Boolean,
    tls: TlsMode,
    sni: SniSettings,
    clientAuth: ClientAuth,
    port: Int,
    interface: String = "0.0.0.0",
    rules: Seq[TcpRule],
    // clientValidatorRef: Option[String]
    // clientConfig: ClientConfig
    // ipFiltering: IpFiltering
    // healthCheck
    // snowMonkey
) {
  def json: JsValue                                   = TcpService.fmt.writes(this)
  def save()(implicit ec: ExecutionContext, env: Env) = env.datastores.tcpServiceDataStore.set(this)
}
case class SniSettings(enabled: Boolean,
                       forwardIfNoMatch: Boolean,
                       forwardsTo: TcpTarget = TcpTarget("127.0.0.1", None, 8080, false)) {
  def json: JsValue = SniSettings.fmt.writes(this)
}
object SniSettings {
  def fmt: Format[SniSettings] = new Format[SniSettings] {
    override def writes(o: SniSettings): JsValue = Json.obj(
      "enabled"          -> o.enabled,
      "forwardIfNoMatch" -> o.forwardIfNoMatch,
      "forwardsTo"       -> o.forwardsTo.json,
    )
    override def reads(json: JsValue): JsResult[SniSettings] =
      Try {
        JsSuccess(
          SniSettings(
            enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
            forwardIfNoMatch = (json \ "forwardIfNoMatch").asOpt[Boolean].getOrElse(false),
            forwardsTo = (json \ "forwardsTo").asOpt(TcpTarget.fmt).getOrElse(TcpTarget("127.0.0.1", None, 8080, false))
          )
        )
      } recover {
        case e => JsError(e.getMessage)
      } get
  }
}
case class TcpTarget(host: String, ip: Option[String], port: Int, tls: Boolean) {
  def json: JsValue             = TcpTarget.fmt.writes(this)
  def toAnalyticsString: String = s"${host}${ip.map(v => "/" + v).getOrElse("")}:${port}"
}
object TcpTarget {
  def fmt: Format[TcpTarget] = new Format[TcpTarget] {
    override def writes(o: TcpTarget): JsValue = Json.obj(
      "host" -> o.host,
      "ip"   -> o.ip.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "port" -> o.port,
      "tls"  -> o.tls
    )
    override def reads(json: JsValue): JsResult[TcpTarget] =
      Try {
        JsSuccess(
          TcpTarget(
            host = (json \ "host").as[String],
            ip = (json \ "ip").asOpt[String],
            port = (json \ "port").asOpt[Int].getOrElse(8080),
            tls = (json \ "tls").asOpt[Boolean].getOrElse(false)
          )
        )
      } recover {
        case e => JsError(e.getMessage)
      } get
  }
}
case class TcpRule(domain: String, targets: Seq[TcpTarget]) {
  def json: JsValue = TcpRule.fmt.writes(this)
}
object TcpRule {
  def fmt: Format[TcpRule] = new Format[TcpRule] {
    override def writes(o: TcpRule): JsValue = Json.obj(
      "domain"  -> o.domain,
      "targets" -> JsArray(o.targets.map(_.json))
    )
    override def reads(json: JsValue): JsResult[TcpRule] =
      Try {
        JsSuccess(
          TcpRule(
            domain = (json \ "domain").asOpt[String].getOrElse("*"),
            targets = (json \ "targets").asOpt(Reads.seq(TcpTarget.fmt)).getOrElse(Seq.empty)
          )
        )
      } recover {
        case e => JsError(e.getMessage)
      } get
  }
}
sealed trait TlsMode {
  def name: String
}
object TlsMode {
  case object Disabled extends TlsMode {
    def name: String = "Disabled"
  }
  case object Enabled extends TlsMode {
    def name: String = "Enabled"
  }
  case object PassThrough extends TlsMode {
    def name: String = "PassThrough"
  }
  def apply(v: String): Option[TlsMode] = v match {
    case "Disabled"    => Some(Disabled)
    case "disabled"    => Some(Disabled)
    case "Enabled"     => Some(Enabled)
    case "enabled"     => Some(Enabled)
    case "PassThrough" => Some(PassThrough)
    case "passthrough" => Some(PassThrough)
    case _             => None
  }
}

object TcpService {

  private val reqCounter = new AtomicLong(0L)
  private val log        = Logger("otoroshi-tcp-proxy")

  def fromJsons(value: JsValue): TcpService =
    try {
      fmt.reads(value).get
    } catch {
      case e: Throwable => {
        log.error(s"Try to deserialize ${Json.prettyPrint(value)}")
        throw e
      }
    }

  def fromJsonSafe(value: JsValue): Either[Seq[(JsPath, Seq[JsonValidationError])], TcpService] =
    fmt.reads(value).asEither

  val fmt: Format[TcpService] = new Format[TcpService] {
    override def reads(json: JsValue): JsResult[TcpService] =
      Try {
        JsSuccess(
          TcpService(
            id = (json \ "id").as[String],
            name = (json \ "name").as[String],
            port = (json \ "port").as[Int],
            interface = (json \ "interface").asOpt[String].getOrElse("0.0.0.0"),
            enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
            tls = (json \ "tls").asOpt[String].flatMap(TlsMode.apply).getOrElse(TlsMode.Disabled),
            sni = (json \ "sni").asOpt(SniSettings.fmt).getOrElse(SniSettings(false, false)),
            clientAuth = (json \ "clientAuth").asOpt[String].flatMap(ClientAuth.apply).getOrElse(ClientAuth.None),
            rules = (json \ "rules").asOpt(Reads.seq(TcpRule.fmt)).getOrElse(Seq.empty)
          )
        )
      } recover {
        case e => JsError(e.getMessage)
      } get

    override def writes(o: TcpService): JsValue = Json.obj(
      "id"         -> o.id,
      "name"       -> o.name,
      "enabled"    -> o.enabled,
      "tls"        -> o.tls.name,
      "sni"        -> o.sni.json,
      "clientAuth" -> o.clientAuth.name,
      "port"       -> o.port,
      "interface"  -> o.interface,
      "rules"      -> JsArray(o.rules.map(_.json)),
    )
  }

  def runServers(env: Env): RunningServers = {
    new RunningServers(env).start()
  }

  def findAll()(implicit ec: ExecutionContext, env: Env): Future[Seq[TcpService]] =
    env.datastores.tcpServiceDataStore.findAll()

  def findByPort(port: Int)(implicit ec: ExecutionContext, env: Env): Future[Option[TcpService]] =
    findAll().map(_.find(_.port == port))

  def domainMatch(matchRule: String, domain: String): Boolean = {
    RegexPool(matchRule).matches(domain)
  }

  def routeWithoutSNI(incoming: Tcp.IncomingConnection,
                      port: Int,
                      id: String,
                      tls: Boolean,
                      start: Long,
                      debugger: String => Sink[ByteString, Future[Done]])(cb: (Long, Long) => Unit)(
      implicit ec: ExecutionContext,
      actorSystem: ActorSystem,
      materializer: ActorMaterializer,
      env: Env
  ): Future[TcpEvent] = {
    val dataIn    = new AtomicLong(0L)
    val dataOut   = new AtomicLong(0L)
    val targetRef = new AtomicReference[TcpTarget]()
    TcpService.findByPort(incoming.localAddress.getPort).flatMap {
      case Some(service) if service.enabled => {
        try {
          log.info(s"local: ${incoming.localAddress}, remote: ${incoming.remoteAddress}")
          val fullLayer: Flow[ByteString, ByteString, Future[Tcp.OutgoingConnection]] = {
            val targets = service.rules.flatMap(_.targets)
            val index   = reqCounter.incrementAndGet() % (if (targets.nonEmpty) targets.size else 1)
            val target  = targets.apply(index.toInt)
            targetRef.set(target)
            target.tls match {
              case true => {
                val remoteAddress = target.ip match {
                  case Some(ip) =>
                    new InetSocketAddress(InetAddress.getByAddress(target.host, InetAddress.getByName(ip).getAddress),
                                          target.port)
                  case None => new InetSocketAddress(target.host, target.port)
                }
                Tcp().outgoingTlsConnection(remoteAddress,
                                            DynamicSSLEngineProvider.current,
                                            NegotiateNewSession.withDefaults)
              }
              case false => {
                val remoteAddress = target.ip match {
                  case Some(ip) =>
                    new InetSocketAddress(InetAddress.getByAddress(target.host, InetAddress.getByName(ip).getAddress),
                                          target.port)
                  case None => new InetSocketAddress(target.host, target.port)
                }
                Tcp().outgoingConnection(remoteAddress)
              }
            }
          }
          val overhead = System.currentTimeMillis() - start
          fullLayer
            .alsoTo(Sink.foreach(bs => dataOut.addAndGet(bs.size))) // debugger("[RESP]: ")
            .alsoTo(Sink.onComplete(_ => cb(dataIn.get(), dataOut.get())))
            .joinMat(incoming.flow.alsoTo(Sink.foreach(bs => dataIn.addAndGet(bs.size))))(Keep.left) // debugger("[REQ]: ")
            .run()
            .map(_ => {
              val target    = Option(targetRef.get()).map(t => t.toAnalyticsString).getOrElse("--")
              val targetTls = Option(targetRef.get()).map(t => t.tls).getOrElse(false)
              TcpEvent(
                `@id` = env.snowflakeGenerator.nextIdStr(),
                `@timestamp` = DateTime.now(),
                reqId = id,
                protocol = if (tls) "Tcp/Tls" else "Tcp",
                to = Location("*", if (tls) "Tcp/Tls" else "Tcp", ""),
                target = Location(target, if (targetTls) "Tcp/Tls" else "Tcp", ""),
                remote = incoming.remoteAddress.toString,
                local = incoming.localAddress.toString,
                duration = 0L,
                overhead = overhead,
                data = DataInOut(dataIn.get(), dataOut.get()),
                gwError = None,
                `@serviceId` = service.id,
                `@service` = service.name,
                port = port,
                service = Some(service)
              )
            })
            .recover {
              case NonFatal(ex) =>
                val target    = Option(targetRef.get()).map(t => t.toAnalyticsString).getOrElse("--")
                val targetTls = Option(targetRef.get()).map(t => t.tls).getOrElse(false)
                TcpEvent(
                  `@id` = env.snowflakeGenerator.nextIdStr(),
                  `@timestamp` = DateTime.now(),
                  reqId = id,
                  protocol = if (tls) "Tcp/Tls" else "Tcp",
                  to = Location("*", if (tls) "Tcp/Tls" else "Tcp", ""),
                  target = Location(target, if (targetTls) "Tcp/Tls" else "Tcp", ""),
                  remote = incoming.remoteAddress.toString,
                  local = incoming.localAddress.toString,
                  duration = 0L,
                  overhead = overhead,
                  data = DataInOut(dataIn.get(), dataOut.get()),
                  gwError = Some(ex.getMessage),
                  `@serviceId` = service.id,
                  `@service` = service.name,
                  port = port,
                  service = Some(service)
                )
            }
        } catch {
          case NonFatal(e) =>
            val target    = Option(targetRef.get()).map(t => t.toAnalyticsString).getOrElse("--")
            val targetTls = Option(targetRef.get()).map(t => t.tls).getOrElse(false)
            log.error(s"Could not materialize handling flow for ${incoming}", e)
            Future.successful(
              TcpEvent(
                `@id` = env.snowflakeGenerator.nextIdStr(),
                `@timestamp` = DateTime.now(),
                reqId = id,
                protocol = if (tls) "Tcp/Tls" else "Tcp",
                to = Location("*", if (tls) "Tcp/Tls" else "Tcp", ""),
                target = Location(target, if (targetTls) "Tcp/Tls" else "Tcp", ""),
                remote = incoming.remoteAddress.toString,
                local = incoming.localAddress.toString,
                duration = 0L,
                overhead = 0L,
                data = DataInOut(dataIn.get(), dataOut.get()),
                gwError = Some(s"Could not materialize handling flow for ${incoming}: $e"),
                `@serviceId` = "otoroshi",
                `@service` = "otoroshi",
                port = port,
                service = None
              )
            )
        }
      }
      case _ =>
        val target    = Option(targetRef.get()).map(t => t.toAnalyticsString).getOrElse("--")
        val targetTls = Option(targetRef.get()).map(t => t.tls).getOrElse(false)
        Future.successful(
          TcpEvent(
            `@id` = env.snowflakeGenerator.nextIdStr(),
            `@timestamp` = DateTime.now(),
            reqId = id,
            protocol = if (tls) "Tcp/Tls" else "Tcp",
            to = Location("*", if (tls) "Tcp/Tls" else "Tcp", ""),
            target = Location(target, if (targetTls) "Tcp/Tls" else "Tcp", ""),
            remote = incoming.remoteAddress.toString,
            local = incoming.localAddress.toString,
            duration = 0L,
            overhead = 0L,
            data = DataInOut(dataIn.get(), dataOut.get()),
            gwError = Some("No matching service !"),
            `@serviceId` = "otoroshi",
            `@service` = "otoroshi",
            port = port,
            service = None
          )
        )
    }
  }

  def routeWithSNI(incoming: AwesomeIncomingConnection,
                   port: Int,
                   id: String,
                   tls: Boolean,
                   start: Long,
                   debugger: String => Sink[ByteString, Future[Done]])(cb: (Long, Long) => Unit)(
      implicit ec: ExecutionContext,
      actorSystem: ActorSystem,
      materializer: ActorMaterializer,
      env: Env
  ): Future[TcpEvent] = {
    val dataIn    = new AtomicLong(0L)
    val dataOut   = new AtomicLong(0L)
    val targetRef = new AtomicReference[TcpTarget]()
    val ref       = new AtomicReference[String]()
    TcpService.findByPort(incoming.localAddress.getPort).flatMap {
      case Some(service) if service.enabled && service.sni.enabled => {
        try {
          val fullLayer: Flow[ByteString, ByteString, Future[_]] = Flow.lazyInitAsync { () =>
            incoming.domain.map { sniDomain =>
              ref.set(sniDomain + ":" + port)
              log.info(s"domain: $sniDomain, local: ${incoming.localAddress}, remote: ${incoming.remoteAddress}")
              service.rules.find(r => domainMatch(r.domain, sniDomain)) match {
                case Some(rule) => {
                  val targets = rule.targets
                  val index   = reqCounter.incrementAndGet() % (if (targets.nonEmpty) targets.size else 1)
                  val target  = targets.apply(index.toInt)
                  targetRef.set(target)
                  target.tls match {
                    case true => {
                      val remoteAddress = target.ip match {
                        case Some(ip) =>
                          new InetSocketAddress(InetAddress.getByAddress(target.host,
                                                                         InetAddress.getByName(ip).getAddress),
                                                target.port)
                        case None => new InetSocketAddress(target.host, target.port)
                      }
                      Tcp().outgoingTlsConnection(remoteAddress,
                                                  DynamicSSLEngineProvider.current,
                                                  NegotiateNewSession.withDefaults)
                    }
                    case false => {
                      val remoteAddress = target.ip match {
                        case Some(ip) =>
                          new InetSocketAddress(InetAddress.getByAddress(target.host,
                                                                         InetAddress.getByName(ip).getAddress),
                                                target.port)
                        case None => new InetSocketAddress(target.host, target.port)
                      }
                      Tcp().outgoingConnection(remoteAddress)
                    }
                  }
                }
                case None if service.sni.forwardIfNoMatch => {
                  val target = service.sni.forwardsTo
                  val remoteAddress = target.ip match {
                    case Some(ip) =>
                      new InetSocketAddress(InetAddress.getByAddress(target.host, InetAddress.getByName(ip).getAddress),
                                            target.port)
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
          val overhead = System.currentTimeMillis() - start
          fullLayer
            .alsoTo(Sink.foreach(bs => dataOut.addAndGet(bs.size))) // debugger("[RESP]: ")
            .alsoTo(Sink.onComplete(_ => cb(dataIn.get(), dataOut.get())))
            .joinMat(incoming.flow.alsoTo(Sink.foreach(bs => dataIn.addAndGet(bs.size))))(Keep.left) // debugger("[REQ]: ")
            .run()
            .map(_ => {
              val target    = Option(targetRef.get()).map(t => t.toAnalyticsString).getOrElse("--")
              val targetTls = Option(targetRef.get()).map(t => t.tls).getOrElse(false)
              TcpEvent(
                `@id` = env.snowflakeGenerator.nextIdStr(),
                `@timestamp` = DateTime.now(),
                reqId = id,
                protocol = if (tls) "Tcp/Tls" else "Tcp",
                to = Location(Option(ref.get()).getOrElse("no-sni"), if (tls) "Tcp/Tls" else "Tcp", ""),
                target = Location(target, if (targetTls) "Tcp/Tls" else "Tcp", ""),
                remote = incoming.remoteAddress.toString,
                local = incoming.localAddress.toString,
                duration = 0L,
                overhead = overhead,
                data = DataInOut(dataIn.get(), dataOut.get()),
                gwError = None,
                `@serviceId` = service.id,
                `@service` = service.name,
                port = port,
                service = Some(service)
              )
            })
            .recover {
              case NonFatal(ex) =>
                val target    = Option(targetRef.get()).map(t => t.toAnalyticsString).getOrElse("--")
                val targetTls = Option(targetRef.get()).map(t => t.tls).getOrElse(false)
                TcpEvent(
                  `@id` = env.snowflakeGenerator.nextIdStr(),
                  `@timestamp` = DateTime.now(),
                  reqId = id,
                  protocol = if (tls) "Tcp/Tls" else "Tcp",
                  to = Location(Option(ref.get()).getOrElse("no-sni"), if (tls) "Tcp/Tls" else "Tcp", ""),
                  target = Location(target, if (targetTls) "Tcp/Tls" else "Tcp", ""),
                  remote = incoming.remoteAddress.toString,
                  local = incoming.localAddress.toString,
                  duration = 0L,
                  overhead = overhead,
                  data = DataInOut(dataIn.get(), dataOut.get()),
                  gwError = Some(ex.getMessage),
                  `@serviceId` = service.id,
                  `@service` = service.name,
                  port = port,
                  service = Some(service)
                )
            }
        } catch {
          case NonFatal(e) =>
            log.error(s"Could not materialize handling flow for ${incoming}", e)
            val target    = Option(targetRef.get()).map(t => t.toAnalyticsString).getOrElse("--")
            val targetTls = Option(targetRef.get()).map(t => t.tls).getOrElse(false)
            Future.successful(
              TcpEvent(
                `@id` = env.snowflakeGenerator.nextIdStr(),
                `@timestamp` = DateTime.now(),
                reqId = id,
                protocol = if (tls) "Tcp/Tls" else "Tcp",
                to = Location(Option(ref.get()).getOrElse("no-sni"), if (tls) "Tcp/Tls" else "Tcp", ""),
                target = Location(target, if (targetTls) "Tcp/Tls" else "Tcp", ""),
                remote = incoming.remoteAddress.toString,
                local = incoming.localAddress.toString,
                duration = 0L,
                overhead = 0L,
                data = DataInOut(dataIn.get(), dataOut.get()),
                gwError = Some(s"Could not materialize handling flow for ${incoming}: $e"),
                `@serviceId` = "otoroshi",
                `@service` = "otoroshi",
                port = port,
                service = None
              )
            )
        }
      }
      case _ =>
        val target    = Option(targetRef.get()).map(t => t.toAnalyticsString).getOrElse("--")
        val targetTls = Option(targetRef.get()).map(t => t.tls).getOrElse(false)
        Future.successful(
          TcpEvent(
            `@id` = env.snowflakeGenerator.nextIdStr(),
            `@timestamp` = DateTime.now(),
            reqId = id,
            protocol = if (tls) "Tcp/Tls" else "Tcp",
            to = Location(Option(ref.get()).getOrElse("no-sni"), if (tls) "Tcp/Tls" else "Tcp", ""),
            target = Location(target, if (targetTls) "Tcp/Tls" else "Tcp", ""),
            remote = incoming.remoteAddress.toString,
            local = incoming.localAddress.toString,
            duration = 0L,
            overhead = 0L,
            data = DataInOut(dataIn.get(), dataOut.get()),
            gwError = Some("No matching service !"),
            `@serviceId` = "otoroshi",
            `@service` = "otoroshi",
            port = port,
            service = None
          )
        )
    }
  }
}

class TcpEngineProvider {
  def createSSLEngine(clientAuth: ClientAuth, env: Env): SSLEngine = {
    lazy val cipherSuites = env.configuration.getOptional[Seq[String]]("otoroshi.ssl.cipherSuites").filterNot(_.isEmpty)
    lazy val protocols    = env.configuration.getOptional[Seq[String]]("otoroshi.ssl.protocols").filterNot(_.isEmpty)

    val context: SSLContext = DynamicSSLEngineProvider.current
    DynamicSSLEngineProvider.logger.debug(s"Create SSLEngine from: $context")
    val rawEngine              = context.createSSLEngine()
    val engine                 = new CustomSSLEngine(rawEngine)
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
  def apply(tcp: TcpService)(implicit system: ActorSystem, mat: ActorMaterializer): TcpProxy =
    new TcpProxy(tcp.interface, tcp.port, tcp.tls, tcp.sni.enabled, tcp.clientAuth, false)(system, mat)
  def apply(interface: String, port: Int, tls: TlsMode, sni: Boolean, clientAuth: ClientAuth, debug: Boolean = false)(
      implicit system: ActorSystem,
      mat: ActorMaterializer
  ): TcpProxy = new TcpProxy(interface, port, tls, sni, clientAuth, debug)(system, mat)
}

class TcpProxy(interface: String, port: Int, tls: TlsMode, sni: Boolean, clientAuth: ClientAuth, debug: Boolean = false)(
    implicit system: ActorSystem,
    mat: ActorMaterializer
) {

  private val log         = Logger("otoroshi-tcp-proxy")
  private implicit val ec = system.dispatcher
  private val provider    = new TcpEngineProvider()

  private def debugger(title: String): Sink[ByteString, Future[Done]] = debug match {
    case true  => Sink.foreach[ByteString](bs => log.info(title + bs.utf8String))
    case false => Sink.ignore
  }

  private def tcpBindTlsAndSNI(settings: ServerSettings, env: Env): Future[Tcp.ServerBinding] = {
    TcpUtils
      .bindTlsWithSSLEngineAndSNI(
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
      )
      .mapAsyncUnordered(settings.maxConnections) { incoming =>
        val id    = env.snowflakeGenerator.nextIdStr()
        val start = System.currentTimeMillis()
        val ref   = new AtomicReference[TcpEvent]()
        TcpService
          .routeWithSNI(incoming, port, id, true, start, debugger) {
            case (in, out) =>
              ref
                .get()
                .copy(duration = System.currentTimeMillis() - start, data = DataInOut(in, out))
                .toAnalytics()(env)
          }(ec, system, mat, env)
          .andThen {
            case Success(evt) =>
              ref.set(evt) //evt.copy(duration = System.currentTimeMillis() - start).toAnalytics()(env)
          }
      }
      .to(Sink.ignore)
      .run()
  }

  private def tcpBindTls(settings: ServerSettings, env: Env): Future[Tcp.ServerBinding] = {
    TcpUtils
      .bindTlsWithSSLEngine(
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
      )
      .mapAsyncUnordered(settings.maxConnections) { incoming =>
        val id    = env.snowflakeGenerator.nextIdStr()
        val start = System.currentTimeMillis()
        val ref   = new AtomicReference[TcpEvent]()
        TcpService
          .routeWithoutSNI(incoming, port, id, true, start, debugger) {
            case (in, out) =>
              ref
                .get()
                .copy(duration = System.currentTimeMillis() - start, data = DataInOut(in, out))
                .toAnalytics()(env)
          }(ec, system, mat, env)
          .andThen {
            case Success(evt) =>
              ref.set(evt) //evt.copy(duration = System.currentTimeMillis() - start).toAnalytics()(env)
          }
      }
      .to(Sink.ignore)
      .run()
  }

  private def tcpBindNoTls(settings: ServerSettings, env: Env): Future[Tcp.ServerBinding] = {
    Tcp()
      .bind(
        interface = interface,
        port = port,
        halfClose = false,
        backlog = settings.backlog,
        options = settings.socketOptions,
        idleTimeout = Duration.Inf
      )
      .mapAsyncUnordered(settings.maxConnections) { incoming =>
        val id    = env.snowflakeGenerator.nextIdStr()
        val start = System.currentTimeMillis()
        val ref   = new AtomicReference[TcpEvent]()
        TcpService
          .routeWithoutSNI(incoming, port, id, false, start, debugger) {
            case (in, out) =>
              ref
                .get()
                .copy(duration = System.currentTimeMillis() - start, data = DataInOut(in, out))
                .toAnalytics()(env)
          }(ec, system, mat, env)
          .andThen {
            case Success(evt) =>
              ref.set(evt) //evt.copy(duration = System.currentTimeMillis() - start).toAnalytics()(env)
          }
      }
      .to(Sink.ignore)
      .run()
  }

  private def tcpBindNoTlsAndSNI(settings: ServerSettings, env: Env): Future[Tcp.ServerBinding] = {
    Tcp()
      .bind(
        interface = interface,
        port = port,
        halfClose = false,
        backlog = settings.backlog,
        options = settings.socketOptions,
        idleTimeout = Duration.Inf
      )
      .map { incomingConnection =>
        val promise    = Promise[String]
        val firstChunk = new AtomicBoolean(false)
        AwesomeIncomingConnection(
          incomingConnection.copy(
            flow = incomingConnection.flow.alsoTo(Sink.foreach { bs =>
              if (firstChunk.compareAndSet(false, true)) {
                val packetString = bs.utf8String
                val matcher      = akka.TcpUtils.domainNamePattern.matcher(packetString)
                while (matcher.find()) {
                  val matchResult: MatchResult = matcher.toMatchResult
                  val expression: String       = matchResult.group()
                  promise.trySuccess(expression)
                }
                if (!promise.isCompleted) {
                  promise.tryFailure(new RuntimeException("SNI not found !"))
                }
              }
            })
          ),
          promise.future
        )
      }
      .mapAsyncUnordered(settings.maxConnections) { incoming =>
        val id    = env.snowflakeGenerator.nextIdStr()
        val start = System.currentTimeMillis()
        val ref   = new AtomicReference[TcpEvent]()
        TcpService
          .routeWithSNI(incoming, port, id, false, start, debugger) {
            case (in, out) =>
              val e = ref.get().copy(duration = System.currentTimeMillis() - start, data = DataInOut(in, out))
              // println(Json.prettyPrint(e.toJson(env)))
              e.toAnalytics()(env)
          }(ec, system, mat, env)
          .andThen {
            case Success(evt) =>
              ref.set(evt) //evt.copy(duration = System.currentTimeMillis() - start).toAnalytics()(env)
          }
      }
      .to(Sink.ignore)
      .run()
  }

  def start(env: Env): Future[Tcp.ServerBinding] = {
    val config   = env.configuration.underlying
    val settings = ServerSettings(config)
    (tls match {
      case TlsMode.Disabled            => tcpBindNoTls(settings, env)
      case TlsMode.PassThrough if sni  => tcpBindNoTlsAndSNI(settings, env)
      case TlsMode.PassThrough if !sni => tcpBindNoTls(settings, env)
      case TlsMode.Enabled if !sni     => tcpBindTls(settings, env)
      case TlsMode.Enabled if sni      => tcpBindTlsAndSNI(settings, env)
    }).andThen {
      case Success(_) if tls == TlsMode.Enabled => log.info(s"Tcp/Tls proxy listening on $interface:$port")
      case Success(_)                           => log.info(s"Tcp     proxy listening on $interface:$port")
      case Failure(e) if tls == TlsMode.Enabled =>
        log.error(s"Error while binding Tcp/Tls proxy on $interface:$port", e)
      case Failure(e) => log.error(s"Error while binding Tcp     proxy on $interface:$port", e)
    }
  }
}

case class RunningServer(port: Int, oldService: TcpService, binding: Future[Tcp.ServerBinding])

class RunningServers(env: Env) {

  import scala.concurrent.duration._

  private implicit val system = env.otoroshiActorSystem
  private implicit val ec     = env.otoroshiExecutionContext
  private implicit val mat    = env.otoroshiMaterializer
  private implicit val ev     = env
  private val ref             = new AtomicReference[Cancellable]()
  private val running         = new AtomicBoolean(false)
  private val syncing         = new AtomicBoolean(false)
  private val runningServers  = new AtomicReference[Seq[RunningServer]](Seq.empty)
  private val log             = Logger("otoroshi-tcp-proxy")

  private def updateRunningServers(): Unit = {
    if (running.get() && syncing.compareAndSet(false, true)) {
      TcpService
        .findAll()
        .map { services =>
          val actualServers = runningServers.get()
          val existingPorts = actualServers.map(_.port)
          log.debug(s"[RunningServer] existing $existingPorts")
          val changed = services.filter(s => existingPorts.contains(s.port)).filter { s =>
            val server = actualServers.find(_.port == s.port).get
            s.interface != server.oldService.interface ||
            s.sni != server.oldService.sni ||
            s.tls != server.oldService.tls ||
            s.clientAuth != server.oldService.clientAuth
          }
          log.debug(s"[RunningServer] changed ${changed.map(_.port)}")
          val notRunning = services.filterNot(s => existingPorts.contains(s.port))
          log.debug(s"[RunningServer] notRunning ${notRunning.map(_.port)}")
          val willExistPort = (changed ++ notRunning ++ services).distinct.map(_.port)
          log.debug(s"[RunningServer] willExist ${willExistPort}")
          val toShutDown = actualServers.filterNot(s => willExistPort.contains(s.port))
          log.debug(s"[RunningServer] toShutDown ${toShutDown.map(_.port)}")
          val allDown1 = Future.sequence(toShutDown.map { s =>
            log.info(
              s"Stopping Tcp proxy on ${s.oldService.interface}:${s.oldService.port} because it does not exists anymore"
            )
            s.binding.flatMap(_.unbind())
          })
          val allDown2 = Future.sequence(changed.map { s =>
            val server = actualServers.find(_.port == s.port).get
            log.info(
              s"Stopping Tcp proxy on ${server.oldService.interface}:${server.oldService.port} because the service changed"
            )
            server.binding.flatMap(_.unbind())
          })
          for {
            _ <- allDown1
            _ <- allDown2
          } yield {
            val running1      = changed.map(s => RunningServer(s.port, s, TcpProxy(s).start(env)))
            val running2      = notRunning.map(s => RunningServer(s.port, s, TcpProxy(s).start(env)))
            val changedPorts  = changed.map(_.port)
            val shutdownPorts = toShutDown.map(_.port)
            val stayServers =
              actualServers.filterNot(s => changedPorts.contains(s.port) || shutdownPorts.contains(s.port))
            runningServers.set(stayServers ++ running1 ++ running2)
          }
        }
        .andThen {
          case _ => syncing.compareAndSet(true, false)
        }
    }
  }

  def start(): RunningServers = {
    if (running.compareAndSet(false, true)) {
      ref.set(system.scheduler.schedule(1.second, 10.seconds) {
        updateRunningServers()
      })
    }
    this
  }

  def stop(): Future[Unit] = {
    if (running.compareAndSet(true, false)) {
      Option(ref.get()).foreach(_.cancel())
      Future
        .sequence(runningServers.get().map { server =>
          log.info(s"Stopping Tcp proxy on ${server.oldService.interface}:${server.oldService.port}")
          server.binding.flatMap(_.unbind())
        })
        .map(_ => ())
    } else {
      FastFuture.successful(())
    }
  }
}

sealed trait TcpServiceDataStore extends BasicStore[TcpService]

class InMemoryTcpServiceDataStoreDataStore(redisCli: RedisLike, env: Env)
    extends TcpServiceDataStore
    with RedisLikeStore[TcpService] {

  override def fmt: Format[TcpService]                 = TcpService.fmt
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): models.Key             = models.Key(s"${env.storageRoot}:tcp:services:$id")
  override def extractId(value: TcpService): String    = value.id
}

class RedisTcpServiceDataStoreDataStore(redisCli: RedisClientMasterSlaves, env: Env)
    extends TcpServiceDataStore
    with RedisStore[TcpService] {

  override def _redis(implicit env: Env): RedisClientMasterSlaves = redisCli
  override def fmt: Format[TcpService]                            = TcpService.fmt
  override def key(id: String): models.Key                        = models.Key(s"${env.storageRoot}:tcp:services:$id")
  override def extractId(value: TcpService): String               = value.id
}

class TcpServiceApiController(ApiAction: ApiAction, cc: ControllerComponents)(
    implicit env: Env
) extends AbstractController(cc) {

  import gnieh.diffson.playJson._
  import utils.future.Implicits._

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  val logger = Logger("otoroshi-tcp-service-api")

  def initiateTcpService() = ApiAction { ctx =>
    Ok(
      TcpService(
        id = IdGenerator.token,
        enabled = true,
        tls = TlsMode.Disabled,
        sni = SniSettings(false, false),
        clientAuth = ClientAuth.None,
        port = 4200,
        rules = Seq(
          TcpRule(
            domain = "*",
            targets = Seq(
              TcpTarget(
                "42.42.42.42",
                None,
                4200,
                false
              )
            )
          )
        )
      ).json
    )
  }

  def findAllTcpServices() = ApiAction.async { ctx =>
    env.datastores.tcpServiceDataStore.findAll().map(all => Ok(JsArray(all.map(_.json))))
  }

  def findTcpServiceById(id: String) = ApiAction.async { ctx =>
    env.datastores.tcpServiceDataStore.findById(id).map {
      case Some(service) => Ok(service.json)
      case None =>
        NotFound(
          Json.obj("error" -> s"TcpService with id $id not found")
        )
    }
  }

  def createTcpService() = ApiAction.async(parse.json) { ctx =>
    TcpService.fromJsonSafe(ctx.request.body) match {
      case Left(_) => BadRequest(Json.obj("error" -> "Bad TcpService format")).asFuture
      case Right(service) =>
        env.datastores.tcpServiceDataStore.set(service).map { _ =>
          Ok(service.json)
        }
    }
  }

  def updateTcpService(id: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.tcpServiceDataStore.findById(id).flatMap {
      case None =>
        NotFound(
          Json.obj("error" -> s"TcpService with id $id not found")
        ).asFuture
      case Some(initialTcpService) => {
        TcpService.fromJsonSafe(ctx.request.body) match {
          case Left(_) => BadRequest(Json.obj("error" -> "Bad TcpService format")).asFuture
          case Right(service) => {
            env.datastores.tcpServiceDataStore.set(service).map { _ =>
              Ok(service.json)
            }
          }
        }
      }
    }
  }

  def patchTcpService(id: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.tcpServiceDataStore.findById(id).flatMap {
      case None =>
        NotFound(
          Json.obj("error" -> s"TcpService with id $id not found")
        ).asFuture
      case Some(initialTcpService) => {
        val currentJson   = initialTcpService.json
        val patch         = JsonPatch(ctx.request.body)
        val newTcpService = patch(currentJson)
        TcpService.fromJsonSafe(newTcpService) match {
          case Left(_) => BadRequest(Json.obj("error" -> "Bad TcpService format")).asFuture
          case Right(newTcpService) => {
            env.datastores.tcpServiceDataStore.set(newTcpService).map { _ =>
              Ok(newTcpService.json)
            }
          }
        }
      }
    }
  }

  def deleteTcpService(id: String) = ApiAction.async { ctx =>
    env.datastores.tcpServiceDataStore.delete(id).map { _ =>
      Ok(Json.obj("done" -> true))
    }
  }
}

/*

  private val _services = Seq(
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
    ),
    TcpService(
      enabled = true,
      tls = TlsMode.PassThrough,
      sni = SniSettings(true, false),
      clientAuth = ClientAuth.None,
      port = 1205,
      // test with
      // curl -v --resolve www.google.fr:1205:127.0.0.1 https://www.google.fr:1205/
      // curl -v --resolve www.amazon.fr:1205:127.0.0.1 https://www.amazon.fr:1205/ --compressed
      rules = Seq(
        TcpRule(
          domain = "www.google.fr",
          targets = Seq(
            TcpTarget(
              "www.google.fr",
              None,
              443,
              false
            )
          )
        ),
        TcpRule(
          domain = "www.amazon.fr",
          targets = Seq(
            TcpTarget(
              "www.amazon.fr",
              None,
              443,
              false
            )
          )
        )
      )
    )
  )
 */
