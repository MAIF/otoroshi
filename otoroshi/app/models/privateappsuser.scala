package models

import akka.http.scaladsl.util.FastFuture
import env.Env
import models.ApiKeyHelper.PassWithApiKeyContext
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.Results.InternalServerError
import play.api.mvc.{RequestHeader, Result, Results}
import storage.BasicStore
import utils.JsonImplicits._
import utils.TypedMap

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class PrivateAppsUser(randomId: String,
                           name: String,
                           email: String,
                           profile: JsValue,
                           token: JsValue = Json.obj(),
                           realm: String,
                           otoroshiData: Option[JsValue],
                           createdAt: DateTime = DateTime.now(),
                           expiredAt: DateTime = DateTime.now()) {

  def picture: Option[String]             = (profile \ "picture").asOpt[String]
  def field(name: String): Option[String] = (profile \ name).asOpt[String]
  def userId: Option[String]              = (profile \ "user_id").asOpt[String].orElse((profile \ "sub").asOpt[String])

  def save(duration: Duration)(implicit ec: ExecutionContext, env: Env): Future[PrivateAppsUser] =
    env.datastores.privateAppsUserDataStore
      .set(this.copy(expiredAt = DateTime.now().plusMillis(duration.toMillis.toInt)), Some(duration))
      .map(_ => this)

  def delete()(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    env.datastores.privateAppsUserDataStore.delete(randomId)

  def toJson: JsValue = PrivateAppsUser.fmt.writes(this)
  def asJsonCleaned: JsValue = Json.obj(
    "name"     -> name,
    "email"    -> email,
    "profile"  -> profile,
    "metadata" -> otoroshiData
  )
}

object PrivateAppsUser {

  def select(from: JsValue, selector: String): JsValue = {
    val parts = selector.split("\\|")
    parts.foldLeft(from) { (o, rawPath) =>
      val path = rawPath.trim
      (o \ path).asOpt[JsValue].getOrElse(JsNull)
    }
  }

  val fmt = new Format[PrivateAppsUser] {
    override def reads(json: JsValue) =
      Try {
        JsSuccess(
          PrivateAppsUser(
            randomId = (json \ "randomId").as[String],
            name = (json \ "name").as[String],
            email = (json \ "email").as[String],
            profile = (json \ "profile").as[JsValue],
            token = (json \ "token").asOpt[JsValue].getOrElse(Json.obj()),
            realm = (json \ "realm").asOpt[String].getOrElse("none"),
            otoroshiData = (json \ "otoroshiData").asOpt[JsValue],
            createdAt = new DateTime((json \ "createdAt").as[Long]),
            expiredAt = new DateTime((json \ "expiredAt").as[Long])
          )
        )
      } recover {
        case e => JsError(e.getMessage)
      } get

    override def writes(o: PrivateAppsUser) = Json.obj(
      "randomId"     -> o.randomId,
      "name"         -> o.name,
      "email"        -> o.email,
      "profile"      -> o.profile,
      "token"        -> o.token,
      "realm"        -> o.realm,
      "otoroshiData" -> o.otoroshiData,
      "createdAt"    -> o.createdAt.toDate.getTime,
      "expiredAt"    -> o.expiredAt.toDate.getTime,
    )
  }
}

trait PrivateAppsUserDataStore extends BasicStore[PrivateAppsUser]

object PrivateAppsUserHelper {

  import utils.RequestImplicits._

  case class PassWithAuthContext(req: RequestHeader, query: ServiceDescriptorQuery, descriptor: ServiceDescriptor, attrs: TypedMap, config: GlobalConfig, logger: Logger)

  def isPrivateAppsSessionValid(req: RequestHeader, desc: ServiceDescriptor, attrs: TypedMap)(implicit executionContext: ExecutionContext, env: Env): Future[Option[PrivateAppsUser]] = {
    attrs.get(otoroshi.plugins.Keys.UserKey) match {
      case Some(preExistingUser) => FastFuture.successful(Some(preExistingUser))
      case _ => desc.authConfigRef match {
        case Some(ref) =>
          env.datastores.authConfigsDataStore.findById(ref).flatMap {
            case None => FastFuture.successful(None)
            case Some(auth) => {
              val expected = "oto-papps-" + auth.cookieSuffix(desc)
              req.cookies
                .get(expected)
                .flatMap { cookie =>
                  env.extractPrivateSessionId(cookie)
                }
                .orElse(
                  req.getQueryString("pappsToken").flatMap(value => env.extractPrivateSessionIdFromString(value))
                )
                .orElse(
                  req.headers.get("Otoroshi-Token").flatMap(value => env.extractPrivateSessionIdFromString(value))
                )
                .map { id =>
                  env.datastores.privateAppsUserDataStore.findById(id)
                } getOrElse {
                FastFuture.successful(None)
              }
            }
          }
        case None => FastFuture.successful(None)
      }
    }
  }

  def passWithAuth[T](
    ctx: PassWithAuthContext,
    callDownstream: (GlobalConfig, Option[ApiKey], Option[PrivateAppsUser]) => Future[Either[Result, T]],
    errorResult: (Results.Status, String, String) => Future[Either[Result, T]]
  )(implicit ec: ExecutionContext, env: Env): Future[Either[Result, T]] = {

    val PassWithAuthContext(req, query, descriptor, attrs, config, logger) = ctx

    isPrivateAppsSessionValid(req, descriptor, attrs).flatMap {
      case Some(paUsr) =>
        callDownstream(config, None, Some(paUsr))
      case None => {
        val redirect = req
          .getQueryString("redirect")
          .getOrElse(s"${req.theProtocol}://${req.theHost}${req.relativeUri}")
        val redirectTo = env.rootScheme + env.privateAppsHost + env.privateAppsPort
          .map(a => s":$a")
          .getOrElse("") + controllers.routes.AuthController
          .confidentialAppLoginPage()
          .url + s"?desc=${descriptor.id}&redirect=${redirect}"
        logger.trace("should redirect to " + redirectTo)
        descriptor.authConfigRef match {
          case None =>
            errorResult(InternalServerError, "Auth. config. ref not found on the descriptor", "errors.auth.config.ref.not.found")
          case Some(ref) => {
            env.datastores.authConfigsDataStore.findById(ref).flatMap {
              case None =>
                errorResult(InternalServerError, "Auth. config. not found on the descriptor", "errors.auth.config.not.found")
              case Some(auth) => {
                FastFuture
                  .successful(
                    Left(Results
                      .Redirect(redirectTo)
                      .discardingCookies(
                        env.removePrivateSessionCookies(
                          query.toHost,
                          descriptor,
                          auth
                        ): _*
                      ))
                  )
              }
            }
          }
        }
      }
    }
  }
}
