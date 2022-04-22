package otoroshi.next.plugins

import akka.Done
import otoroshi.el.RedirectionExpressionLanguage
import otoroshi.env.Env
import otoroshi.models.RedirectionSettings
import otoroshi.next.plugins.api.{
  NgPluginCategory,
  NgPluginConfig,
  NgPluginVisibility,
  NgPreRouting,
  NgPreRoutingContext,
  NgPreRoutingError,
  NgPreRoutingErrorWithResult,
  NgStep
}
import otoroshi.utils.syntax.implicits.{BetterJsReadable, BetterSyntax}
import play.api.libs.json._
import play.api.mvc.Results

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

case class NgRedirectionSettings(code: Int = 303, to: String = "https://www.otoroshi.io") extends NgPluginConfig {
  def json: JsValue                    = NgRedirectionSettings.format.writes(this)
  lazy val hasValidCode: Boolean       = legacy.hasValidCode
  lazy val legacy: RedirectionSettings = RedirectionSettings(
    enabled = true,
    code = code,
    to = to
  )
}

object NgRedirectionSettings {
  def fromLegacy(settings: RedirectionSettings): NgRedirectionSettings = {
    NgRedirectionSettings(
      code = settings.code,
      to = settings.to
    )
  }
  val format = new Format[NgRedirectionSettings] {
    override def reads(json: JsValue): JsResult[NgRedirectionSettings] = {
      Try {
        NgRedirectionSettings(
          code = (json \ "code").asOpt[Int].getOrElse(303),
          to = (json \ "to").asOpt[String].filterNot(_.trim.isEmpty).getOrElse("https://www.otoroshi.io")
        )
      } match {
        case Success(entity) => JsSuccess(entity)
        case Failure(err)    => JsError(err.getMessage)
      }
    }

    override def writes(o: NgRedirectionSettings): JsValue = {
      Json.obj(
        "code" -> o.code,
        "to"   -> o.to
      )
    }
  }
}

class Redirection extends NgPreRouting {

  private val configReads: Reads[NgRedirectionSettings] = NgRedirectionSettings.format

  override def steps: Seq[NgStep]                = Seq(NgStep.PreRoute)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.TrafficControl, NgPluginCategory.Standard)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Redirection"
  override def description: Option[String]                 = "This plugin redirects the current request elsewhere".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgRedirectionSettings().some
  override def isPreRouteAsync: Boolean                    = false

  override def preRouteSync(
      ctx: NgPreRoutingContext
  )(implicit env: Env, ec: ExecutionContext): Either[NgPreRoutingError, Done] = {
    val config = ctx.cachedConfig(internalName)(configReads).getOrElse(NgRedirectionSettings())
    if (config.hasValidCode) {
      val to = RedirectionExpressionLanguage(
        config.to,
        ctx.request.some,
        ctx.route.serviceDescriptor.some,
        None,
        None,
        ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).get,
        ctx.attrs,
        env
      )
      Left(
        NgPreRoutingErrorWithResult(
          Results
            .Status(config.code)
            .withHeaders("Location" -> to)
        )
      )
    } else {
      Right(Done)
    }
  }
}
