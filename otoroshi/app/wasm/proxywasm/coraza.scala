package otoroshi.wasm.proxywasm

import akka.stream.Materializer
import akka.util.ByteString
import io.otoroshi.wasm4s.scaladsl._
import org.joda.time.DateTime
import otoroshi.api.{GenericResourceAccessApiWithState, Resource, ResourceVersion}
import otoroshi.env.Env
import otoroshi.events.AnalyticEvent
import otoroshi.models.{EntityLocation, EntityLocationSupport}
import otoroshi.next.extensions._
import otoroshi.next.models.NgRoute
import otoroshi.next.plugins.api._
import otoroshi.next.utils.JsonHelpers
import otoroshi.security.IdGenerator
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.{ReplaceAllWith, TypedMap}
import otoroshi.wasm._
import play.api._
import play.api.libs.json._
import play.api.libs.typedmap.TypedKey
import play.api.mvc.RequestHeader

import java.util.concurrent.atomic._
import scala.concurrent._
import scala.concurrent.duration._
import scala.util._

object CorazaPlugin {
  val rootContextIds     = new AtomicInteger(100)
}

object CorazaPluginKeys {
  val CorazaContextIdKey = TypedKey[Int]("otoroshi.next.plugins.CorazaContextId")
  val CorazaWasmVmKey    = TypedKey[WasmVm]("otoroshi.next.plugins.CorazaWasmVm")
}

trait CorazaImplementation {
  def config: CorazaWafConfig

  def start(attrs: TypedMap): Future[Unit]

  def stop(attrs: TypedMap): Future[Unit] = {
    ().vfuture
  }

  def runRequestPath(request: RequestHeader, attrs: TypedMap): Future[NgAccess]

  def runRequestBodyPath(
      request: RequestHeader,
      req: NgPluginHttpRequest,
      body_bytes: Option[ByteString],
      attrs: TypedMap
  ): Future[Either[mvc.Result, Unit]]

  def runResponsePath(
      response: NgPluginHttpResponse,
      body_bytes: Option[ByteString],
      attrs: TypedMap
  ): Future[Either[mvc.Result, Unit]]
}

case class NgCorazaWAFConfig(ref: String) extends NgPluginConfig {
  override def json: JsValue = NgCorazaWAFConfig.format.writes(this)
}

object NgCorazaWAFConfig {
  val format = new Format[NgCorazaWAFConfig] {
    override def writes(o: NgCorazaWAFConfig): JsValue             = Json.obj("ref" -> o.ref)
    override def reads(json: JsValue): JsResult[NgCorazaWAFConfig] = Try {
      NgCorazaWAFConfig(
        ref = json.select("ref").asString
      )
    } match {
      case Success(e) => JsSuccess(e)
      case Failure(e) => JsError(e.getMessage)
    }
  }
}

object NgCorazaWAF {

  private val plugins = new UnboundedTrieMap[String, CorazaImplementation]()

  def getPlugin(ref: String, attrs: TypedMap)(implicit env: Env): CorazaImplementation = plugins.synchronized {
    val config     = env.adminExtensions.extension[CorazaWafAdminExtension].get.states.config(ref).get
    val configHash = config.json.stringify.sha512
    val key        = s"ref=${ref}&hash=${configHash}"

    val plugin          = if (plugins.contains(key)) {
      plugins(key)
    } else {
      val url = "wasm/corazanext.wasm"

      val wasmConfig = WasmConfig(
          source = WasmSource(
            kind = WasmSourceKind.ClassPath,
            path = url
          ),
          memoryPages = 10000,
          functionName = None,
          wasi = true,
          // lifetime = WasmVmLifetime.Forever,
          instances = config.poolCapacity,
          killOptions = WasmVmKillOptions(
            maxCalls = 2000,
            maxMemoryUsage = 0.9,
            maxAvgCallDuration = 1.day,
            maxUnusedDuration = 5.minutes
          ),
          allowedPaths = Map("/tmp" -> "/tmp"))
      val p   = new CorazaNextPlugin(wasmConfig, config, url, env)

      plugins.put(key, p)
      p
    }
    val oldVersionsKeys = plugins.keySet.filter(_.startsWith(s"ref=${ref}&hash=")).filterNot(_ == key)
    val oldVersions     = oldVersionsKeys.flatMap(plugins.get)
    if (oldVersions.nonEmpty) {
      oldVersions.foreach(_.stop(attrs))
      oldVersionsKeys.foreach(plugins.remove)
    }
    plugin
  }
}

