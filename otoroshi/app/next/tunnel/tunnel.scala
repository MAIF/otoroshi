package otoroshi.next.tunnel

import akka.actor.{Actor, ActorRef, Props}
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.ws.{InvalidUpgradeResponse, ValidUpgrade, WebSocketRequest}
import akka.http.scaladsl.model.{HttpProtocols, Uri}
import akka.stream.scaladsl.{Flow, Sink, Source, SourceQueueWithComplete}
import akka.stream.{Materializer, OverflowStrategy}
import akka.util.ByteString
import com.github.blemale.scaffeine.Scaffeine
import org.joda.time.DateTime
import otoroshi.actions.ApiAction
import otoroshi.cluster.MemberView
import otoroshi.env.Env
import otoroshi.models.{ApiKey, Target, TargetPredicate}
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.{NgProxyEngineError, ProxyEngine, TunnelRequest}
import otoroshi.script.RequestHandler
import otoroshi.security.IdGenerator
import otoroshi.utils.http.{ManualResolveTransport, MtlsConfig}
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import play.api.http.HttpEntity
import play.api.http.websocket._
import play.api.libs.json._
import play.api.libs.streams.ActorFlow
import play.api.libs.ws.WSCookie
import play.api.mvc._
import play.api.{Configuration, Logger}

import java.net.InetSocketAddress
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicReference}
import scala.collection.concurrent.TrieMap
import scala.collection.immutable
import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}
import otoroshi.cluster.ClusterConfig
import play.api.libs.ws.DefaultWSProxyServer
import akka.http.scaladsl.ClientTransport
import otoroshi.utils.cache.types.LegitTrieMap

case class TunnelPluginConfig(tunnelId: String) extends NgPluginConfig {
  override def json: JsValue = Json.obj("tunnel_id" -> tunnelId)
}

object TunnelPluginConfig {
  val default = TunnelPluginConfig("default")
  def format  = new Format[TunnelPluginConfig] {
    override def writes(o: TunnelPluginConfig): JsValue             = o.json
    override def reads(json: JsValue): JsResult[TunnelPluginConfig] = Try {
      TunnelPluginConfig(
        tunnelId = json.select("tunnel_id").asString
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value)     => JsSuccess(value)
    }
  }
}

// TODO: TCP implementation to support SSE and websockets ??? or just improve the protocol to create source of bytestring
class TunnelPlugin extends NgBackendCall {

  private val logger = Logger("otoroshi-tunnel-plugin")

  override def useDelegates: Boolean                       = false
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = false
  override def name: String                                = "Remote tunnel calls"
  override def description: Option[String]                 =
    "This plugin can contact remote service using tunnels".some
  override def defaultConfigObject: Option[NgPluginConfig] = TunnelPluginConfig.default.some

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Integrations)
  override def steps: Seq[NgStep]                = Seq(NgStep.CallBackend)

  override def callBackend(
      ctx: NgbBackendCallContext,
      delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]]
  )(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config   = ctx.cachedConfig(internalName)(TunnelPluginConfig.format).getOrElse(TunnelPluginConfig.default)
    val tunnelId = config.tunnelId
    if (logger.isDebugEnabled) logger.debug(s"routing call through tunnel '${tunnelId}'")
    env.tunnelManager.sendRequest(tunnelId, ctx.request, ctx.rawRequest.remoteAddress, ctx.rawRequest.theSecured).map {
      result =>
        if (logger.isDebugEnabled) logger.debug(s"response from tunnel '${tunnelId}'")
        val setCookieHeader: Seq[WSCookie] = result.header.headers
          .getIgnoreCase("Set-Cookie")
          .map { sc =>
            Cookies.decodeSetCookieHeader(sc)
          }
          .getOrElse(Seq.empty)
          .map(_.wsCookie)
        Right(
          BackendCallResponse(
            NgPluginHttpResponse(
              status = result.header.status,
              headers = result.header.headers,
              cookies = setCookieHeader,
              body = result.body.dataStream
            ),
            None
          )
        )
    }
  }
}

class TunnelAgent(env: Env) {

  private val logger = Logger("otoroshi-tunnel-agent")

  private val counter = new AtomicInteger(0)

  def start(): TunnelAgent = {
    env.configuration.getOptional[Configuration]("otoroshi.tunnels").map { config =>
      val genabled = config.getOptional[Boolean]("enabled").getOrElse(false)
      if (genabled) {
        config.subKeys
          .map { key =>
            (key, config.getOpt[Configuration](key))
          }
          .collect { case (key, Some(conf)) =>
            (key, conf)
          }
          .map { case (key, conf) =>
            val enabled = conf.getOptional[Boolean]("enabled").getOrElse(false)
            if (enabled) {
              val tunnelId     = conf.getOptional[String]("id").getOrElse(key)
              val name         = conf.getOptional[String]("name").getOrElse("default")
              val url          = conf.getOptional[String]("url").getOrElse("http://127.0.0.1:9999")
              val hostname     = conf.getOptional[String]("host").getOrElse("otoroshi-api.oto.tools")
              val clientId     = conf.getOptional[String]("clientId").getOrElse("client")
              val clientSecret = conf.getOptional[String]("clientSecret").getOrElse("secret")
              val ipAddress    = conf.getOptional[String]("ipAddress")
              val exportMeta   = conf.getOptional[Boolean]("export-routes").getOrElse(true)
              val exportTag    = conf.getOptional[String]("export-routes-tag")
              val proxy        = if (conf.getOptional[Boolean]("proxy.enabled").getOrElse(false)) {
                Some(
                  DefaultWSProxyServer(
                    host = conf.getOptional[String]("proxy.host").get,
                    port = conf.getOptional[Int]("proxy.port").get,
                    protocol = Some("https"),
                    principal = conf.getOptional[String]("proxy.principal"),
                    password = conf.getOptional[String]("proxy.password"),
                    nonProxyHosts = conf.getOptional[Seq[String]]("proxy.nonProxyHosts")
                  )
                )
              } else {
                None
              }
              val tls = {
                val enabled =
                  conf
                    .getOptionalWithFileSupport[Boolean]("tls.mtls")
                    .orElse(conf.getOptionalWithFileSupport[Boolean]("tls.enabled"))
                    .getOrElse(false)
                if (enabled) {
                  val loose        =
                    conf.getOptionalWithFileSupport[Boolean]("tls.loose").getOrElse(false)
                  val trustAll     =
                    conf.getOptionalWithFileSupport[Boolean]("tls.trustAll").getOrElse(false)
                  val certs        =
                    conf.getOptionalWithFileSupport[Seq[String]]("tls.certs").getOrElse(Seq.empty)
                  val trustedCerts = conf
                    .getOptionalWithFileSupport[Seq[String]]("tls.trustedCerts")
                    .getOrElse(Seq.empty)
                  MtlsConfig(
                    certs = certs,
                    trustedCerts = trustedCerts,
                    mtls = enabled,
                    loose = loose,
                    trustAll = trustAll
                  ).some
                } else {
                  None
                }
              }
              connect(
                tunnelId,
                name,
                url,
                hostname,
                clientId,
                clientSecret,
                ipAddress,
                tls,
                proxy,
                exportMeta,
                exportTag,
                200,
                200
              )
            }
          }
      }
    }
    this
  }

