package otoroshi.next.plugins

import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.plugins.api.{NgAccess, NgAccessContext, NgAccessValidator, NgPluginCategory, NgPluginConfig, NgPluginVisibility, NgStep}
import otoroshi.utils.RegexPool
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class ContextValidator(path: String, value: JsValue) {
  def json: JsValue = ContextValidator.format.writes(this)
  def validate(ctx: JsValue): Boolean = {
    ctx.atPath(path).asOpt[JsValue] match {
      case None => false
      case Some(JsString(v)) if value.isInstanceOf[JsString] => {
        val expected = value.asString
        if (expected.trim.startsWith("Regex(") && expected.trim.endsWith(")")) {
          val regex = expected.substring(6).init
          RegexPool.regex(regex).matches(v)
        } else if (expected.trim.startsWith("Wildcard(") && expected.trim.endsWith(")")) {
          val regex = expected.substring(9).init
          RegexPool.apply(regex).matches(v)
        } else {
          v == expected
        }
      }
      case Some(v) => v == value
    }
  }
}

object ContextValidator {
  val format = new Format[ContextValidator] {
    override def writes(o: ContextValidator): JsValue = Json.obj(
      "path" -> o.path,
      "value" -> o.value
    )
    override def reads(json: JsValue): JsResult[ContextValidator] = Try {
      ContextValidator(
        path = json.select("path").as[String],
        value = json.select("value").asValue
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

case class ContextValidationConfig(validators: Seq[ContextValidator] = Seq.empty) extends NgPluginConfig {
  def json: JsValue = ContextValidationConfig.format.writes(this)
}

object ContextValidationConfig {
  val format = new Format[ContextValidationConfig] {
    override def writes(o: ContextValidationConfig): JsValue = Json.obj(
      "validators" -> JsArray(o.validators.map(_.json))
    )
    override def reads(json: JsValue): JsResult[ContextValidationConfig] = Try {
      ContextValidationConfig(
        validators = (json \ "validators").asOpt[Seq[JsValue]].map(_.flatMap(v => ContextValidator.format.reads(v).asOpt)).getOrElse(Seq.empty)
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

class ContextValidation extends NgAccessValidator {

  override def steps: Seq[NgStep] = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl, NgPluginCategory.Security)
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean = true
  override def core: Boolean               = true
  override def name: String                = "Context validator"
  override def description: Option[String] = "This plugin validates the current context".some
  override def defaultConfigObject: Option[NgPluginConfig] = ContextValidationConfig().some

  private def validate(ctx: NgAccessContext): Boolean = {
    val config = ctx.cachedConfig(internalName)(ContextValidationConfig.format).getOrElse(ContextValidationConfig())
    val token: JsValue = ctx.attrs.get(otoroshi.next.plugins.Keys.JwtInjectionKey).flatMap(_.decodedToken).map { token =>
      Json.obj(
        "header" -> token.getHeader.fromBase64.parseJson,
        "payload" -> token.getPayload.fromBase64.parseJson,
      )
    }.getOrElse(JsNull)
    val json = ctx.json.asObject ++ Json.obj("route" -> ctx.route.json, "token" -> token)
    config.validators.forall(validator => validator.validate(json))
  }

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    if (validate(ctx)) {
      NgAccess.NgAllowed.vfuture
    } else {
      Errors
        .craftResponseResult(
          "forbidden",
          Results.Forbidden,
          ctx.request,
          None,
          None,
          duration = ctx.report.getDurationNow(),
          overhead = ctx.report.getOverheadInNow(),
          attrs = ctx.attrs,
          maybeRoute = ctx.route.some
        )
        .map(r => NgAccess.NgDenied(r))
    }
  }
}