class NgCorazaWAF extends NgAccessValidator with NgRequestTransformer {

  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess, NgStep.TransformRequest, NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.AccessControl, NgPluginCategory.Custom("WAF"))
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Coraza WAF"
  override def description: Option[String]                 = "Coraza WAF plugin".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgCorazaWAFConfig("none").some

  override def isAccessAsync: Boolean            = true
  override def isTransformRequestAsync: Boolean  = true
  override def isTransformResponseAsync: Boolean = true
  override def usesCallbacks: Boolean            = true
  override def transformsRequest: Boolean        = true
  override def transformsResponse: Boolean       = true
  override def transformsError: Boolean          = false

  override def beforeRequest(
      ctx: NgBeforeRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    val config = ctx.cachedConfig(internalName)(NgCorazaWAFConfig.format).getOrElse(NgCorazaWAFConfig("none"))
    val plugin = NgCorazaWAF.getPlugin(config.ref, ctx.attrs)
    plugin.start(ctx.attrs)
  }

  override def afterRequest(
      ctx: NgAfterRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    ctx.attrs.get(otoroshi.wasm.proxywasm.CorazaPluginKeys.CorazaWasmVmKey).foreach(_.release())
    ().vfuture
  }

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx.cachedConfig(internalName)(NgCorazaWAFConfig.format).getOrElse(NgCorazaWAFConfig("none"))
    val plugin = NgCorazaWAF.getPlugin(config.ref, ctx.attrs)
    plugin.runRequestPath(ctx.request, ctx.attrs)
  }

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[mvc.Result, NgPluginHttpRequest]] = {
    val config                             = ctx.cachedConfig(internalName)(NgCorazaWAFConfig.format).getOrElse(NgCorazaWAFConfig("none"))
    val plugin                             = NgCorazaWAF.getPlugin(config.ref, ctx.attrs)
    val hasBody                            = ctx.request.theHasBody
    val bytesf: Future[Option[ByteString]] =
      if (!plugin.config.inspectBody) None.vfuture
      else if (!hasBody) None.vfuture
      else {
        ctx.otoroshiRequest.body.runFold(ByteString.empty)(_ ++ _).map(_.some)
      }
    bytesf.flatMap { bytes =>
      val req =
        if (plugin.config.inspectBody && hasBody) ctx.otoroshiRequest.copy(body = bytes.get.chunks(16 * 1024))
        else ctx.otoroshiRequest
      plugin
        .runRequestBodyPath(
          ctx.request,
          req,
          bytes,
          ctx.attrs
        )
        .map {
          case Left(result) => Left(result)
          case Right(_)     => Right(req)
        }
    }
  }

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[mvc.Result, NgPluginHttpResponse]] = {
    val config                             = ctx.cachedConfig(internalName)(NgCorazaWAFConfig.format).getOrElse(NgCorazaWAFConfig("none"))
    val plugin                             = NgCorazaWAF.getPlugin(config.ref, ctx.attrs)
    val bytesf: Future[Option[ByteString]] =
      if (!plugin.config.inspectBody) None.vfuture
      else ctx.otoroshiResponse.body.runFold(ByteString.empty)(_ ++ _).map(_.some)
    bytesf.flatMap { bytes =>
      val res =
        if (plugin.config.inspectBody) ctx.otoroshiResponse.copy(body = bytes.get.chunks(16 * 1024))
        else ctx.otoroshiResponse
      plugin
        .runResponsePath(
          res,
          bytes,
          ctx.attrs
        )
        .map {
          case Left(result) => Left(result)
          case Right(_)     => Right(res)
        }
    }
  }
}