  private def connect(
      tunnelId: String,
      name: String,
      url: String,
      hostname: String,
      clientId: String,
      clientSecret: String,
      ipAddress: Option[String],
      tls: Option[MtlsConfig],
      proxy: Option[DefaultWSProxyServer],
      exportMeta: Boolean,
      exportTag: Option[String],
      initialWaiting: Long,
      waiting: Long
  ): Future[Unit] = {

    implicit val ec  = env.otoroshiExecutionContext
    implicit val mat = env.otoroshiMaterializer
    implicit val ev  = env

    logger.info(s"connecting tunnel '${tunnelId}' ...")

    val promise                                                                                                     = Promise[Unit]()
    val metadataSource: Source[akka.http.scaladsl.model.ws.Message, _]                                              = Source
      .tick(1.seconds, 10.seconds, ())
      .map { _ =>
        Try {
          if (exportMeta) {
            val allRoutes = env.proxyState.allRoutes()
            val routes    = exportTag.map(t => allRoutes.filter(_.tags.contains(t))).getOrElse(allRoutes)
            akka.http.scaladsl.model.ws.BinaryMessage.Strict(
              Json
                .obj(
                  "tunnel_id" -> tunnelId,
                  "type"      -> "tunnel_meta",
                  "name"      -> name,
                  "routes"    -> JsArray(
                    routes.map(r => Json.obj("name" -> r.name, "id" -> r.id, "frontend" -> r.frontend.tunnelUrl))
                  )
                )
                .stringify
                .byteString
            )

          } else {
            akka.http.scaladsl.model.ws.BinaryMessage
              .Strict(Json.obj("tunnel_id" -> tunnelId, "type" -> "ping").stringify.byteString)
          }
        }
      }
      .collect { case Success(value) =>
        value
      }
    val pingSource: Source[akka.http.scaladsl.model.ws.Message, _]                                                  = Source
      .tick(10.seconds, 10.seconds, ())
      .map(_ => BinaryMessage(Json.obj("tunnel_id" -> tunnelId, "type" -> "ping").stringify.byteString))
      .map { pm =>
        akka.http.scaladsl.model.ws.BinaryMessage.Strict(pm.data)
      }
    val queueRef                                                                                                    = new AtomicReference[SourceQueueWithComplete[akka.http.scaladsl.model.ws.Message]]()
    val pushSource
        : Source[akka.http.scaladsl.model.ws.Message, SourceQueueWithComplete[akka.http.scaladsl.model.ws.Message]] =
      Source.queue[akka.http.scaladsl.model.ws.Message](512, OverflowStrategy.dropHead).mapMaterializedValue { q =>
        queueRef.set(q)
        q
      }
    val source: Source[akka.http.scaladsl.model.ws.Message, _]                                                      = pushSource.merge(pingSource.merge(metadataSource))

    def handleRequest(rawRequest: ByteString): Unit = Try {
      val obj = Json.parse(rawRequest.toArray)
      val typ = obj.select("type").asString
      if (typ == "request") {
        val requestId: String = obj.select("request_id").asString
        val reqId             = Try(requestId.split("_").last.toInt).getOrElse(counter.incrementAndGet())
        if (logger.isDebugEnabled) logger.debug(s"got request from server on tunnel '${tunnelId}' - ${requestId}")
        // println(s"handling request ${requestId}")
        val url               = obj.select("url").asString
        val addr              = obj.select("addr").asString
        val secured           = obj.select("secured").asOpt[Boolean].getOrElse(false)
        val hasBody           = obj.select("hasBody").asOpt[Boolean].getOrElse(false)
        val version           = obj.select("version").asString
        val method            = obj.select("method").asString
        val headers           = obj.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty)
        val cookies           = obj
          .select("cookies")
          .asOpt[Seq[JsObject]]
          .map { arr =>
            arr.map { c =>
              Cookie(
                name = c.select("name").asString,
                value = c.select("value").asString,
                maxAge = c.select("maxAge").asOpt[Int],
                path = c.select("path").asString,
                domain = c.select("domain").asOpt[String],
                secure = c.select("secure").asOpt[Boolean].getOrElse(false),
                httpOnly = c.select("httpOnly").asOpt[Boolean].getOrElse(false),
                sameSite = None
              )
            }
          }
          .getOrElse(Seq.empty)
        val certs             = obj.select("client_cert_chain").asOpt[Seq[String]].map(_.map(_.trim.toCertificate))
        val body              = obj.select("body").asOpt[String].map(b => ByteString(b).decodeBase64) match {
          case None    => Source.empty[ByteString]
          case Some(b) => Source.single(b)
        }
        val engine            = env.scriptManager.getAnyScript[RequestHandler](s"cp:${classOf[ProxyEngine].getName}").right.get
        val request           = new TunnelRequest(
          requestId = reqId,
          version = version,
          method = method,
          body = body,
          _remoteUriStr = url,
          _remoteAddr = addr,
          _remoteSecured = secured,
          _remoteHasBody = hasBody,
          _headers = headers,
          cookies = Cookies(cookies),
          certs = certs
        )
        engine.handle(request, _ => Results.InternalServerError("bad default routing").vfuture).flatMap { result =>
          TunnelActor.resultToJson(result, requestId).map { res =>
            if (logger.isDebugEnabled)
              logger.debug(s"sending response back to server on tunnel '${tunnelId}' - ${requestId}")
            Option(queueRef.get()).foreach(queue =>
              queue
                .offer(akka.http.scaladsl.model.ws.BinaryMessage.Streamed(res.stringify.byteString.chunks(16 * 1024)))
            )
          }
        }
      }
    } match {
      case Failure(exception) => if (logger.isDebugEnabled) logger.error("error while handling request", exception)
      case Success(_)         => ()
    }

