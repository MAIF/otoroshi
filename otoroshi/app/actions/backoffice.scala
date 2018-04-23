package actions

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.util.FastFuture
import akka.util.ByteString
import env.Env
import gateway.Errors
import events.{Alerts, BlackListedBackOfficeUserAlert}
import models.BackOfficeUser
import play.api.Logger
import play.api.http.HttpEntity
import play.api.libs.json.{JsArray, JsValue, Json}
import play.api.mvc._
import play.api.mvc.Results.Status

import scala.concurrent.{ExecutionContext, Future}

case class BackOfficeActionContext[A](request: Request[A], user: Option[BackOfficeUser]) {
  def connected: Boolean = user.isDefined
  def from: String       = request.headers.get("X-Forwarded-For").getOrElse(request.remoteAddress)
}

case class BackOfficeActionContextAuth[A](request: Request[A], user: BackOfficeUser) {
  def from: String = request.headers.get("X-Forwarded-For").getOrElse(request.remoteAddress)
}

class BackOfficeAction(val parser: BodyParser[AnyContent])(implicit env: Env)
    extends ActionBuilder[BackOfficeActionContext, AnyContent]
    with ActionFunction[Request, BackOfficeActionContext] {

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
        Errors.craftResponseResult(s"Not found", Status(404), request, None, Some("errors.not.found"))
      }
    }
  }

  override protected def executionContext: ExecutionContext = ec
}

