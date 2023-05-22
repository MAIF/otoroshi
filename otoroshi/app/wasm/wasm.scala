package otoroshi.wasm

import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Keep, Sink, Source, SourceQueueWithComplete}
import akka.util.ByteString
import org.extism.sdk.manifest.{Manifest, MemoryOptions}
import org.extism.sdk.parameters.{Parameters, Results}
import org.extism.sdk.wasm.WasmSourceResolver
import org.extism.sdk.{Context, HostFunction, HostUserData, Plugin}
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.models.{WSProxyServerJson, WasmManagerSettings}
import otoroshi.next.models.NgTlsConfig
import otoroshi.next.plugins.api._
import otoroshi.security.IdGenerator
import otoroshi.utils.TypedMap
import otoroshi.utils.http.MtlsConfig
import otoroshi.utils.syntax.implicits._
import otoroshi.wasm.proxywasm.Result
import otoroshi.wasm.proxywasm.VmData
import play.api.Logger
import play.api.libs.json._
import play.api.libs.ws.{DefaultWSCookie, WSCookie}
import play.api.mvc.Cookie

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.concurrent.Executors
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{Duration, DurationLong, FiniteDuration, MILLISECONDS}
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
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
      case None         => fetchAndAddToCache()
      case Some(CacheableWasmScript.FetchingWasmScript(fu)) => fu
      case Some(CacheableWasmScript.CachedWasmScript(script, createAt)) if createAt + env.wasmCacheTtl < System.currentTimeMillis =>
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
object WasmVmLifetime {

  case object Invocation extends WasmVmLifetime { def name: String = "Invocation" }
  case object Request extends WasmVmLifetime { def name: String = "Request" }
  case object Forever extends WasmVmLifetime { def name: String = "Forever" }

  def parse(str: String): Option[WasmVmLifetime] = str.toLowerCase() match {
    case "invocation" => Invocation.some
    case "request" => Request.some
    case "forever" => Forever.some
    case _ => None
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
    lifetime: WasmVmLifetime = WasmVmLifetime.Forever,
    wasi: Boolean = false,
    opa: Boolean = false,
    instances: Int = 1,
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
    "lifetime"       -> lifetime.json,
    "authorizations" -> authorizations.json,
    "instances"      -> instances,
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
        lifetime = json.select("lifetime").asOpt[String].flatMap(WasmVmLifetime.parse).orElse(
          (json \ "preserve").asOpt[Boolean].map {
            case true => WasmVmLifetime.Request
            case false => WasmVmLifetime.Forever
          }
        ).getOrElse(WasmVmLifetime.Forever),
        authorizations = (json \ "authorizations")
          .asOpt[WasmAuthorizations](WasmAuthorizations.format.reads)
          .orElse((json \ "accesses").asOpt[WasmAuthorizations](WasmAuthorizations.format.reads))
          .getOrElse {
            WasmAuthorizations()
          },
        instances = json.select("instances").asOpt[Int].getOrElse(1)
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
    override def writes(o: WasmConfig): JsValue             = o.json
  }
}

object WasmContextSlot {
  private val _currentContext = new ThreadLocal[Any]()
  def getCurrentContext(): Option[Any] = Option(_currentContext.get())
  private def setCurrentContext(value: Any): Unit = _currentContext.set(value)
  private def clearCurrentContext(): Unit = _currentContext.remove()
}

class WasmContextSlot(id: String, instance: Int, context: Context, plugin: Plugin, cfg: WasmConfig, wsm: ByteString, closed: AtomicBoolean, updating: AtomicBoolean, instanceId: String, functions: Array[HostFunction[_ <: HostUserData]]) {

