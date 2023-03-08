package otoroshi.next.plugins

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import org.extism.sdk.Plugin
import org.extism.sdk.Context
import org.extism.sdk.manifest.{Manifest, MemoryOptions}
import org.extism.sdk.wasm.WasmSourceResolver
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.models.WasmManagerSettings
import otoroshi.next.models.NgRoute
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.next.utils.JsonHelpers
import otoroshi.script.{Job, RequestHandler}
import otoroshi.utils.TypedMap
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import play.api.http.HttpEntity
import play.api.libs.json._
import play.api.libs.ws.{DefaultWSCookie, WSCookie}
import play.api.mvc.{Request, Result, Results}

import java.nio.file.{Files, Paths}
import java.util.concurrent.{Executors, TimeUnit}
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration, MILLISECONDS}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

case class WasmDataRights(read: Boolean = false, write: Boolean = false)

object WasmDataRights {
  def fmt =
    new Format[WasmDataRights] {
      override def writes(o: WasmDataRights) =
        Json.obj(
          "read"  -> o.read,
          "write"        -> o.write
        )

      override def reads(json: JsValue) =
        Try {
          JsSuccess(
            WasmDataRights(
              read = (json \ "read").asOpt[Boolean].getOrElse(false),
              write = (json \ "write").asOpt[Boolean].getOrElse(false)
            )
          )
        } recover { case e =>
          JsError(e.getMessage)
        } get
    }
}

