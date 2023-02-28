package otoroshi.next.plugins

import akka.stream.Materializer
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsNull, JsString, JsValue, Json}
import play.api.mvc.{RequestHeader, Result}

import scala.concurrent.{ExecutionContext, Future}

case class NgDefaultRequestBodyConfig(body: ByteString, contentType: String, contentEncoding: Option[String])
    extends NgPluginConfig {
  override def json: JsValue = Json.obj(
    "bodyBinary"      -> body.encodeBase64.utf8String,
    "contentType"     -> contentType,
    "contentEncoding" -> contentEncoding.map(JsString.apply).getOrElse(JsNull).asValue
  )
}

object NgDefaultRequestBodyConfig {
  val default = NgDefaultRequestBodyConfig(ByteString.empty, "text/plain", None)
  def from(ctx: NgTransformerRequestContext): NgDefaultRequestBodyConfig = {
    NgDefaultRequestBodyConfig(
      body = ctx.config
        .select("bodyStr")
        .asOpt[String]
        .filter(_.nonEmpty)
        .map(ByteString.apply)
        .orElse(
          ctx.config.select("bodyBinary").asOpt[String].filter(_.nonEmpty).map(ByteString.apply).map(_.decodeBase64)
        )
        .getOrElse(ByteString.empty),
      contentType = ctx.config.select("contentType").asOpt[String].getOrElse("text/plain"),
      contentEncoding = ctx.config.select("contentEncoding").asOpt[String]
    )
  }
}

class NgDefaultRequestBody extends NgRequestTransformer {

  override def multiInstance: Boolean                      = true
  override def defaultConfigObject: Option[NgPluginConfig] = NgDefaultRequestBodyConfig.default.some
  override def core: Boolean                               = true
  override def name: String                                = "Default request body"
  override def description: Option[String]                 = "This plugin adds a default request body if none specified".some
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Transformations)
  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformRequest)
  override def transformsRequest: Boolean                  = true
  override def transformsError: Boolean                    = false
  override def transformsResponse: Boolean                 = false

  private def hasNoRequestBody(req: RequestHeader): Boolean = !hasRequestBody(req)

  private def hasRequestBody(req: RequestHeader): Boolean = {
    val hasType             = req.headers.get("Content-Type").isDefined
    val hasLength           = req.headers.get("Content-Length").isDefined
    val hasTransferEncoding = req.headers.get("Transfer-Encoding").isDefined
    hasType && (hasLength || hasTransferEncoding)
  }

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    if (hasNoRequestBody(ctx.request)) {
      val config                          = NgDefaultRequestBodyConfig.from(ctx)
      val addHeaders: Map[String, String] = Map(
        "Content-Type"   -> config.contentType,
        "Content-Length" -> config.body.size.toString
      ).applyOnWithOpt(config.contentEncoding) { case (hdrs, enc) =>
        hdrs ++ Map("Content-Encoding" -> enc)
      }
      ctx.otoroshiRequest
        .copy(
          headers = ctx.otoroshiRequest.headers
            .remove("Content-Type")
            .removeIgnoreCase("Content-Length")
            .removeIgnoreCase("Content-Encoding")
            .removeIgnoreCase("Transfer-Encoding")
            .addAll(addHeaders),
          body = config.body.chunks(32 * 1024)
        )
        .right
        .vfuture
    } else {
      ctx.otoroshiRequest.right.vfuture
    }
  }
}
