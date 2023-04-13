package otoroshi.wasm

import akka.util.ByteString
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import org.extism.sdk.manifest.{Manifest, MemoryOptions}
import org.extism.sdk.wasm.WasmSourceResolver
import org.extism.sdk.{Context, Plugin}
import otoroshi.env.Env
import otoroshi.models.{WSProxyServerJson, WasmManagerSettings}
import otoroshi.next.models.NgTlsConfig
import otoroshi.next.plugins.api._
import otoroshi.utils.TypedMap
import otoroshi.utils.http.MtlsConfig
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.libs.ws.{DefaultWSCookie, WSCookie}

import java.nio.file.{Files, Paths}
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{Executors, TimeUnit}
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{Duration, DurationLong, FiniteDuration, MILLISECONDS}
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
          "write" -> o.write
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
  def json: JsValue                                                                                               = JsString(name)
  def getWasm(path: String, opts: JsValue)(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, ByteString]]
  def getConfig(path: String, opts: JsValue)(implicit env: Env, ec: ExecutionContext): Future[Option[WasmConfig]] =
    None.vfuture
}
object WasmSourceKind       {
  case object Unknown     extends WasmSourceKind {
    def name: String = "Unknown"
    def getWasm(path: String, opts: JsValue)(implicit
                                             env: Env,
                                             ec: ExecutionContext
    ): Future[Either[JsValue, ByteString]] = {
      Left(Json.obj("error" -> "unknown source")).vfuture
    }
  }
  case object Base64      extends WasmSourceKind {
    def name: String = "Base64"
    def getWasm(path: String, opts: JsValue)(implicit
                                             env: Env,
                                             ec: ExecutionContext
    ): Future[Either[JsValue, ByteString]] = {
      ByteString(path.replace("base64://", "")).decodeBase64.right.future
    }
  }
  case object Http        extends WasmSourceKind {
    def name: String = "Http"
    def getWasm(path: String, opts: JsValue)(implicit
                                             env: Env,
                                             ec: ExecutionContext
    ): Future[Either[JsValue, ByteString]] = {
      val method         = opts.select("method").asOpt[String].getOrElse("GET")
      val headers        = opts.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty)
      val timeout        = opts.select("timeout").asOpt[Long].getOrElse(10000L).millis
      val followRedirect = opts.select("followRedirect").asOpt[Boolean].getOrElse(true)
      val proxy          = opts.select("proxy").asOpt[JsObject].flatMap(v => WSProxyServerJson.proxyFromJson(v))
      val tlsConfig      =
        opts.select("tls").asOpt(NgTlsConfig.format).map(_.legacy).orElse(opts.select("tls").asOpt(MtlsConfig.format))
      (tlsConfig match {
        case None      => env.Ws.url(path)
        case Some(cfg) => env.MtlsWs.url(path, cfg)
      })
        .withMethod(method)
        .withFollowRedirects(followRedirect)
        .withHttpHeaders(headers.toSeq: _*)
        .withRequestTimeout(timeout)
        .applyOnWithOpt(proxy) { case (req, proxy) =>
          req.withProxyServer(proxy)
        }
        .execute()
        .map { resp =>
          if (resp.status == 200) {
            val body = resp.bodyAsBytes
            Right(body)
          } else {
            val body: String = resp.body
            Left(
              Json.obj(
                "error"   -> "bad response",
                "status"  -> resp.status,
                "headers" -> resp.headers.mapValues(_.last),
                "body"    -> body
              )
            )
          }
        }
    }
  }
  case object WasmManager extends WasmSourceKind {
    def name: String = "WasmManager"
    def getWasm(path: String, opts: JsValue)(implicit
                                             env: Env,
                                             ec: ExecutionContext
    ): Future[Either[JsValue, ByteString]] = {
      env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig =>
        globalConfig.wasmManagerSettings match {
          case Some(WasmManagerSettings(url, clientId, clientSecret, kind)) => {
            // println(s"fechting the plugin at $path")
            env.Ws
              .url(s"$url/wasm/$path")
              .withFollowRedirects(false)
              .withRequestTimeout(FiniteDuration(5 * 1000, MILLISECONDS))
              .withHttpHeaders(
                "Accept"                 -> "application/json",
                "Otoroshi-Client-Id"     -> clientId,
                "Otoroshi-Client-Secret" -> clientSecret,
                "kind"                   -> kind.getOrElse("*")
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
          case _                                                            =>
            Left(Json.obj("error" -> "missing wasm manager url")).vfuture
        }
      }

    }
  }
  case object Local       extends WasmSourceKind {
    def name: String = "Local"
    override def getWasm(path: String, opts: JsValue)(implicit
                                                      env: Env,
                                                      ec: ExecutionContext
    ): Future[Either[JsValue, ByteString]] = {
      env.proxyState.wasmPlugin(path) match {
        case None         => Left(Json.obj("error" -> "resource not found")).vfuture
        case Some(plugin) => plugin.config.source.getWasm()
      }
    }
    override def getConfig(path: String, opts: JsValue)(implicit
                                                        env: Env,
                                                        ec: ExecutionContext
    ): Future[Option[WasmConfig]] = {
      env.proxyState.wasmPlugin(path).map(_.config).vfuture
    }
  }
  case object File        extends WasmSourceKind {
    def name: String = "File"
    def getWasm(path: String, opts: JsValue)(implicit
                                             env: Env,
                                             ec: ExecutionContext
    ): Future[Either[JsValue, ByteString]] = {
      Right(ByteString(Files.readAllBytes(Paths.get(path.replace("file://", ""))))).vfuture
    }
  }

  def apply(value: String): WasmSourceKind = value.toLowerCase match {
    case "base64"      => Base64
    case "http"        => Http
    case "wasmmanager" => WasmManager
    case "local"       => Local
    case "file"        => File
    case _             => Unknown
  }
}

