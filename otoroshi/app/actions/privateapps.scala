package actions

import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture._
import env.Env
import models.PrivateAppsUser
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

case class PrivateAppsActionContext[A](request: Request[A],
                                       user: Option[PrivateAppsUser],
                                       globalConfig: models.GlobalConfig) {
  def connected: Boolean = user.isDefined
}

class PrivateAppsAction(val parser: BodyParser[AnyContent])(implicit env: Env)
    extends ActionBuilder[PrivateAppsActionContext, AnyContent]
    with ActionFunction[Request, PrivateAppsActionContext] {

  implicit lazy val ec = env.privateAppsExecutionContext

  override def invokeBlock[A](request: Request[A],
                              block: (PrivateAppsActionContext[A]) => Future[Result]): Future[Result] = {
    val host = if (request.host.contains(":")) request.host.split(":")(0) else request.host
    host match {
      case env.privateAppsHost => {
        env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig =>
          request.cookies.get("oto-papps").flatMap(env.extractPrivateSessionId).map { id =>
            env.datastores.privateAppsUserDataStore.findById(id).flatMap {
              case Some(user) => block(PrivateAppsActionContext(request, Some(user), globalConfig))
              case None       => {
                // TODO : #75 if in worker mode, fetch from master
                block(PrivateAppsActionContext(request, None, globalConfig))
              }
            }
          } getOrElse {
            block(PrivateAppsActionContext(request, None, globalConfig)).fast
              .map(_.discardingCookies(env.removePrivateSessionCookies(host): _*))
          }
        }
      }
      case _ => {
        // TODO : based on Accept header
        FastFuture.successful(Results.NotFound(views.html.otoroshi.error("Not found", env)))
      }
    }
  }

  override protected def executionContext: ExecutionContext = ec
}
