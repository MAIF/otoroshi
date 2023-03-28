package otoroshi.next.plugins

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import org.extism.sdk.Plugin
import org.extism.sdk.Context
import org.extism.sdk.manifest.{Manifest, MemoryOptions}
import org.extism.sdk.wasm.WasmSourceResolver
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.models.{WSProxyServerJson, WasmManagerSettings}
import otoroshi.next.models.{NgRoute, NgTlsConfig}
import otoroshi.next.plugins.api.{NgPreRoutingError, _}
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.next.utils.JsonHelpers
import otoroshi.script.{Job, JobContext, JobId, JobInstantiation, JobKind, JobStarting, JobVisibility, RequestHandler}
import otoroshi.utils.TypedMap
import otoroshi.utils.http.MtlsConfig
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.http.HttpEntity
import play.api.libs.json._
import play.api.libs.ws.{DefaultWSCookie, WSCookie}
import play.api.mvc.{Request, Result, Results}

import java.nio.file.{Files, Paths}
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{Executors, TimeUnit}
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{Duration, DurationInt, DurationLong, FiniteDuration, MILLISECONDS}
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
  def getWasm(path: String, opts: JsValue)(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, ByteString]]
  def getConfig(path: String, opts: JsValue)(implicit env: Env, ec: ExecutionContext): Future[Option[WasmConfig]] = None.vfuture
}
object WasmSourceKind {
  case object Unknown extends WasmSourceKind {
    def name: String = "Unknown"
    def getWasm(path: String, opts: JsValue)(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, ByteString]] = {
      Left(Json.obj("error" -> "unknown source")).vfuture
    }
  }
  case object Base64 extends WasmSourceKind {
    def name: String = "Base64"
    def getWasm(path: String, opts: JsValue)(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, ByteString]] = {
      ByteString(path.replace("base64://", "")).decodeBase64.right.future
    }
  }
  case object Http extends WasmSourceKind {
    def name: String = "Http"
    def getWasm(path: String, opts: JsValue)(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, ByteString]] = {
      val method = opts.select("method").asOpt[String].getOrElse("GET")
      val headers = opts.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty)
      val timeout = opts.select("timeout").asOpt[Long].getOrElse(10000L).millis
      val followRedirect = opts.select("followRedirect").asOpt[Boolean].getOrElse(true)
      val proxy = opts.select("proxy").asOpt[JsObject].flatMap(v => WSProxyServerJson.proxyFromJson(v))
      val tlsConfig = opts.select("tls").asOpt(NgTlsConfig.format).map(_.legacy).orElse(opts.select("tls").asOpt(MtlsConfig.format))
      (tlsConfig match {
        case None => env.Ws.url(path)
        case Some(cfg) => env.MtlsWs.url(path, cfg)
      })
        .withMethod(method)
        .withFollowRedirects(followRedirect)
        .withHttpHeaders(headers.toSeq: _*)
        .withRequestTimeout(timeout)
        .applyOnWithOpt(proxy) {
          case (req, proxy) => req.withProxyServer(proxy)
        }
        .execute()
        .map { resp =>
          if (resp.status == 200) {
            val body = resp.bodyAsBytes
            Right(body)
          } else {
            val body: String = resp.body
            Left(Json.obj(
              "error" -> "bad response",
              "status" -> resp.status,
              "headers" -> resp.headers.mapValues(_.last),
              "body" -> body
            ))
          }
        }
    }
  }
  case object WasmManager extends WasmSourceKind {
    def name: String = "WasmManager"
    def getWasm(path: String, opts: JsValue)(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, ByteString]] = {
      env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig =>
        globalConfig.wasmManagerSettings match {
          case Some(WasmManagerSettings(url, clientId, clientSecret, kind)) => {
            // println(s"fechting the plugin at $path")
            env.Ws
              .url(s"$url/wasm/$path")
              .withFollowRedirects(false)
              .withRequestTimeout(FiniteDuration(5 * 1000, MILLISECONDS))
              .withHttpHeaders(
                "Accept" -> "application/json",
                "Otoroshi-Client-Id" -> clientId,
                "Otoroshi-Client-Secret" -> clientSecret,
                "kind" -> kind.getOrElse("*")
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
    override def getWasm(path: String, opts: JsValue)(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, ByteString]] = {
      env.proxyState.wasmPlugins(path) match {
        case None => Left(Json.obj("error" -> "resource not found")).vfuture
        case Some(plugin) => plugin.config.source.getWasm()
      }
    }
    override def getConfig(path: String, opts: JsValue)(implicit env: Env, ec: ExecutionContext): Future[Option[WasmConfig]] = {
      env.proxyState.wasmPlugins(path).map(_.config).vfuture
    }
  }
  case object File extends WasmSourceKind {
    def name: String = "File"
    def getWasm(path: String, opts: JsValue)(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, ByteString]] = {
      Right(ByteString(Files.readAllBytes(Paths.get(path.replace("file://", ""))))).vfuture
    }
  }

  def apply(value: String): WasmSourceKind = value.toLowerCase match {
    case "base64" => Base64
    case "http" => Http
    case "wasmmanager" => WasmManager
    case "local" => Local
    case "file" => File
    case _ => Unknown
  }
}

