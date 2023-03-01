package otoroshi.next.plugins

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import org.extism.sdk.{Context, HostFunction}
import org.extism.sdk.manifest.{Manifest, MemoryOptions}
import org.extism.sdk.wasm.WasmSourceResolver
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.models.WasmManagerSettings
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.libs.ws.{DefaultWSCookie, WSCookie}
import play.api.mvc.{Result, Results}

import java.nio.file.{Files, Paths}
import scala.annotation.tailrec
import scala.concurrent.duration.{DurationInt, FiniteDuration, MILLISECONDS}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try, Using}

case class WasmQueryConfig(
    compilerSource: Option[String] = None,
    rawSource: Option[String] = None,
    memoryPages: Int = 4,
    functionName: String = "execute",
    config: Map[String, String] = Map.empty,
    allowedHosts: Seq[String] = Seq.empty,
    wasi: Boolean = false
) extends NgPluginConfig {
  def json: JsValue = Json.obj(
    "raw_source"      -> rawSource,
    "compiler_source" -> compilerSource,
    "memoryPages"     -> memoryPages,
    "functionName"    -> functionName,
    "config"          -> config,
    "allowedHosts"    -> allowedHosts,
    "wasi"            -> wasi
  )
}

object WasmQueryConfig {
  val format = new Format[WasmQueryConfig] {
    override def reads(json: JsValue): JsResult[WasmQueryConfig] = Try {
      WasmQueryConfig(
        compilerSource = (json \ "compiler_source").asOpt[String],
        rawSource = (json \ "raw_source").asOpt[String],
        memoryPages = (json \ "memoryPages").asOpt[Int].getOrElse(4),
        functionName = (json \ "functionName").asOpt[String].getOrElse("execute"),
        config = (json \ "config").asOpt[Map[String, String]].getOrElse(Map.empty),
        allowedHosts = (json \ "allowedHosts").asOpt[Seq[String]].getOrElse(Seq.empty),
        wasi = (json \ "wasi").asOpt[Boolean].getOrElse(false)
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: WasmQueryConfig): JsValue = o.json
  }
}

object WasmUtils {
  private val scriptCache: Cache[String, ByteString] = Scaffeine()
    .recordStats()
    .expireAfterWrite(30.seconds)
    .maximumSize(100)
    .build()

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

  def getWasm(source: String)(implicit env: Env, ec: ExecutionContext): Future[ByteString] = {
    //val wasm = config.source.getOrElse("https://raw.githubusercontent.com/extism/extism/main/wasm/code.wasm")
    if (source.startsWith("http://") || source.startsWith("https://")) {
      scriptCache.getIfPresent(source) match {
        case Some(script) => script.future
        case None         => {
          env.Ws.url(source).withRequestTimeout(10.seconds).get().map { resp =>
            val body = resp.bodyAsBytes
            scriptCache.put(source, body)
            body
          }
        }
      }
    } else if (source.startsWith("file://")) {
      scriptCache.getIfPresent(source) match {
        case Some(script) => script.future
        case None         => {
          val body = ByteString(Files.readAllBytes(Paths.get(source.replace("file://", ""))))
          scriptCache.put(source, body)
          body.future
        }
      }
    } else if (source.startsWith("base64://")) {
      ByteString(source.replace("base64://", "")).decodeBase64.future
    } else {
      ByteString(source).decodeBase64.future
    }
  }


  @tailrec
  def callWasm(wasm: ByteString, config: WasmQueryConfig, input: JsValue, nRetry: Int = 1): String = {
      val resolver = new WasmSourceResolver()
      val source = resolver.resolve("wasm", wasm.toByteBuffer.array())
      val manifest = new Manifest(
        Seq[org.extism.sdk.wasm.WasmSource](source).asJava,
        new MemoryOptions(config.memoryPages),
        config.config.asJava,
        config.allowedHosts.asJava
      )

      try {
        val context = new Context()
        val plugin = context.newPlugin(manifest, config.wasi, Array())
        val output = plugin.call(config.functionName, input.stringify)
        plugin.close()
        context.free()
        output
      } catch {
        case e: Exception =>
          if (e.getMessage == "unknown import: `wasi_snapshot_preview1::fd_write` has not been defined" && nRetry > 0) {
            println("retry" + e.getMessage)
            callWasm(wasm, config, input, nRetry - 1)
          } else {
            ""
          }
      }
  }