    val secured = url.startsWith("https")
    val userpwd = s"${clientId}:${clientSecret}".base64
    val uri     = Uri(url + s"/api/tunnels/register?tunnel_id=${tunnelId}").copy(scheme = if (secured) "wss" else "ws")
    val target  = Target(
      host = uri.authority.host.toString(),
      scheme = uri.scheme,
      protocol = otoroshi.models.HttpProtocols.HTTP_1_1,
      predicate = TargetPredicate.AlwaysMatch,
      ipAddress = ipAddress,
      mtlsConfig = tls.getOrElse(MtlsConfig())
    )
    val port    = target.thePort
    val start   = System.currentTimeMillis()
    val (fu, _) = env.Ws.ws(
      request = WebSocketRequest(
        uri = uri,
        extraHeaders = List(
          RawHeader("Host", hostname),
          RawHeader("Authorization", s"Basic ${userpwd}"),
          RawHeader(env.Headers.OtoroshiClientId, clientId),
          RawHeader(env.Headers.OtoroshiClientSecret, clientSecret)
        ),
        subprotocols = immutable.Seq.empty[String]
      ),
      targetOpt = target.some,
      clientFlow = Flow
        .fromSinkAndSource(
          Sink.foreach[akka.http.scaladsl.model.ws.Message] {
            case akka.http.scaladsl.model.ws.TextMessage.Strict(data)       =>
              if (logger.isDebugEnabled) logger.debug(s"invalid text message: '${data}'")
            case akka.http.scaladsl.model.ws.TextMessage.Streamed(source)   =>
              source
                .runFold("")(_ + _)
                .map(b => if (logger.isDebugEnabled) logger.debug(s"invalid text message: '${b}'"))
            case akka.http.scaladsl.model.ws.BinaryMessage.Strict(data)     => handleRequest(data)
            case akka.http.scaladsl.model.ws.BinaryMessage.Streamed(source) =>
              source.runFold(ByteString.empty)(_ ++ _).map(b => handleRequest(b))
          },
          source
        )
        .alsoTo(Sink.onComplete {
          case Success(e) =>
            val shouldIncreaseWaiting = (System.currentTimeMillis() - start) < 10000
            val newWaiting            = if (shouldIncreaseWaiting) waiting * 2 else initialWaiting
            logger.info(
              s"tunnel '${tunnelId}' disconnected, launching reconnection in ${newWaiting.milliseconds.toHumanReadable} ..."
            )
            timeout(waiting.millis).andThen { case _ =>
              connect(
                tunnelId,
                name,
                url,
                hostname,
                clientId,
                clientSecret,
                ipAddress,
                tls,
                proxy,
                exportMeta,
                exportTag,
                initialWaiting,
                newWaiting
              )
            }
            promise.trySuccess(())
          case Failure(e) =>
            val shouldIncreaseWaiting = (System.currentTimeMillis() - start) < 10000
            val newWaiting            = if (shouldIncreaseWaiting) waiting * 2 else initialWaiting
            logger.error(
              s"tunnel '${tunnelId}' disconnected, launching reconnection in ${newWaiting.milliseconds.toHumanReadable} ...",
              e
            )
            timeout(waiting.millis).andThen { case _ =>
              connect(
                tunnelId,
                name,
                url,
                hostname,
                clientId,
                clientSecret,
                ipAddress,
                tls,
                proxy,
                exportMeta,
                exportTag,
                initialWaiting,
                newWaiting
              )
            }
            promise.trySuccess(())
        }),
      customizer = m => {
        (ipAddress, proxy) match {
          case (_, Some(proxySettings)) => {
            val proxyAddress = InetSocketAddress.createUnresolved(proxySettings.host, proxySettings.port)
            val transport    = (proxySettings.principal, proxySettings.password) match {
              case (Some(principal), Some(password)) =>
                ClientTransport.httpsProxy(
                  proxyAddress,
                  akka.http.scaladsl.model.headers.BasicHttpCredentials(principal, password)
                )
              case _                                 => ClientTransport.httpsProxy(proxyAddress)
            }
            m.withTransport(transport)
          }
          case (Some(addr), _)          =>
            m.withTransport(ManualResolveTransport.resolveTo(InetSocketAddress.createUnresolved(addr, port)))
          case _                        => m
        }
      }
    )
    fu.andThen {
      case Success(ValidUpgrade(response, chosenSubprotocol)) =>
        if (logger.isDebugEnabled) logger.debug(s"upgrade successful and valid: ${response} - ${chosenSubprotocol}")
      case Success(InvalidUpgradeResponse(response, cause))   =>
        if (logger.isDebugEnabled) logger.error(s"upgrade successful but invalid: ${response} - ${cause}")
      case Failure(ex)                                        => logger.error(s"upgrade failure", ex)
    }
    promise.future
  }

  private def timeout(duration: FiniteDuration): Future[Unit] = {
    val promise = Promise[Unit]
    env.otoroshiActorSystem.scheduler.scheduleOnce(duration) {
      promise.trySuccess(())
    }(env.otoroshiExecutionContext)
    promise.future
  }
}

class TunnelManager(env: Env) {

  private val tunnels        = Scaffeine().maximumSize(10000).expireAfterWrite(10.minute).build[String, Seq[Tunnel]]()
  private val counter        = new AtomicInteger(0)
  private val tunnelsEnabled = env.configuration.getOptional[Boolean]("otoroshi.tunnels.enabled").getOrElse(false)
  private val workerWs       = env.configuration.getOptional[Boolean]("otoroshi.tunnels.worker-ws").getOrElse(true)
  private val logger         = Logger(s"otoroshi-tunnel-manager")

  private implicit val ec = env.otoroshiExecutionContext
  private implicit val ev = env

  def currentTunnels: Set[String] = tunnels.asMap().keySet.toSet

  private def whenEnabled(f: => Unit): Unit = {
    if (tunnelsEnabled) {
      f
    }
  }

  def start(): TunnelManager = this

  def closeTunnel(tunnelId: String, instanceId: String): Unit = whenEnabled {
    // TODO: close the right tunnel
    tunnels.getIfPresent(tunnelId) match {
      case None                                                        => ()
      case Some(instances) if instances.isEmpty || instances.size == 1 => tunnels.invalidate(tunnelId)
      case Some(instances)                                             => tunnels.put(tunnelId, instances.filterNot(_.instanceId == instanceId))
    }
    if (env.clusterConfig.mode.isLeader) {
      env.clusterLeaderAgent.renewMemberShip()
    }
  }

