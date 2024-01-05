package otoroshi.next.plugins

import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.plugins.api._
import otoroshi.utils.JsonPathValidator
import otoroshi.utils.syntax.implicits._
import play.api.http.websocket._
import play.api.libs.json._
import play.api.mvc.{Result, Results}

import scala.concurrent.{ExecutionContext, Future}
import scala.util._

case class FrameFormatValidatorConfig(
    validator: Option[JsonPathValidator] = None
) extends NgPluginConfig {
  def json: JsValue = FrameFormatValidatorConfig.format.writes(this)
}

object FrameFormatValidatorConfig {
  val default = FrameFormatValidatorConfig()
  val format  = new Format[FrameFormatValidatorConfig] {
    override def writes(o: FrameFormatValidatorConfig): JsValue = Json.obj(
      "validator" -> o.validator.map(_.json)
    )
    override def reads(json: JsValue): JsResult[FrameFormatValidatorConfig] = {
      Try {
        FrameFormatValidatorConfig(
          validator = (json \ "validator").asOpt[JsValue]
            .flatMap(v => JsonPathValidator.format.reads(v).asOpt)
        )
      } match {
        case Failure(e) => JsError(e.getMessage)
        case Success(s) => JsSuccess(s)
      }
    }
  }
}

class FrameFormatValidator extends NgWebsocketPlugin {

  override def multiInstance: Boolean                      = true
  override def defaultConfigObject: Option[NgPluginConfig] = Some(FrameFormatValidatorConfig.default)
  override def core: Boolean                               = false
  override def name: String                                = "Websocket frame format validator"
  override def description: Option[String]                 = "Validate the format of each frames".some
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Websocket)
  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)

  override def onResponseFlow: Boolean                     = true
  override def onRequestFlow: Boolean                      = true

  private def validate(ctx: NgWebsocketPluginContext, message: String)(implicit env: Env): Boolean = {
    val config = ctx.cachedConfig(internalName)(FrameFormatValidatorConfig.format).getOrElse(FrameFormatValidatorConfig())
//    val token: JsValue = ctx.attrs
//      .get(otoroshi.next.plugins.Keys.JwtInjectionKey)
//      .flatMap(_.decodedToken)
//      .map { token =>
//        Json.obj(
//          "header" -> token.getHeader.fromBase64.parseJson,
//          "payload" -> token.getPayload.fromBase64.parseJson
//        )
//      }
//      .getOrElse(JsNull)
    val json = ctx.json.asObject ++ Json.obj(
      "route" -> ctx.route.json,
        "message" -> message
//      "token" -> token
    )
    config.validator.forall(validator => validator.validate(json))
  }

  override def access(ctx: NgWebsocketPluginContext, message: String)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    if (validate(ctx, message)) {
      NgAccess.NgAllowed.vfuture
    } else {
      Errors
        .craftResponseResult(
          "forbidden",
          Results.Forbidden,
          ctx.request,
          None,
          None,
          duration = 0L,// ctx.report.getDurationNow(),
          overhead = 0L, //ctx.report.getOverheadInNow(),
          attrs = ctx.attrs,
          maybeRoute = ctx.route.some
        )
        .map(r => NgAccess.NgDenied(r))
    }
  }
}
