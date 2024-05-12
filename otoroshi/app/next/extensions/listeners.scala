package otoroshi.next.extensions

import otoroshi.api.{GenericResourceAccessApiWithState, Resource, ResourceVersion}
import otoroshi.env.Env
import otoroshi.models.{EntityLocation, EntityLocationSupport}
import otoroshi.netty._
import otoroshi.ssl.ClientAuth
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._

import java.util.UUID
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

object HttpListenerNames {
  val Classic = "classic"
  val Standard = "standard-listener"
}

case class HttpListenerConfig(
  enabled: Boolean,
  exclusive: Boolean = false,
  tls: Boolean = true,
  http2: Boolean = true,
  h2c: Boolean = false,
  http3: Boolean = false,
  port: Int,
  exposedPort: Int,
  host: String = "0.0.0.0",
  accessLog: Boolean = false,
  clientAuth: ClientAuth = ClientAuth.None,
) {
  def json: JsValue = HttpListenerConfig.format.writes(this)
}

object HttpListenerConfig {
  val default = HttpListenerConfig(
    enabled = true,
    port = 7890,
    exposedPort = 7890,
  )
  val format = new Format[HttpListenerConfig] {
    override def reads(json: JsValue): JsResult[HttpListenerConfig] = Try {
      HttpListenerConfig(
        enabled = json.select("enabled").asOpt[Boolean].getOrElse(false),
        exclusive = json.select("exclusive").asOpt[Boolean].getOrElse(false),
        tls = json.select("tls").asOpt[Boolean].getOrElse(true),
        http2 = json.select("http2").asOpt[Boolean].getOrElse(true),
        h2c = json.select("h2c").asOpt[Boolean].getOrElse(false),
        http3 = json.select("http3").asOpt[Boolean].getOrElse(false),
        port = json.select("port").asOpt[Int].getOrElse(7890),
        exposedPort = json.select("exposedPort").asOpt[Int].getOrElse(7890),
        host = json.select("host").asOpt[String].getOrElse("0.0.0.0"),
        accessLog = json.select("accessLog").asOpt[Boolean].getOrElse(false),
        clientAuth = json.select("clientAuth").asOpt[String].flatMap(ClientAuth.apply).getOrElse(ClientAuth.None),
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
    override def writes(o: HttpListenerConfig): JsValue = Json.obj(
      "enabled" -> o.enabled,
      "exclusive" -> o.exclusive,
      "tls" -> o.tls,
      "http2" -> o.http2,
      "h2c" -> o.h2c,
      "http3" -> o.http3,
      "port" -> o.port,
      "exposedPort" -> o.exposedPort,
      "host" -> o.host,
      "accessLog" -> o.accessLog,
      "clientAuth" -> o.clientAuth.name,
    )
  }
}

case class HttpListener(
                location: EntityLocation,
                id: String,
                name: String,
                description: String,
                tags: Seq[String],
                config: HttpListenerConfig,
                metadata: Map[String, String]
              ) extends EntityLocationSupport {
  override def internalId: String               = id
  override def json: JsValue                    = HttpListener.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata
  def toNettyConfig(env: Env): ReactorNettyServerConfig = {
    ReactorNettyServerConfig(
      id = id,
      enabled = config.enabled,
      exclusive = config.exclusive,
      newEngineOnly = true,
      host = config.host,
      httpPort = if (config.tls) -1 else config.port,
      exposedHttpPort = config.exposedPort,
      httpsPort = if (!config.tls) -1 else config.port,
      exposedHttpsPort = config.exposedPort,
      nThread = 0,
      wiretap = false,
      accessLog = config.accessLog,
      idleTimeout = java.time.Duration.ofMillis(60000),
      cipherSuites = env.configuration
        .getOptionalWithFileSupport[Seq[String]]("otoroshi.ssl.cipherSuites")
        .filterNot(_.isEmpty), // TODO: custom
      protocols = env.configuration
        .getOptionalWithFileSupport[Seq[String]]("otoroshi.ssl.protocols")
        .filterNot(_.isEmpty), // TODO: custom
      clientAuth = config.clientAuth,
      parser = HttpRequestParserConfig.default,
      http2 = Http2Settings(enabled = config.http2, h2cEnabled = config.h2c),
      http3 = Http3Settings.default.copy(
        enabled = config.tls && config.http3,
        port = config.port,
        exposedPort = config.exposedPort,
      ),
      native = NativeSettings.default
    )
  }
  def start(kind: String, env: Env, cache: (DisposableReactorNettyServer) => Unit): Unit = {
    if (config.enabled) {
      HttpListener.logger.info(s"starting ${kind} http listener '${id}' on ${if (config.tls) "https" else "http"}://${config.host}:(${config.port}/${config.exposedPort}) - h1${if (config.http2) "/h2" else ""}${if (config.http3) "/h3" else ""}")
      val nettyConfig = toNettyConfig(env)
      val server = new ReactorNettyServer(nettyConfig, env).start(env.handlerRef.get())
      cache(server)
    }
  }
}

object HttpListener {
  val logger = Logger("otoroshi-http-listeners")
  val default = HttpListener(
    location = EntityLocation.default,
    id = "http-listener_" + UUID.randomUUID().toString,
    name = "http listener",
    description = "A new http listener",
    tags = Seq.empty,
    config = HttpListenerConfig.default,
    metadata = Map.empty,
  )
  val format = new Format[HttpListener] {
    override def writes(o: HttpListener): JsValue             = o.location.jsonWithKey ++ Json.obj(
      "id"          -> o.id,
      "name"        -> o.name,
      "description" -> o.description,
      "config" -> o.config.json,
      "metadata"    -> o.metadata,
      "tags"        -> JsArray(o.tags.map(JsString.apply))
    )
    override def reads(json: JsValue): JsResult[HttpListener] = Try {
      HttpListener(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        description = (json \ "description").as[String],
        config = (json \ "config").as(HttpListenerConfig.format),
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String])
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

trait HttpListenerDataStore extends BasicStore[HttpListener]

class KvHttpListenerDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
  extends HttpListenerDataStore
    with RedisLikeStore[HttpListener] {
  override def fmt: Format[HttpListener]                        = HttpListener.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): String                 = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:httplisteners:$id"
  override def extractId(value: HttpListener): String           = value.id
}

class HttpListenerAdminExtensionDatastores(env: Env, extensionId: AdminExtensionId) {
  val httpListenerDatastore: HttpListenerDataStore = new KvHttpListenerDataStore(extensionId, env.datastores.redis, env)
}

class HttpListenerAdminExtensionState(env: Env) {

  private val httpListeners = new UnboundedTrieMap[String, HttpListener]()

  def httpListener(id: String): Option[HttpListener] = httpListeners.get(id)
  def allHttpListeners(): Seq[HttpListener]          = httpListeners.values.toSeq

  private[extensions] def updateHttpListeners(values: Seq[HttpListener]): Unit = {
    httpListeners.addAll(values.map(v => (v.id, v))).remAll(httpListeners.keySet.toSeq.diff(values.map(_.id)))
  }
}

class HttpListenerAdminExtension(val env: Env) extends AdminExtension {

  private lazy val datastores = new HttpListenerAdminExtensionDatastores(env, id)
  private lazy val states     = new HttpListenerAdminExtensionState(env)

  private val staticListeners = new UnboundedTrieMap[String, DisposableReactorNettyServer]()
  private val dynamicListeners = new UnboundedTrieMap[String, (HttpListener, DisposableReactorNettyServer)]()

  override def id: AdminExtensionId = AdminExtensionId("otoroshi.extensions.HttpListeners")

  override def name: String = "Http Listeners"

  override def description: Option[String] = "provide individual and dynamic http listeners".some

  override def enabled: Boolean = env.isDev || configuration.getOptional[Boolean]("enabled").getOrElse(false)

  override def start(): Unit = {
    val root = env.configurationJson.select("otoroshi").select("admin-extensions").select("configurations").select("otoroshi_extensions_httplisteners").asObject
    val listenerConfigsJson1 = root.select("listeners_json").asOpt[String].flatMap(str => Json.parse(str).asOpt[Seq[JsObject]]).getOrElse(Seq.empty)
    val listenerConfigsJson2 = root.select("listeners").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
    val listenerConfigs = (listenerConfigsJson1 ++ listenerConfigsJson2).flatMap(obj => HttpListenerConfig.format.reads(obj).asOpt.map(r => (obj, r)))
    listenerConfigs
      .filter(_._2.enabled)
      .foreach {
        case (obj, config) =>
          val cid = obj.select("id").asString
          val listener = HttpListener(
            location = EntityLocation.default,
            id = cid,
            name = cid,
            description = cid,
            config = config,
            tags = Seq.empty,
            metadata = Map.empty,
          )
          listener.start("static", env, server => staticListeners.put(cid, server))
      }
  }

  override def stop(): Unit = {
    staticListeners.foreach(_._2.stop())
    dynamicListeners.foreach(_._2._2.stop())
  }

  def syncServerStates(listeners: Seq[HttpListener]): Unit = {
    Future {
      val newOnes = listeners.map(l => (l.id, l)).toMap
      listeners.foreach { listener =>
        if (dynamicListeners.contains(listener.id)) {
          dynamicListeners.get(listener.id).map { existing =>
            if (listener != existing._1) {
              // restart
              dynamicListeners.get(listener.id).foreach(_._2.stop())
              if (!listener.config.enabled) {
                dynamicListeners.put(listener.id, (listener, existing._2))
              } else {
                listener.start("dynamic", env, server => dynamicListeners.put(listener.id, (listener, server)))
              }
            }
          }
        } else {
          // start
          listener.start("dynamic", env, server => dynamicListeners.put(listener.id, (listener, server)))
        }
      }
      dynamicListeners.values.map {
        case (listener, server) => {
          newOnes.get(listener.id) match {
            case None =>
              println("stop")
              server.stop()
              dynamicListeners.remove(listener.id)
            case Some(_) => ()
          }
        }
      }
    }(env.analyticsExecutionContext)
  }

  override def syncStates(): Future[Unit] = {
    implicit val ec = env.otoroshiExecutionContext
    implicit val ev = env
    for {
      listeners <- datastores.httpListenerDatastore.findAll()
    } yield {
      states.updateHttpListeners(listeners)
      syncServerStates(listeners)
      ()
    }
  }

  override def entities(): Seq[AdminExtensionEntity[EntityLocationSupport]] = {
    Seq(
      AdminExtensionEntity(
        Resource(
          "HttpListener",
          "http-listeners",
          "http-listener",
          "http-listeners.extensions.otoroshi.io",
          ResourceVersion("v1", true, false, true),
          GenericResourceAccessApiWithState[HttpListener](
            HttpListener.format,
            classOf[HttpListener],
            id => datastores.httpListenerDatastore.key(id),
            c => datastores.httpListenerDatastore.extractId(c),
            json => json.select("id").asString,
            () => "id",
            tmpl = (a, b) => HttpListener.default.json,
            stateAll = () => states.allHttpListeners(),
            stateOne = id => states.httpListener(id),
            stateUpdate = values => states.updateHttpListeners(values)
          )
        )
      )
    )
  }
}