  def callSync(functionName: String, input: Option[String], parameters: Option[Parameters], resultSize: Option[Int], context: Option[VmData])
              (implicit env: Env, ec: ExecutionContext): Either[JsValue, (String, Results)] = {
    if (closed.get()) {
      val plug = WasmUtils.pluginCache.apply(s"$id-$instance")
      plug.callSync(functionName, input, parameters, resultSize, context)
    } else {
      try {
        context.foreach(ctx => WasmContextSlot.setCurrentContext(ctx))
        if (WasmUtils.logger.isDebugEnabled) WasmUtils.logger.debug(s"calling instance $id-$instance")
        WasmUtils.debugLog.debug(s"calling '${functionName}' on instance '$id-$instance'")
        val res: Either[JsValue, (String, Results)] = env.metrics.withTimer("otoroshi.wasm.core.call") {
          // TODO: need to split this !!
          (input, parameters, resultSize) match {
            case (Some(in), Some(p), Some(s)) => plugin.call(functionName, p, s, in.getBytes(StandardCharsets.UTF_8)).right.map(res => ("", res))
            case (_, Some(p), None) =>
              plugin.callWithoutResults(functionName, p)
              Right[JsValue, (String, Results)](("", new Results(0)))
            case (_, Some(p), Some(s)) => plugin.call(functionName, p, s).right.map(res => ("", res))
            case (_, None, Some(s)) => plugin.callWithoutParams(functionName, s).right.map(_ => ("", new Results(0)))
            case (Some(in), None, None) => plugin.call(functionName, in).right.map(str => (str, new Results(0)))
            case _ => Left(Json.obj("error" -> "bad call combination"))
          }
        }
        env.metrics.withTimer("otoroshi.wasm.core.reset") {
          plugin.reset()
          // TODO: need to do that at some point
          // res match {
          //   case Left(_) =>
          //   case Right((_, results)) => plugin.freeResults(results)
          // }
        }
        env.metrics.withTimer("otoroshi.wasm.core.count-thunks") {
          WasmUtils.logger.debug(s"thunks: ${functions.size}")
        }
        res
      } catch {
        case e: Throwable if e.getMessage.contains("wasm backtrace") =>
          WasmUtils.logger.error(s"error while invoking wasm function '${functionName}'", e)
          Json
            .obj(
              "error" -> "wasm_error",
              "error_description" -> JsArray(e.getMessage.split("\\n").filter(_.trim.nonEmpty).map(JsString.apply))
            )
            .left
        case e: Throwable =>
          WasmUtils.logger.error(s"error while invoking wasm function '${functionName}'", e)
          Json.obj("error" -> "wasm_error", "error_description" -> JsString(e.getMessage)).left
      } finally {
        context.foreach(ctx => WasmContextSlot.clearCurrentContext())
      }
    }
  }

  def callOpaSync(input: String)(implicit env: Env, ec: ExecutionContext): Either[JsValue, String] = {
    if (closed.get()) {
      val plug = WasmUtils.pluginCache.apply(s"$id-$instance")
      plug.callOpaSync(input)
    } else {
      try {
        val res = env.metrics.withTimer("otoroshi.wasm.core.call-opa") {
          OPA.evaluate(plugin, input)
        }
        env.metrics.withTimer("otoroshi.wasm.core.reset") {
          plugin.reset()
        }
        res.right
      } catch {
        case e: Throwable if e.getMessage.contains("wasm backtrace") =>
          WasmUtils.logger.error(s"error while invoking wasm function 'opa'", e)
          Json
            .obj(
              "error" -> "wasm_error",
              "error_description" -> JsArray(e.getMessage.split("\\n").filter(_.trim.nonEmpty).map(JsString.apply))
            )
            .left
        case e: Throwable =>
          WasmUtils.logger.error(s"error while invoking wasm function 'opa'", e)
          Json.obj("error" -> "wasm_error", "error_description" -> JsString(e.getMessage)).left
      }
    }
  }