  def execute(config: WasmQueryConfig, input: JsValue)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[String, JsValue]] = {
    (config.compilerSource, config.rawSource) match {
      case (Some(pluginId), _)  =>
        scriptCache.getIfPresent(pluginId) match {
          case Some(wasm) =>
            WasmUtils.callWasm(wasm, config, input).left.future
          case None       =>
            env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig =>
              globalConfig.wasmManagerSettings match {
                case Some(WasmManagerSettings(url, clientId, clientSecret, _)) =>
                  env.Ws
                    .url(s"$url/wasm/$pluginId")
                    .withFollowRedirects(false)
                    .withRequestTimeout(FiniteDuration(5 * 1000, MILLISECONDS))
                    .withHttpHeaders(
                      "Accept"                 -> "application/json",
                      "Otoroshi-Client-Id"     -> clientId,
                      "Otoroshi-Client-Secret" -> clientSecret
                    )
                    .get()
                    .flatMap { resp =>
                      if (resp.status == 400) {
                        Right(Json.obj("error" -> "missing signed plugin url")).future
                      } else {
                        val wasm = resp.bodyAsBytes
                        scriptCache.put(pluginId, resp.bodyAsBytes)
                        WasmUtils.callWasm(wasm, config, input).left.future
                      }
                    }
                case _                                                         =>
                  Right(Json.obj("error" -> "missing wasm manager url")).future
              }
            }
        }
      case (_, Some(rawSource)) =>
        WasmUtils
          .getWasm(rawSource)
          .map(wasm => WasmUtils.callWasm(wasm, config, input).left)
      case _                    => Right(Json.obj("error" -> "missing source")).future
    }

  }
}

class WasmBackend extends NgBackendCall {

  override def useDelegates: Boolean                       = false
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = false
  override def name: String                                = "WASM Backend"
  override def description: Option[String]                 = "This plugin can be used to launch a WASM file".some
  override def defaultConfigObject: Option[NgPluginConfig] = WasmQueryConfig().some

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
      .cachedConfig(internalName)(WasmQueryConfig.format)
      .getOrElse(WasmQueryConfig())

    ctx.wasmJson
      .flatMap(input => WasmUtils.execute(config, input))
      .map {
        case Left(output) =>
          val response = Json.parse(output)
          bodyResponse(
            status = response.select("status").asOpt[Int].getOrElse(200),
            headers = response
              .select("headers")
              .asOpt[Map[String, String]]
              .getOrElse(Map("Content-Type" -> "application/json")),
            body = (response.select("body") match {
              case JsDefined(value) =>
                value match {
                  case JsString(value) => value
                  case o: JsObject     => Json.stringify(o)
                  case _               => "{}"
                }
              case _: JsUndefined   => "{}"
            }).byteString.chunks(16 * 1024)
          )
        case Right(value) =>
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
  override def defaultConfigObject: Option[NgPluginConfig] = WasmQueryConfig().some

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx
      .cachedConfig(internalName)(WasmQueryConfig.format)
      .getOrElse(WasmQueryConfig())

    WasmUtils
      .execute(config, ctx.wasmJson)
      .flatMap {
        case Left(res)  =>
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
        case Right(err) =>
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
  override def defaultConfigObject: Option[NgPluginConfig] = WasmQueryConfig().some

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config = ctx
      .cachedConfig(internalName)(WasmQueryConfig.format)
      .getOrElse(WasmQueryConfig())

    ctx.wasmJson
      .flatMap(input => {
        WasmUtils
          .execute(config, input)
          .map {
            case Left(res)    =>
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
            case Right(value) => Left(Results.BadRequest(value))
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
  override def defaultConfigObject: Option[NgPluginConfig] = WasmQueryConfig().some

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val config = ctx
      .cachedConfig(internalName)(WasmQueryConfig.format)
      .getOrElse(WasmQueryConfig())

    ctx.wasmJson
      .flatMap(input => {
        WasmUtils
          .execute(config, input)
          .map {
            case Left(res)    =>
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
            case Right(value) => Left(Results.BadRequest(value))
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

  override def defaultConfigObject: Option[NgPluginConfig] = WasmQueryConfig().some

  override def matches(ctx: NgRequestSinkContext)(implicit env: Env, ec: ExecutionContext): Boolean = {
    val config = WasmQueryConfig.format.reads(ctx.config) match {
      case JsSuccess(value, _) => value
      case JsError(_)          => WasmQueryConfig()
    }

    config.compilerSource.getOrElse(config.rawSource) match {
      case Some(source: String) =>
        Await.result(
          WasmUtils
            .getWasm(source)
            .map(wasm => WasmUtils.callWasm(wasm, config.copy(functionName = "matches"), ctx.json))
            .map(res => {
              val response = Json.parse(res)
              (response \ "result").asOpt[Boolean].getOrElse(false)
            }),
          10.seconds
        )
      case None                 => false
    }
  }

  override def handleSync(ctx: NgRequestSinkContext)(implicit env: Env, ec: ExecutionContext): Result =
    Await.result(this.handle(ctx), 10.seconds)

  override def handle(ctx: NgRequestSinkContext)(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    val config = WasmQueryConfig.format.reads(ctx.config) match {
      case JsSuccess(value, _) => value
      case JsError(_)          => WasmQueryConfig()
    }

    config.compilerSource.getOrElse(config.rawSource) match {
      case Some(source: String) =>
        WasmUtils
          .getWasm(source)
          .map(wasm => WasmUtils.callWasm(wasm, config, ctx.json))
          .map(res => {
            val response = Json.parse(res)

            val status = response
              .select("status")
              .asOpt[Int]
              .getOrElse(200)

            val _headers    = response
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
          })
      case None                 => Results.BadRequest(Json.obj("error" -> "missing source")).future
    }
  }
}
