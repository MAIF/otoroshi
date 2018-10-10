package auth

import controllers.routes
import env.Env
import models._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.Results.Redirect
import play.api.mvc.{AnyContent, Request, RequestHeader, Result}
import security.IdGenerator
import storage.BasicStore

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object GenericOauth2ModuleConfig extends FromJson[AuthModuleConfig] {

  lazy val logger = Logger("otoroshi-global-oauth2-config")

  val _fmt = new Format[GenericOauth2ModuleConfig] {

    override def reads(json: JsValue) = fromJson(json) match {
      case Left(e)  => JsError(e.getMessage)
      case Right(v) => JsSuccess(v.asInstanceOf[GenericOauth2ModuleConfig])
    }

    override def writes(o: GenericOauth2ModuleConfig) = o.asJson
  }

  override def fromJson(json: JsValue): Either[Throwable, AuthModuleConfig] =
    Try {
      Right(
        GenericOauth2ModuleConfig(
          id = (json \ "id").as[String],
          name = (json \ "name").as[String],
          desc = (json \ "desc").asOpt[String].getOrElse("--"),
          sessionMaxAge = (json \ "sessionMaxAge").asOpt[Int].getOrElse(86400),
          clientId = (json \ "clientId").asOpt[String].getOrElse("client"),
          clientSecret = (json \ "clientSecret").asOpt[String].getOrElse("secret"),
          authorizeUrl = (json \ "authorizeUrl").asOpt[String].getOrElse("http://localhost:8082/oauth/authorize"),
          tokenUrl = (json \ "tokenUrl").asOpt[String].getOrElse("http://localhost:8082/oauth/token"),
          userInfoUrl = (json \ "userInfoUrl").asOpt[String].getOrElse("http://localhost:8082/userinfo"),
          loginUrl = (json \ "loginUrl").asOpt[String].getOrElse("http://localhost:8082/login"),
          logoutUrl = (json \ "logoutUrl").asOpt[String].getOrElse("http://localhost:8082/logout"),
          accessTokenField = (json \ "accessTokenField").asOpt[String].getOrElse("access_token"),
          nameField = (json \ "nameField").asOpt[String].getOrElse("name"),
          emailField = (json \ "emailField").asOpt[String].getOrElse("email"),
          otoroshiDataField = (json \ "otoroshiDataField").asOpt[String].getOrElse("app_metadata | otoroshi_data"),
          callbackUrl = (json \ "callbackUrl")
            .asOpt[String]
            .getOrElse("http://privateapps.foo.bar:8080/privateapps/generic/callback")
        )
      )
    } recover {
      case e => Left(e)
    } get
}

case class GenericOauth2ModuleConfig(
    id: String,
    name: String,
    desc: String,
    sessionMaxAge: Int = 86400,
    clientId: String = "client",
    clientSecret: String = "secret",
    tokenUrl: String = "http://localhost:8082/oauth/token",
    authorizeUrl: String = "http://localhost:8082/oauth/authorize",
    userInfoUrl: String = "http://localhost:8082/userinfo",
    loginUrl: String = "http://localhost:8082/login",
    logoutUrl: String = "http://localhost:8082/logout",
    accessTokenField: String = "access_token",
    nameField: String = "name",
    emailField: String = "email",
    otoroshiDataField: String = "app_metadata|otoroshi_data",
    callbackUrl: String = "http://privateapps.foo.bar:8080/privateapps/generic/callback"
) extends OAuth2ModuleConfig {
  def `type`: String                                        = "oauth2"
  override def authModule(config: GlobalConfig): AuthModule = GenericOauth2Module(this)
  override def asJson = Json.obj(
    "type"              -> "oauth2",
    "id"                -> this.id,
    "name"              -> this.name,
    "desc"              -> this.desc,
    "sessionMaxAge"              -> this.sessionMaxAge,
    "clientId"          -> this.clientId,
    "clientSecret"      -> this.clientSecret,
    "authorizeUrl"      -> this.authorizeUrl,
    "tokenUrl"          -> this.tokenUrl,
    "userInfoUrl"       -> this.userInfoUrl,
    "loginUrl"          -> this.loginUrl,
    "logoutUrl"         -> this.logoutUrl,
    "accessTokenField"  -> this.accessTokenField,
    "nameField"         -> this.nameField,
    "emailField"        -> this.emailField,
    "otoroshiDataField" -> this.otoroshiDataField,
    "callbackUrl"       -> this.callbackUrl
  )
  def save()(implicit ec: ExecutionContext, env: Env): Future[Boolean] = env.datastores.authConfigsDataStore.set(this)
  override def cookieSuffix(desc: ServiceDescriptor)                   = s"global-oauth-$id"
}

