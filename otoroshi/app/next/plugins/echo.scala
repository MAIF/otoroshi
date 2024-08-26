package otoroshi.next.plugins

import akka.http.scaladsl.model.MediaTypes
import akka.stream.Materializer
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class EchoBackendConfig(limit: Long = 512L * 1024L) extends NgPluginConfig {
  def json: JsValue = EchoBackendConfig.format.writes(this)
}

object EchoBackendConfig {
  val default = EchoBackendConfig()
  val format = new Format[EchoBackendConfig] {
    override def reads(json: JsValue): JsResult[EchoBackendConfig] = Try {
      EchoBackendConfig(
        limit = json.select("limit").asOptLong.getOrElse(512L * 1024L)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(s) => JsSuccess(s)
    }
    override def writes(o: EchoBackendConfig): JsValue = Json.obj(
      "limit" -> o.limit
    )
  }
  val configFlow: Seq[String] = Seq("limit")
  val configSchema: Option[JsObject] = Some(Json.obj(
    "limit" -> Json.obj(
      "type" -> "number",
      "label" -> s"Request body limit",
      "props" -> Json.obj(
        "suffix" -> "bytes"
      )
    )
  ))
}

class EchoBackend extends NgBackendCall {

  override def steps: Seq[NgStep]                          = Seq(NgStep.CallBackend)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Other)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Request Echo"
  override def description: Option[String]                 = "This plugin returns request content as json".some
  override def useDelegates: Boolean                       = false
  override def noJsForm: Boolean                           = true
  override def defaultConfigObject: Option[NgPluginConfig] = EchoBackendConfig.default.some
  override def configFlow: Seq[String]                     = EchoBackendConfig.configFlow
  override def configSchema: Option[JsObject]              = EchoBackendConfig.configSchema

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(EchoBackendConfig.format).getOrElse(EchoBackendConfig.default)
    val cookies = JsObject(ctx.request.cookies.map(c => (c.name, c.json)).toMap)
    val payload = Json.obj(
      "method" -> ctx.request.method.toUpperCase,
      "path" -> ctx.request.path,
      "raw_path" -> ctx.request.relativeUri,
      "query" -> ctx.request.queryParams,
      "headers" -> ctx.request.headers,
      "cookies" -> cookies,
    )
    if (ctx.request.hasBody) {
      ctx.request.body.limit(config.limit).runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
        val body: JsValue = ctx.request.typedContentType match {
          case Some(ctype) if ctype.mediaType == MediaTypes.`application/x-www-form-urlencoded` => bodyRaw.utf8String.json
          case Some(ctype) if ctype.mediaType == MediaTypes.`application/json` => Try(Json.parse(bodyRaw.utf8String)) match {
            case Success(value) => value
            case Failure(_) => JsString(bodyRaw.utf8String)
          }
          case Some(ctype) if ctype.mediaType.isText => JsString(bodyRaw.utf8String)
          case _ => JsString(bodyRaw.encodeBase64.utf8String)
        }
        BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(payload ++ Json.obj("body" -> body))), None).right
      }
    } else {
      BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(payload ++ Json.obj("body" -> JsNull))), None).rightf
    }
  }
}

class RequestBodyEchoBackend extends NgBackendCall {

  override def steps: Seq[NgStep]                          = Seq(NgStep.CallBackend)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Other)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Request body Echo"
  override def description: Option[String]                 = "This plugin returns request body content".some
  override def useDelegates: Boolean                       = false
  override def noJsForm: Boolean                           = true
  override def defaultConfigObject: Option[NgPluginConfig] = EchoBackendConfig.default.some
  override def configFlow: Seq[String]                     = EchoBackendConfig.configFlow
  override def configSchema: Option[JsObject]              = EchoBackendConfig.configSchema

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(EchoBackendConfig.format).getOrElse(EchoBackendConfig.default)
    if (ctx.request.hasBody) {
      ctx.request.body.limit(config.limit).runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
        val ctype = ctx.request.contentType.getOrElse("application/octet-stream")
        BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(bodyRaw).as(ctype)), None).right
      }
    } else {
      BackendCallResponse(NgPluginHttpResponse.fromResult(Results.NoContent), None).rightf
    }
  }
}