class NgIncomingRequestValidatorCorazaWAF extends NgIncomingRequestValidator {

  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.AccessControl)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Coraza WAF - Incoming Request Validator"
  override def description: Option[String]                 = "Coraza WAF - Incoming Request Validator plugin".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgCorazaWAFConfig("none").some

  override def access(
      ctx: NgIncomingRequestValidatorContext
  )(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    ctx.config.select("ref").asOpt[String] match {
      case None      => NgAccess.NgAllowed.vfuture
      case Some(ref) => {
        val plugin = NgCorazaWAF.getPlugin(ref, ctx.attrs)
        plugin.start(ctx.attrs).flatMap { _ =>
          val res = plugin.runRequestPath(ctx.request, ctx.attrs)
          ctx.attrs.get(otoroshi.wasm.proxywasm.CorazaPluginKeys.CorazaWasmVmKey).foreach(_.release())
          res
        }
      }
    }
  }
}

case class CorazaWafConfig(
    location: EntityLocation,
    id: String,
    name: String,
    description: String,
    tags: Seq[String],
    metadata: Map[String, String],
    inspectBody: Boolean,
    includeOwaspCRS: Boolean = true,
    isBlockingMode: Boolean = true,
    directives: Seq[String] = Seq(
      "Include @recommended-conf",
      "Include @crs-setup-conf",
      "Include @owasp_crs/*.conf",
      "SecRuleEngine On"
    ),
    poolCapacity: Int
) extends EntityLocationSupport {
  override def internalId: String               = id
  override def json: JsValue                    = CorazaWafConfig.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata
}