sealed trait WasmSourceKind {
  def name: String
  def json: JsValue = JsString(name)
  def getWasm(path: String)(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, ByteString]]
}
object WasmSourceKind {
  case object Unknown extends WasmSourceKind {
    def name: String = "Unknown"
    def getWasm(path: String)(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, ByteString]] = {
      Left(Json.obj("error" -> "unknown source")).vfuture
    }
  }
  case object Base64 extends WasmSourceKind {
    def name: String = "Base64"
    def getWasm(path: String)(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, ByteString]] = {
      ByteString(path.replace("base64://", "")).decodeBase64.right.future
    }
  }
  case object Http extends WasmSourceKind {
    def name: String = "Http"
    def getWasm(path: String)(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, ByteString]] = {
      // TODO: support headers
      env.Ws.url(path).withRequestTimeout(10.seconds).get().map { resp =>
        val body = resp.bodyAsBytes
        Right(body)
      }
    }
  }
  case object WasmManager extends WasmSourceKind {
    def name: String = "WasmManager"
    def getWasm(path: String)(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, ByteString]] = {
      env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig =>
        globalConfig.wasmManagerSettings match {
          case Some(WasmManagerSettings(url, clientId, clientSecret, _)) => {
            env.Ws
              .url(s"$url/wasm/$path")
              .withFollowRedirects(false)
              .withRequestTimeout(FiniteDuration(5 * 1000, MILLISECONDS))
              .withHttpHeaders(
                "Accept" -> "application/json",
                "Otoroshi-Client-Id" -> clientId,
                "Otoroshi-Client-Secret" -> clientSecret
              )
              .get()
              .flatMap { resp =>
                if (resp.status == 400) {
                  Left(Json.obj("error" -> "missing signed plugin url")).vfuture
                } else {
                  Right(resp.bodyAsBytes).vfuture
                }
              }
          }
          case _ =>
            Left(Json.obj("error" -> "missing wasm manager url")).vfuture
        }
      }

    }
  }
  case object Local extends WasmSourceKind {
    def name: String = "Local"
    def getWasm(path: String)(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, ByteString]] = {
      // TODO: implements when entity exists
      ???
    }
  }
  case object File extends WasmSourceKind {
    def name: String = "File"
    def getWasm(path: String)(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, ByteString]] = {
      Right(ByteString(Files.readAllBytes(Paths.get(path.replace("file://", ""))))).vfuture
    }
  }

  def apply(value: String): WasmSourceKind = value.toLowerCase match {
    case "base64" => Base64
    case "http" => Http
    case "wasmlanager" => WasmManager
    case "local" => Local
    case "file" => File
    case _ => Unknown
  }
}
case class WasmSource(kind: WasmSourceKind, path: String) {
  def json: JsValue = WasmSource.format.writes(this)
  def cacheKey = s"${kind.name.toLowerCase}://${path}"
  def getWasm(cache: Cache[String, ByteString])(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, ByteString]] = {
    cache.getIfPresent(cacheKey) match {
      case Some(bs) => bs.right.vfuture
      case None => {
        kind.getWasm(path).map {
          case Left(err) => err.left
          case Right(bs) => {
            cache.put(cacheKey, bs)
            bs.right
          }
        }
      }
    }
  }
}
object WasmSource {
  val format = new Format[WasmSource] {
    override def writes(o: WasmSource): JsValue = Json.obj(
      "kind" -> o.kind.json,
      "path" -> o.path,
    )
    override def reads(json: JsValue): JsResult[WasmSource] = Try {
      WasmSource(
        kind = json.select("kind").asOpt[String].map(WasmSourceKind.apply).getOrElse(WasmSourceKind.Unknown),
        path = json.select("path").asString,
      )
    } match {
      case Success(s) => JsSuccess(s)
      case Failure(e) => JsError(e.getMessage)
    }
  }
}
case class WasmConfig(
    source: WasmSource = WasmSource(WasmSourceKind.Unknown, ""),
    memoryPages: Int = 4,
    functionName: String = "execute",
    config: Map[String, String] = Map.empty,
    allowedHosts: Seq[String] = Seq.empty,
    ////
    wasi: Boolean = false,
    proxyHttpCallTimeout: Int = 5000,
    httpAccess: Boolean = false,
    globalDataStoreAccess: WasmDataRights = WasmDataRights(),
    pluginDataStoreAccess: WasmDataRights = WasmDataRights(),
    globalMapAccess: WasmDataRights = WasmDataRights(),
    pluginMapAccess: WasmDataRights = WasmDataRights(),
    proxyStateAccess: Boolean = false,
    configurationAccess: Boolean = false

) extends NgPluginConfig {
  def json: JsValue = Json.obj(
    "source"           -> source.json,
    "memoryPages"             -> memoryPages,
    "functionName"            -> functionName,
    "config"                  -> config,
    "allowedHosts"            -> allowedHosts,
    "wasi"                    -> wasi,
    "httpAccess"              -> httpAccess,
    "proxyHttpCallTimeout"    -> proxyHttpCallTimeout,
    "globalDataStoreAccess"   -> WasmDataRights.fmt.writes(globalDataStoreAccess),
    "pluginDataStoreAccess"   -> WasmDataRights.fmt.writes(pluginDataStoreAccess),
    "globalMapAccess"         -> WasmDataRights.fmt.writes(globalMapAccess),
    "pluginMapAccess"         -> WasmDataRights.fmt.writes(pluginMapAccess),
    "proxyStateAccess"        -> proxyStateAccess,
    "configurationAccess"      -> configurationAccess,
  )
}