  def registerTunnel(tunnelId: String, tunnel: Tunnel): Unit = whenEnabled {
    tunnels.getIfPresent(tunnelId) match {
      case None     => tunnels.put(tunnelId, Seq(tunnel))
      case Some(ts) => tunnels.put(tunnelId, ts :+ tunnel)
    }
    if (env.clusterConfig.mode.isLeader) {
      env.clusterLeaderAgent.renewMemberShip()
    }
  }

  def tunnelHeartBeat(tunnelId: String): Unit = whenEnabled {
    tunnels.getIfPresent(tunnelId).foreach(ts => tunnels.put(tunnelId, ts)) // yeah, i know :(
  }

  def sendLocalRequestRaw(tunnelId: String, request: JsValue): Future[Result] = Try {
    if (tunnelsEnabled) {
      tunnels.getIfPresent(tunnelId) match {
        case None          => Results.NotFound(Json.obj("error" -> s"missing local tunnel '${tunnelId}'")).vfuture
        case Some(tunnels) => {
          val index  = counter.incrementAndGet() % (if (tunnels.nonEmpty) tunnels.size else 1)
          val tunnel = tunnels.apply(index)
          tunnel.actor match {
            case None         =>
              Results.NotFound(Json.obj("error" -> s"missing tunnel connection for tunnel '${tunnelId}'")).vfuture
            case Some(tunnel) => tunnel.sendRequestRaw(request)
          }
        }
      }
    } else {
      Results.InternalServerError(Json.obj("error" -> s"tunnels not enabled")).vfuture
    }
  } match {
    case Failure(exception) =>
      logger.error("error while sendLocalRequestRaw", exception)
      Results.InternalServerError(Json.obj("error" -> s"tunnel error")).vfuture
    case Success(f)         => f
  }

  def sendRequest(tunnelId: String, request: NgPluginHttpRequest, addr: String, secured: Boolean): Future[Result] = {
    if (tunnelsEnabled) {
      tunnels.getIfPresent(tunnelId) match {
        case None          => {
          env.datastores.clusterStateDataStore.getMembers().flatMap { members =>
            val membersWithTunnel = members.filter(_.tunnels.contains(tunnelId))
            if (membersWithTunnel.isEmpty) {
              Results.NotFound(Json.obj("error" -> s"missing tunnel '${tunnelId}'")).vfuture
            } else {
              val index  = counter.incrementAndGet() % (if (membersWithTunnel.nonEmpty) membersWithTunnel.size else 1)
              val member = membersWithTunnel.apply(index)
              // println(s"choosing between ${membersWithTunnel.size} possible members. selected member ${member.name} located at ${member.location}")
              forwardRequest(tunnelId, request, addr, secured, member)
            }
          }
        }
        case Some(tunnels) => {
          if (tunnels.isEmpty) {
            Results.NotFound(Json.obj("error" -> s"missing tunnel connection for tunnel '${tunnelId}'")).vfuture
          } else {
            val index  = counter.incrementAndGet() % (if (tunnels.nonEmpty) tunnels.size else 1)
            val tunnel = tunnels.apply(index)
            tunnel.actor match {
              case None         =>
                Results.NotFound(Json.obj("error" -> s"missing tunnel connection for tunnel '${tunnelId}'")).vfuture
              case Some(tunnel) => tunnel.sendRequest(request, addr, secured)
            }
          }
        }
      }
    } else {
      Results.InternalServerError(Json.obj("error" -> s"tunnels not enabled")).vfuture
    }
  }

  private def forwardRequest(
      tunnelId: String,
      request: NgPluginHttpRequest,
      addr: String,
      secured: Boolean,
      member: MemberView
  ): Future[Result] = {
    if (workerWs) {
      forwardRequestWs(tunnelId, request, addr, secured, member)
    } else {
      val requestId: String = TunnelActor.genRequestId(env) // legit !
      if (logger.isDebugEnabled) logger.debug(s"forwarding request for '${tunnelId}' - ${requestId} to ${member.name}")
      val requestJson       = TunnelActor.requestToJson(request, addr, secured, requestId).stringify.byteString
      val url               = Uri(env.clusterConfig.leader.urls.head)
      val ipAddress         = member.location
      val service           = env.proxyState.service(env.backOfficeServiceId).get
      env.Ws
        .akkaUrlWithTarget(
          s"${url.toString()}/api/tunnels/${tunnelId}/relay",
          Target(
            host = url.authority.toString(),
            scheme = url.scheme,
            ipAddress = ipAddress.some
          )
        )
        .withMethod("POST")
        .withRequestTimeout(service.clientConfig.globalTimeout.milliseconds)
        .withHttpHeaders(
          env.Headers.OtoroshiClientId     -> env.clusterConfig.leader.clientId,
          env.Headers.OtoroshiClientSecret -> env.clusterConfig.leader.clientSecret,
          "Content-Type"                   -> "application/json",
          "Host"                           -> env.clusterConfig.leader.host,
          "Accept"                         -> "application/json"
        )
        .withBody(requestJson)
        .execute()
        .map { resp =>
          if (resp.status == 200) {
            TunnelActor.responseToResult(resp.json)
          } else {
            logger.error(
              s"error while forwarding tunnel request to '${tunnelId}' - ${resp.status} - ${resp.headers} - ${resp.body}"
            )
            Results.InternalServerError(Json.obj("error" -> s"error while handling request"))
          }
        }
    }
  }

  private val leaderConnections = new LegitTrieMap[String, LeaderConnection]()

  private def forwardRequestWs(
      tunnelId: String,
      request: NgPluginHttpRequest,
      addr: String,
      secured: Boolean,
      member: MemberView
  ): Future[Result] = {
    implicit val ec  = env.otoroshiExecutionContext
    implicit val mat = env.otoroshiMaterializer
    implicit val ev  = env

    val requestId: String = TunnelActor.genRequestId(env) // legit
    val requestJson       = TunnelActor.requestToJson(request, addr, secured, requestId).stringify.byteString

    leaderConnections.get(member.location) match {
      case Some(conn) =>
        if (logger.isDebugEnabled) logger.debug("sending ws")
        conn.push(requestJson, requestId)
      case None       =>
        if (logger.isDebugEnabled) logger.debug("connecting ws")
        new LeaderConnection(
          tunnelId,
          member,
          env,
          c => leaderConnections.put(member.location, c),
          c => leaderConnections.remove(c.location)
        )
          .connect(0L)
          .push(requestJson, requestId)
    }
  }
}