  def call(functionName: String, input: Option[String], parameters: Option[Parameters], resultSize: Option[Int], context: Option[VmData])(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, (String, Results)]] = {
    val promise = Promise.apply[Either[JsValue, (String, Results)]]()
    WasmUtils.getInvocationQueueFor(id, instance).offer(WasmAction.WasmInvocation(() => callSync(functionName, input, parameters, resultSize, context), promise))
    promise.future
  }

  def callOpa(input: String)(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, String]] = {
    val promise = Promise.apply[Either[JsValue, String]]()
    WasmUtils.getInvocationQueueFor(id, instance).offer(WasmAction.WasmOpaInvocation(() => callOpaSync(input), promise))
    promise.future
  }

  def close(lifetime: WasmVmLifetime): Unit = {
    if (lifetime == WasmVmLifetime.Invocation) {
      if (WasmUtils.logger.isDebugEnabled) WasmUtils.logger.debug(s"calling close on WasmContextSlot of ${id}")
      forceClose()
    }
  }

  def forceClose(): Unit = {
    if (WasmUtils.logger.isDebugEnabled) WasmUtils.logger.debug(s"calling forceClose on WasmContextSlot of ${id}")
    if (closed.compareAndSet(false, true)) {
      try {
        plugin.close()
        context.free()
      } catch {
        case e: Throwable => e.printStackTrace()
      }
    }
  }

  def needsUpdate(wasmConfig: WasmConfig, wasm: ByteString): Boolean = {
    val configHasChanged = wasmConfig !=  cfg
    val wasmHasChanged = wasm != wsm
    if (WasmUtils.logger.isDebugEnabled && configHasChanged) WasmUtils.logger.debug(s"plugin ${id} needs update because of config change")
    if (WasmUtils.logger.isDebugEnabled && wasmHasChanged) WasmUtils.logger.debug(s"plugin ${id} needs update because of wasm change")
    configHasChanged || wasmHasChanged
  }

  def updateIfNeeded(pluginId: String, config: WasmConfig, wasm: ByteString, attrsOpt: Option[TypedMap], addHostFunctions: Seq[HostFunction[_ <: HostUserData]])(implicit env: Env, ec: ExecutionContext): WasmContextSlot = {
    if (needsUpdate(config, wasm) && updating.compareAndSet(false, true)) {

      if (config.instances < cfg.instances) {
        env.otoroshiActorSystem.scheduler.scheduleOnce(20.seconds) { // TODO: config ?
          if (WasmUtils.logger.isDebugEnabled) WasmUtils.logger.debug(s"trying to kill unused instances of ${pluginId}")
          (config.instances to cfg.instances).map { idx =>
            WasmUtils.pluginCache.get(s"${pluginId}-${instance}").foreach(p => p.forceClose())
            WasmUtils.queues.remove(s"${pluginId}-${instance}")
            WasmUtils.pluginCache.remove(s"$pluginId-$instance")
          }
        }
      }
      if (WasmUtils.logger.isDebugEnabled) WasmUtils.logger.debug(s"scheduling update ${instanceId}")
      WasmUtils.getInvocationQueueFor(id, instance).offer(WasmAction.WasmUpdate(() => {
        val plugin = WasmUtils.actuallyCreatePlugin(
          instance,
          wasm,
          config,
          pluginId,
          attrsOpt,
          addHostFunctions,
        )
        if (WasmUtils.logger.isDebugEnabled) WasmUtils.logger.debug(s"updating ${instanceId}")
        WasmUtils.pluginCache.put(s"$pluginId-$instance", plugin)
        env.otoroshiActorSystem.scheduler.scheduleOnce(20.seconds) { // TODO: config ?
          if (WasmUtils.logger.isDebugEnabled) WasmUtils.logger.debug(s"delayed force close ${instanceId}")
          if (!closed.get()) {
            forceClose()
          }
        }
      }))
    }
    this
  }
}
class WasmContext(plugins: TrieMap[String, WasmContextSlot] = new TrieMap[String, WasmContextSlot]()) {
  def put(id: String, slot: WasmContextSlot): Unit = plugins.put(id, slot)
  def get(id: String): Option[WasmContextSlot]     = plugins.get(id)
  def close(): Unit = {
    if (WasmUtils.logger.isDebugEnabled) WasmUtils.logger.debug(s"[WasmContext] will close ${plugins.size} wasm plugin instances")
    plugins.foreach(_._2.forceClose())
    plugins.clear()
  }
}