object WasmConfig {
  val format = new Format[WasmConfig] {
    override def reads(json: JsValue): JsResult[WasmConfig] = Try {
      val compilerSource = json.select("compiler_source").asOpt[String]
      val rawSource = json.select("raw_source").asOpt[String]
      val sourceOpt = json.select("source").asOpt[JsObject]
      val source = if (sourceOpt.isDefined) {
        WasmSource.format.reads(sourceOpt.get).get
      } else {
        compilerSource match {
          case Some(source) => WasmSource(WasmSourceKind.WasmManager, source)
          case None => rawSource match {
            case Some(source) if source.startsWith("http://") => WasmSource(WasmSourceKind.Http, source)
            case Some(source) if source.startsWith("https://") => WasmSource(WasmSourceKind.Http, source)
            case Some(source) if source.startsWith("file://") => WasmSource(WasmSourceKind.File, source.replace("file://", ""))
            case Some(source) if source.startsWith("base64://") => WasmSource(WasmSourceKind.Base64, source.replace("base64://", ""))
            case Some(source) if source.startsWith("entity://") => WasmSource(WasmSourceKind.Local, source.replace("entity://", ""))
            case Some(source) if source.startsWith("local://") => WasmSource(WasmSourceKind.Local, source.replace("local://", ""))
            case Some(source) => WasmSource(WasmSourceKind.Base64, source)
            case _ => WasmSource(WasmSourceKind.Unknown, "")
          }
        }
      }
      WasmConfig(
        source = source,
        memoryPages = (json \ "memoryPages").asOpt[Int].getOrElse(4),
        functionName = (json \ "functionName").asOpt[String].getOrElse("execute"),
        config = (json \ "config").asOpt[Map[String, String]].getOrElse(Map.empty),
        allowedHosts = (json \ "allowedHosts").asOpt[Seq[String]].getOrElse(Seq.empty),
        wasi = (json \ "wasi").asOpt[Boolean].getOrElse(false),
        httpAccess = (json \ "httpAccess").asOpt[Boolean].getOrElse(false),
        proxyHttpCallTimeout = (json \ "proxyHttpCallTimeout").asOpt[Int].getOrElse(5000),
        globalDataStoreAccess = (json \ "globalDataStoreAccess")
          .asOpt[WasmDataRights](WasmDataRights.fmt.reads).getOrElse(WasmDataRights()),
        pluginDataStoreAccess = (json \ "pluginDataStoreAccess")
          .asOpt[WasmDataRights](WasmDataRights.fmt.reads).getOrElse(WasmDataRights()),
        globalMapAccess = (json \ "globalMapAccess")
          .asOpt[WasmDataRights](WasmDataRights.fmt.reads).getOrElse(WasmDataRights()),
        pluginMapAccess = (json \ "pluginMapAccess")
          .asOpt[WasmDataRights](WasmDataRights.fmt.reads).getOrElse(WasmDataRights()),
        proxyStateAccess = (json \ "proxyStateAccess").asOpt[Boolean].getOrElse(false),
        configurationAccess = (json \ "configurationAccess").asOpt[Boolean].getOrElse(false)
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: WasmConfig): JsValue = o.json
  }
}

case class WasmContextSlot(manifest: Manifest, context: Context, plugin: Plugin) {
  def close(): Unit = {
    plugin.close()
    context.free()
  }
}
class WasmContext(plugins: TrieMap[String, WasmContextSlot] = new TrieMap[String, WasmContextSlot]()) {
  def get(id: String): Option[WasmContextSlot] = plugins.get(id)
  def close(): Unit = {
    println(s"[WasmContext] will close ${plugins.size} wasm plugin instances")
    plugins.foreach(_._2.close())
    plugins.clear()
  }
}

object WasmUtils {

  private implicit val executor = ExecutionContext.fromExecutorService(
    Executors.newWorkStealingPool((Runtime.getRuntime.availableProcessors * 2) + 1)
  )

  // Not great !
  private var _cache: Option[Cache[String, ByteString]] = None

  private def scriptCache(implicit env: Env) = {
    _cache match {
      case Some(value) => value
      case None =>
        _cache = Scaffeine()
          .recordStats()
          .expireAfterWrite(Duration(env.wasmCacheTtl, TimeUnit.MILLISECONDS))
          .maximumSize(env.wasmCacheSize)
          .build[String, ByteString]
          .some
        _cache.get
    }
  }

