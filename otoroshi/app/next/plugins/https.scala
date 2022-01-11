package otoroshi.next.plugins

import akka.Done
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.syntax.implicits._
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}

class ForceHttpsTraffic extends NgPreRouting {
  // TODO: add name and config
  override def preRoute(ctx: NgPreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    if (!ctx.request.theSecured) {
      NgPreRoutingErrorWithResult(Results.Redirect(s"https://${ctx.request.theDomain}${env.exposedHttpsPort}${ctx.request.relativeUri}")).leftf
    } else {
      NgPreRouting.futureDone
    }
  }
}