sealed trait WasmAction
object WasmAction {
  case class WasmOpaInvocation(call: () => Either[JsValue, String], promise: Promise[Either[JsValue, String]]) extends WasmAction
  case class WasmInvocation(call: () => Either[JsValue, (String, Results)], promise: Promise[Either[JsValue, (String, Results)]]) extends WasmAction
  case class WasmUpdate(call: () => Unit) extends WasmAction
}


sealed trait CacheableWasmScript
object CacheableWasmScript {
  case class CachedWasmScript(script: ByteString, createAt: Long) extends CacheableWasmScript
  case class FetchingWasmScript(f: Future[Either[JsValue, ByteString]]) extends CacheableWasmScript
}

object WasmUtils {

  private[wasm] val logger = Logger("otoroshi-wasm")

  val debugLog = Logger("otoroshi-wasm-debug")

  implicit val executor = ExecutionContext.fromExecutorService(
    Executors.newWorkStealingPool((Runtime.getRuntime.availableProcessors * 4) + 1)
  )

  // TODO: handle env.wasmCacheSize based on creation date ?
  private[wasm] val _script_cache: TrieMap[String, CacheableWasmScript] = new TrieMap[String, CacheableWasmScript]()
  private[wasm] val pluginCache = new TrieMap[String, WasmContextSlot]()
  private[wasm] val queues = new TrieMap[String, (DateTime, SourceQueueWithComplete[WasmAction])]()
  private[wasm] val instancesCounter = new AtomicInteger(0)


  def scriptCache(implicit env: Env): TrieMap[String, CacheableWasmScript] = _script_cache

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

  def convertJsonPlayCookies(wasmResponse: JsValue): Option[Seq[Cookie]] =
    wasmResponse
      .select("cookies")
      .asOpt[Seq[JsObject]]
      .map { arr =>
        arr.map { c =>
          Cookie(
            name = c.select("name").asString,
            value = c.select("value").asString,
            maxAge = c.select("maxAge").asOpt[Int],
            path = c.select("path").asOpt[String].getOrElse("/"),
            domain = c.select("domain").asOpt[String],
            secure = c.select("secure").asOpt[Boolean].getOrElse(false),
            httpOnly = c.select("httpOnly").asOpt[Boolean].getOrElse(false),
            sameSite = c.select("domain").asOpt[String].flatMap(Cookie.SameSite.parse)
          )
        }
      }

  private[wasm] def getInvocationQueueFor(id: String, instance: Int)(implicit env: Env): SourceQueueWithComplete[WasmAction] = {
    val key = s"$id-$instance"
    queues.getOrUpdate(key) {
      val stream = Source
        .queue[WasmAction](env.wasmQueueBufferSize, OverflowStrategy.dropHead)
        .mapAsync(1) { action =>
          Future.apply {
              action match {
                case WasmAction.WasmInvocation(invoke, promise) => try {
                  val res = invoke()
                  promise.trySuccess(res)
                } catch {
                  case e: Throwable => promise.tryFailure(e)
                }
                case WasmAction.WasmOpaInvocation(invoke, promise) => try {
                  val res = invoke()
                  promise.trySuccess(res)
                } catch {
                  case e: Throwable => promise.tryFailure(e)
                }
                case WasmAction.WasmUpdate(update) => try {
                  update()
                } catch {
                  case e: Throwable => e.printStackTrace()
                }
              }
          }(executor)
        }
      (DateTime.now(), stream.toMat(Sink.ignore)(Keep.both).run()(env.otoroshiMaterializer)._1)
    }
  }._2

  private[wasm] def internalCreateManifest(config: WasmConfig, wasm: ByteString, env: Env) = env.metrics.withTimer("otoroshi.wasm.core.create-plugin.manifest") {
    val resolver = new WasmSourceResolver()
    val source = resolver.resolve("wasm", wasm.toByteBuffer.array())
    new Manifest(
      Seq[org.extism.sdk.wasm.WasmSource](source).asJava,
      new MemoryOptions(config.memoryPages),
      config.config.asJava,
      config.allowedHosts.asJava,
      config.allowedPaths.asJava,
    )
  }

