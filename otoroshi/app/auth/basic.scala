package auth

import akka.http.scaladsl.util.FastFuture
import com.google.common.base.Charsets
import controllers.routes
import env.Env
import models._
import org.mindrot.jbcrypt.BCrypt
import play.api.Logger
import play.api.libs.json._
import play.api.mvc._
import security.{IdGenerator, OtoroshiClaim}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class BasicAuthUser(
    name: String,
    password: String,
    email: String,
    metadata: JsObject = Json.obj()
) {
  def asJson: JsValue = BasicAuthUser.fmt.writes(this)
}

object BasicAuthUser {
  def fmt = new Format[BasicAuthUser] {
    override def writes(o: BasicAuthUser) = Json.obj(
      "name"     -> o.name,
      "password" -> o.password,
      "email"    -> o.email,
      "metadata" -> o.metadata,
    )
    override def reads(json: JsValue) =
      Try {
        JsSuccess(
          BasicAuthUser(
            name = (json \ "name").as[String],
            password = (json \ "password").as[String],
            email = (json \ "email").as[String],
            metadata = (json \ "metadata").asOpt[JsObject].getOrElse(Json.obj())
          )
        )
      } recover {
        case e => JsError(e.getMessage)
      } get
  }
}

object BasicAuthModuleConfig extends FromJson[AuthModuleConfig] {

  lazy val logger = Logger("otoroshi-basic-auth-config")

  def fromJsons(value: JsValue): BasicAuthModuleConfig =
    try {
      _fmt.reads(value).get
    } catch {
      case e: Throwable => {
        logger.error(s"Try to deserialize ${Json.prettyPrint(value)}")
        throw e
      }
    }

  val _fmt = new Format[BasicAuthModuleConfig] {

    override def reads(json: JsValue) = fromJson(json) match {
      case Left(e)  => JsError(e.getMessage)
      case Right(v) => JsSuccess(v.asInstanceOf[BasicAuthModuleConfig])
    }

    override def writes(o: BasicAuthModuleConfig) = o.asJson
  }

  override def fromJson(json: JsValue): Either[Throwable, AuthModuleConfig] =
    Try {
      Right(
        BasicAuthModuleConfig(
          id = (json \ "id").as[String],
          name = (json \ "name").as[String],
          desc = (json \ "desc").asOpt[String].getOrElse("--"),
          sessionMaxAge = (json \ "sessionMaxAge").asOpt[Int].getOrElse(86400),
          basicAuth = (json \ "basicAuth").asOpt[Boolean].getOrElse(false),
          users = (json \ "users").asOpt(Reads.seq(BasicAuthUser.fmt)).getOrElse(Seq.empty[BasicAuthUser])
        )
      )
    } recover {
      case e => Left(e)
    } get
}

case class BasicAuthModuleConfig(
    id: String,
    name: String,
    desc: String,
    users: Seq[BasicAuthUser] = Seq.empty[BasicAuthUser],
    sessionMaxAge: Int = 86400,
    basicAuth: Boolean = false,
) extends AuthModuleConfig {
  def `type`: String                                        = "basic"
  override def authModule(config: GlobalConfig): AuthModule = BasicAuthModule(this)
  override def asJson = Json.obj(
    "type"          -> "basic",
    "id"            -> this.id,
    "name"          -> this.name,
    "desc"          -> this.desc,
    "sessionMaxAge" -> this.sessionMaxAge,
    "users"         -> Writes.seq(BasicAuthUser.fmt).writes(this.users)
  )
  def save()(implicit ec: ExecutionContext, env: Env): Future[Boolean] = env.datastores.authConfigsDataStore.set(this)
  override def cookieSuffix(desc: ServiceDescriptor)                   = s"basic-auth-$id"
}

