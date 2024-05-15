package otoroshi.wasm.httpwasm

import akka.stream.Materializer
import akka.util.ByteString
import io.otoroshi.wasm4s.scaladsl.{WasmSource, WasmSourceKind, WasmVm, WasmVmKillOptions}
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.TypedMap
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.wasm.WasmConfig
import otoroshi.wasm.httpwasm.api.HttpHandler
import play.api.libs.json._
import play.api.libs.typedmap.TypedKey
import play.api.mvc

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class NgHttpWasmConfig(ref: String) extends NgPluginConfig {
  override def json: JsValue = NgHttpWasmConfig.format.writes(this)
}

object NgHttpWasmConfig {
  val format = new Format[NgHttpWasmConfig] {
    override def writes(o: NgHttpWasmConfig): JsValue              = Json.obj("ref" -> o.ref)
    override def reads(json: JsValue): JsResult[NgHttpWasmConfig] = Try {
      NgHttpWasmConfig((json \ "ref").as[String])
    } match {
      case Success(e) => JsSuccess(e)
      case Failure(e) => JsError(e.getMessage)
    }
  }
}

object HttpWasmPluginKeys {
  val HttpWasmVmKey    = TypedKey[WasmVm]("otoroshi.next.plugins.HttpWasmVm")
}

class NgHttpWasm extends NgRequestTransformer {

  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformRequest, NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Wasm)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Http WASM"
  override def description: Option[String]                 = "Http Wasm plugin".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgHttpWasmConfig("").some

  override def isTransformRequestAsync: Boolean  = true
  override def isTransformResponseAsync: Boolean = true
  override def usesCallbacks: Boolean            = true
  override def transformsRequest: Boolean        = true
  override def transformsResponse: Boolean       = true
  override def transformsError: Boolean          = false

  private val plugins = new UnboundedTrieMap[String, HttpHandler]()

  private def getPlugin(ref: String, attrs: TypedMap)(implicit env: Env): HttpHandler = plugins.synchronized {
    val key = s"ref=$ref"

//    val url = s"http://127.0.0.1:${env.httpPort}/__otoroshi_assets/wasm/httpwasm/log.wasm?$key"
      val url = "file:///Users/79966b/Documents/opensource/otoroshi/otoroshi/conf/wasm/httpwasm/log.wasm"

    val plugin   = new HttpHandler(
      WasmConfig(
        source = WasmSource(
          kind = WasmSourceKind.File,
          path = url
        ),
        memoryPages = 1000000,
        functionName = None,
        wasi = true,
        // lifetime = WasmVmLifetime.Forever,
        instances = 1,
        killOptions = WasmVmKillOptions(
          maxCalls = 2000,
          maxMemoryUsage = 0.9,
          maxAvgCallDuration = 1.day,
          maxUnusedDuration = 5.minutes
        )
      ),
      ByteString.empty,
      url,
      env
    )
    plugins.put(key, plugin)

    val oldVersionsKeys = plugins.keySet.filter(_.startsWith(s"ref=${ref}&hash=")).filterNot(_ == key)
    val oldVersions     = oldVersionsKeys.flatMap(plugins.get)
    if (oldVersions.nonEmpty) {
      oldVersionsKeys.foreach(plugins.remove)
    }
    plugin
  }

  override def beforeRequest(
                              ctx: NgBeforeRequestContext
                            )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    val config = ctx.cachedConfig(internalName)(NgHttpWasmConfig.format).getOrElse(NgHttpWasmConfig("none"))
    val plugin = getPlugin(config.ref, ctx.attrs)
    plugin.start(ctx.attrs)
  }

  override def afterRequest(
                             ctx: NgAfterRequestContext
                           )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    ctx.attrs.get(otoroshi.wasm.httpwasm.HttpWasmPluginKeys.HttpWasmVmKey).foreach(_.release())
    ().vfuture
  }

  override def transformRequest(
                                 ctx: NgTransformerRequestContext
                               )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[mvc.Result, NgPluginHttpRequest]] = {
    val config                             = ctx.cachedConfig(internalName)(NgHttpWasmConfig.format).getOrElse(NgHttpWasmConfig("none"))
    val plugin                             = getPlugin(config.ref, ctx.attrs)
    val hasBody                            = ctx.request.theHasBody


    val plugin = getPlugin(config.ref, ctx.attrs)
    plugin.callPluginWithResults(ctx.attrs)
    Right(ctx.otoroshiRequest).future
  }

  override def transformResponse(
                                  ctx: NgTransformerResponseContext
                                )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[mvc.Result, NgPluginHttpResponse]] = {
    val config                             = ctx.cachedConfig(internalName)(NgHttpWasmConfig.format).getOrElse(NgHttpWasmConfig("none"))
    val plugin                             = getPlugin(config.ref, ctx.attrs)

    Right(ctx.otoroshiResponse).future
  }

}