  private[wasm] def actuallyCreatePlugin(
    instance: Int,
    wasm: ByteString,
    config: WasmConfig,
    pluginId: String,
    attrsOpt: Option[TypedMap],
    addHostFunctions: Seq[HostFunction[_ <: HostUserData]],
  )(implicit env: Env, ec: ExecutionContext): WasmContextSlot = env.metrics.withTimer("otoroshi.wasm.core.act-create-plugin") {
    if (WasmUtils.logger.isDebugEnabled)
      WasmUtils.logger.debug(s"creating wasm plugin instance for ${pluginId}")
    val manifest = internalCreateManifest(config, wasm, env)
    val context = env.metrics.withTimer("otoroshi.wasm.core.create-plugin.context")(new Context())
    val functions: Array[HostFunction[_ <: HostUserData]] = HostFunctions.getFunctions(config, pluginId, attrsOpt) ++ addHostFunctions
    val plugin = env.metrics.withTimer("otoroshi.wasm.core.create-plugin.plugin") {
      context.newPlugin(
        manifest,
        config.wasi,
        functions,
        LinearMemories.getMemories(config)
      )
    }
    new WasmContextSlot(
      pluginId,
      instance,
      context,
      plugin,
      config,
      wasm,
      functions = functions,
      closed = new AtomicBoolean(false),
      updating = new AtomicBoolean(false),
      instanceId = IdGenerator.uuid,
    )
  }