object CorazaWafConfig {
  def template(): CorazaWafConfig = CorazaWafConfig(
    location = EntityLocation.default,
    id = s"coraza-waf-config_${IdGenerator.uuid}",
    name = "New WAF",
    description = "New WAF",
    metadata = Map.empty,
    tags = Seq.empty,
    inspectBody = true,
    includeOwaspCRS = true,
    directives = Seq.empty,
    poolCapacity = 2
  )
  val format                      = new Format[CorazaWafConfig] {
    override def writes(o: CorazaWafConfig): JsValue             = o.location.jsonWithKey ++ Json.obj(
      "id"                -> o.id,
      "name"              -> o.name,
      "description"       -> o.description,
      "metadata"          -> o.metadata,
      "tags"              -> JsArray(o.tags.map(JsString.apply)),
      "inspect_body"      -> o.inspectBody,
      "is_blocking_mode"  -> o.isBlockingMode,
      "include_owasp_crs" -> o.includeOwaspCRS,
      "directives"        -> o.directives,
      "pool_capacity"     -> o.poolCapacity,
    )
    override def reads(json: JsValue): JsResult[CorazaWafConfig] = Try {
      val oldConfig = (json \ "config").asOpt[JsObject]
      val oldDirectives: Option[JsArray] = oldConfig.flatMap(_.selectAsOptObject("directives_map").flatMap(_.selectAsOptArray("default")))

      CorazaWafConfig(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        description = (json \ "description").as[String],
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        inspectBody = (json \ "inspect_body").asOpt[Boolean].getOrElse(true),
        isBlockingMode = (json \ "is_blocking_mode").asOpt[Boolean].getOrElse(true),
        includeOwaspCRS = (json \ "include_owasp_crs").asOpt[Boolean].getOrElse(true),
        directives = (json \ "directives")
          .asOpt[JsArray]
          .getOrElse(oldDirectives.getOrElse(Json.arr()))
          .value
          .map(_.as[String]),
        poolCapacity = (json \ "pool_capacity").asOpt[Int].getOrElse(2)
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

trait CorazaWafConfigDataStore extends BasicStore[CorazaWafConfig]

class KvCorazaWafConfigDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
    extends CorazaWafConfigDataStore
    with RedisLikeStore[CorazaWafConfig] {
  override def fmt: Format[CorazaWafConfig]              = CorazaWafConfig.format
  override def redisLike(implicit env: Env): RedisLike   = redisCli
  override def key(id: String): String                   = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:configs:$id"
  override def extractId(value: CorazaWafConfig): String = value.id
}

class CorazaWafConfigAdminExtensionDatastores(env: Env, extensionId: AdminExtensionId) {
  val corazaConfigsDatastore: CorazaWafConfigDataStore =
    new KvCorazaWafConfigDataStore(extensionId, env.datastores.redis, env)
}

class CorazaWafConfigAdminExtensionState(env: Env) {

  private val configs = new UnboundedTrieMap[String, CorazaWafConfig]()

  def config(id: String): Option[CorazaWafConfig] = configs.get(id)
  def allConfigs(): Seq[CorazaWafConfig]          = configs.values.toSeq

  private[proxywasm] def updateConfigs(values: Seq[CorazaWafConfig]): Unit = {
    configs.addAll(values.map(v => (v.id, v))).remAll(configs.keySet.toSeq.diff(values.map(_.id)))
  }
}

class CorazaWafAdminExtension(val env: Env) extends AdminExtension {

  private[proxywasm] lazy val datastores = new CorazaWafConfigAdminExtensionDatastores(env, id)
  private[proxywasm] lazy val states     = new CorazaWafConfigAdminExtensionState(env)

  override def id: AdminExtensionId = AdminExtensionId("otoroshi.extensions.CorazaWAF")

  override def name: String = "Coraza WAF extension"

  override def description: Option[String] = "Coraza WAF extension".some

  override def enabled: Boolean = true

  override def start(): Unit = ()

  override def stop(): Unit = ()

  override def syncStates(): Future[Unit] = {
    implicit val ec = env.otoroshiExecutionContext
    implicit val ev = env
    for {
      configs <- datastores.corazaConfigsDatastore.findAll()
    } yield {
      states.updateConfigs(configs)
      ()
    }
  }

  // override def frontendExtensions(): Seq[AdminExtensionFrontendExtension] = {
  //   Seq(
  //     AdminExtensionFrontendExtension("/__otoroshi_assets/javascripts/extensions/coraza-extension.js")
  //   )
  // }

  override def entities(): Seq[AdminExtensionEntity[EntityLocationSupport]] = {
    Seq(
      AdminExtensionEntity(
        Resource(
          "CorazaConfig",
          "coraza-configs",
          "coraza-config",
          "coraza-waf.extensions.otoroshi.io",
          ResourceVersion("v1", true, false, true),
          GenericResourceAccessApiWithState[CorazaWafConfig](
            CorazaWafConfig.format,
            classOf[CorazaWafConfig],
            id => datastores.corazaConfigsDatastore.key(id),
            c => datastores.corazaConfigsDatastore.extractId(c),
            json => json.select("id").asString,
            () => "id",
            tmpl = (v, p, _ctx) => CorazaWafConfig.template().json,
            stateAll = () => states.allConfigs(),
            stateOne = id => states.config(id),
            stateUpdate = values => states.updateConfigs(values)
          )
        )
      )
    )
  }
}

case class CorazaTrailEvent(
    level: org.slf4j.event.Level,
    msg: String,
    request: Option[RequestHeader],
    route: Option[NgRoute]
) extends AnalyticEvent {

  override def `@service`: String            = "--"
  override def `@serviceId`: String          = "--"
  def `@id`: String                          = IdGenerator.uuid
  def `@timestamp`: org.joda.time.DateTime   = timestamp
  def `@type`: String                        = "CorazaTrailEvent"
  override def fromOrigin: Option[String]    = None
  override def fromUserAgent: Option[String] = None

  private val timestamp = DateTime.now()

  override def toJson(implicit env: Env): JsValue = {
    var fields = Map.empty[String, String]
    val txt    = Try(
      ReplaceAllWith("\\[([^\\]]*)\\]")
        .replaceOn(msg, 1) { token =>
          val parts = token.split(" ")
          val key   = parts.head
          val value = parts.tail
            .mkString(" ")
            .applyOnWithPredicate(_.startsWith("\""))(_.substring(1))
            .applyOnWithPredicate(_.endsWith("\""))(_.init)
          fields = fields.put((key, value))
          ""
        }
        .trim
    ).getOrElse("--")
    Json.obj(
      "@id" -> `@id`,
      "@timestamp" -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(timestamp),
      "@type"      -> "CorazaTrailEvent",
      "@product"   -> "otoroshi",
      "@serviceId" -> `@serviceId`,
      "@service"   -> `@service`,
      "@env"       -> "prod",
      "level"      -> level.name(),
      "raw"        -> msg,
      "msg"        -> txt,
      "fields"     -> JsObject(fields.mapValues(JsString.apply)),
      "route"      -> route.map(_.json).getOrElse(JsNull).asValue,
      "request"    -> request.map(JsonHelpers.requestToJson).getOrElse(JsNull).asValue
    )
  }
}