  private[plugins] def convertJsonCookies(wasmResponse: JsValue): Option[Seq[WSCookie]] =
    wasmResponse
      .select("cookies")
      .asOpt[Seq[JsObject]]
      .map { arr =>
        arr.map { c =>
          DefaultWSCookie(
            name = c.select("name").asString,
            value = c.select("value").asString,
            maxAge = c.select("maxAge").asOpt[Long],
            path = c.select("path").asOpt[String],
            domain = c.select("domain").asOpt[String],
            secure = c.select("secure").asOpt[Boolean].getOrElse(false),
            httpOnly = c.select("httpOnly").asOpt[Boolean].getOrElse(false)
          )
        }
      }

  private def callWasm(wasm: ByteString, config: WasmConfig, input: JsValue, ctx: Option[NgCachedConfigContext] = None, pluginId: String, attrsOpt: Option[TypedMap])(implicit env: Env): String = {
    try {

      def createPlugin(): WasmContextSlot = {
        val resolver = new WasmSourceResolver()
        val source = resolver.resolve("wasm", wasm.toByteBuffer.array())
        val manifest = new Manifest(
          Seq[org.extism.sdk.wasm.WasmSource](source).asJava,
          new MemoryOptions(config.memoryPages),
          config.config.asJava,
          config.allowedHosts.asJava
        )

        val context = new Context()
        val plugin = context.newPlugin(manifest, config.wasi, next.plugins.HostFunctions.getFunctions(config, ctx, pluginId))
        WasmContextSlot(manifest, context, plugin)
      }

      attrsOpt match {
        case None => {
          val slot = createPlugin()
          val output = slot.plugin.call(config.functionName, input.stringify)
          slot.close()
          output
        }
        case Some(attrs) => {
          val context = attrs.get(otoroshi.next.plugins.Keys.WasmContextKey) match {
            case None => {
              val context = new WasmContext()
              attrs.put(otoroshi.next.plugins.Keys.WasmContextKey -> context)
              context
            }
            case Some(context) => context
          }
          context.get(config.source.cacheKey) match {
            case None => {
              val slot = createPlugin()
              slot.plugin.call(config.functionName, input.stringify)
            }
            case Some(plugin) => plugin.plugin.call(config.functionName, input.stringify)
          }
        }
      }
    } catch {
      case e: Exception =>
        s"""{ "error": ${e.getMessage()} }"""
    }
  }

  def execute(config: WasmConfig, input: JsValue, ctx: Option[NgCachedConfigContext], attrs: Option[TypedMap])(implicit env: Env): Future[Either[JsValue, String]] = {
    val pluginId = config.source.cacheKey
    scriptCache.getIfPresent(pluginId) match {
      case Some(wasm) => WasmUtils.callWasm(wasm, config, input, ctx, pluginId, attrs).right.future
      case None if config.source.kind == WasmSourceKind.Unknown => Left(Json.obj("error" -> "missing source")).future
      case _ => config.source.getWasm(scriptCache(env))
        .map {
          case Left(err) => err.left
          case Right(wasm) =>  WasmUtils.callWasm(wasm, config, input, ctx, pluginId, attrs).right
        }
    }
  }
}

////////////////////////////////////////////////////////

class WasmBackend extends NgBackendCall {

  override def useDelegates: Boolean                       = false
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = false
  override def name: String                                = "WASM Backend"
  override def description: Option[String]                 = "This plugin can be used to launch a WASM file".some
  override def defaultConfigObject: Option[NgPluginConfig] = WasmConfig().some

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
    val config = ctx
      .cachedConfig(internalName)(WasmConfig.format)
      .getOrElse(WasmConfig())

