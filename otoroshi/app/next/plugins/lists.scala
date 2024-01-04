package otoroshi.next.plugins

import otoroshi.el.GlobalExpressionLanguage
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class NgGenericListConfig(expression: Option[String] = "${req.ip_address}".some, values: Seq[String] = Seq.empty) extends NgPluginConfig {
  def json: JsValue = NgGenericListConfig.format.writes(this)
}

object NgGenericListConfig {
  val format = new Format[NgGenericListConfig] {
    override def writes(o: NgGenericListConfig): JsValue = Json.obj(
      "expression" -> o.expression.map(_.json).getOrElse(JsNull).asValue,
      "values" -> o.values,
    )
    override def reads(json: JsValue): JsResult[NgGenericListConfig] = Try {
      NgGenericListConfig(
        expression = json.select("expression").asOpt[String].filterNot(_.isBlank),
        values = json.select("values").asOpt[Seq[String]].getOrElse(Seq.empty)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(s) => JsSuccess(s)
    }
  }
}

class NgGenericAllowedList extends NgAccessValidator {

  override def name: String = "Generic allowed list"
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl)
  override def steps: Seq[NgStep] = Seq(NgStep.ValidateAccess)
  override def defaultConfigObject: Option[NgPluginConfig] = NgGenericListConfig().some
  override def description: Option[String] =
    "This plugin checks let requests pass based on an el expression".some

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx
      .cachedConfig(internalName)(NgGenericListConfig.format)
      .getOrElse(NgGenericListConfig())
    if (config.values.nonEmpty) {
      config.expression match {
        case None => NgAccess.NgAllowed.vfuture
        case Some(expression) => {
          val value = GlobalExpressionLanguage.apply(
            value = expression,
            req = ctx.request.some,
            service = None,
            route = ctx.route.some,
            apiKey = ctx.apikey,
            user = ctx.user,
            context = Map.empty,
            attrs = ctx.attrs,
            env = env
          )
          if (config.values.contains(value)) {
            NgAccess.NgAllowed.vfuture
          } else {
            Errors
              .craftResponseResult(
                "forbidden",
                Results.Forbidden,
                ctx.request,
                None,
                Some("errors.unallowed.expression"),
                duration = ctx.report.getDurationNow(),
                overhead = ctx.report.getOverheadInNow(),
                attrs = ctx.attrs,
                maybeRoute = ctx.route.some
              )
              .map(r => NgAccess.NgDenied(r))
          }
        }
      }
    } else {
      Errors
        .craftResponseResult(
          "forbidden",
          Results.Forbidden,
          ctx.request,
          None,
          Some("errors.no.allowed.values"),
          duration = ctx.report.getDurationNow(),
          overhead = ctx.report.getOverheadInNow(),
          attrs = ctx.attrs,
          maybeRoute = ctx.route.some
        )
        .map(r => NgAccess.NgDenied(r))
    }
  }
}

class NgGenericBlockList extends NgAccessValidator {

  override def name: String = "Generic block list"
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl)
  override def steps: Seq[NgStep] = Seq(NgStep.ValidateAccess)
  override def multiInstance: Boolean = true
  override def defaultConfigObject: Option[NgPluginConfig] = NgGenericListConfig().some
  override def description: Option[String] =
    "This plugin checks let requests is blocked based on an el expression".some

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx
      .cachedConfig(internalName)(NgGenericListConfig.format)
      .getOrElse(NgGenericListConfig())
    if (config.values.nonEmpty) {
      config.expression match {
        case None => NgAccess.NgAllowed.vfuture
        case Some(expression) => {
          val value = GlobalExpressionLanguage.apply(
            value = expression,
            req = ctx.request.some,
            service = None,
            route = ctx.route.some,
            apiKey = ctx.apikey,
            user = ctx.user,
            context = Map.empty,
            attrs = ctx.attrs,
            env = env
          )
          if (!config.values.contains(value)) {
            NgAccess.NgAllowed.vfuture
          } else {
            Errors
              .craftResponseResult(
                "forbidden",
                Results.Forbidden,
                ctx.request,
                None,
                Some("errors.blocked.expression"),
                duration = ctx.report.getDurationNow(),
                overhead = ctx.report.getOverheadInNow(),
                attrs = ctx.attrs,
                maybeRoute = ctx.route.some
              )
              .map(r => NgAccess.NgDenied(r))
          }
        }
      }
    } else {
      NgAccess.NgAllowed.vfuture
    }
  }
}