case class BasicAuthModule(authConfig: BasicAuthModuleConfig) extends AuthModule {

  import utils.future.Implicits._

  def decodeBase64(encoded: String): String = new String(OtoroshiClaim.decoder.decode(encoded), Charsets.UTF_8)

  def extractUsernamePassword(header: String): Option[(String, String)] = {
    val base64 = header.replace("Basic ", "").replace("basic ", "")
    Option(base64)
      .map(decodeBase64).map(_.split(":").toSeq).
      flatMap(a => a.headOption.flatMap(head => a.lastOption.map(last => (head, last))))

  }

  def bindUser(username: String, password: String, descriptor: ServiceDescriptor): Either[String, PrivateAppsUser] = {
    authConfig.users
      .find(u => u.email == username)
      .filter(u => BCrypt.checkpw(password, u.password)) match {
      case Some(user) =>
        Right(
          PrivateAppsUser(
            randomId = IdGenerator.token(64),
            name = user.name,
            email = user.email,
            profile = user.asJson,
            realm = authConfig.cookieSuffix(descriptor),
            otoroshiData = user.metadata.asOpt[Map[String, String]]
          )
        )
      case None => Left(s"You're not authorized here")
    }
  }

  def bindAdminUser(username: String, password: String): Either[String, BackOfficeUser] = {
    authConfig.users
      .find(u => u.email == username)
      .filter(u => BCrypt.checkpw(password, u.password)) match {
      case Some(user) =>
        Right(
          BackOfficeUser(
            randomId = IdGenerator.token(64),
            name = user.name,
            email = user.email,
            profile = user.asJson,
            authorizedGroup = None,
            simpleLogin = false
          )
        )
      case None => Left(s"You're not authorized here")
    }
  }

  override def paLoginPage(request: RequestHeader,
                           config: GlobalConfig,
                           descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    implicit val req = request
    val redirect     = request.getQueryString("redirect")
    env.datastores.authConfigsDataStore.generateLoginToken().flatMap { token =>

      if (authConfig.basicAuth) {

        def unauthorized() = Results
          .Unauthorized(views.html.otoroshi.error("You are not authorized here", env))
          .withHeaders("WWW-Authenticate" -> authConfig.cookieSuffix(descriptor))
          .addingToSession(
            "pa-redirect-after-login" -> redirect.getOrElse(
              routes.PrivateAppsController.home().absoluteURL(env.isProd && env.exposedRootSchemeIsHttps)
            )
          ).future

        req.headers.get("Authorization") match {
          case Some(auth) if auth.startsWith("Basic ") => extractUsernamePassword(auth) match {
            case None => Results.Forbidden(views.html.otoroshi.error("Forbidden access", env)).future
            case Some((username, password)) => bindUser(username, password, descriptor) match {
              case Left(_) => Results.Forbidden(views.html.otoroshi.error("Forbidden access", env)).future
              case Right(user) => env.datastores.authConfigsDataStore.setUserForToken(token, user.toJson).map { _ =>
                Results.Redirect(s"/privateapps/generic/callback?desc=${descriptor.id}&token=$token")
              }
            }
          }
          case _ => unauthorized()
        }
      } else {
        Results
          .Ok(views.html.otoroshi.login(s"/privateapps/generic/callback?desc=${descriptor.id}", "POST", token, env))
          .addingToSession(
            "pa-redirect-after-login" -> redirect.getOrElse(
              routes.PrivateAppsController.home().absoluteURL(env.isProd && env.exposedRootSchemeIsHttps)
            )
          ).future
      }
    }
  }

  override def paLogout(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(
      implicit ec: ExecutionContext,
      env: Env
  ) = FastFuture.successful(None)

  override def paCallback(request: Request[AnyContent], config: GlobalConfig, descriptor: ServiceDescriptor)(
      implicit ec: ExecutionContext,
      env: Env
  ): Future[Either[String, PrivateAppsUser]] = {
    implicit val req = request
    if (req.method == "GET" && authConfig.basicAuth) {
      req.getQueryString("token") match {
        case Some(token) => env.datastores.authConfigsDataStore.getUserForToken(token).map(_.flatMap(a => PrivateAppsUser.fmt.reads(a).asOpt)).map {
          case Some(user) => Right(user)
          case None => Left("No user found")
        }
        case _ => FastFuture.successful(Left("Forbidden access"))
      }
    } else {
      request.body.asFormUrlEncoded match {
        case None => FastFuture.successful(Left("No Authorization form here"))
        case Some(form) => {
          (form.get("username").map(_.last), form.get("password").map(_.last), form.get("token").map(_.last)) match {
            case (Some(username), Some(password), Some(token)) => {
              env.datastores.authConfigsDataStore.validateLoginToken(token).map {
                case false => Left("Bad token")
                case true =>
                  authConfig.users
                    .find(u => u.email == username)
                    .filter(u => BCrypt.checkpw(password, u.password)) match {
                    case Some(user) =>
                      Right(
                        PrivateAppsUser(
                          randomId = IdGenerator.token(64),
                          name = user.name,
                          email = user.email,
                          profile = user.asJson,
                          realm = authConfig.cookieSuffix(descriptor),
                          otoroshiData = user.metadata.asOpt[Map[String, String]]
                        )
                      )
                    case None => Left(s"You're not authorized here")
                  }
              }
            }
            case _ => {
              FastFuture.successful(Left("Authorization form is not complete"))
            }
          }
        }
      }
    }
  }

  override def boLoginPage(request: RequestHeader, config: GlobalConfig)(implicit ec: ExecutionContext,
                                                                         env: Env): Future[Result] = {
    implicit val req = request
    val redirect     = request.getQueryString("redirect")
    env.datastores.authConfigsDataStore.generateLoginToken().flatMap { token =>

      if (authConfig.basicAuth) {

        def unauthorized() = Results
          .Unauthorized(views.html.otoroshi.error("You are not authorized here", env))
          .withHeaders("WWW-Authenticate" -> "otoroshi-admin-realm")
          .addingToSession(
            "pa-redirect-after-login" -> redirect.getOrElse(
              routes.PrivateAppsController.home().absoluteURL(env.isProd && env.exposedRootSchemeIsHttps)
            )
          ).future

        req.headers.get("Authorization") match {
          case Some(auth) if auth.startsWith("Basic ") => extractUsernamePassword(auth) match {
            case None => Results.Forbidden(views.html.otoroshi.error("Forbidden access", env)).future
            case Some((username, password)) => bindAdminUser(username, password) match {
              case Left(_) => Results.Forbidden(views.html.otoroshi.error("Forbidden access", env)).future
              case Right(user) => env.datastores.authConfigsDataStore.setUserForToken(token, user.toJson).map { _ =>
                Results.Redirect(s"/backoffice/auth0/callback?token=$token")
              }
            }
          }
          case _ => unauthorized()
        }
      } else {
        Results
          .Ok(views.html.otoroshi.login(s"/backoffice/auth0/callback", "POST", token, env))
          .addingToSession(
            "bo-redirect-after-login" -> redirect.getOrElse(
              routes.BackOfficeController.dashboard().absoluteURL(env.isProd && env.exposedRootSchemeIsHttps)
            )
          ).future
      }
    }
  }
  override def boLogout(request: RequestHeader, config: GlobalConfig)(implicit ec: ExecutionContext, env: Env) =
    FastFuture.successful(None)

  override def boCallback(
      request: Request[AnyContent],
      config: GlobalConfig
  )(implicit ec: ExecutionContext, env: Env): Future[Either[String, BackOfficeUser]] = {
    implicit val req = request
    if (req.method == "GET" && authConfig.basicAuth) {
      req.getQueryString("token") match {
        case Some(token) => env.datastores.authConfigsDataStore.getUserForToken(token).map(_.flatMap(a => BackOfficeUser.fmt.reads(a).asOpt)).map {
          case Some(user) => Right(user)
          case None => Left("No user found")
        }
        case _ => FastFuture.successful(Left("Forbidden access"))
      }
    } else {
      request.body.asFormUrlEncoded match {
        case None => FastFuture.successful(Left("No Authorization form here"))
        case Some(form) => {
          (form.get("username").map(_.last), form.get("password").map(_.last), form.get("token").map(_.last)) match {
            case (Some(username), Some(password), Some(token)) => {
              env.datastores.authConfigsDataStore.validateLoginToken(token).map {
                case false => Left("Bad token")
                case true => bindAdminUser(username, password)
              }
            }
            case _ => {
              FastFuture.successful(Left("Authorization form is not complete"))
            }
          }
        }
      }
    }
  }
}