  private def callWasm(
      wasm: ByteString,
      config: WasmConfig,
      defaultFunctionName: String,
      input: Option[JsValue],
      parameters: Option[Parameters],
      resultSize: Option[Int],
      pluginId: String,
      attrsOpt: Option[TypedMap],
      ctx: Option[VmData],
      addHostFunctions: Seq[HostFunction[_ <: HostUserData]],
  )(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, (String, Results)]] = env.metrics.withTimerAsync("otoroshi.wasm.core.call-wasm") {

    WasmUtils.debugLog.debug("callWasm")

    val functionName = config.functionName.filter(_.nonEmpty).getOrElse(defaultFunctionName)
    val instance = instancesCounter.incrementAndGet() % config.instances

    def createPlugin(): WasmContextSlot = {
      if (config.lifetime == WasmVmLifetime.Forever) {
        pluginCache.getOrUpdate(s"$pluginId-$instance") {
          actuallyCreatePlugin(instance, wasm, config, pluginId, None, addHostFunctions)
        }.seffectOn(_.updateIfNeeded(pluginId, config, wasm, None, addHostFunctions))
      } else {
        actuallyCreatePlugin(instance, wasm, config, pluginId, attrsOpt, addHostFunctions)
      }
    }

    attrsOpt match {
      case None        => {
        val slot   = createPlugin()
        if (config.opa) {
          slot.callOpa(input.get.stringify).map { output =>
            slot.close(config.lifetime)
            output.map(str => (str, new Results(0)))
          }
        } else {
          slot.call(functionName, input.map(_.stringify), parameters, resultSize, ctx).map { output =>
            slot.close(config.lifetime)
            output
          }
        }
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
        val slot = context.get(pluginId) match {
          case None => {
            val plugin = createPlugin()
            if (config.lifetime == WasmVmLifetime.Invocation) context.put(pluginId, plugin)
            plugin
          }
          case Some(plugin) => plugin
        }
        if (config.opa) {
          slot.callOpa(input.get.stringify).map { output =>
            slot.close(config.lifetime)
            output.map(str => (str, new Results(0)))
          }
        } else {
          slot.call(functionName, input.map(_.stringify), parameters, resultSize, ctx).map { output =>
            slot.close(config.lifetime)
            output
          }
        }
      }
    }
  }

  // def executeSync(
  //     config: WasmConfig,
  //     defaultFunctionName: String,
  //     input: Option[JsValue],
  //     parameters: Option[Parameters],
  //     resultSize: Option[Int],
  //     attrs: Option[TypedMap],
  //     ctx: Option[VmData],
  //     atMost: Duration,
  //     addHostFunctions: Seq[HostFunction[_ <: HostUserData]],
  // )(implicit env: Env): Either[JsValue, (String, Results)] = {
  //   Await.result(execute(config, defaultFunctionName, input, parameters, resultSize, attrs, ctx, addHostFunctions)(env), atMost)
  // }

  def execute(
    config: WasmConfig,
    defaultFunctionName: String,
    input: JsValue,
    attrs: Option[TypedMap],
    ctx: Option[VmData],
  )(implicit env: Env): Future[Either[JsValue, String]] = {
    rawExecute(config, defaultFunctionName, input.some, None, None, attrs, ctx, Seq.empty).map(r => r.map(_._1))
  }

  def rawExecute(
      config: WasmConfig,
      defaultFunctionName: String,
      input: Option[JsValue],
      parameters: Option[Parameters],
      resultSize: Option[Int],
      attrs: Option[TypedMap],
      ctx: Option[VmData],
      addHostFunctions: Seq[HostFunction[_ <: HostUserData]],
  )(implicit env: Env): Future[Either[JsValue, (String, Results)]] = env.metrics.withTimerAsync("otoroshi.wasm.core.raw-execute") {
    WasmUtils.debugLog.debug("execute")
    val pluginId = config.source.kind match {
      case WasmSourceKind.Local => {
        env.proxyState.wasmPlugin(config.source.path) match {
          case None => config.source.cacheKey
          case Some(plugin) => plugin.config.source.cacheKey
        }
      }
      case _ => config.source.cacheKey
    }
    scriptCache.get(pluginId) match {
      case Some(CacheableWasmScript.FetchingWasmScript(fu)) => fu.flatMap { _ =>
        rawExecute(config, defaultFunctionName, input, parameters, resultSize, attrs, ctx, addHostFunctions)
      }
      case Some(CacheableWasmScript.CachedWasmScript(script, _))                                           => {
        env.metrics.withTimerAsync("otoroshi.wasm.core.get-config")(config.source.getConfig()).flatMap {
          case None => WasmUtils.callWasm(script, config, defaultFunctionName, input, parameters, resultSize, pluginId, attrs, ctx, addHostFunctions)
          case Some(finalConfig) =>
            val functionName = config.functionName.filter(_.nonEmpty).orElse(finalConfig.functionName)
            WasmUtils.callWasm(
              script,
              finalConfig.copy(functionName = functionName),
              defaultFunctionName,
              input,
              parameters, resultSize,
              pluginId,
              attrs,
              ctx,
              addHostFunctions,
            )
        }
      }
      case None if config.source.kind == WasmSourceKind.Unknown => Left(Json.obj("error" -> "missing source")).future
      case _                                                    =>
        env.metrics.withTimerAsync("otoroshi.wasm.core.get-wasm")(config.source.getWasm()).flatMap {
          case Left(err)   => err.left.vfuture
          case Right(wasm) => {
            env.metrics.withTimerAsync("otoroshi.wasm.core.get-config")(config.source.getConfig()).flatMap {
              case None              => WasmUtils.callWasm(wasm, config, defaultFunctionName, input, parameters, resultSize, pluginId, attrs, ctx, addHostFunctions)
              case Some(finalConfig) =>
                val functionName = config.functionName.filter(_.nonEmpty).orElse(finalConfig.functionName)
                WasmUtils.callWasm(
                  wasm,
                  finalConfig.copy(functionName = functionName),
                  defaultFunctionName,
                  input,
                  parameters, resultSize,
                  pluginId,
                  attrs,
                  ctx,
                  addHostFunctions,
                )
            }
          }
        }
    }
  }
}