class LeaderConnection(
    tunnelId: String,
    member: MemberView,
    env: Env,
    register: LeaderConnection => Unit,
    unregister: LeaderConnection => Unit
) {

  private val logger           = Logger("otoroshi-tunnel-leader-connection")
  private implicit val ec      = env.otoroshiExecutionContext
  private implicit val mat     = env.otoroshiMaterializer
  private implicit val factory = env.otoroshiActorSystem

  private val useInternalPorts =
    env.configuration.getOptional[Boolean]("otoroshi.tunnels.worker-use-internal-ports").getOrElse(false)
  private val useLoadbalancing =
    env.configuration.getOptional[Boolean]("otoroshi.tunnels.worker-use-loadbalancing").getOrElse(false)

  def location: String = member.location

  private val ref                                                                                                 = new AtomicLong(0L)
  private val pingSource: Source[akka.http.scaladsl.model.ws.Message, _]                                          = Source
    .tick(10.seconds, 10.seconds, ())
    .map(_ => BinaryMessage(Json.obj("tunnel_id" -> tunnelId, "type" -> "ping").stringify.byteString))
    .map { pm =>
      akka.http.scaladsl.model.ws.BinaryMessage.Strict(pm.data)
    }
  private val queueRef                                                                                            = new AtomicReference[SourceQueueWithComplete[akka.http.scaladsl.model.ws.Message]]()
  private val pushSource
      : Source[akka.http.scaladsl.model.ws.Message, SourceQueueWithComplete[akka.http.scaladsl.model.ws.Message]] =
    Source.queue[akka.http.scaladsl.model.ws.Message](512, OverflowStrategy.dropHead).mapMaterializedValue { q =>
      queueRef.set(q)
      q
    }
  private val source: Source[akka.http.scaladsl.model.ws.Message, _]                                              = pushSource.merge(pingSource)
  private val awaitingResponse                                                                                    = new LegitTrieMap[String, Promise[Result]]()

  def close(): Unit = {
    unregister(this)
    awaitingResponse.values.map(p => p.trySuccess(Results.InternalServerError(Json.obj("error" -> "tunnel closed !"))))
    awaitingResponse.clear()
  }

  def push(req: ByteString, requestId: String): Future[Result] = {
    // println(s"pushing to ${member.name} - ${member.location}")
    if (logger.isDebugEnabled)
      logger.debug(
        s"pushing request for '${requestId}' - ${(System.currentTimeMillis() - ref.get()).milliseconds.toHumanReadable}"
      )
    val promise = Promise.apply[Result]()
    awaitingResponse.put(requestId, promise)
    Option(queueRef.get()).foreach(_.offer(akka.http.scaladsl.model.ws.BinaryMessage.Strict(req)))
    promise.future
  }

  def connect(waiting: Long): LeaderConnection = {
    if (useLoadbalancing) {
      connectLoadBalanced(waiting)
    } else {
      connectDirect(waiting)
    }
  }

  def connectDirect(waiting: Long): LeaderConnection = {
    val url       = env.clusterConfig.leader.urls.head
    val ipAddress = member.location
    val secured   = url.startsWith("https")
    val port      =
      if (useInternalPorts) (if (secured) member.internalHttpsPort else member.internalHttpPort)
      else (if (secured) member.httpsPort
            else member.httpPort)
    val userpwd   = s"${env.clusterConfig.leader.clientId}:${env.clusterConfig.leader.clientSecret}".base64
    val uriRaw    = Uri(url + s"/api/tunnels/$tunnelId/relay").copy(scheme = if (secured) "wss" else "ws")
    val uri       = uriRaw.copy(authority = uriRaw.authority.copy(port = port))
    env.Ws
      .ws(
        request = WebSocketRequest(
          uri = uri,
          extraHeaders = List(
            RawHeader("Host", env.clusterConfig.leader.host),
            RawHeader("Authorization", s"Basic ${userpwd}"),
            RawHeader(env.Headers.OtoroshiClientId, env.clusterConfig.leader.clientId),
            RawHeader(env.Headers.OtoroshiClientSecret, env.clusterConfig.leader.clientSecret)
          ),
          subprotocols = immutable.Seq.empty[String]
        ),
        targetOpt = Target(
          host = uri.authority.host.toString(),
          scheme = uri.scheme,
          ipAddress = ipAddress.some,
          mtlsConfig = env.clusterConfig.mtlsConfig
        ).some,
        clientFlow = Flow
          .fromSinkAndSource(
            Sink.foreach[akka.http.scaladsl.model.ws.Message] {
              case akka.http.scaladsl.model.ws.BinaryMessage.Strict(data)     => handleResponse(data)
              case akka.http.scaladsl.model.ws.BinaryMessage.Streamed(source) =>
                source.runFold(ByteString.empty)(_ ++ _).map(b => handleResponse(b))
              case _                                                          =>
            },
            source
          )
          .alsoTo(Sink.onComplete {
            case Success(e) =>
              logger.info(
                s"tunnel relay ws '${tunnelId}' disconnected, launching reconnection in ${waiting.milliseconds.toHumanReadable} ..."
              )
              close()
              timeout(waiting.millis).andThen { case _ =>
                new LeaderConnection(tunnelId, member, env, register, unregister).connect(waiting)
              }
            case Failure(e) =>
              logger.error(
                s"tunnel relay ws '${tunnelId}' disconnected, launching reconnection in ${(waiting * 2).milliseconds.toHumanReadable} ...",
                e
              )
              close()
              timeout(waiting.millis).andThen { case _ =>
                new LeaderConnection(tunnelId, member, env, register, unregister).connect(waiting * 2)
              }
          }),
        customizer =
          m => m.withTransport(ManualResolveTransport.resolveTo(InetSocketAddress.createUnresolved(ipAddress, port)))
      )
      ._1
      .map { _ =>
        ref.set(System.currentTimeMillis())
        register(this)
      }
    this
  }

  def connectLoadBalanced(waiting: Long): LeaderConnection = {
    val url     = env.clusterConfig.leader.urls.head
    val secured = url.startsWith("https")
    val port    = if (secured) member.httpsPort else member.httpPort
    val userpwd = s"${env.clusterConfig.leader.clientId}:${env.clusterConfig.leader.clientSecret}".base64
    val uriRaw  = Uri(url + s"/api/tunnels/$tunnelId/relay?mid=${member.id}").copy(scheme = if (secured) "wss" else "ws")
    val uri     = uriRaw.copy(authority = uriRaw.authority.copy(port = port))
    logger.debug(s"trying to find node at: '${uri.toString()}' from node '${ClusterConfig.clusterNodeId}'")
    logger.debug(s"from: '${ClusterConfig.clusterNodeId}' to '${member.id}'")
    env.Ws
      .ws(
        request = WebSocketRequest(
          uri = uri,
          extraHeaders = List(
            RawHeader("Host", env.clusterConfig.leader.host),
            RawHeader("Authorization", s"Basic ${userpwd}"),
            RawHeader(env.Headers.OtoroshiClientId, env.clusterConfig.leader.clientId),
            RawHeader(env.Headers.OtoroshiClientSecret, env.clusterConfig.leader.clientSecret)
          ),
          subprotocols = immutable.Seq.empty[String]
        ),
        targetOpt = Target(
          host = uri.authority.host.toString(),
          scheme = uri.scheme,
          mtlsConfig = env.clusterConfig.mtlsConfig
        ).some,
        clientFlow = Flow
          .fromSinkAndSource(
            Sink.foreach[akka.http.scaladsl.model.ws.Message] {
              case akka.http.scaladsl.model.ws.BinaryMessage.Strict(data)     => handleResponse(data)
              case akka.http.scaladsl.model.ws.BinaryMessage.Streamed(source) =>
                source.runFold(ByteString.empty)(_ ++ _).map(b => handleResponse(b))
              case _                                                          =>
            },
            source
          )
          .alsoTo(Sink.onComplete {
            case Success(e) =>
              logger.info(
                s"tunnel relay ws lb '${tunnelId}' disconnected, launching reconnection in ${waiting.milliseconds.toHumanReadable} ..."
              )
              close()
              timeout(waiting.millis).andThen { case _ =>
                new LeaderConnection(tunnelId, member, env, register, unregister).connect(waiting)
              }
            case Failure(e) =>
              logger.error(
                s"tunnel relay ws lb '${tunnelId}' disconnected, launching reconnection in ${(waiting * 2).milliseconds.toHumanReadable} ...",
                e
              )
              close()
              timeout(waiting.millis).andThen { case _ =>
                new LeaderConnection(tunnelId, member, env, register, unregister).connect(waiting * 2)
              }
          }),
        customizer = identity
      )
      ._1
      .map { resp =>
        resp.response.status.intValue() match {
          case 200 => {
            logger.debug("lb connection accepted 200")
            ref.set(System.currentTimeMillis())
            register(this)
          }
          case 101 => {
            logger.debug("lb connection accepted 101")
            ref.set(System.currentTimeMillis())
            register(this)
          }
          case 417 =>
            // retry to find the right node
            logger.debug("lb retry ws connection to find the right leader")
            connectLoadBalanced(waiting)
          case v   =>
            logger.error(s"lb received unknown status: $v")
        }
      }
    this
  }

  private def handleResponse(data: ByteString): Unit = {
    val json    = Json.parse(data.toArray)
    val msgKind = json.select("type").asString
    if (msgKind == "response") {
      val requestId = json.select("request_id").asString
      if (logger.isDebugEnabled) logger.debug(s"handling response for '${requestId}'")
      val result    = TunnelActor.responseToResult(json)
      awaitingResponse.get(requestId).foreach(_.trySuccess(result))
    }
  }

  private def timeout(duration: FiniteDuration): Future[Unit] = {
    val promise = Promise[Unit]
    env.otoroshiActorSystem.scheduler.scheduleOnce(duration) {
      promise.trySuccess(())
    }(env.otoroshiExecutionContext)
    promise.future
  }
}

