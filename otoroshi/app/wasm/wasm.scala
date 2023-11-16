package otoroshi.wasm

import akka.stream.Materializer
import io.otoroshi.common.wasm.scaladsl._
import io.otoroshi.common.wasm.scaladsl.security.TlsConfig
import org.extism.sdk.wasmotoroshi.{WasmOtoroshiHostFunction, WasmOtoroshiHostUserData}
import otoroshi.env.Env
import otoroshi.next.models.NgTlsConfig
import otoroshi.next.plugins.api.{NgPluginConfig, NgPluginVisibility, NgStep}
import otoroshi.script._
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.libs.ws.{DefaultWSCookie, WSCookie, WSRequest}
import play.api.mvc.Cookie

import java.util.concurrent.Executors
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
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
    memoryPages: Int = 20,
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
) extends NgPluginConfig
    with WasmConfiguration {
  // still here for compat reason
  def lifetime: WasmVmLifetime = WasmVmLifetime.Forever
  //def wasmPool()(implicit env: Env): WasmVmPool = WasmVmPool.forConfig(this)
  def json: JsValue            = Json.obj(
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
    "killOptions"    -> killOptions.json
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
        memoryPages = (json \ "memoryPages").asOpt[Int].getOrElse(20),
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
        killOptions = json
          .select("killOptions")
          .asOpt[JsValue]
          .flatMap(v => WasmVmKillOptions.format.reads(v).asOpt)
          .getOrElse(WasmVmKillOptions.default)
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
    override def writes(o: WasmConfig): JsValue             = o.json
  }
}

class OtoroshiWasmIntegrationContext(env: Env) extends WasmIntegrationContext {

  implicit val ec = env.otoroshiExecutionContext
  implicit val ev = env

  val logger: Logger                                        = Logger("otoroshi-wasm-integration")
  val materializer: Materializer                            = env.otoroshiMaterializer
  val executionContext: ExecutionContext                    = env.otoroshiExecutionContext
  val wasmCacheTtl: Long                                    = env.wasmCacheTtl
  val wasmQueueBufferSize: Int                              = env.wasmQueueBufferSize
  val selfRefreshingPools: Boolean                          = false
  val wasmScriptCache: TrieMap[String, CacheableWasmScript] = new TrieMap[String, CacheableWasmScript]()
  val wasmExecutor: ExecutionContext                        = ExecutionContext.fromExecutorService(
    Executors.newWorkStealingPool(Math.max(32, (Runtime.getRuntime.availableProcessors * 4) + 1))
  )

  override def url(path: String, tlsConfigOpt: Option[TlsConfig] = None): WSRequest = {
    tlsConfigOpt match {
      case None            => env.Ws.url(path)
      case Some(tlsConfig) => {
        val cfg = NgTlsConfig.format.reads(tlsConfig.json).get.legacy
        env.MtlsWs.url(path, cfg)
      }
    }
  }

  override def wasmManagerSettings: Future[Option[WasmManagerSettings]] =
    env.datastores.globalConfigDataStore.latest().wasmManagerSettings.vfuture

  override def wasmConfig(path: String): Future[Option[WasmConfiguration]] =
    env.proxyState.wasmPlugin(path).map(_.config).vfuture

  override def wasmConfigs(): Future[Seq[WasmConfiguration]] = env.proxyState.allWasmPlugins().map(_.config).vfuture

  override def inlineWasmSources(): Future[Seq[WasmSource]] = {
    val routes                   = env.proxyState.allRoutes() ++ env.proxyState.allRawRoutes()
    val sources: Seq[WasmSource] = routes
      .flatMap(route =>
        route.plugins.slots
          .collect {
            case slot if slot.plugin.toLowerCase().contains("wasm") => slot.config.raw.select("source").asOpt[JsObject]
          }
          .collect { case Some(sourceRaw) =>
            WasmSource.format.reads(sourceRaw)
          }
          .collect { case JsSuccess(source, _) =>
            source
          }
      )
      .filter { source =>
        source.kind match {
          case WasmSourceKind.Local => false
          case _                    => true
        }
      }
    sources.vfuture
  }

  override def hostFunctions(
      config: WasmConfiguration,
      pluginId: String
  ): Array[WasmOtoroshiHostFunction[_ <: WasmOtoroshiHostUserData]] = {
    HostFunctions.getFunctions(config.asInstanceOf[WasmConfig], pluginId, None)
  }
}

class WasmVmPoolCleaner extends Job {

  private val logger = Logger("otoroshi-wasm-vm-pool-cleaner")

  override def uniqueId: JobId = JobId("io.otoroshi.core.jobs.wasm.WasmVmPoolCleaner")

  override def visibility: NgPluginVisibility = NgPluginVisibility.NgInternal

  override def steps: Seq[NgStep] = Seq(NgStep.Job)

  override def kind: JobKind = JobKind.ScheduledEvery

  override def starting: JobStarting = JobStarting.Automatically

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation =
    JobInstantiation.OneInstancePerOtoroshiInstance

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 10.seconds.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = 60.seconds.some

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val config = env.datastores.globalConfigDataStore
      .latest()
      .plugins
      .config
      .select("wasm-vm-pool-cleaner-config")
      .asOpt[JsObject]
      .getOrElse(Json.obj())
    env.wasmIntegration.runVmCleanerJob(config)
  }
}

object WasmUtils {

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
}