case class WasmSource(kind: WasmSourceKind, path: String, opts: JsValue = Json.obj()) {
  def json: JsValue = WasmSource.format.writes(this)
  def cacheKey = s"${kind.name.toLowerCase}://${path}"
  def getConfig()(implicit env: Env, ec: ExecutionContext): Future[Option[WasmConfig]] = kind.getConfig(path, opts)
  def getWasm()(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, ByteString]] = {
    val cache = WasmUtils.scriptCache(env)
    cache.getIfPresent(cacheKey) match {
      case Some(script) => script.script.right.vfuture
      case None => {
        kind.getWasm(path, opts).map {
          case Left(err) => err.left
          case Right(bs) => {
            cache.put(cacheKey, CachedWasmScript(bs, System.currentTimeMillis()))
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
      "opts" -> o.opts,
    )
    override def reads(json: JsValue): JsResult[WasmSource] = Try {
      WasmSource(
        kind = json.select("kind").asOpt[String].map(WasmSourceKind.apply).getOrElse(WasmSourceKind.Unknown),
        path = json.select("path").asString,
        opts = json.select("opts").asOpt[JsValue].getOrElse(Json.obj()),
      )
    } match {
      case Success(s) => JsSuccess(s)
      case Failure(e) => JsError(e.getMessage)
    }
  }
}

case class WasmAuthorizations(
   httpAccess: Boolean = false,
   globalDataStoreAccess: WasmDataRights = WasmDataRights(),
   pluginDataStoreAccess: WasmDataRights = WasmDataRights(),
   globalMapAccess: WasmDataRights = WasmDataRights(),
   pluginMapAccess: WasmDataRights = WasmDataRights(),
   proxyStateAccess: Boolean = false,
   configurationAccess: Boolean = false,
   proxyHttpCallTimeout: Int = 5000,
) {
  def json: JsValue = WasmAuthorizations.format.writes(this)
}

object WasmAuthorizations {
  val format = new Format[WasmAuthorizations] {
    override def writes(o: WasmAuthorizations): JsValue = Json.obj(
      "httpAccess" -> o.httpAccess,
      "proxyHttpCallTimeout" -> o.proxyHttpCallTimeout,
      "globalDataStoreAccess" -> WasmDataRights.fmt.writes(o.globalDataStoreAccess),
      "pluginDataStoreAccess" -> WasmDataRights.fmt.writes(o.pluginDataStoreAccess),
      "globalMapAccess" -> WasmDataRights.fmt.writes(o.globalMapAccess),
      "pluginMapAccess" -> WasmDataRights.fmt.writes(o.pluginMapAccess),
      "proxyStateAccess" -> o.proxyStateAccess,
      "configurationAccess" -> o.configurationAccess,

    )
    override def reads(json: JsValue): JsResult[WasmAuthorizations] = Try {
      WasmAuthorizations(
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
      case Failure(ex) => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

case class WasmConfig(
    source: WasmSource = WasmSource(WasmSourceKind.Unknown, "", Json.obj()),
    memoryPages: Int = 4,
    functionName: Option[String] = None,
    config: Map[String, String] = Map.empty,
    allowedHosts: Seq[String] = Seq.empty,
    allowedPaths: Map[String, String] = Map.empty,
    ////
    preserve: Boolean = true,
    wasi: Boolean = false,
    opa: Boolean = false,
    authorizations: WasmAuthorizations = WasmAuthorizations(),
) extends NgPluginConfig {
  def json: JsValue = Json.obj(
    "source"           -> source.json,
    "memoryPages"             -> memoryPages,
    "functionName"            -> functionName,
    "config"                  -> config,
    "allowedHosts"            -> allowedHosts,
    "allowedPaths"            -> allowedPaths,
    "wasi"                    -> wasi,
    "opa"                     -> opa,
    "preserve"                -> preserve,
    "authorizations"          -> authorizations.json
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
        functionName = (json \ "functionName").asOpt[String].filter(_.nonEmpty),
        config = (json \ "config").asOpt[Map[String, String]].getOrElse(Map.empty),
        allowedHosts = (json \ "allowedHosts").asOpt[Seq[String]].getOrElse(Seq.empty),
        allowedPaths = (json \ "allowedPaths").asOpt[Map[String, String]].getOrElse(Map.empty),
        wasi = (json \ "wasi").asOpt[Boolean].getOrElse(false),
        opa = (json \ "opa").asOpt[Boolean].getOrElse(false),
        preserve = (json \ "preserve").asOpt[Boolean].getOrElse(true),
        authorizations = (json \ "authorizations").asOpt[WasmAuthorizations](WasmAuthorizations.format.reads)
          .orElse((json \ "accesses").asOpt[WasmAuthorizations](WasmAuthorizations.format.reads))
          .getOrElse {
            println("pouet")
            WasmAuthorizations()
          },
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
//    plugin.free()
    context.free()
  }
}
class WasmContext(plugins: TrieMap[String, WasmContextSlot] = new TrieMap[String, WasmContextSlot]()) {
  def put(id: String, slot: WasmContextSlot): Unit = plugins.put(id, slot)
  def get(id: String): Option[WasmContextSlot] = plugins.get(id)
  def close(): Unit = {
    if (WasmUtils.logger.isDebugEnabled) WasmUtils.logger.debug(s"[WasmContext] will close ${plugins.size} wasm plugin instances")
    plugins.foreach(_._2.close())
    plugins.clear()
  }
}

case class CachedWasmScript(script: ByteString, createAt: Long)

object WasmUtils {

  private[plugins] val logger = Logger("otoroshi-wasm")

  implicit val executor = ExecutionContext.fromExecutorService(
    Executors.newWorkStealingPool((Runtime.getRuntime.availableProcessors * 4) + 1)
  )

  private val _cache: AtomicReference[Cache[String, CachedWasmScript]] = new AtomicReference[Cache[String, CachedWasmScript]]()

  def scriptCache(implicit env: Env) = {
    Option(_cache.get()) match {
      case Some(value) => value
      case None =>
        _cache.set(Scaffeine()
          .recordStats()
          .expireAfterWrite(Duration(env.wasmCacheTtl, TimeUnit.MILLISECONDS))
          .maximumSize(env.wasmCacheSize)
          .build[String, CachedWasmScript])
        _cache.get()
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

  private def callWasm(wasm: ByteString, config: WasmConfig, defaultFunctionName: String, input: JsValue, ctx: Option[NgCachedConfigContext] = None, pluginId: String, attrsOpt: Option[TypedMap])(implicit env: Env): Either[JsValue, String] = {
    val functionName = config.functionName.filter(_.nonEmpty).getOrElse(defaultFunctionName)
    try {
      def createPlugin(): WasmContextSlot = {
        if (WasmUtils.logger.isDebugEnabled) WasmUtils.logger.debug(s"creating wasm plugin instance for ${config.source.cacheKey}")
        // println(s"""creating plugin with wasm with wasi at "${config.wasi}" of "${wasm.size}" bytes""")
        val resolver = new WasmSourceResolver()
        val source = resolver.resolve("wasm", wasm.toByteBuffer.array())
        val manifest = new Manifest(
          Seq[org.extism.sdk.wasm.WasmSource](source).asJava,
          new MemoryOptions(config.memoryPages),
          config.config.asJava,
          config.allowedHosts.asJava,
          // config.allowedPaths.asJava, // TODO: uncomment when new lib version available
        )

        val context = new Context()
        val plugin = context.newPlugin(manifest, config.wasi,
          next.plugins.HostFunctions.getFunctions(config, ctx, pluginId),
          next.plugins.LinearMemories.getMemories(config, ctx, pluginId))
        WasmContextSlot(manifest, context, plugin)
      }

      attrsOpt match {
        case None => {
          val slot = createPlugin()

          val output = (if (config.opa) {
            next.plugins.OPA.evalute(slot.plugin, input.stringify)
          } else {
            slot.plugin.call(functionName, input.stringify)
          })
          slot.close()
          output.right
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
              if (config.preserve) context.put(config.source.cacheKey, slot)
              val output = (if (config.opa) {
                next.plugins.OPA.evalute(slot.plugin, input.stringify)
              } else {
                slot.plugin.call(functionName, input.stringify)
              })
              if (!config.preserve) slot.close()
              output.right
            }
            case Some(plugin) => plugin.plugin.call(functionName, input.stringify).right
          }
        }
      }
    } catch {
      case e: Throwable if e.getMessage.contains("wasm backtrace") =>
        logger.error(s"error while invoking wasm function '${functionName}'", e)
        Json.obj(
          "error" -> "wasm_error",
          "error_description" -> JsArray(e.getMessage.split("\\n").filter(_.trim.nonEmpty).map(JsString.apply))
        ).left
      case e: Throwable =>
        logger.error(s"error while invoking wasm function '${functionName}'", e)
        Json.obj("error" -> "wasm_error", "error_description" -> JsString(e.getMessage)).left
    }
  }

  def execute(config: WasmConfig, defaultFunctionName: String, input: JsValue, ctx: Option[NgCachedConfigContext], attrs: Option[TypedMap])(implicit env: Env): Future[Either[JsValue, String]] = {
    val pluginId = config.source.cacheKey
    scriptCache.getIfPresent(pluginId) match {
      case Some(wasm) =>
        // println(s"\n\nusing script from ${new DateTime(wasm.createAt).toString()}\n")
        config.source.getConfig().map {
          case None => WasmUtils.callWasm(wasm.script, config, defaultFunctionName, input, ctx, pluginId, attrs)
          case Some(finalConfig) => WasmUtils.callWasm(wasm.script, finalConfig.copy(functionName = finalConfig.functionName.orElse(config.functionName)), defaultFunctionName, input, ctx, pluginId, attrs)
        }
      case None if config.source.kind == WasmSourceKind.Unknown => Left(Json.obj("error" -> "missing source")).future
      case _ => config.source.getWasm().flatMap {
        case Left(err) => err.left.vfuture
        case Right(wasm) => {
          if (env.isProd) scriptCache.put(pluginId, CachedWasmScript(wasm, System.currentTimeMillis()))
          config.source.getConfig().map {
            case None => WasmUtils.callWasm(wasm, config, defaultFunctionName, input, ctx, pluginId, attrs)
            case Some(finalConfig) => WasmUtils.callWasm(wasm, finalConfig.copy(functionName = finalConfig.functionName.orElse(config.functionName)), defaultFunctionName, input, ctx, pluginId, attrs)
          }
        }
      }
    }
  }
}

////////////////////////////////////////////////////////

class WasmRouteMatcher extends NgRouteMatcher {

  private val logger = Logger("otoroshi-plugins-wasm-route-matcher")

  override def multiInstance: Boolean = true
  override def core: Boolean = true
  override def name: String = "Wasm Route Matcher"
  override def description: Option[String] = "This plugin can be used to use a wasm plugin as route matcher".some
  override def defaultConfigObject: Option[NgPluginConfig] = WasmConfig().some
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Wasm)
  override def steps: Seq[NgStep] = Seq(NgStep.MatchRoute)

  override def matches(ctx: NgRouteMatcherContext)(implicit env: Env): Boolean = {
    implicit val ec = WasmUtils.executor
    val config = ctx
      .cachedConfig(internalName)(WasmConfig.format)
      .getOrElse(WasmConfig())
    val res = Await.result(WasmUtils.execute(config, "matches_route", ctx.wasmJson, ctx.some, ctx.attrs.some), 10.seconds)
    res match {
      case Right(res) => {
        val response = Json.parse(res)
        (response \ "result").asOpt[Boolean].getOrElse(false)
      }
      case Left(err) =>
        logger.error(s"error while calling wasm route matcher: ${err.prettify}")
        false
    }
  }
}

class WasmPreRoute extends NgPreRouting {

  override def multiInstance: Boolean = true
  override def core: Boolean = true
  override def name: String = "Wasm pre-route"
  override def description: Option[String] = "This plugin can be used to use a wasm plugin as in pre-route phase".some
  override def defaultConfigObject: Option[NgPluginConfig] = WasmConfig().some
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Wasm)
  override def steps: Seq[NgStep] = Seq(NgStep.PreRoute)
  override def isPreRouteAsync: Boolean = true

  override def preRoute(ctx: NgPreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    val config = ctx
      .cachedConfig(internalName)(WasmConfig.format)
      .getOrElse(WasmConfig())
    val input = ctx.wasmJson
    WasmUtils.execute(config, "pre_route", input, ctx.some, ctx.attrs.some).map {
      case Left(err) => Left(NgPreRoutingErrorWithResult(Results.InternalServerError(err)))
      case Right(resStr) => {
        Try(Json.parse(resStr)) match {
          case Failure(e) =>  Left(NgPreRoutingErrorWithResult(Results.InternalServerError(Json.obj("error" -> e.getMessage))))
          case Success(response) => {
            val error = response.select("error").asOpt[Boolean].getOrElse(false)
            if (error) {
              val bodyAsBytes = response.select("body_bytes").asOpt[Array[Byte]].map(bytes => ByteString(bytes))
              val bodyBase64 = response.select("body_base64").asOpt[String].map(str => ByteString(str).decodeBase64)
              val bodyJson = response.select("body_json").asOpt[JsValue].map(str => ByteString(str.stringify))
              val bodyStr = response.select("body_str").asOpt[String].orElse(response.select("body").asOpt[String]).map(str => ByteString(str))
              val body: ByteString = bodyStr.orElse(bodyJson).orElse(bodyBase64).orElse(bodyAsBytes).getOrElse(ByteString.empty)
              val headers: Map[String, String] = response
                .select("headers")
                .asOpt[Map[String, String]]
                .getOrElse(Map("Content-Type" -> "application/json"))
              val contentType = headers.getIgnoreCase("Content-Type").getOrElse("application/json")
              Left(NgPreRoutingErrorRaw(
                code = response.select("status").asOpt[Int].getOrElse(200),
                headers = headers,
                contentType = contentType,
                body = body
              ))
            } else {
              // TODO: handle attrs
              Right(Done)
            }
          }
        }
      }
    }
  }
}

class WasmBackend extends NgBackendCall {

  override def useDelegates: Boolean                       = false
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Wasm Backend"
  override def description: Option[String]                 = "This plugin can be used to use a wasm plugin as backend".some
  override def defaultConfigObject: Option[NgPluginConfig] = WasmConfig().some

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Wasm)
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
      .flatMap(input => WasmUtils.execute(config, "call_backend", input, ctx.some, ctx.attrs.some))
      .map {
        case Right(output) =>
          val response = try {
            Json.parse(output)
          } catch {
            case e: Exception =>
              WasmUtils.logger.error("error during json parsing", e)
              Json.obj()
          }
          val bodyAsBytes = response.select("body_bytes").asOpt[Array[Byte]].map(bytes => ByteString(bytes))
          val bodyBase64 = response.select("body_base64").asOpt[String].map(str => ByteString(str).decodeBase64)
          val bodyJson = response.select("body_json").asOpt[JsValue].map(str => ByteString(str.stringify))
          val bodyStr = response.select("body_str").asOpt[String].orElse(response.select("body").asOpt[String]).map(str => ByteString(str))
          val body: Source[ByteString, _] = bodyStr.orElse(bodyJson).orElse(bodyBase64).orElse(bodyAsBytes).getOrElse(ByteString.empty).chunks(16 * 1024)
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
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl, NgPluginCategory.Wasm)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Wasm Access control"
  override def description: Option[String]                 = "Delegate route access to a wasm plugin".some
  override def isAccessAsync: Boolean                      = true
  override def defaultConfigObject: Option[NgPluginConfig] = WasmConfig().some

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx
      .cachedConfig(internalName)(WasmConfig.format)
      .getOrElse(WasmConfig())

    WasmUtils
      .execute(config, "access", ctx.wasmJson, ctx.some, ctx.attrs.some)
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
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Wasm, NgPluginCategory.Transformations)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = true
  override def transformsResponse: Boolean                 = false
  override def transformsError: Boolean                    = false
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = false
  override def name: String                                = "Wasm Request Transformer"
  override def description: Option[String]                 =
    "Transform the content of the request with a wasm plugin".some
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
          .execute(config, "transform_request", input, ctx.some, ctx.attrs.some)
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
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Wasm, NgPluginCategory.Transformations)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = false
  override def transformsResponse: Boolean                 = true
  override def transformsError: Boolean                    = false
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = true
  override def name: String                                = "Wasm Response Transformer"
  override def description: Option[String]                 =
    "Transform the content of a response with a wasm plugin".some
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
          .execute(config, "transform_response", input, ctx.some, ctx.attrs.some)
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
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Wasm)
  override def steps: Seq[NgStep] = Seq(NgStep.Sink)
  override def multiInstance: Boolean = false
  override def core: Boolean = true
  override def name: String = "Wasm Sink"
  override def description: Option[String] = "Handle unmatched requests with a wasm plugin".some
  override def defaultConfigObject: Option[NgPluginConfig] = WasmConfig().some

  override def matches(ctx: NgRequestSinkContext)(implicit env: Env, ec: ExecutionContext): Boolean = {
    val config = WasmConfig.format.reads(ctx.config) match {
      case JsSuccess(value, _) => value
      case JsError(_) => WasmConfig()
    }
    val fu = WasmUtils.execute(config.copy(functionName = "sink_matches".some), "matches", ctx.wasmJson, FakeWasmContext(ctx.config).some, ctx.attrs.some)
      .map {
        case Left(error) => false
        case Right(res) => {
          val response = Json.parse(res)
          (response \ "result").asOpt[Boolean].getOrElse(false)
        }
      }
    Await.result(fu, 10.seconds)
  }

  private def requestToWasmJson(body: Source[ByteString, _])(implicit ec: ExecutionContext, env: Env): Future[JsValue] = {
    implicit val mat = env.otoroshiMaterializer
    body.runFold(ByteString.empty)(_ ++ _).map { rawBody =>
      Writes.arrayWrites[Byte].writes(rawBody.toArray[Byte])
    }
  }

  override def handleSync(ctx: NgRequestSinkContext)(implicit env: Env, ec: ExecutionContext): Result =
    Await.result(this.handle(ctx), 10.seconds)

  override def handle(ctx: NgRequestSinkContext)(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    val config = WasmConfig.format.reads(ctx.config) match {
      case JsSuccess(value, _) => value
      case JsError(_) => WasmConfig()
    }
    requestToWasmJson(ctx.body).flatMap { body =>
      val input = ctx.wasmJson.asObject ++ Json.obj("body_bytes" -> body)
      WasmUtils.execute(config, "sink_handle", input, FakeWasmContext(ctx.config).some, ctx.attrs.some)
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

            val bodyAsBytes = response.select("body_bytes").asOpt[Array[Byte]].map(bytes => ByteString(bytes))
            val bodyBase64 = response.select("body_base64").asOpt[String].map(str => ByteString(str).decodeBase64)
            val bodyJson = response.select("body_json").asOpt[JsValue].map(str => ByteString(str.stringify))
            val bodyStr = response.select("body_str").asOpt[String].orElse(response.select("body").asOpt[String]).map(str => ByteString(str))
            val body:ByteString = bodyStr.orElse(bodyJson).orElse(bodyBase64).orElse(bodyAsBytes).getOrElse(ByteString.empty)

            Results
              .Status(status)(body)
              .withHeaders(headers.toSeq: _*)
              .as(contentType)
          }
        }
    }
  }
}

