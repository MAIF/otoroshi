package actions

import akka.http.scaladsl.util.FastFuture
import controllers.routes
import env.Env
import events.{Alerts, BlackListedBackOfficeUserAlert}
import models.BackOfficeUser
import play.api.mvc.{ActionBuilder, Request, Result, Results}

import scala.concurrent.Future

case class BackOfficeActionContext[A](request: Request[A], user: Option[BackOfficeUser]) {
  def connected: Boolean = user.isDefined
  def from: String       = request.headers.get("X-Forwarded-For").getOrElse(request.remoteAddress)
}

case class BackOfficeActionContextAuth[A](request: Request[A], user: BackOfficeUser) {
  def from: String = request.headers.get("X-Forwarded-For").getOrElse(request.remoteAddress)
}

class BackOfficeAction()(implicit env: Env) extends ActionBuilder[BackOfficeActionContext] {

  implicit lazy val ec = env.backOfficeExecutionContext

  override def invokeBlock[A](request: Request[A],
                              block: (BackOfficeActionContext[A]) => Future[Result]): Future[Result] = {
    val host = if (request.host.contains(":")) request.host.split(":")(0) else request.host
    host match {
      case env.backOfficeHost => {
        request.session.get("bousr").map { id =>
          env.datastores.backOfficeUserDataStore.findById(id).flatMap {
            case Some(user) => block(BackOfficeActionContext(request, Some(user)))
            case None       => block(BackOfficeActionContext(request, None))
          }
        } getOrElse {
          block(BackOfficeActionContext(request, None))
        }
      }
      case _ => {
        // TODO : based on Accept header
        FastFuture.successful(Results.NotFound(views.html.otoroshi.error("Not found", env)))
      }
    }
  }
}

class BackOfficeActionAuth()(implicit env: Env) extends ActionBuilder[BackOfficeActionContextAuth] {

  implicit lazy val ec = env.backOfficeExecutionContext

  override def invokeBlock[A](request: Request[A],
                              block: (BackOfficeActionContextAuth[A]) => Future[Result]): Future[Result] = {

    implicit val req = request

    val host = if (request.host.contains(":")) request.host.split(":")(0) else request.host
    host match {
      case env.backOfficeHost => {
        // val redirectTo = env.rootScheme + env.backOfficeHost + controllers.routes.Auth0Controller.backOfficeLogin(Some(s"${env.rootScheme}${request.host}${request.uri}")).url
        val redirectTo = env.rootScheme + request.host + controllers.routes.BackOfficeController.index().url
        request.session.get("bousr").map { id =>
          env.datastores.backOfficeUserDataStore.findById(id).flatMap {
            case Some(user) => {
              env.datastores.backOfficeUserDataStore.blacklisted(user.email).flatMap {
                case true => {
                  Alerts.send(BlackListedBackOfficeUserAlert(env.snowflakeGenerator.nextId().toString, env.env, user))
                  FastFuture.successful(
                    Results.NotFound(views.html.otoroshi.error("Error", env)).removingFromSession("bousr")(request)
                  )
                }
                case false => block(BackOfficeActionContextAuth(request, user))
              }
            }
            case None =>
              FastFuture.successful(
                Results
                  .Redirect(redirectTo)
                  .addingToSession(
                    "bo-redirect-after-login" -> s"${env.rootScheme}${request.host}${request.uri}"
                  )
              )
          }
        } getOrElse {
          FastFuture.successful(
            Results
              .Redirect(redirectTo)
              .addingToSession(
                "bo-redirect-after-login" -> s"${env.rootScheme}${request.host}${request.uri}"
              )
          )
        }
      }
      case _ => {
        // TODO : based on Accept header
        FastFuture.successful(Results.NotFound(views.html.otoroshi.error("Not found", env)))
      }
    }
  }
}