    ctx.wasmJson
      .flatMap(input => WasmUtils.execute(config, input, ctx.some, ctx.attrs.some))
      .map {
        case Right(output) =>
          val response = try {
            Json.parse(output)
          } catch {
            case e: Exception =>
              println(e)
              Json.obj()
          }

          val bodyAsBytes = (response \ "body").asOpt[Array[Byte]].map(bytes => ByteString(bytes))
          val body: Source[ByteString, _] = bodyAsBytes.getOrElse((response.select("body") match {
              case JsDefined(value) =>
                value match {
                  case JsString(value) => value
                  case JsNumber(value) => value.toString()
                  case JsBoolean(value) => value.toString()
                  case o: JsObject => Json.stringify(o)
                  case o: JsArray => Json.stringify(o)
                  case _ => "{}"
                }
              case _: JsUndefined => "{}"
            }).byteString
          ).chunks(16 * 1024)
          bodyResponse(
            status = response.select("status").asOpt[Int].getOrElse(200),
            headers = response
              .select("headers")
              .asOpt[Map[String, String]]
              .getOrElse(Map("Content-Type" -> "application/json")),
            body = body
          )
        case Left(value) =>
          bodyResponse(
            status = 400,
            headers = Map.empty,
            body = Json.stringify(value).byteString.chunks(16 * 1024)
          )
      }
  }
}

class WasmAccessValidator extends NgAccessValidator {

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl, NgPluginCategory.Classic)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "WASM Access control"
  override def description: Option[String]                 = "Delegate route access to a specified file WASM".some
  override def isAccessAsync: Boolean                      = true
  override def defaultConfigObject: Option[NgPluginConfig] = WasmConfig().some

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx
      .cachedConfig(internalName)(WasmConfig.format)
      .getOrElse(WasmConfig())

    WasmUtils
      .execute(config, ctx.wasmJson, ctx.some, ctx.attrs.some)
      .flatMap {
        case Right(res)  =>
          val response = Json.parse(res)
          val result   = (response \ "result").asOpt[Boolean].getOrElse(false)
          if (result) {
            NgAccess.NgAllowed.vfuture
          } else {
            val error = (response \ "error").asOpt[JsObject].getOrElse(Json.obj())
            Errors
              .craftResponseResult(
                (error \ "message").asOpt[String].getOrElse("An error occured"),
                Results.Status((error \ "status").asOpt[Int].getOrElse(403)),
                ctx.request,
                None,
                None,
                attrs = ctx.attrs,
                maybeRoute = ctx.route.some
              )
              .map(r => NgAccess.NgDenied(r))
          }
        case Left(err) =>
          Errors
            .craftResponseResult(
              (err \ "error").asOpt[String].getOrElse("An error occured"),
              Results.Status(400),
              ctx.request,
              None,
              None,
              attrs = ctx.attrs,
              maybeRoute = ctx.route.some
            )
            .map(r => NgAccess.NgDenied(r))
      }
  }
}

class WasmRequestTransformer extends NgRequestTransformer {

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Headers, NgPluginCategory.TrafficControl)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = true
  override def transformsResponse: Boolean                 = false
  override def transformsError: Boolean                    = false
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = false
  override def name: String                                = "WASM Request Transformer"
  override def description: Option[String]                 =
    "Transform the content of the request by executing a WASM file".some
  override def defaultConfigObject: Option[NgPluginConfig] = WasmConfig().some

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config = ctx
      .cachedConfig(internalName)(WasmConfig.format)
      .getOrElse(WasmConfig())

    ctx.wasmJson
      .flatMap(input => {
        WasmUtils
          .execute(config, input, ctx.some, ctx.attrs.some)
          .map {
            case Right(res)    =>
              val response = Json.parse(res)

              Right(
                ctx.otoroshiRequest.copy(
                  headers = (response \ "headers").asOpt[Map[String, String]].getOrElse(ctx.otoroshiRequest.headers),
                  cookies = WasmUtils.convertJsonCookies(response).getOrElse(ctx.otoroshiRequest.cookies),
                  body = response.select("body").asOpt[String].map(b => ByteString(b)) match {
                    case None    => ctx.otoroshiRequest.body
                    case Some(b) => Source.single(b)
                  }
                )
              )
            case Left(value) => Left(Results.BadRequest(value))
          }
      })
  }
}