case class GenericOauth2Module(authConfig: OAuth2ModuleConfig) extends AuthModule {

  import play.api.libs.ws.DefaultBodyWritables._
  import utils.future.Implicits._

  override def paLoginPage(request: RequestHeader,
                           config: GlobalConfig,
                           descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    implicit val req = request

    val redirect     = request.getQueryString("redirect")
    val clientId     = authConfig.clientId
    val responseType = "code"
    val scope        = "openid profile email name"

    val redirectUri = authConfig.callbackUrl + s"?desc=${descriptor.id}"
    val loginUrl =
      s"${authConfig.loginUrl}?scope=$scope&client_id=$clientId&response_type=$responseType&redirect_uri=$redirectUri"
    Redirect(
      loginUrl
    ).addingToSession(
        "pa-redirect-after-login" -> redirect.getOrElse(
          routes.PrivateAppsController.home().absoluteURL(env.isProd && env.exposedRootSchemeIsHttps)
        )
      )
      .asFuture
  }

  override def boLoginPage(request: RequestHeader, config: GlobalConfig)(implicit ec: ExecutionContext,
                                                                         env: Env): Future[Result] = {
    implicit val req = request

    val redirect     = request.getQueryString("redirect")
    val clientId     = authConfig.clientId
    val responseType = "code"
    val scope        = "openid profile email name"

    val redirectUri = authConfig.callbackUrl
    val loginUrl =
      s"${authConfig.loginUrl}?scope=$scope&client_id=$clientId&response_type=$responseType&redirect_uri=$redirectUri"
    Redirect(
      loginUrl
    ).addingToSession(
        "bo-redirect-after-login" -> redirect.getOrElse(
          routes.BackOfficeController.dashboard().absoluteURL(env.isProd && env.exposedRootSchemeIsHttps)
        )
      )
      .asFuture
  }

  override def paLogout(request: RequestHeader,
                        config: GlobalConfig,
                        descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    // TODO: implements if needed
    ().asFuture
  }

  override def boLogout(request: RequestHeader, config: GlobalConfig)(implicit ec: ExecutionContext,
                                                                      env: Env): Future[Unit] = {
    // TODO: implements if needed
    ().asFuture
  }

  override def paCallback(request: Request[AnyContent], config: GlobalConfig, descriptor: ServiceDescriptor)(
      implicit ec: ExecutionContext,
      env: Env
  ): Future[Either[String, PrivateAppsUser]] = {
    val clientId     = authConfig.clientId
    val clientSecret = authConfig.clientSecret
    val redirectUri  = authConfig.callbackUrl + s"?desc=${descriptor.id}"
    request.getQueryString("error") match {
      case Some(error) => Left(error).asFuture
      case None => {
        request.getQueryString("code") match {
          case None => Left("No code :(").asFuture
          case Some(code) => {
            env.Ws
              .url(authConfig.tokenUrl)
              .post(
                Map(
                  "code"          -> code,
                  "grant_type"    -> "authorization_code",
                  "client_id"     -> clientId,
                  "client_secret" -> clientSecret,
                  "redirect_uri"  -> redirectUri
                )
              )(writeableOf_urlEncodedSimpleForm)
              .flatMap { resp =>
                val accessToken = (resp.json \ authConfig.accessTokenField).as[String]
                env.Ws
                  .url(authConfig.userInfoUrl)
                  .post(
                    Map(
                      "access_token" -> accessToken
                    )
                  )(writeableOf_urlEncodedSimpleForm)
                  .map(_.json)
              }
              .map { user =>
                val meta = PrivateAppsUser
                  .select(user, authConfig.otoroshiDataField)
                  .asOpt[String]
                  .map(
                    s =>
                      Json
                        .parse(s)
                        .as[Map[String, String]]
                  )
                  .orElse(
                    PrivateAppsUser.select(user, authConfig.otoroshiDataField).asOpt[Map[String, String]]
                  )
                Right(
                  PrivateAppsUser(
                    randomId = IdGenerator.token(64),
                    name = (user \ authConfig.nameField).asOpt[String].getOrElse("No Name"),
                    email = (user \ authConfig.emailField).asOpt[String].getOrElse("no.name@foo.bar"),
                    profile = user,
                    realm = authConfig.cookieSuffix(descriptor),
                    otoroshiData = meta
                  )
                )
              }
          }
        }
      }
    }
  }

  override def boCallback(
      request: Request[AnyContent],
      config: GlobalConfig
  )(implicit ec: ExecutionContext, env: Env): Future[Either[String, BackOfficeUser]] = {
    val clientId     = authConfig.clientId
    val clientSecret = authConfig.clientSecret
    val redirectUri  = authConfig.callbackUrl
    request.getQueryString("error") match {
      case Some(error) => Left(error).asFuture
      case None => {
        request.getQueryString("code") match {
          case None => Left("No code :(").asFuture
          case Some(code) => {
            env.Ws
              .url(authConfig.tokenUrl)
              .post(
                Map(
                  "code"          -> code,
                  "grant_type"    -> "authorization_code",
                  "client_id"     -> clientId,
                  "client_secret" -> clientSecret,
                  "redirect_uri"  -> redirectUri
                )
              )(writeableOf_urlEncodedSimpleForm)
              .flatMap { resp =>
                val accessToken = (resp.json \ authConfig.accessTokenField).as[String]
                env.Ws
                  .url(authConfig.userInfoUrl)
                  .post(
                    Map(
                      "access_token" -> accessToken
                    )
                  )(writeableOf_urlEncodedSimpleForm)
                  .map(_.json)
              }
              .map { user =>
                Right(
                  BackOfficeUser(
                    randomId = IdGenerator.token(64),
                    name = (user \ authConfig.nameField).asOpt[String].getOrElse("No Name"),
                    email = (user \ authConfig.emailField).asOpt[String].getOrElse("no.name@foo.bar"),
                    profile = user,
                    authorizedGroup = None
                  )
                )
              }
          }
        }
      }
    }
  }
}
