package otoroshi.wasm.proxywasm

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
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
import otoroshi.utils.{ReplaceAllWith, TypedMap}
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import otoroshi.wasm._
import play.api.libs.json._
import play.api.libs.typedmap.TypedKey
import play.api.mvc
import play.api.mvc.{RequestHeader, Results}

import scala.collection.Seq
import scala.concurrent._
import scala.concurrent.duration._
import scala.util._


object CorazaPluginKeys {
  val CorazaWasmVmKey = TypedKey[WasmVm]("otoroshi.next.plugins.CorazaWasmVm")

  val RequestBodyKey  = TypedKey[Future[Source[ByteString, _]]]("otoroshi.next.plugins.RequestBodyKey")
  val HasBodyKey  = TypedKey[Boolean]("otoroshi.next.plugins.HasBodyKey")
}

trait CorazaImplementation {
  def config: CorazaWafConfig

  def start(attrs: TypedMap): Future[Unit]

  def runRequestPath(request: NgPluginHttpRequest, attrs: TypedMap, route: Option[NgRoute] = None): Future[NgAccess]

  def runRequestBodyPath(
      req: NgPluginHttpRequest,
      body_bytes: Option[ByteString],
      attrs: TypedMap,
      route: Option[NgRoute]
  ): Future[Either[mvc.Result, Unit]]

  def runResponsePath(
      req: NgPluginHttpRequest,
      response: NgPluginHttpResponse,
      body_bytes: Option[ByteString],
      attrs: TypedMap,
      route: Option[NgRoute]
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
      val url = "wasm/coraza.wasm"

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
      oldVersionsKeys.foreach(plugins.remove)
    }
    plugin
  }
}

class NgCorazaWAF extends NgRequestTransformer {

  override def steps: Seq[NgStep] = Seq(NgStep.ValidateAccess, NgStep.TransformRequest, NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl, NgPluginCategory.Custom("WAF"))
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean = true
  override def core: Boolean = true
  override def name: String = "Coraza WAF"
  override def description: Option[String] = "Coraza WAF plugin".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgCorazaWAFConfig("none").some

  override def isTransformRequestAsync: Boolean = true
  override def isTransformResponseAsync: Boolean = true
  override def usesCallbacks: Boolean = true
  override def transformsRequest: Boolean = true
  override def transformsResponse: Boolean = true
  override def transformsError: Boolean = false

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