class TunnelController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
    extends AbstractController(cc) {

  implicit lazy val ec      = env.otoroshiExecutionContext
  implicit lazy val mat     = env.otoroshiMaterializer
  implicit lazy val factory = env.otoroshiActorSystem

  private val tunnelsEnabled = env.configuration.getOptional[Boolean]("otoroshi.tunnels.enabled").getOrElse(false)

  private def validateRequest(request: RequestHeader): Option[ApiKey] = {
    env.backOfficeApiKey.some // TODO: actual validation
  }

  def infos = ApiAction {
    Ok(
      Json.obj(
        "domain"             -> env.domain,
        "scheme"             -> env.exposedRootScheme,
        "exposed_port_http"  -> env.exposedHttpPortInt,
        "exposed_port_https" -> env.exposedHttpsPortInt
      )
    )
  }

  def tunnelInfos = ApiAction.async { ctx =>
    for {
      members <- env.datastores.clusterStateDataStore.getMembers()
      tunnels <- env.datastores.rawDataStore.allMatching(s"${env.storageRoot}:tunnels:*:meta")
    } yield {
      var membersByTunnelId = Map.empty[String, Seq[MemberView]]
      members.foreach { member =>
        member.tunnels.foreach { tunnelId =>
          val nm = (membersByTunnelId.getOrElse(tunnelId, Seq.empty) :+ member).distinct
          membersByTunnelId = membersByTunnelId.put(tunnelId, nm)
        }
      }
      val arr               = JsArray(tunnels.map { tunnel =>
        val tunnelJson = tunnel.utf8String.parseJson.asObject
        val tunnelId   = tunnelJson.select("tunnel_id").asString
        val nodes      = JsArray(
          membersByTunnelId
            .getOrElse(tunnelId, Seq.empty)
            .groupBy(mv => s"${mv.location}:${mv.httpPort}:${mv.httpsPort}")
            .toSeq
            .map { mvs =>
              val mv = mvs._2.last
              Json.obj(
                "id"         -> mv.id,
                "name"       -> mv.name,
                "location"   -> mv.location,
                "http_port"  -> mv.httpPort,
                "https_port" -> mv.httpsPort,
                "last_seen"  -> mv.lastSeen.toString(),
                "type"       -> "Leader"
              )
            }
        )
        tunnelJson ++ Json.obj(
          "nodes" -> nodes
        )
      })
      Ok(arr)
    }
  }

  def tunnelRelay(tunnelId: String) = ApiAction.async(parse.json) { ctx =>
    if (tunnelsEnabled) {
      val requestId =
        ctx.request.body.select("request_id").asOpt[String].getOrElse(TunnelActor.genRequestId(env)) // legit
      env.tunnelManager.sendLocalRequestRaw(tunnelId, ctx.request.body).flatMap { res =>
        TunnelActor.resultToJson(res, requestId).map(v => Ok(v))
      }
    } else {
      NotFound(Json.obj("error" -> "resource not found")).vfuture
    }
  }

  def tunnelRelayWs(tunnelId: String) = WebSocket.acceptOrResult[Message, Message] { request =>
    if (tunnelsEnabled) {
      validateRequest(request) match {
        case None    => Left(Results.Unauthorized(Json.obj("error" -> "unauthorized"))).vfuture
        case Some(_) => {
          request.getQueryString("mid") match {
            case Some(mid) if mid != ClusterConfig.clusterNodeId => {
              Left(Results.ExpectationFailed(Json.obj("error" -> "bad node"))).vfuture
            }
            case _                                               => {
              Right(ActorFlow.actorRef { out =>
                TunnelRelayActor.props(out, tunnelId, env)
              }).vfuture
            }
          }
        }
      }
    } else {
      Left(Results.NotFound(Json.obj("error" -> "resource not found"))).vfuture
    }
  }

  def tunnelEndpoint() = WebSocket.acceptOrResult[Message, Message] { request =>
    if (tunnelsEnabled) {
      val tunnelId: String         = request.getQueryString("tunnel_id").get
      val reversePingPong: Boolean = request.getQueryString("pong_ping").getOrElse("false") == "true"
      validateRequest(request) match {
        case None    => Left(Results.Unauthorized(Json.obj("error" -> "unauthorized"))).vfuture
        case Some(_) =>
          val instanceId = IdGenerator.uuid
          val holder     = new Tunnel(instanceId)
          val flow       = ActorFlow.actorRef { out =>
            TunnelActor.props(out, tunnelId, instanceId, request, reversePingPong, env)(r => holder.setActor(r))
          }
          holder.setFlow(flow)
          env.tunnelManager.registerTunnel(tunnelId, holder)
          flow.rightf
      }
    } else {
      Left(Results.NotFound(Json.obj("error" -> "resource not found"))).vfuture
    }
  }
}

