package otoroshi.wasm

import akka.util.ByteString
import org.extism.sdk.wasmotoroshi._
import otoroshi.env.Env
import otoroshi.models.{WSProxyServerJson, WasmManagerSettings}
import otoroshi.next.models.NgTlsConfig
import otoroshi.next.plugins.api._
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.http.MtlsConfig
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import java.nio.file.{Files, Paths}
import scala.concurrent.duration.{DurationLong, FiniteDuration, MILLISECONDS}
import scala.concurrent.{ExecutionContext, Future, Promise}
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
    def fetchAndAddToCache(): Future[Either[JsValue, ByteString]] = {
      val promise = Promise[Either[JsValue, ByteString]]()
      cache.put(cacheKey, CacheableWasmScript.FetchingWasmScript(promise.future))
      kind.getWasm(path, opts).map {
        case Left(err) =>
          promise.trySuccess(err.left)
          err.left
        case Right(bs) => {
          cache.put(cacheKey, CacheableWasmScript.CachedWasmScript(bs, System.currentTimeMillis()))
          promise.trySuccess(bs.right)
          bs.right
        }
      }
    }
    cache.get(cacheKey) match {
      case None                                                  => fetchAndAddToCache()
      case Some(CacheableWasmScript.FetchingWasmScript(fu))      => fu
      case Some(CacheableWasmScript.CachedWasmScript(script, createAt))
          if createAt + env.wasmCacheTtl < System.currentTimeMillis =>
        fetchAndAddToCache()
        script.right.vfuture
      case Some(CacheableWasmScript.CachedWasmScript(script, _)) => script.right.vfuture
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

sealed trait WasmVmLifetime {
  def name: String
  def json: JsValue = JsString(name)
}

object WasmVmLifetime       {

  case object Invocation extends WasmVmLifetime { def name: String = "Invocation" }
  case object Request    extends WasmVmLifetime { def name: String = "Request"    }
  case object Forever    extends WasmVmLifetime { def name: String = "Forever"    }

  def parse(str: String): Option[WasmVmLifetime] = str.toLowerCase() match {
    case "invocation" => Invocation.some
    case "request"    => Request.some
    case "forever"    => Forever.some
    case _            => None
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
    // lifetime: WasmVmLifetime = WasmVmLifetime.Forever,
    wasi: Boolean = false,
    opa: Boolean = false,
    instances: Int = 1,
    killOptions: WasmVmKillOptions = WasmVmKillOptions.default,
    authorizations: WasmAuthorizations = WasmAuthorizations()
) extends NgPluginConfig {
  // still here for compat reason
  def lifetime: WasmVmLifetime = WasmVmLifetime.Forever
  def pool()(implicit env: Env): WasmVmPool = WasmVmPool.forConfig(this)
  def json: JsValue = Json.obj(
    "source"         -> source.json,
    "memoryPages"    -> memoryPages,
    "functionName"   -> functionName,
    "config"         -> config,
    "allowedHosts"   -> allowedHosts,
    "allowedPaths"   -> allowedPaths,
    "wasi"           -> wasi,
    "opa"            -> opa,
    // "lifetime"       -> lifetime.json,
    "authorizations" -> authorizations.json,
    "instances"      -> instances,
    "killOptions"    -> killOptions.json,
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
        // lifetime = json
        //   .select("lifetime")
        //   .asOpt[String]
        //   .flatMap(WasmVmLifetime.parse)
        //   .orElse(
        //     (json \ "preserve").asOpt[Boolean].map {
        //       case true  => WasmVmLifetime.Request
        //       case false => WasmVmLifetime.Forever
        //     }
        //   )
        //   .getOrElse(WasmVmLifetime.Forever),
        authorizations = (json \ "authorizations")
          .asOpt[WasmAuthorizations](WasmAuthorizations.format.reads)
          .orElse((json \ "accesses").asOpt[WasmAuthorizations](WasmAuthorizations.format.reads))
          .getOrElse {
            WasmAuthorizations()
          },
        instances = json.select("instances").asOpt[Int].getOrElse(1),
        killOptions = json.select("killOptions").asOpt[JsValue].flatMap(v => WasmVmKillOptions.format.reads(v).asOpt).getOrElse(WasmVmKillOptions.default)
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
    override def writes(o: WasmConfig): JsValue             = o.json
  }
}

object ResultsWrapper                                                  {
  def apply(results: WasmOtoroshiResults): ResultsWrapper                 = new ResultsWrapper(results, None)
  def apply(results: WasmOtoroshiResults, plugin: WasmOtoroshiInstance): ResultsWrapper = new ResultsWrapper(results, Some(plugin))
}

case class ResultsWrapper(results: WasmOtoroshiResults, pluginOpt: Option[WasmOtoroshiInstance]) {
  def free(): Unit = try {
    if (results.getLength > 0) {
      results.close()
    }
  } catch {
    case t: Throwable =>
      t.printStackTrace()
      ()
  }
}

class WasmContext(plugins: UnboundedTrieMap[String, WasmContextSlot] = new UnboundedTrieMap[String, WasmContextSlot]()) {
  def put(id: String, slot: WasmContextSlot): Unit = plugins.put(id, slot)
  def get(id: String): Option[WasmContextSlot]     = plugins.get(id)
  def close(): Unit = {
    if (WasmUtils.logger.isDebugEnabled)
      WasmUtils.logger.debug(s"[WasmContext] will close ${plugins.size} wasm plugin instances")
    plugins.foreach(_._2.forceClose())
    plugins.clear()
  }
}

sealed trait CacheableWasmScript

object CacheableWasmScript {
  case class CachedWasmScript(script: ByteString, createAt: Long)       extends CacheableWasmScript
  case class FetchingWasmScript(f: Future[Either[JsValue, ByteString]]) extends CacheableWasmScript
}