  override def transformRequest(
                                 ctx: NgTransformerRequestContext
                               )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[mvc.Result, NgPluginHttpRequest]] = {
    val config = ctx.cachedConfig(internalName)(NgCorazaWAFConfig.format).getOrElse(NgCorazaWAFConfig("none"))
    val plugin = NgCorazaWAF.getPlugin(config.ref, ctx.attrs)
    val hasBody = ctx.request.theHasBody

    ctx.attrs.put(otoroshi.wasm.proxywasm.CorazaPluginKeys.HasBodyKey -> hasBody)

    if (hasBody && plugin.config.inspectBody) {
      ctx.otoroshiRequest.body.runFold(ByteString.empty)(_ ++ _)
        .flatMap { bytes =>
          val req = ctx.otoroshiRequest.copy(body = bytes.chunks(16 * 1024))
          val promise   = Promise[Source[ByteString, _]]()
          ctx.attrs.put(otoroshi.wasm.proxywasm.CorazaPluginKeys.RequestBodyKey -> promise.future)

          val source = Source(bytes.grouped(16 * 1024).toList)
          promise.trySuccess(source)

          plugin
            .runRequestBodyPath(
              req,
              bytes.some,
              ctx.attrs,
              ctx.route.some
            )
            .map {
              case Left(result) => Left(result)
              case Right(_) => Right(req)
            }
        }
    } else {
      plugin.runRequestPath(ctx.otoroshiRequest, ctx.attrs, ctx.route.some)
        .map {
          case NgAccess.NgAllowed => ctx.otoroshiRequest.right
          case NgAccess.NgDenied(result) => result.left
        }
    }
  }

  override def transformResponse(
                                  ctx: NgTransformerResponseContext
                                )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[mvc.Result, NgPluginHttpResponse]] = {
    val config = ctx.cachedConfig(internalName)(NgCorazaWAFConfig.format).getOrElse(NgCorazaWAFConfig("none"))
    val plugin = NgCorazaWAF.getPlugin(config.ref, ctx.attrs)

    val request = NgPluginHttpRequest.fromRequest(ctx.request)

    if (plugin.config.inspectBody) {
      ctx.otoroshiResponse.body.runFold(ByteString.empty)(_ ++ _)
        .flatMap { bytes =>
          val res = ctx.otoroshiResponse.copy(body = bytes.chunks(16 * 1024))
          plugin
            .runResponsePath(
              request,
              res,
              bytes.some,
              ctx.attrs,
              ctx.route.some
            )
            .map {
              case Left(result) => Left(result)
              case Right(_) => Right(res)
            }
        }
    }
    else {
      plugin.runResponsePath(request, ctx.otoroshiResponse, None, ctx.attrs, ctx.route.some)
        .map {
          case Left(result) => Left(result)
          case Right(_) => Right(ctx.otoroshiResponse)
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
          val res = plugin.runRequestPath(NgPluginHttpRequest.fromRequest(ctx.request), ctx.attrs)
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

case class CorazaRule(id: Int = 0, file: String = "", severity: Int = 0) {
  def json = Json.obj(
    "id" -> id,
    "file" -> file,
    "severity" -> severity
  )
}

case class CorazaError(message: String, uri: String, rule: CorazaRule) {
  def json = Json.obj(
    "message" -> message,
    "uri" -> uri,
    "rule" -> rule.json
  )
}

object CorazaError {
  val fmt = new Format[CorazaError] {
    override def writes(o: CorazaError): JsValue             = o.json
    override def reads(json: JsValue): JsResult[CorazaError] = Try {
      CorazaError(
        message= json.selectAsOptString("message").getOrElse(""),
        uri = json.selectAsOptString("uri").getOrElse(""),
        rule = CorazaRule.fmt
              .reads((json \ "rule").asOpt[JsValue].getOrElse(JsNull))
              .getOrElse(CorazaRule())
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(route)     => JsSuccess(route)
    }
  }
}

object CorazaRule {
  val fmt = new Format[CorazaRule] {
    override def writes(o: CorazaRule): JsValue             = o.json
    override def reads(json: JsValue): JsResult[CorazaRule] = Try {
      CorazaRule(
        id = json.selectAsOptInt("id").getOrElse(0),
        file = json.selectAsOptString("file").getOrElse(""),
        severity = json.selectAsOptInt("severity").getOrElse(0),
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(route)     => JsSuccess(route)
    }
  }
}

case class CorazaTrailEvent(
    rawMatchedRules: String,
    request: NgPluginHttpRequest,
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
    val rules = rawMatchedRules.split("\n").toSeq.map(line => CorazaError.fmt.reads(JsString(line))).collect {
      case JsSuccess(e, _) => e
    }

    Json.obj(
      "@id" -> `@id`,
      "@timestamp" -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(timestamp),
      "@type"      -> "CorazaTrailEvent",
      "@product"   -> "otoroshi",
      "@serviceId" -> `@serviceId`,
      "@service"   -> `@service`,
      "@env"       -> "prod",
      "msg"        -> rules.map(_.json),
      "route"      -> route.map(_.json).getOrElse(JsNull).asValue,
      "request"    -> request.json
    )
  }
}


class CorazaNextPlugin(wasm: WasmConfig, val config: CorazaWafConfig, key: String, env: Env) extends CorazaImplementation {
  private implicit val ec = env.otoroshiExecutionContext

  private lazy val pool: WasmVmPool = WasmVmPool.forConfigurationWithId(key, wasm)(env.wasmIntegration.context)

  def start(attrs: TypedMap): Future[Unit] = {
    pool.getPooledVm(WasmVmInitOptions(importDefaultHostFunctions = false, resetMemory = false, _ => Seq.empty)).flatMap { vm =>
      attrs.put(otoroshi.wasm.proxywasm.CorazaPluginKeys.CorazaWasmVmKey -> vm)
      vm.finitialize {
        var directives = Seq("Include @recommended-conf", "Include @crs-setup-conf", "SecRequestBodyAccess On", "SecResponseBodyAccess On")
        if (config.includeOwaspCRS) {
          directives = directives :+ "Include @owasp_crs/*.conf"
        }

        val defaultDirectives = Seq(
          "SecRuleEngine On",
          "SecRuleEngine DetectionOnly",
          "SecRequestBodyAccess On",
          "SecResponseBodyAccess On",
          "Include @coraza",
          "Include @recommended-conf",
          "Include @crs-setup",
          "Include @owasp_crs/*.conf")

        config
          .directives
          .filter(line => !defaultDirectives.contains(line))
          .foreach(v => directives = directives :+ v)

         if (config.isBlockingMode) {
           directives = directives :+ "SecRuleEngine On"
         } else {
           directives = directives :+ "SecRuleEngine DetectionOnly"
         }

        vm.callCorazaNext("initialize", "", None,  Json.stringify(Json.obj(
          "directives" -> directives.mkString("\n"),
          "inspect_bodies" -> config.inspectBody
        )).some)
      }
    }
  }

  override def runRequestPath(request: NgPluginHttpRequest, attrs: TypedMap, route: Option[NgRoute]): Future[NgAccess] = {
    val instance: WasmVm = attrs.get(otoroshi.wasm.proxywasm.CorazaPluginKeys.CorazaWasmVmKey).get
    val in = Json.obj(
        "url" -> request.uri.path.toString(),
        "method" -> request.method,
        "headers" -> request.headers,
        "proto" -> request.version.toUpperCase
    )

    evaluate(instance, in, isResponse = false, request, route)
  }

  def evaluate(instance: WasmVm,
               in: JsObject,
               isResponse: Boolean = false,
               request: NgPluginHttpRequest,
               route: Option[NgRoute]) = {
    instance.callCorazaNext(if (isResponse) "evaluateResponse" else "evaluate", in.stringify).map {
      case Left(err) => rejectCall(Json.stringify(Json.obj("error" -> err)), request, route)
      case Right(value) =>
        val result = Json.parse(value._1)
        val response = result.select("response").asOpt[String].forall(_.toBoolean)
        val errors = result.select("errors")
          .asOpt[String]
          .getOrElse(Json.stringify(Json.obj("error" -> "---")))

        if (response) {
          NgAccess.NgAllowed
        } else {
          rejectCall(errors, request, route)
        }
    }
  }

  private def rejectCall(errors: String,
                         request: NgPluginHttpRequest,
                         route: Option[NgRoute]): NgAccess = {
    if (config.isBlockingMode) {
      NgAccess.NgDenied(Results.Forbidden)
    } else {
      // TODO - send event and log it
      CorazaTrailEvent(errors, request, route)
      NgAccess.NgAllowed
    }
  }

  override def runRequestBodyPath(
                                   req: NgPluginHttpRequest,
                                   body_bytes: Option[ByteString],
                                   attrs: TypedMap,
                                   route: Option[NgRoute]): Future[Either[mvc.Result, Unit]] = {
    if (body_bytes.isDefined) {
      val instance = attrs.get(otoroshi.wasm.proxywasm.CorazaPluginKeys.CorazaWasmVmKey).get
      val in = Json.obj(
          "url"     -> req.uri.path.toString(),
          "method"  -> req.method,
          "headers" -> req.headers,
          "body"    -> body_bytes.get,
          "proto" -> req.version.toUpperCase
      )

      evaluate(instance, in, isResponse = false, req, route)
        .map {
          case NgAccess.NgAllowed => ().right
          case NgAccess.NgDenied(_) => Results.Forbidden.left
        }
    } else {
      ().rightf
    }
  }

  override def runResponsePath(request: NgPluginHttpRequest,
                               response: NgPluginHttpResponse,
                               body_bytes: Option[ByteString],
                               attrs: TypedMap,
                               route: Option[NgRoute]): Future[Either[mvc.Result, Unit]] = {
    val instance = attrs.get(otoroshi.wasm.proxywasm.CorazaPluginKeys.CorazaWasmVmKey).get

    val body_source: Source[ByteString, NotUsed] = attrs.get(otoroshi.wasm.proxywasm.CorazaPluginKeys.RequestBodyKey) match {
      case None       => Source.single(ByteString.empty)
      case Some(body) => Source.future(body).flatMapConcat(b => b)
    }

    body_source.runWith(Sink.head)(env.otoroshiMaterializer)
      .flatMap(requestBody => {
         val in = Json.obj(
          "request" -> Json.obj(
            "url"     -> request.uri.path.toString(),
            "method"  -> request.method,
            "headers" -> request.headers,
            "proto" -> request.version.toUpperCase,
          ).applyOnWithOpt(attrs.get(otoroshi.wasm.proxywasm.CorazaPluginKeys.HasBodyKey)) {
            case (obj, hasBody) =>
              if (hasBody)
                obj.deepMerge(Json.obj("body"-> requestBody))
              else
                obj
          },
          "response" -> Json.obj(
            "headers" -> response.headers,
            "proto" -> request.version.toUpperCase,
            "status" -> response.status,
          ).applyOnWithOpt(body_bytes) {
            case (obj, body) => obj.deepMerge(Json.obj("body"-> body))
          },
        )

        evaluate(instance, in, isResponse = true, request, route)
          .map {
            case NgAccess.NgAllowed => ().right
            case NgAccess.NgDenied(_) => Results.Forbidden.left
          }
      })
  }
}