class WasmRequestHandler extends RequestHandler {

  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def steps: Seq[NgStep] = Seq(NgStep.HandlesRequest)
  override def core: Boolean = true
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Wasm)
  override def description: Option[String] = "this plugin entirely handle request with a wasm plugin".some
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
          "request_body_bytes" -> rawBody.toArray[Byte]
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
              WasmUtils.execute(config, "handle_request", Json.obj("request" -> json), fakeCtx.some, None)
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

case class WasmJobsConfig(
  uniqueId: String = "1",
  config: WasmConfig = WasmConfig(),
  kind: JobKind = JobKind.ScheduledEvery,
  instantiation: JobInstantiation = JobInstantiation.OneInstancePerOtoroshiInstance,
  initialDelay: Option[FiniteDuration] = None,
  interval: Option[FiniteDuration] = None,
  cronExpression: Option[String] = None,
  rawConfig: JsValue = Json.obj()
) {
  def json: JsValue = Json.obj(
    "unique_id" -> uniqueId,
    "config" -> config.json,
    "kind" -> kind.name,
    "instantiation" -> instantiation.name,
    "initial_delay" -> initialDelay.map(_.toMillis).map(v => JsNumber(BigDecimal(v))).getOrElse(JsNull).asValue,
    "interval" -> interval.map(_.toMillis).map(v => JsNumber(BigDecimal(v))).getOrElse(JsNull).asValue,
    "cron_expression" -> cronExpression.map(JsString.apply).getOrElse(JsNull).asValue,
  )
}