class BackOfficeActionAuth(val parser: BodyParser[AnyContent])(implicit env: Env)
    extends ActionBuilder[BackOfficeActionContextAuth, AnyContent]
    with ActionFunction[Request, BackOfficeActionContextAuth] {

  implicit lazy val ec = env.backOfficeExecutionContext

  val checker = new AdminClearanceChecker()(env)

  override def invokeBlock[A](request: Request[A],
                              block: (BackOfficeActionContextAuth[A]) => Future[Result]): Future[Result] = {

    implicit val req = request

    val host = if (request.host.contains(":")) request.host.split(":")(0) else request.host
    host match {
      case env.backOfficeHost => {

        def callAction() = {
          // val redirectTo = env.rootScheme + env.backOfficeHost + controllers.routes.Auth0Controller.backOfficeLogin(Some(s"${env.rootScheme}${request.host}${request.uri}")).url
          val redirectTo = env.rootScheme + request.host + controllers.routes.BackOfficeController.index().url
          request.session.get("bousr").map { id =>
            env.datastores.backOfficeUserDataStore.findById(id).flatMap {
              case Some(user) => {
                env.datastores.backOfficeUserDataStore.blacklisted(user.email).flatMap {
                  case true => {
                    Alerts.send(BlackListedBackOfficeUserAlert(env.snowflakeGenerator.nextIdStr(), env.env, user))
                    FastFuture.successful(
                      Results.NotFound(views.html.otoroshi.error("Error", env)).removingFromSession("bousr")(request)
                    )
                  }
                  case false =>
                    checker.check(req, user) {
                      block(BackOfficeActionContextAuth(request, user))
                    }
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

        request.headers
          .get("Origin")
          .map(Uri.apply)
          .orElse(
            request.headers.get("Referer").map(Uri.apply).map(uri => uri.copy(path = Path.Empty))
          )
          .map(u => u.authority.copy(port = 0).toString()) match {
          case Some(origin) if origin == env.backOfficeHost => callAction()
          case Some(origin) if origin != env.backOfficeHost && request.method.toLowerCase != "get" =>
            Errors.craftResponseResult(s"Bad origin", Status(417), request, None, Some("errors.bad.origin"))
          case _ => callAction()
        }
      }
      case _ => {
        Errors.craftResponseResult(s"Not found", Status(404), request, None, Some("errors.not.found"))
      }
    }
  }

  override protected def executionContext: ExecutionContext = ec
}

/**
 * A class to check if a user can do something based on clearance metadata (only authorized on one service group)
 * This class is only an experimentation to see if we actually need this feature.
 * If so, it will be rewritten from scratch to implement it correctly everywhere based on a fine grained habiliation model.
 *
 * PLEASE BE INDULGENT THE FOLLOWING CODE :)
 */
class AdminClearanceChecker()(implicit env: Env) {

  import kaleidoscope._

  implicit val ec  = env.apiExecutionContext
  implicit val mat = env.materializer

  lazy val logger = Logger("otoroshi-clearance-checker")

  def callIfServiceIsInGroup(groupId: String, serviceId: String)(f: => Future[Result]): Future[Result] = {
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case Some(service) if service.groupId == groupId => f
      case _                                           => FastFuture.successful(Results.Unauthorized(Json.obj("error" -> "unauthorized")))
    }
  }

  def filterBody(result: Result, groupId: String, filterKey: String): Future[Result] = {
    result.body.dataStream.runFold(ByteString.empty)(_ ++ _).map { body =>
      val arr     = Json.parse(body.utf8String).as[JsArray]
      val newBody = ByteString(Json.stringify(JsArray(arr.value.filter(v => (v \ filterKey).as[String] == groupId))))
      result.copy(body = HttpEntity.Strict(newBody, result.body.contentType))
    }
  }

  def checkAdminApiCall(req: Request[_], user: BackOfficeUser, groupId: String)(
      f: => Future[Result]
  ): Future[Result] = {
    if (!req.path.startsWith("/bo/api/proxy/")) {
      FastFuture.successful(Results.Unauthorized(Json.obj("error" -> "unauthorized")))
    } else
      req.path.replace("/bo/api/proxy", "") match {
        case r"/api/groups/$group@([^/]+)/[./]*" if req.method == "GET" && group != groupId =>
          FastFuture.successful(Results.Ok(Json.arr()))
        case r"/api/groups/$group@([^/]+)/[./]*" if group != groupId =>
          FastFuture.successful(Results.Ok(Json.obj("done" -> false)))
        case r"/api/services/$serviceId@([^/]+)/apikeys/[^/]+/quotas" => callIfServiceIsInGroup(groupId, serviceId)(f)
        case r"/api/services/$serviceId@([^/]+)/apikeys/[^/]+/group"  => callIfServiceIsInGroup(groupId, serviceId)(f)
        case r"/api/services/$serviceId@([^/]+)/apikeys/[^/]+"        => callIfServiceIsInGroup(groupId, serviceId)(f)
        case r"/api/services/$serviceId@([^/]+)/apikeys"              => callIfServiceIsInGroup(groupId, serviceId)(f)
        case r"/api/services/$serviceId@([^/]+)/template"             => callIfServiceIsInGroup(groupId, serviceId)(f)
        case r"/api/services/$serviceId@([^/]+)/targets"              => callIfServiceIsInGroup(groupId, serviceId)(f)
        case r"/api/services/$serviceId@([^/]+)/live"                 => callIfServiceIsInGroup(groupId, serviceId)(f)
        case r"/api/services/$serviceId@([^/]+)/stats"                => callIfServiceIsInGroup(groupId, serviceId)(f)
        case r"/api/services/$serviceId@([^/]+)/events"               => callIfServiceIsInGroup(groupId, serviceId)(f)
        case r"/api/services/$serviceId@([^/]+)/health"               => callIfServiceIsInGroup(groupId, serviceId)(f)
        case r"/api/services/$serviceId@([^/]+)/canary"               => callIfServiceIsInGroup(groupId, serviceId)(f)
        case r"/api/services/$serviceId@([^/]+)"                      => callIfServiceIsInGroup(groupId, serviceId)(f)
        case "/api/groups" if req.method == "POST" =>
          FastFuture.successful(Results.Unauthorized(Json.obj("error" -> "unauthorized")))
        case r"/api/apikeys"                            => f.flatMap(r => filterBody(r, groupId, "authorizedGroup"))
        case r"/api/services"                           => f.flatMap(r => filterBody(r, groupId, "groupId"))
        case r"/api/groups"                             => f.flatMap(r => filterBody(r, groupId, "id"))
        case r"/api/lines/[^/]+/services"               => f.flatMap(r => filterBody(r, groupId, "groupId"))
        case "/api/globalconfig" if req.method == "GET" => FastFuture.successful(Results.Ok(Json.obj()))
        case "/api/globalconfig" if req.method == "PUT" => FastFuture.successful(Results.Ok(Json.obj("done" -> false)))
        case "/api/globalconfig" if req.method == "PATCH" =>
          FastFuture.successful(Results.Ok(Json.obj("done" -> false)))
        case "/api/otoroshi.json" if req.method == "GET" => FastFuture.successful(Results.Ok(Json.obj()))
        case "/api/otoroshi.json" if req.method == "POST" =>
          FastFuture.successful(Results.Ok(Json.obj("done" -> false)))
        case "/api/import" if req.method == "POST" => FastFuture.successful(Results.Ok(Json.obj("done" -> false)))
        case "/api/stats/global"                   => FastFuture.successful(Results.Ok(Json.arr()))
        case "/api/services" if req.method == "POST" => {
          (req.asInstanceOf[Request[JsValue]].body \ "groupId").asOpt[String] match {
            case Some(group) if group == groupId => f
            case _                               => FastFuture.successful(Results.Unauthorized(Json.obj("error" -> "unauthorized")))
          }
        }
        case r"/api/live/$serviceId@([^/]+)" if req.method == "GET" => {
          if (serviceId == "global") {
            f
          } else {
            env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
              case Some(service) if service.groupId == groupId => f
              case _                                           => FastFuture.successful(Results.NotFound(Json.obj()))
            }
          }
        }

        case r"/api/groups/$group@([^/]+)/services" if group == groupId => f
        case r"/api/groups/$group@([^/]+)" if group == groupId          => f
        case r"/api/groups/$group@([^/]+)/[./]*" if group == groupId    => f
        case "/api/live/host"                                           => f
        case "/api/live"                                                => f
        case "/api/lines"                                               => f
        case "/api/new/apikey"                                          => f
        case "/api/new/service"                                         => f
        case "/api/new/group"                                           => f

        case _ => FastFuture.successful(Results.Unauthorized(Json.obj("error" -> "unauthorized")))
      }
  }

  def check(req: Request[_], user: BackOfficeUser)(f: => Future[Result]): Future[Result] = {
    user.authorizedGroup match {
      case None => f
      case Some(groupId) => {
        logger.warn(s"User ${user.email} is only authorized for group: $groupId => ${req.path}")
        req.path match {
          case uri if uri.startsWith("/bo/dashboard")             => f
          case r"/bo/api/lines/[^/]+/$serviceId@([^/]+)/docframe" => callIfServiceIsInGroup(groupId, serviceId)(f)
          case r"/bo/api/lines/[^/]+/$serviceId@([^/]+)/docdesc"  => callIfServiceIsInGroup(groupId, serviceId)(f)
          case "/bo/api/version"                                  => f
          case "/bo/api/env"                                      => f
          case "/bo/api/apps"                                     => FastFuture.successful(Results.Ok(Json.arr()))
          case "/bo/api/sessions" if req.method == "GET"          => FastFuture.successful(Results.Ok(Json.arr()))
          case "/bo/api/sessions" if req.method == "DELETE" =>
            FastFuture.successful(Results.Ok(Json.obj("done" -> false)))
          case r"/bo/api/sessions/[^/]+" if req.method == "DELETE" =>
            FastFuture.successful(Results.Ok(Json.obj("done" -> false)))
          case "/bo/api/apps/sessions" if req.method == "GET" => FastFuture.successful(Results.Ok(Json.arr()))
          case "/bo/api/apps/sessions" if req.method == "DELETE" =>
            FastFuture.successful(Results.Ok(Json.obj("done" -> false)))
          case r"/bo/api/apps/sessions/[^/]+" if req.method == "DELETE" =>
            FastFuture.successful(Results.Ok(Json.obj("done" -> false)))
          case "/bo/api/panic"          => FastFuture.successful(Results.Ok(Json.obj("done" -> false)))
          case "/bo/api/events/audit"   => FastFuture.successful(Results.Ok(Json.arr()))
          case "/bo/api/events/alert"   => FastFuture.successful(Results.Ok(Json.arr()))
          case "/bo/api/services/top10" => FastFuture.successful(Results.Ok(Json.arr()))
          case "/bo/api/services/map" =>
            FastFuture.successful(Results.Ok(Json.obj("name" -> "Otoroshi Services", "children" -> Json.arr())))
          case r"/bo/api/loggers" if req.method == "GET"             => f
          case r"/bo/api/loggers/[^/]+/level" if req.method == "GET" => f
          case r"/bo/api/loggers/$name@([^/]+)/level" if req.method == "PUT" =>
            FastFuture.successful(Results.Ok(Json.obj("name" -> name, "oldLevel" -> "--", "newLevel" -> "--")))
          case r"/bo/api/services/[^/]+/circuitbreakers" if req.method == "DELETE" =>
            FastFuture.successful(Results.Ok(Json.obj("done" -> false)))
          case "/bo/api/search/services" => f.flatMap(r => filterBody(r, groupId, "groupId"))
          case "/"                       => f
          case "/robot.txt"              => f
          case "/error"                  => f
          case "/auth0error"             => f
          case "/bo/u2f/register/start" =>
            FastFuture.successful(Results.Unauthorized(Json.obj("error" -> "unauthorized")))
          case "/bo/u2f/register/finish" =>
            FastFuture.successful(Results.Unauthorized(Json.obj("error" -> "unauthorized")))
          case "/bo/u2f/login/start" => FastFuture.successful(Results.Unauthorized(Json.obj("error" -> "unauthorized")))
          case "/bo/u2f/login/finish" =>
            FastFuture.successful(Results.Unauthorized(Json.obj("error" -> "unauthorized")))
          case "/bo/u2f/admins" => FastFuture.successful(Results.Unauthorized(Json.obj("error" -> "unauthorized")))
          case r"/bo/u2f/admins/[^/]+/[^/]+" =>
            FastFuture.successful(Results.Unauthorized(Json.obj("error" -> "unauthorized")))
          case uri if uri.startsWith("/backoffice/auth0/")  => f
          case uri if uri.startsWith("/privateapps/auth0/") => f
          case "/bo/simple/login"                           => f
          case "/privateapps/error"                         => f
          case "/privateapps/home"                          => f
          case "/privateapps/redirect"                      => f
          case "/bo/simple/admins"                          => FastFuture.successful(Results.Unauthorized(Json.obj("error" -> "unauthorized")))
          case r"/bo/simple/admins/[^/]+" =>
            FastFuture.successful(Results.Unauthorized(Json.obj("error" -> "unauthorized")))
          case uri if uri.startsWith("/bo/api/proxy/") => checkAdminApiCall(req, user, groupId)(f)
          case _                                       => FastFuture.successful(Results.Unauthorized(Json.obj("error" -> "unauthorized")))
        }
      }
    }
  }
}