case class WasmSource(kind: WasmSourceKind, path: String, opts: JsValue = Json.obj()) {
  def json: JsValue                                                                    = WasmSource.format.writes(this)
  def cacheKey                                                                         = s"${kind.name.toLowerCase}://${path}"
  def getConfig()(implicit env: Env, ec: ExecutionContext): Future[Option[WasmConfig]] = kind.getConfig(path, opts)
  def getWasm()(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, ByteString]] = {
    val cache = WasmUtils.scriptCache(env)
    cache.getIfPresent(cacheKey) match {
      case Some(script) => script.script.right.vfuture
      case None         => {
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
object WasmSource                                                                     {
  val format = new Format[WasmSource] {
    override def writes(o: WasmSource): JsValue             = Json.obj(
      "kind" -> o.kind.json,
      "path" -> o.path,
      "opts" -> o.opts
    )
    override def reads(json: JsValue): JsResult[WasmSource] = Try {
      WasmSource(
        kind = json.select("kind").asOpt[String].map(WasmSourceKind.apply).getOrElse(WasmSourceKind.Unknown),
        path = json.select("path").asString,
        opts = json.select("opts").asOpt[JsValue].getOrElse(Json.obj())
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
                               proxyHttpCallTimeout: Int = 5000
                             ) {
  def json: JsValue = WasmAuthorizations.format.writes(this)
}

object WasmAuthorizations {
  val format = new Format[WasmAuthorizations] {
    override def writes(o: WasmAuthorizations): JsValue             = Json.obj(
      "httpAccess"            -> o.httpAccess,
      "proxyHttpCallTimeout"  -> o.proxyHttpCallTimeout,
      "globalDataStoreAccess" -> WasmDataRights.fmt.writes(o.globalDataStoreAccess),
      "pluginDataStoreAccess" -> WasmDataRights.fmt.writes(o.pluginDataStoreAccess),
      "globalMapAccess"       -> WasmDataRights.fmt.writes(o.globalMapAccess),
      "pluginMapAccess"       -> WasmDataRights.fmt.writes(o.pluginMapAccess),
      "proxyStateAccess"      -> o.proxyStateAccess,
      "configurationAccess"   -> o.configurationAccess
    )
    override def reads(json: JsValue): JsResult[WasmAuthorizations] = Try {
      WasmAuthorizations(
        httpAccess = (json \ "httpAccess").asOpt[Boolean].getOrElse(false),
        proxyHttpCallTimeout = (json \ "proxyHttpCallTimeout").asOpt[Int].getOrElse(5000),
        globalDataStoreAccess = (json \ "globalDataStoreAccess")
          .asOpt[WasmDataRights](WasmDataRights.fmt.reads)
          .getOrElse(WasmDataRights()),
        pluginDataStoreAccess = (json \ "pluginDataStoreAccess")
          .asOpt[WasmDataRights](WasmDataRights.fmt.reads)
          .getOrElse(WasmDataRights()),
        globalMapAccess = (json \ "globalMapAccess")
          .asOpt[WasmDataRights](WasmDataRights.fmt.reads)
          .getOrElse(WasmDataRights()),
        pluginMapAccess = (json \ "pluginMapAccess")
          .asOpt[WasmDataRights](WasmDataRights.fmt.reads)
          .getOrElse(WasmDataRights()),
        proxyStateAccess = (json \ "proxyStateAccess").asOpt[Boolean].getOrElse(false),
        configurationAccess = (json \ "configurationAccess").asOpt[Boolean].getOrElse(false)
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
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
                       authorizations: WasmAuthorizations = WasmAuthorizations()
                     ) extends NgPluginConfig {
  def json: JsValue = Json.obj(
    "source"         -> source.json,
    "memoryPages"    -> memoryPages,
    "functionName"   -> functionName,
    "config"         -> config,
    "allowedHosts"   -> allowedHosts,
    "allowedPaths"   -> allowedPaths,
    "wasi"           -> wasi,
    "opa"            -> opa,
    "preserve"       -> preserve,
    "authorizations" -> authorizations.json
  )
}

object WasmConfig {
  val format = new Format[WasmConfig] {
    override def reads(json: JsValue): JsResult[WasmConfig] = Try {
      val compilerSource = json.select("compiler_source").asOpt[String]
      val rawSource      = json.select("raw_source").asOpt[String]
      val sourceOpt      = json.select("source").asOpt[JsObject]
      val source         = if (sourceOpt.isDefined) {
        WasmSource.format.reads(sourceOpt.get).get
      } else {
        compilerSource match {
          case Some(source) => WasmSource(WasmSourceKind.WasmManager, source)
          case None         =>
            rawSource match {
              case Some(source) if source.startsWith("http://")   => WasmSource(WasmSourceKind.Http, source)
              case Some(source) if source.startsWith("https://")  => WasmSource(WasmSourceKind.Http, source)
              case Some(source) if source.startsWith("file://")   =>
                WasmSource(WasmSourceKind.File, source.replace("file://", ""))
              case Some(source) if source.startsWith("base64://") =>
                WasmSource(WasmSourceKind.Base64, source.replace("base64://", ""))
              case Some(source) if source.startsWith("entity://") =>
                WasmSource(WasmSourceKind.Local, source.replace("entity://", ""))
              case Some(source) if source.startsWith("local://")  =>
                WasmSource(WasmSourceKind.Local, source.replace("local://", ""))
              case Some(source)                                   => WasmSource(WasmSourceKind.Base64, source)
              case _                                              => WasmSource(WasmSourceKind.Unknown, "")
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
        authorizations = (json \ "authorizations")
          .asOpt[WasmAuthorizations](WasmAuthorizations.format.reads)
          .orElse((json \ "accesses").asOpt[WasmAuthorizations](WasmAuthorizations.format.reads))
          .getOrElse {
            println("pouet")
            WasmAuthorizations()
          }
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
    override def writes(o: WasmConfig): JsValue             = o.json
  }
}

case class WasmContextSlot(manifest: Manifest, context: Context, plugin: Plugin)                      {
  def close(): Unit = {
    //    plugin.free()
    context.free()
  }
}
class WasmContext(plugins: TrieMap[String, WasmContextSlot] = new TrieMap[String, WasmContextSlot]()) {
  def put(id: String, slot: WasmContextSlot): Unit = plugins.put(id, slot)
  def get(id: String): Option[WasmContextSlot]     = plugins.get(id)
  def close(): Unit = {
    if (WasmUtils.logger.isDebugEnabled)
      WasmUtils.logger.debug(s"[WasmContext] will close ${plugins.size} wasm plugin instances")
    plugins.foreach(_._2.close())
    plugins.clear()
  }
}

case class CachedWasmScript(script: ByteString, createAt: Long)

object WasmUtils {

  private[wasm] val logger = Logger("otoroshi-wasm")

  implicit val executor = ExecutionContext.fromExecutorService(
    Executors.newWorkStealingPool((Runtime.getRuntime.availableProcessors * 4) + 1)
  )

  private val _cache: AtomicReference[Cache[String, CachedWasmScript]] =
    new AtomicReference[Cache[String, CachedWasmScript]]()

  def scriptCache(implicit env: Env) = {
    Option(_cache.get()) match {
      case Some(value) => value
      case None        =>
        _cache.set(
          Scaffeine()
            .recordStats()
            .expireAfterWrite(Duration(env.wasmCacheTtl, TimeUnit.MILLISECONDS))
            .maximumSize(env.wasmCacheSize)
            .build[String, CachedWasmScript]
        )
        _cache.get()
    }
  }

  def convertJsonCookies(wasmResponse: JsValue): Option[Seq[WSCookie]] =
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

  private def callWasm(
                        wasm: ByteString,
                        config: WasmConfig,
                        defaultFunctionName: String,
                        input: JsValue,
                        ctx: Option[NgCachedConfigContext] = None,
                        pluginId: String,
                        attrsOpt: Option[TypedMap]
                      )(implicit env: Env): Either[JsValue, String] = {
    val functionName = config.functionName.filter(_.nonEmpty).getOrElse(defaultFunctionName)
    try {
      def createPlugin(): WasmContextSlot = {
        if (WasmUtils.logger.isDebugEnabled)
          WasmUtils.logger.debug(s"creating wasm plugin instance for ${config.source.cacheKey}")
        // println(s"""creating plugin with wasm with wasi at "${config.wasi}" of "${wasm.size}" bytes""")
        val resolver = new WasmSourceResolver()
        val source   = resolver.resolve("wasm", wasm.toByteBuffer.array())
        val manifest = new Manifest(
          Seq[org.extism.sdk.wasm.WasmSource](source).asJava,
          new MemoryOptions(config.memoryPages),
          config.config.asJava,
          config.allowedHosts.asJava
          // config.allowedPaths.asJava, // TODO: uncomment when new lib version available
        )

        val context = new Context()
        val plugin  = context.newPlugin(
          manifest,
          config.wasi,
          HostFunctions.getFunctions(config, ctx, pluginId, attrsOpt),
          LinearMemories.getMemories(config, ctx, pluginId)
        )
        WasmContextSlot(manifest, context, plugin)
      }

      attrsOpt match {
        case None        => {
          val slot = createPlugin()

          val output = if (config.opa) {
            OPA.evalute(slot.plugin, input.stringify)
          } else {
            slot.plugin.call(functionName, input.stringify)
          }
          slot.close()
          output.right
        }
        case Some(attrs) => {
          val context = attrs.get(otoroshi.next.plugins.Keys.WasmContextKey) match {
            case None          => {
              val context = new WasmContext()
              attrs.put(otoroshi.next.plugins.Keys.WasmContextKey -> context)
              context
            }
            case Some(context) => context
          }
          context.get(config.source.cacheKey) match {
            case None         => {
              val slot   = createPlugin()
              if (config.preserve) context.put(config.source.cacheKey, slot)
              val output = if (config.opa) {
                OPA.evalute(slot.plugin, input.stringify)
              } else {
                slot.plugin.call(functionName, input.stringify)
              }
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
        Json
          .obj(
            "error"             -> "wasm_error",
            "error_description" -> JsArray(e.getMessage.split("\\n").filter(_.trim.nonEmpty).map(JsString.apply))
          )
          .left
      case e: Throwable =>
        logger.error(s"error while invoking wasm function '${functionName}'", e)
        Json.obj("error" -> "wasm_error", "error_description" -> JsString(e.getMessage)).left
    }
  }

  def executeSync(
                   config: WasmConfig,
                   defaultFunctionName: String,
                   input: JsValue,
                   ctx: Option[NgCachedConfigContext],
                   attrs: Option[TypedMap],
                   atMost: Duration
                 )(implicit env: Env): Either[JsValue, String] = {
    Await.result(execute(config, defaultFunctionName, input, ctx, attrs)(env), atMost)
  }

  def execute(
               config: WasmConfig,
               defaultFunctionName: String,
               input: JsValue,
               ctx: Option[NgCachedConfigContext],
               attrs: Option[TypedMap]
             )(implicit env: Env): Future[Either[JsValue, String]] = {
    val pluginId = config.source.cacheKey
    scriptCache.getIfPresent(pluginId) match {
      case Some(wasm)                                           =>
        // println(s"\n\nusing script from ${new DateTime(wasm.createAt).toString()}\n")
        config.source.getConfig().map {
          case None              => WasmUtils.callWasm(wasm.script, config, defaultFunctionName, input, ctx, pluginId, attrs)
          case Some(finalConfig) =>
            val functionName = config.functionName.filter(_.nonEmpty).orElse(finalConfig.functionName)
            WasmUtils.callWasm(
              wasm.script,
              finalConfig.copy(functionName = functionName),
              defaultFunctionName,
              input,
              ctx,
              pluginId,
              attrs
            )
        }
      case None if config.source.kind == WasmSourceKind.Unknown => Left(Json.obj("error" -> "missing source")).future
      case _                                                    =>
        config.source.getWasm().flatMap {
          case Left(err)   => err.left.vfuture
          case Right(wasm) => {
            config.source.getConfig().map {
              case None              => WasmUtils.callWasm(wasm, config, defaultFunctionName, input, ctx, pluginId, attrs)
              case Some(finalConfig) =>
                val functionName = config.functionName.filter(_.nonEmpty).orElse(finalConfig.functionName)
                WasmUtils.callWasm(
                  wasm,
                  finalConfig.copy(functionName = functionName),
                  defaultFunctionName,
                  input,
                  ctx,
                  pluginId,
                  attrs
                )
            }
          }
        }
    }
  }
}