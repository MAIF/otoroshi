package otoroshi.actions

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.util.FastFuture
import otoroshi.auth.GenericOauth2Module
import otoroshi.env.Env
import otoroshi.events.{Alerts, BlackListedBackOfficeUserAlert}
import otoroshi.gateway.Errors
import otoroshi.models.BackOfficeUser
import otoroshi.models.RightsChecker.{SuperAdminOnly, TenantAdminOnly}
import otoroshi.models.{EntityLocationSupport, RightsChecker, TenantId}
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.Json
import play.api.mvc.Results.Status
import play.api.mvc._
import otoroshi.utils.http.RequestImplicits._

import scala.concurrent.{ExecutionContext, Future}

case class BackOfficeActionContext[A](request: Request[A], user: Option[BackOfficeUser]) {
  def connected: Boolean              = user.isDefined
  def from(implicit env: Env): String = request.theIpAddress
  def ua: String                      = request.theUserAgent
}

case class BackOfficeActionContextAuth[A](request: Request[A], user: BackOfficeUser) {

  lazy val forbidden  = ApiActionContext.forbidden
  lazy val fforbidden = ApiActionContext.fforbidden

  def from(implicit env: Env): String = request.theIpAddress
  def ua: String                      = request.theUserAgent

  lazy val currentTenant: TenantId = {
    val value = request.headers.get("Otoroshi-Tenant").getOrElse("default")
    TenantId(value)
  }

  private def rootOrTenantAdmin(user: BackOfficeUser)(f: => Boolean)(implicit env: Env): Boolean = {
    if (env.bypassUserRightsCheck || SuperAdminOnly.canPerform(user, currentTenant)) { // || TenantAdminOnly.canPerform(user, currentTenant)) {
      true
    } else {
      f
    }
  }

  def canUserRead[T <: EntityLocationSupport](item: T)(implicit env: Env): Boolean = {
    rootOrTenantAdmin(user) {
      (currentTenant.value == item.location.tenant.value || item.location.tenant == TenantId.all) && user.rights
        .canReadTenant(item.location.tenant) && user.rights.canReadTeams(currentTenant, item.location.teams)
    }
  }
  def canUserWrite[T <: EntityLocationSupport](item: T)(implicit env: Env): Boolean = {
    rootOrTenantAdmin(user) {
      (currentTenant.value == item.location.tenant.value || item.location.tenant == TenantId.all) && user.rights
        .canWriteTenant(item.location.tenant) && user.rights.canWriteTeams(currentTenant, item.location.teams)
    }
  }

  def checkRights(rc: RightsChecker)(f: Future[Result])(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    if (env.bypassUserRightsCheck) {
      f
    } else {
      if (rc.canPerform(user, currentTenant)) {
        f
      } else {
        Results.Forbidden(Json.obj("error" -> "You're not authorized here !")).future
      }
    }
  }
}

class BackOfficeAction(val parser: BodyParser[AnyContent])(implicit env: Env)
    extends ActionBuilder[BackOfficeActionContext, AnyContent]
    with ActionFunction[Request, BackOfficeActionContext] {

  implicit lazy val ec = env.otoroshiExecutionContext

  override def invokeBlock[A](
      request: Request[A],
      block: (BackOfficeActionContext[A]) => Future[Result]
  ): Future[Result] = {
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
      case _                  => {
        Errors.craftResponseResult(
          s"Not found",
          Status(404),
          request,
          None,
          Some("errors.not.found"),
          attrs = TypedMap.empty
        )
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

  override def invokeBlock[A](
      request: Request[A],
      block: (BackOfficeActionContextAuth[A]) => Future[Result]
  ): Future[Result] = {

    implicit val req = request

    val host = request.theDomain // if (request.host.contains(":")) request.host.split(":")(0) else request.host
    host match {
      case env.backOfficeHost => {

        def callAction() = {
          // val redirectTo = env.rootScheme + env.backOfficeHost + otoroshi.controllers.routes.Auth0Controller.backOfficeLogin(Some(s"${env.rootScheme}${request.host}${request.relativeUri}")).url
          val redirectTo =
            env.rootScheme + request.theHost + otoroshi.controllers.routes.BackOfficeController.index().url
          request.session.get("bousr").map { id =>
            env.datastores.backOfficeUserDataStore.findById(id).flatMap {
              case Some(user) => {
                env.datastores.backOfficeUserDataStore.blacklisted(user.email).flatMap {
                  case true  => {
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
                      Results.NotFound(views.html.oto.error("Error", env)).removingFromSession("bousr")(request)
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
              case None       =>
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
          case Some(origin) if origin == env.backOfficeHost                                        => callAction()
          case Some(origin) if origin != env.backOfficeHost && request.method.toLowerCase != "get" =>
            Errors.craftResponseResult(
              s"Bad origin",
              Status(417),
              request,
              None,
              Some("errors.bad.origin"),
              attrs = TypedMap.empty
            )
          case _                                                                                   => callAction()
        }
      }
      case _ => {
        Errors.craftResponseResult(
          s"Not found",
          Status(404),
          request,
          None,
          Some("errors.not.found"),
          attrs = TypedMap.empty
        )
      }
    }
  }

  override protected def executionContext: ExecutionContext = ec
}