class WasmResponseTransformer extends NgRequestTransformer {

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Headers, NgPluginCategory.TrafficControl)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = false
  override def transformsResponse: Boolean                 = true
  override def transformsError: Boolean                    = false
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = true
  override def name: String                                = "WASM Response Transformer"
  override def description: Option[String]                 =
    "Transform the content of the request by executing a WASM file".some
  override def defaultConfigObject: Option[NgPluginConfig] = WasmConfig().some

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val config = ctx
      .cachedConfig(internalName)(WasmConfig.format)
      .getOrElse(WasmConfig())

    ctx.wasmJson
      .flatMap(input => {
        WasmUtils
          .execute(config, input, ctx.some, ctx.attrs.some)
          .map {
            case Right(res)    =>
              val response = Json.parse(res)

              ctx.otoroshiResponse
                .copy(
                  headers = (response \ "headers").asOpt[Map[String, String]].getOrElse(ctx.otoroshiResponse.headers),
                  status = (response \ "status").asOpt[Int].getOrElse(200),
                  cookies = WasmUtils.convertJsonCookies(response).getOrElse(ctx.otoroshiResponse.cookies),
                  body = response.select("body").asOpt[String].map(b => ByteString(b)) match {
                    case None    => ctx.otoroshiResponse.body
                    case Some(b) => Source.single(b)
                  }
                )
                .right
            case Left(value) => Left(Results.BadRequest(value))
          }
      })
  }
}

class WasmSink extends NgRequestSink {

  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand

  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Monitoring)

  override def steps: Seq[NgStep] = Seq(NgStep.Sink)

  override def multiInstance: Boolean = false

  override def core: Boolean = true

  override def name: String = "WASM Sink"

  override def description: Option[String] = "Handle unmatched requests".some

  override def defaultConfigObject: Option[NgPluginConfig] = WasmConfig().some

  override def matches(ctx: NgRequestSinkContext)(implicit env: Env, ec: ExecutionContext): Boolean = {
    val config = WasmConfig.format.reads(ctx.config) match {
      case JsSuccess(value, _) => value
      case JsError(_) => WasmConfig()
    }
    val fu = WasmUtils.execute(config.copy(functionName = "matches"), ctx.wasmJson, FakeWasmContext(ctx.config).some, ctx.attrs.some)
      .map {
        case Left(error) => false
        case Right(res) => {
          val response = Json.parse(res)
          (response \ "result").asOpt[Boolean].getOrElse(false)
        }
      }
    Await.result(fu, 10.seconds)
  }

  override def handleSync(ctx: NgRequestSinkContext)(implicit env: Env, ec: ExecutionContext): Result =
    Await.result(this.handle(ctx), 10.seconds)

  override def handle(ctx: NgRequestSinkContext)(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    val config = WasmConfig.format.reads(ctx.config) match {
      case JsSuccess(value, _) => value
      case JsError(_) => WasmConfig()
    }
    WasmUtils.execute(config, ctx.wasmJson, FakeWasmContext(ctx.config).some, ctx.attrs.some)
      .map {
        case Left(error) => Results.InternalServerError(error)
        case Right(res) => {
          val response = Json.parse(res)

          val status = response
            .select("status")
            .asOpt[Int]
            .getOrElse(200)

          val _headers = response
            .select("headers")
            .asOpt[Map[String, String]]
            .getOrElse(Map("Content-Type" -> "application/json"))

          val contentType = _headers
            .get("Content-Type")
            .orElse(_headers.get("content-type"))
            .getOrElse("application/json")

          val headers = _headers
            .filterNot(_._1.toLowerCase() == "content-type")

          val bodytext = response
            .select("body")
            .asOpt[String]
            .map(ByteString.apply)

          val bodyBase64 = response
            .select("bodyBase64")
            .asOpt[String]
            .map(ByteString.apply)
            .map(_.decodeBase64)

          val body: ByteString = bodytext
            .orElse(bodyBase64)
            .getOrElse("""{"message":"hello world!"}""".byteString)

          Results
            .Status(status)(body)
            .withHeaders(headers.toSeq: _*)
            .as(contentType)
        }
      }
  }
}

