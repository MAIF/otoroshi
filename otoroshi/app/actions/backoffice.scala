package actions

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.util.FastFuture
import auth.GenericOauth2Module
import env.Env
import events.{Alerts, BlackListedBackOfficeUserAlert}
import gateway.Errors
import models.BackOfficeUser
import play.api.mvc.Results.Status
import play.api.mvc._
import utils.RequestImplicits._
import utils.TypedMap

import scala.concurrent.{ExecutionContext, Future}

case class BackOfficeActionContext[A](request: Request[A], user: Option[BackOfficeUser]) {
  def connected: Boolean              = user.isDefined
  def from(implicit env: Env): String = request.theIpAddress
  def ua: String                      = request.theUserAgent
}

case class BackOfficeActionContextAuth[A](request: Request[A], user: BackOfficeUser) {
  def from(implicit env: Env): String = request.theIpAddress
  def ua: String                      = request.theUserAgent
}

class BackOfficeAction(val parser: BodyParser[AnyContent])(implicit env: Env)
    extends ActionBuilder[BackOfficeActionContext, AnyContent]
    with ActionFunction[Request, BackOfficeActionContext] {

  implicit lazy val ec = env.otoroshiExecutionContext

  override def invokeBlock[A](request: Request[A],
                              block: (BackOfficeActionContext[A]) => Future[Result]): Future[Result] = {
    val host = request.theDomain // if (request.host.contains(":")) request.host.split(":")(0) else request.host
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
        Errors.craftResponseResult(s"Not found",
                                   Status(404),
                                   request,
                                   None,
                                   Some("errors.not.found"),
                                   attrs = TypedMap.empty)
      }
    }
  }

  override protected def executionContext: ExecutionContext = ec
}

class BackOfficeActionAuth(val parser: BodyParser[AnyContent])(implicit env: Env)
    extends ActionBuilder[BackOfficeActionContextAuth, AnyContent]
    with ActionFunction[Request, BackOfficeActionContextAuth] {

  implicit lazy val ec = env.otoroshiExecutionContext

  // val checker = new AdminClearanceChecker()(env)

  override def invokeBlock[A](request: Request[A],
                              block: (BackOfficeActionContextAuth[A]) => Future[Result]): Future[Result] = {

    implicit val req = request

    val host = request.theDomain // if (request.host.contains(":")) request.host.split(":")(0) else request.host
    host match {
      case env.backOfficeHost => {

        def callAction() = {
          // val redirectTo = env.rootScheme + env.backOfficeHost + controllers.routes.Auth0Controller.backOfficeLogin(Some(s"${env.rootScheme}${request.host}${request.relativeUri}")).url
          val redirectTo = env.rootScheme + request.theHost + controllers.routes.BackOfficeController.index().url
          request.session.get("bousr").map { id =>
            env.datastores.backOfficeUserDataStore.findById(id).flatMap {
              case Some(user) => {
                env.datastores.backOfficeUserDataStore.blacklisted(user.email).flatMap {
                  case true => {
                    Alerts.send(
                      BlackListedBackOfficeUserAlert(
                        env.snowflakeGenerator.nextIdStr(),
                        env.env,
                        user,
                        request.theIpAddress,
                        request.theUserAgent
                      )
                    )
                    FastFuture.successful(
                      Results.NotFound(views.html.otoroshi.error("Error", env)).removingFromSession("bousr")(request)
                    )
                  }
                  case false =>
                    //checker.check(req, user) {
                      user.withAuthModuleConfig { auth =>
                        GenericOauth2Module.handleTokenRefresh(auth, user)
                      }
                      block(BackOfficeActionContextAuth(request, user))
                    //}
                }
              }
              case None =>
                FastFuture.successful(
                  Results
                    .Redirect(redirectTo)
                    .addingToSession(
                      "bo-redirect-after-login" -> s"${env.rootScheme}${request.theHost}${request.relativeUri}"
                    )
                )
            }
          } getOrElse {
            FastFuture.successful(
              Results
                .Redirect(redirectTo)
                .addingToSession(
                  "bo-redirect-after-login" -> s"${env.rootScheme}${request.theHost}${request.relativeUri}"
                )
            )
          }
        }

        request.headers
          .get("Origin")
          .map(Uri.apply)
          .orElse(
            request.headers.get("Referer").map(Uri.apply).map(uri => uri.copy(path = Path.Empty))
          )
          .map(u => u.authority.copy(port = 0).toString()) match {
          case Some(origin) if origin == env.backOfficeHost => callAction()
          case Some(origin) if origin != env.backOfficeHost && request.method.toLowerCase != "get" =>
            Errors.craftResponseResult(s"Bad origin",
                                       Status(417),
                                       request,
                                       None,
                                       Some("errors.bad.origin"),
                                       attrs = TypedMap.empty)
          case _ => callAction()
        }
      }
      case _ => {
        Errors.craftResponseResult(s"Not found",
                                   Status(404),
                                   request,
                                   None,
                                   Some("errors.not.found"),
                                   attrs = TypedMap.empty)
      }
    }
  }

  override protected def executionContext: ExecutionContext = ec
}