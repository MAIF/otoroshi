package otoroshi.next.plugins

import akka.stream.Materializer
import akka.util.ByteString
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import org.extism.sdk.Context
import org.extism.sdk.manifest.{Manifest, MemoryOptions}
import org.extism.sdk.wasm.WasmSourceResolver
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import java.nio.file.{Files, Paths}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}


case class WasmQueryConfig(
                            source: Option[String] = None,
                            memoryPages: Int = 4,
                            functionName: String = "execute",
                            config: Map[String, String] = Map.empty,
                            allowedHosts: Seq[String] = Seq.empty
                          ) extends NgPluginConfig {
  def json: JsValue = Json.obj(
    "source" -> source,
    "memoryPages" -> memoryPages,
    "functionName"-> functionName,
    "config" -> config,
    "allowedHosts" -> allowedHosts
  )
}

object WasmQueryConfig {
  val format = new Format[WasmQueryConfig] {
    override def reads(json: JsValue): JsResult[WasmQueryConfig] = Try {
      WasmQueryConfig(
        source = (json \ "source").asOpt[String],
        memoryPages = (json \ "memoryPages").asOpt[Int].getOrElse(4),
        functionName = (json \ "functionName").asOpt[String].getOrElse("execute"),
        config = (json \ "config").asOpt[Map[String, String]].getOrElse(Map.empty),
        allowedHosts = (json \ "allowedHosts").asOpt[Seq[String]].getOrElse(Seq.empty)
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: WasmQueryConfig): JsValue = o.json
  }
}

class WasmQuery extends NgBackendCall {

  override def useDelegates: Boolean                       = false
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = false
  override def name: String                                = "WASM Function"
  override def description: Option[String]                 = "This plugin can be used to launch a WASM file".some
  override def defaultConfigObject: Option[NgPluginConfig] = WasmQueryConfig().some

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Integrations)
  override def steps: Seq[NgStep]                = Seq(NgStep.CallBackend)

  private val scriptCache: Cache[String, ByteString] = Scaffeine()
    .recordStats()
    .expireAfterWrite(10.minutes)
    .maximumSize(100)
    .build()

  def getWasm(config: WasmQueryConfig)(implicit env: Env, ec: ExecutionContext): Future[ByteString] = {
    val wasm = config.source.getOrElse("https://raw.githubusercontent.com/extism/extism/main/wasm/code.wasm")
    if (wasm.startsWith("http://") || wasm.startsWith("https://")) {
      scriptCache.getIfPresent(wasm) match {
        case Some(script) => script.future
        case None => {
          env.Ws.url(wasm).withRequestTimeout(10.seconds).get().map { resp =>
            val body = resp.bodyAsBytes
            scriptCache.put(wasm, body)
            body
          }
        }
      }
    } else if (wasm.startsWith("file://")) {
      scriptCache.getIfPresent(wasm) match {
        case Some(script) => script.future
        case None => {
          val body = ByteString(Files.readAllBytes(Paths.get(wasm.replace("file://", ""))))
          scriptCache.put(wasm, body)
          body.future
        }
      }
    } else if (wasm.startsWith("base64://")) {
      ByteString(wasm.replace("base64://", "")).decodeBase64.future
    } else {
      ByteString(wasm).decodeBase64.future
    }
  }

  override def callBackend(
                            ctx: NgbBackendCallContext,
                            delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]]
                          )(implicit
                            env: Env,
                            ec: ExecutionContext,
                            mat: Materializer
                          ): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx
      .cachedConfig(internalName)(WasmQueryConfig.format)
      .getOrElse(WasmQueryConfig())

    getWasm(config)
      .flatMap { wasm =>
        val resolver = new WasmSourceResolver()
        val source = resolver.resolve("wasm", wasm.toByteBuffer.array())
        val manifest = new Manifest(
          Seq[org.extism.sdk.wasm.WasmSource](source).asJava,
          new MemoryOptions(config.memoryPages),
          config.config.asJava,
          config.allowedHosts.asJava
        )

        val context = new Context()
        val plugin = context.newPlugin(manifest, true)

        ctx.wasmJson
          .map(input => {
            val output = plugin.call(config.functionName, input.stringify)

            bodyResponse(
              200,
              Map("Content-Type" -> "application/json"),
              output.byteString.chunks(16 * 1024)
            )
          })
      }
  }
}

