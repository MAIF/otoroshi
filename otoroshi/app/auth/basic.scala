package auth

import akka.http.scaladsl.util.FastFuture
import controllers.routes
import env.Env
import models._
import org.mindrot.jbcrypt.BCrypt
import play.api.Logger
import play.api.libs.json._
import play.api.mvc._
import security.IdGenerator

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
  override def paLoginPage(request: RequestHeader,
                           config: GlobalConfig,
                           descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    implicit val req = request
    val redirect     = request.getQueryString("redirect")
    env.datastores.authConfigsDataStore.generateLoginToken().map { token =>
      Results
        .Ok(views.html.otoroshi.login(s"/privateapps/generic/callback?desc=${descriptor.id}", "POST", token, env))
        .addingToSession(
          "pa-redirect-after-login" -> redirect.getOrElse(
            routes.PrivateAppsController.home().absoluteURL(env.isProd && env.exposedRootSchemeIsHttps)
          )
        )
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

  override def boLoginPage(request: RequestHeader, config: GlobalConfig)(implicit ec: ExecutionContext,
                                                                         env: Env): Future[Result] = {
    implicit val req = request
    val redirect     = request.getQueryString("redirect")
    env.datastores.authConfigsDataStore.generateLoginToken().map { token =>
      Results
        .Ok(views.html.otoroshi.login(s"/backoffice/auth0/callback", "POST", token, env))
        .addingToSession(
          "bo-redirect-after-login" -> redirect.getOrElse(
            routes.BackOfficeController.dashboard().absoluteURL(env.isProd && env.exposedRootSchemeIsHttps)
          )
        )
    }
  }
  override def boLogout(request: RequestHeader, config: GlobalConfig)(implicit ec: ExecutionContext, env: Env) =
    FastFuture.successful(None)

  override def boCallback(
      request: Request[AnyContent],
      config: GlobalConfig
  )(implicit ec: ExecutionContext, env: Env): Future[Either[String, BackOfficeUser]] = {
    implicit val req = request
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
                      BackOfficeUser(
                        randomId = IdGenerator.token(64),
                        name = user.name,
                        email = user.email,
                        profile = user.asJson,
                        authorizedGroup = None
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
