package otoroshi.next.plugins

import akka.Done
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.syntax.implicits._
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}

class ForceHttpsTraffic extends NgPreRouting {

  override def steps: Seq[NgStep]                = Seq(NgStep.PreRoute)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.TrafficControl, NgPluginCategory.Standard)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = false
  override def core: Boolean                               = true
  override def name: String                                = "Force HTTPS traffic"
  override def description: Option[String]                 = "This plugin verifies the current request uses HTTPS".some
  override def isPreRouteAsync: Boolean                    = false
  override def defaultConfigObject: Option[NgPluginConfig] = None

  override def preRouteSync(
      ctx: NgPreRoutingContext
  )(implicit env: Env, ec: ExecutionContext): Either[NgPreRoutingError, Done] = {
    if (!ctx.request.theSecured) {
      NgPreRoutingErrorWithResult(
        Results.Redirect(s"https://${ctx.request.theDomain}${env.exposedHttpsPort}${ctx.request.relativeUri}")
      ).left
    } else {
      NgPreRouting.done
    }
  }
}