object WasmJobsConfig {
  val default = WasmJobsConfig()
}

class WasmJob(config: WasmJobsConfig) extends Job {

  private val logger = Logger("otoroshi-wasm-job")
  private val attrs = TypedMap.empty

  override def core: Boolean = true
  override def name: String = "Wasm Job"
  override def description: Option[String] = "this job execute any given Wasm plugin".some
  override def defaultConfig: Option[JsObject] = WasmJobsConfig.default.json.asObject.some
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Wasm)
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def steps: Seq[NgStep] = Seq(NgStep.Job)
  override def jobVisibility: JobVisibility = JobVisibility.UserLand
  override def starting: JobStarting = JobStarting.Automatically

  override def uniqueId: JobId = JobId(s"io.otoroshi.next.plugins.wasm.WasmJob#${config.uniqueId}")
  override def kind: JobKind = config.kind
  override def instantiation(ctx: JobContext, env: Env): JobInstantiation = config.instantiation
  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = config.initialDelay
  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = config.interval
  override def cronExpression(ctx: JobContext, env: Env): Option[String] = config.cronExpression

  override def jobStart(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = Try {
    WasmUtils.execute(config.config.copy(functionName = "job_start".some), "job_start", ctx.wasmJson, FakeWasmContext(config.config.json).some, attrs.some).map {
      case Left(err) => logger.error(s"error while starting wasm job ${config.uniqueId}: ${err.stringify}")
      case Right(_) => ()
    }
  } match {
    case Failure(e) =>
      logger.error("error during wasm job start", e)
      funit
    case Success(s) => s
  }
  override def jobStop(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = Try {
    WasmUtils.execute(config.config.copy(functionName = "job_stop".some), "job_stop", ctx.wasmJson, FakeWasmContext(config.config.json).some, attrs.some).map {
      case Left(err) => logger.error(s"error while stopping wasm job ${config.uniqueId}: ${err.stringify}")
      case Right(_) => ()
    }
  } match {
    case Failure(e) =>
      logger.error("error during wasm job stop", e)
      funit
    case Success(s) => s
  }
  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = Try {
    WasmUtils.execute(config.config, "job_run", ctx.wasmJson, FakeWasmContext(config.config.json).some, attrs.some).map {
      case Left(err) => logger.error(s"error while running wasm job ${config.uniqueId}: ${err.stringify}")
      case Right(_) => ()
    }
  } match {
    case Failure(e) =>
      logger.error("error during wasm job run", e)
      funit
    case Success(s) => s
  }
}

class WasmJobsLauncher extends Job {

  override def core: Boolean = true
  override def name: String = "Wasm Jobs Launcher"
  override def description: Option[String] = "this job execute Wasm jobs".some
  override def defaultConfig: Option[JsObject] = None
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Other)
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgInternal
  override def steps: Seq[NgStep] = Seq(NgStep.Job)
  override def jobVisibility: JobVisibility = JobVisibility.Internal
  override def starting: JobStarting = JobStarting.Automatically
  override def uniqueId: JobId = JobId(s"io.otoroshi.next.plugins.wasm.WasmJobsLauncher")
  override def kind: JobKind = JobKind.ScheduledEvery
  override def instantiation(ctx: JobContext, env: Env): JobInstantiation = JobInstantiation.OneInstancePerOtoroshiInstance
  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 5.seconds.some
  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = 20.seconds.some
  override def cronExpression(ctx: JobContext, env: Env): Option[String] = None

  private val handledJobs = new TrieMap[String, Job]()

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = Try {
    val globalConfig = env.datastores.globalConfigDataStore.latest()
    val wasmJobs = globalConfig.plugins.ngPlugins().slots.filter(_.enabled).filter(_.plugin == s"cp:${classOf[WasmJob].getName}")
    val currentIds: Seq[String] = wasmJobs.map { job =>
      val actualJob = new WasmJob(WasmJobsConfig(
        uniqueId = job.config.raw.select("unique_id").asString,
        config = WasmConfig.format.reads(job.config.raw.select("config").asOpt[JsValue].getOrElse(Json.obj())).getOrElse(WasmConfig()),
        kind = JobKind(job.config.raw.select("kind").asString),
        instantiation = JobInstantiation(job.config.raw.select("instantiation").asString),
        initialDelay = job.config.raw.select("initial_delay").asOpt[Long].map(_.millis),
        interval = job.config.raw.select("interval").asOpt[Long].map(_.millis),
        cronExpression = job.config.raw.select("cron_expression").asOpt[String]
      ))
      val uniqueId: String = actualJob.uniqueId.id
      if (!handledJobs.contains(uniqueId)) {
        println(s"registzriung ${uniqueId}")
        handledJobs.put(uniqueId, actualJob)
        env.jobManager.registerJob(actualJob)
      }
      uniqueId
    }
    handledJobs.values.toSeq.foreach { job =>
      val id: String = job.uniqueId.id
      if (!currentIds.contains(id)) {
        handledJobs.remove(id)
        env.jobManager.unregisterJob(job)
      }
    }
    funit
  } match {
    case Failure(e) =>
      e.printStackTrace()
      funit
    case Success(s) => s
  }
}

class WasmOPA extends NgAccessValidator {

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl, NgPluginCategory.Wasm)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Open Policy Agent (OPA)"
  override def description: Option[String]                 = "Repo policies as WASM modules".some
  override def isAccessAsync: Boolean                      = true
  override def defaultConfigObject: Option[NgPluginConfig] = WasmConfig(
    opa = true
  ).some

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx
      .cachedConfig(internalName)(WasmConfig.format)
      .getOrElse(WasmConfig())

    WasmUtils
      .execute(config, "access", ctx.wasmJson, ctx.some, ctx.attrs.some)
      .flatMap {
        case Right(res)  =>
          val result = Json.parse(res).asOpt[JsArray].getOrElse(Json.arr())
          val canAccess = (result.value.head \ "result").asOpt[Boolean].getOrElse(false)
          if (canAccess) {
            NgAccess.NgAllowed.vfuture
          } else {
            NgAccess.NgDenied(Results.Forbidden).vfuture
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