class Tunnel(val instanceId: String) {
  private var _actor: TunnelActor                 = _
  private var _flow: Flow[Message, Message, _]    = _
  def setActor(r: TunnelActor): Unit              = _actor = r
  def setFlow(r: Flow[Message, Message, _]): Unit = _flow = r
  def actor: Option[TunnelActor]                  = Option(_actor)
  def flow: Option[Flow[Message, Message, _]]     = Option(_flow)
}

object TunnelRelayActor {
  def props(out: ActorRef, tunnelId: String, env: Env): Props = {
    Props(new TunnelRelayActor(out, tunnelId, env))
  }
}

class TunnelRelayActor(out: ActorRef, tunnelId: String, env: Env) extends Actor {

  private val logger       = Logger("otoroshi-tunnel-relay-actor")
  private implicit val ec  = env.otoroshiExecutionContext
  private implicit val mat = env.otoroshiMaterializer

  def handleRequest(data: ByteString): Unit = Try {
    val request = Json.parse(data.toArray)
    val reqType = request.select("type").asString
    reqType match {
      case "ping"    => {
        if (logger.isDebugEnabled) logger.debug(s"ping message from client: ${data.utf8String}")
        out ! BinaryMessage(Json.obj("tunnel_id" -> tunnelId, "type" -> "pong").stringify.byteString)
      }
      case "request" => {
        val requestId = request.select("request_id").asOpt[String].getOrElse(TunnelActor.genRequestId(env)) // legit
        // println(s"handling worker request ${requestId}")
        env.tunnelManager.sendLocalRequestRaw(tunnelId, request).map { res =>
          TunnelActor.resultToJson(res, requestId).map(v => out ! BinaryMessage(v.stringify.byteString))
        }
      }
      case _         =>
    }
  } match {
    case Failure(exception) => logger.error("error while handling request", exception)
    case Success(value)     => ()
  }

  def receive = {
    case BinaryMessage(data) => handleRequest(data)
    case _                   =>
  }
}

object TunnelActor {

  private val counter = new AtomicLong(0L)

  def genRequestId(env: Env): String = {
    val otoroshiId    = env.datastores.globalConfigDataStore.latest()(env.otoroshiExecutionContext, env).otoroshiId
    val clusterNodeId = env.clusterConfig.id
    val requestId     = IdGenerator.uuid
    s"${otoroshiId}-${clusterNodeId}-${requestId}-${counter.incrementAndGet()}"
  }

  def props(
      out: ActorRef,
      tunnelId: String,
      instanceId: String,
      req: RequestHeader,
      reversePingPong: Boolean,
      env: Env
  )(f: TunnelActor => Unit): Props = {
    Props {
      val actor = new TunnelActor(out, tunnelId, instanceId, req, reversePingPong, env)
      f(actor)
      actor
    }
  }

  def resultToJson(result: Result, requestId: String)(implicit
      ec: ExecutionContext,
      mat: Materializer
  ): Future[JsValue] = {
    val ct      = result.body.contentType
    val cl      = result.body.contentLength
    val cookies = result.header.headers
      .getIgnoreCase("Cookie")
      .map { c =>
        Cookies.decodeCookieHeader(c)
      }
      .getOrElse(Seq.empty)
    result.body.dataStream.runFold(ByteString.empty)(_ ++ _).map { br =>
      Json.obj(
        "request_id" -> requestId,
        "type"       -> "response",
        "status"     -> result.header.status,
        "headers"    -> (result.header.headers ++ Map
          .empty[String, String]
          .applyOnWithOpt(ct) { case (headers, ctype) =>
            headers ++ Map("Content-Type" -> ctype)
          }
          .applyOnWithOpt(cl) { case (headers, clength) =>
            headers ++ Map("Content-Length" -> clength.toString)
          }),
        "cookies"    -> cookies.map(_.json),
        "body"       -> br.encodeBase64.utf8String
      )
    }
  }

  def requestToJson(request: NgPluginHttpRequest, addr: String, secured: Boolean, requestId: String): JsValue = {
    (request.json.asObject ++ Json.obj(
      "path"       -> request.relativeUri,
      "request_id" -> requestId,
      "type"       -> "request",
      "addr"       -> addr,
      "secured"    -> secured.toString,
      "hasBody"    -> request.hasBody,
      "version"    -> request.version
    ))
  }