class WasmRequestHandler extends RequestHandler {

  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def steps: Seq[NgStep] = Seq(NgStep.HandlesRequest)
  override def core: Boolean = true
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Other)
  override def description: Option[String] = "this plugin entirely handle request with a wasm script".some
  override def name: String = "Wasm request handler"
  override def configRoot: Option[String] = "WasmRequestHandler".some
  override def defaultConfig: Option[JsObject] = Json
    .obj(
      configRoot.get -> Json.obj(
        "domains" -> Json.obj(
          "my.domain.tld" -> WasmConfig().json
        )
      )
    )
    .some

  override def handledDomains(implicit ec: ExecutionContext, env: Env): Seq[String] = {
    env.datastores.globalConfigDataStore
      .latest()
      .plugins
      .config
      .select(configRoot.get)
      .asOpt[JsObject]
      .map(v => v.value.keys.toSeq)
      .getOrElse(Seq.empty)
  }

  private def requestToWasmJson(request: Request[Source[ByteString, _]])(implicit ec: ExecutionContext, env: Env): Future[JsValue] = {
    if (request.theHasBody) {
      implicit val mat = env.otoroshiMaterializer
      request.body.runFold(ByteString.empty)(_ ++ _).map { rawBody =>
        JsonHelpers.requestToJson(request).asObject ++ Json.obj(
          "body" -> rawBody.utf8String
        )
      }
    } else {
      JsonHelpers.requestToJson(request).vfuture
    }
  }

  override def handle(request: Request[Source[ByteString, _]], defaultRouting: Request[Source[ByteString, _]] => Future[Result])(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    val configmap = env.datastores.globalConfigDataStore
      .latest()
      .plugins
      .config
      .select(configRoot.get)
      .asOpt[JsObject]
      .map(v => v.value)
      .getOrElse(Map.empty[String, JsValue])
    configmap.get(request.theDomain) match {
      case None => defaultRouting(request)
      case Some(configJson) => {
        WasmConfig.format.reads(configJson).asOpt match {
          case None => defaultRouting(request)
          case Some(config) => {
            requestToWasmJson(request).flatMap { json =>
              val fakeCtx = FakeWasmContext(configJson)
              WasmUtils.execute(config, Json.obj("request" -> json), fakeCtx.some, None)
                .flatMap {
                  case Right(ok) => {
                    val response = Json.parse(ok)
                    val headers: Map[String, String] = response.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty)
                    val contentLength: Option[Long] = headers.getIgnoreCase("Content-Length").map(_.toLong)
                    val contentType: Option[String] = headers.getIgnoreCase("Content-Type")
                    val status: Int = (response \ "status").asOpt[Int].getOrElse(200)
                    val cookies: Seq[WSCookie] = WasmUtils.convertJsonCookies(response).getOrElse(Seq.empty)
                    val body: Source[ByteString, _] = response.select("body").asOpt[String].map(b => ByteString(b)) match {
                      case None => ByteString.empty.singleSource
                      case Some(b) => Source.single(b)
                    }
                    Results.Status(status).sendEntity(HttpEntity.Streamed(
                      data = body,
                      contentLength = contentLength,
                      contentType = contentType,
                    ))
                    .withHeaders(headers.toSeq: _*)
                    .withCookies(cookies.map(_.toCookie):_*)
                    .vfuture
                  }
                  case Left(bad) => Results.InternalServerError(bad).vfuture
                }
            }
          }
        }
      }
    }
  }
}

case class FakeWasmContext(config: JsValue) extends NgCachedConfigContext {
  override def route: NgRoute = NgRoute.empty
}