  def responseToResult(response: JsValue): Result = {
    val status                             = response.select("status").asInt
    val headers                            = response.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty)
    val headersList: Seq[(String, String)] = headers.toSeq
    val cookies: Seq[Cookie]               = response
      .select("cookies")
      .asOpt[Seq[JsObject]]
      .map { arr =>
        arr.map { c =>
          Cookie(
            name = c.select("name").asString,
            value = c.select("value").asString,
            maxAge = c.select("maxAge").asOpt[Int],
            path = c.select("path").asString,
            domain = c.select("domain").asOpt[String],
            secure = c.select("secure").asOpt[Boolean].getOrElse(false),
            httpOnly = c.select("httpOnly").asOpt[Boolean].getOrElse(false),
            sameSite = None
          )
        }
      }
      .getOrElse(Seq.empty)
    val body                               = response.select("body").asOpt[String].map(b => ByteString(b).decodeBase64) match {
      case None    => Source.empty[ByteString]
      case Some(b) => Source.single(b)
    }
    val contentType                        = headers.getIgnoreCase("Content-Type")
    val contentLength                      = headers.getIgnoreCase("Content-Length").map(_.toLong)
    Results
      .Status(status)
      .sendEntity(
        HttpEntity.Streamed(
          data = body,
          contentType = contentType,
          contentLength = contentLength
        )
      )
      .withHeaders(headersList: _*)
      .withCookies(cookies: _*)
  }
}

class TunnelActor(
    out: ActorRef,
    tunnelId: String,
    instanceId: String,
    req: RequestHeader,
    reversePingPong: Boolean,
    env: Env
) extends Actor {

  private val logger           = Logger(s"otoroshi-tunnel-actor")
  // private val counter = new AtomicLong(0L)
  private val awaitingResponse = new LegitTrieMap[String, Promise[Result]]()

  private def closeTunnel(): Unit = {
    awaitingResponse.values.map(p => p.trySuccess(Results.InternalServerError(Json.obj("error" -> "tunnel closed !"))))
    awaitingResponse.clear()
    env.tunnelManager.closeTunnel(tunnelId, instanceId)
  }

  override def preStart(): Unit = {
    if (reversePingPong) {
      env.otoroshiScheduler.scheduleWithFixedDelay(10.seconds, 10.seconds)(() => {
        out ! BinaryMessage(Json.obj("tunnel_id" -> tunnelId, "type" -> "pong").stringify.byteString)
      })(env.otoroshiExecutionContext)
    }
  }

  override def postStop() = {
    closeTunnel()
  }

  def sendRequest(request: NgPluginHttpRequest, addr: String, secured: Boolean): Future[Result] = {
    val requestId: String = TunnelActor.genRequestId(env) // legit
    if (logger.isDebugEnabled)
      logger.debug(s"sending request to remote location through tunnel '${tunnelId}' - ${requestId}")
    val requestJson       = TunnelActor.requestToJson(request, addr, secured, requestId).stringify.byteString
    val promise           = Promise.apply[Result]()
    awaitingResponse.put(requestId, promise)
    out ! BinaryMessage(requestJson)
    promise.future
  }

  def sendRequestRaw(request: JsValue): Future[Result] = {
    val requestId: String = request.select("request_id").asOpt[String].getOrElse(TunnelActor.genRequestId(env)) // legit
    if (logger.isDebugEnabled)
      logger.debug(s"sending request to remote location through tunnel '${tunnelId}' - ${requestId}")
    val requestJson       = (request.asObject ++ Json.obj("request_id" -> requestId)).stringify.byteString
    val promise           = Promise.apply[Result]()
    awaitingResponse.put(requestId, promise)
    out ! BinaryMessage(requestJson)
    promise.future
  }

  private def handleResponse(data: ByteString): Unit = {
    val response = Json.parse(data.toArray)
    response.select("type").asString match {
      case "ping"        => {
        if (logger.isDebugEnabled) logger.debug(s"ping message from client: ${data.utf8String}")
        env.tunnelManager.tunnelHeartBeat(tunnelId)
        if (!reversePingPong) {
          out ! BinaryMessage(Json.obj("tunnel_id" -> tunnelId, "type" -> "pong").stringify.byteString)
        }
      }
      case "pong"        => {
        if (logger.isDebugEnabled) logger.debug(s"pong message from client: ${data.utf8String}")
        env.tunnelManager.tunnelHeartBeat(tunnelId)
        out ! BinaryMessage(Json.obj("tunnel_id" -> tunnelId, "type" -> "ping").stringify.byteString)
      }
      case "tunnel_meta" => {
        val updatedData = (data.utf8String.parseJson.asObject ++ Json.obj(
          "last_seen" -> DateTime.now().toString()
        ) - "type").stringify.byteString
        env.datastores.rawDataStore.set(
          s"${env.storageRoot}:tunnels:${tunnelId}:meta",
          updatedData,
          120.seconds.toMillis.some
        )(env.otoroshiExecutionContext, env)
      }
      case "response"    =>
        Try {
          env.tunnelManager.tunnelHeartBeat(tunnelId)
          val requestId: String = response.select("request_id").asString
          if (logger.isDebugEnabled) logger.debug(s"got response on tunnel '${tunnelId}' - ${requestId}")
          val result            = TunnelActor.responseToResult(response)
          awaitingResponse.get(requestId).foreach { tunnel =>
            if (logger.isDebugEnabled) logger.debug(s"found the promise for ${requestId}")
            tunnel.trySuccess(result)
            awaitingResponse.remove(requestId)
            ()
          }
        } match {
          case Failure(exception) => logger.error("error while handling response", exception)
          case Success(value)     => ()
        }
    }
  }

  def receive = {
    case TextMessage(text)            =>
      if (logger.isDebugEnabled) logger.debug(s"invalid message, '${text}'")
    case BinaryMessage(data)          =>
      handleResponse(data)
    case CloseMessage(status, reason) =>
      logger.info(s"closing tunnel '${tunnelId}' - ${status} - '${reason}'")
      closeTunnel()
    // self ! PoisonPill
    case PingMessage(data)            =>
      if (logger.isDebugEnabled) logger.debug(s"mping message from client: ${data.utf8String}")
      env.tunnelManager.tunnelHeartBeat(tunnelId)
      out ! PongMessage(Json.obj("tunnel_id" -> tunnelId).stringify.byteString)
    case PongMessage(data)            =>
      if (logger.isDebugEnabled) logger.debug(s"mpong message from client: ${data.utf8String}")
      env.tunnelManager.tunnelHeartBeat(tunnelId)
      out ! PingMessage(Json.obj("tunnel_id" -> tunnelId).stringify.byteString)
  }
}
