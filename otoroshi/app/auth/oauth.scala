package auth

import akka.http.scaladsl.util.FastFuture
import controllers.routes
import env.Env
import models._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.Results.Redirect
import play.api.mvc.{RequestHeader, Result, Results}
import security.IdGenerator
import storage.BasicStore

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try


object GenericOAuth2AuthModuleConfig extends FromJson[AuthModuleConfig] {
  override def fromJson(json: JsValue): Either[Throwable, AuthModuleConfig] = Try {
    Right(GenericOAuth2AuthModuleConfig(
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
      otoroshiDataField = (json \ "otoroshiDataField").asOpt[String].getOrElse("app_metadata|otoroshi_data"),
      callbackUrl = (json \ "callbackUrl").asOpt[String].getOrElse("http://privateapps.foo.bar:8080/privateapps/generic/callback")
    ))
  } recover {
    case e => Left(e)
  } get
}

case class GenericOAuth2AuthModuleConfig(
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
 ) extends OAuth2AuthModuleConfig {
  override def authModule(config: GlobalConfig): AuthModule = GenericOauth2Module(this)
  override def cookieSuffix(desc: ServiceDescriptor) = s"desc-${desc.id}"
  override def asJson: JsValue = Json.obj(
    "type" -> "oauth2",
    "clientId" -> this.clientId,
    "clientSecret" -> this.clientSecret,
    "authorizeUrl" -> this.authorizeUrl,
    "tokenUrl" -> this.tokenUrl,
    "userInfoUrl" -> this.userInfoUrl,
    "loginUrl" -> this.loginUrl,
    "logoutUrl" -> this.logoutUrl,
    "accessTokenField" -> this.accessTokenField,
    "nameField" -> this.nameField,
    "emailField" -> this.emailField,
    "otoroshiDataField" -> this.otoroshiDataField,
    "callbackUrl" -> this.callbackUrl
  )
}

object GlobalOauth2AuthModuleConfig extends FromJson[AuthModuleConfig] {

  lazy val logger = Logger("otoroshi-global-oauth2-config")

  def fromJsons(value: JsValue): GlobalOauth2AuthModuleConfig =
    try {
      _fmt.reads(value).get
    } catch {
      case e: Throwable => {
        logger.error(s"Try to deserialize ${Json.prettyPrint(value)}")
        throw e
      }
    }

  val _fmt = new Format[GlobalOauth2AuthModuleConfig] {

    override def reads(json: JsValue) = fromJson(json) match {
      case Left(e)  => JsError(e.getMessage)
      case Right(v) => JsSuccess(v.asInstanceOf[GlobalOauth2AuthModuleConfig])
    }

    override def writes(o: GlobalOauth2AuthModuleConfig) = o.asJson
  }

  override def fromJson(json: JsValue): Either[Throwable, AuthModuleConfig] = Try {
    Right(GlobalOauth2AuthModuleConfig(
      id = (json \ "id").as[String],
      name = (json \ "name").as[String],
      desc = (json \ "desc").asOpt[String].getOrElse("--"),
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
      otoroshiDataField = (json \ "otoroshiDataField").asOpt[String].getOrElse("app_metadata|otoroshi_data"),
      callbackUrl = (json \ "callbackUrl").asOpt[String].getOrElse("http://privateapps.foo.bar:8080/privateapps/generic/callback")
    ))
  } recover {
    case e => Left(e)
  } get
}

case class GlobalOauth2AuthModuleConfig(
    id: String,
    name: String,
    desc: String,
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
  ) extends OAuth2AuthModuleConfig {
  override def authModule(config: GlobalConfig): AuthModule = GenericOauth2Module(this)
  override def asJson = Json.obj(
    "type" -> "oauth2-global",
    "id" -> this.id,
    "name" -> this.name,
    "desc" -> this.desc,
    "clientId" -> this.clientId,
    "clientSecret" -> this.clientSecret,
    "authorizeUrl" -> this.authorizeUrl,
    "tokenUrl" -> this.tokenUrl,
    "userInfoUrl" -> this.userInfoUrl,
    "loginUrl" -> this.loginUrl,
    "logoutUrl" -> this.logoutUrl,
    "accessTokenField" -> this.accessTokenField,
    "nameField" -> this.nameField,
    "emailField" -> this.emailField,
    "otoroshiDataField" -> this.otoroshiDataField,
    "callbackUrl" -> this.callbackUrl
  )
  def save()(implicit ec: ExecutionContext, env: Env): Future[Boolean] = env.datastores.globalOAuth2ConfigDataStore.set(this)
  override def cookieSuffix(desc: ServiceDescriptor) = s"global-oauth-$id"
}

object Oauth2RefAuthModuleConfig extends FromJson[AuthModuleConfig] {
  override def fromJson(json: JsValue): Either[Throwable, AuthModuleConfig] = Try {
    Right(Oauth2RefAuthModuleConfig(
      id = (json \ "id").as[String]
    ))
  } recover {
    case e => Left(e)
  } get
}

case class Oauth2RefAuthModuleConfig(id: String) extends AuthModuleConfig {
  override def authModule(config: GlobalConfig) = Oauth2RefModule(this)
  override def cookieSuffix(desc: ServiceDescriptor) = s"global-oauth-$id"
  override def asJson = Json.obj(
    "type" -> "oauth2-ref",
    "id" -> id
  )
}

case class Oauth2RefModule(authConfig: Oauth2RefAuthModuleConfig) extends AuthModule {

  def getActualModule(implicit ec: ExecutionContext, env: Env): Future[Option[GenericOauth2Module]] = {
    env.datastores.globalOAuth2ConfigDataStore.findById(authConfig.id).map { opt =>
      opt.map(c => GenericOauth2Module(c))
    }
  }

  override def paLoginPage(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    getActualModule(ec, env).flatMap {
      case None => FastFuture.successful(Results.NotFound(views.html.otoroshi.error("OAuth config. ref not found ", env)))
      case Some(mod) => mod.paLoginPage(request, config, descriptor)(ec, env)
    }
  }

  override def paLogout(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    getActualModule(ec, env).flatMap {
      case None => FastFuture.successful(Results.NotFound(views.html.otoroshi.error("OAuth config. ref not found ", env)))
      case Some(mod) => mod.paLogout(request, config, descriptor)(ec, env)
    }
  }

  override def paCallback(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env): Future[Either[String, PrivateAppsUser]] = {
    getActualModule(ec, env).flatMap {
      case None => FastFuture.successful(Left("OAuth config. ref not found "))
      case Some(mod) => mod.paCallback(request, config, descriptor)(ec, env)
    }
  }

  override def boLoginPage(request: RequestHeader, config: GlobalConfig)(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    getActualModule(ec, env).flatMap {
      case None => FastFuture.successful(Results.NotFound(views.html.otoroshi.error("OAuth config. ref not found ", env)))
      case Some(mod) => mod.boLoginPage(request, config)(ec, env)
    }
  }

  override def boLogout(request: RequestHeader, config: GlobalConfig)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    getActualModule(ec, env).flatMap {
      case None => FastFuture.successful(Results.NotFound(views.html.otoroshi.error("OAuth config. ref not found ", env)))
      case Some(mod) => mod.boLogout(request, config)(ec, env)
    }
  }

  override def boCallback(request: RequestHeader, config: GlobalConfig)(implicit ec: ExecutionContext, env: Env): Future[Either[String, BackOfficeUser]] = {
    getActualModule(ec, env).flatMap {
      case None => FastFuture.successful(Left("OAuth config. ref not found "))
      case Some(mod) => mod.boCallback(request, config)(ec, env)
    }
  }
}

case class GenericOauth2Module(authConfig: OAuth2AuthModuleConfig) extends AuthModule {

  import play.api.libs.ws.DefaultBodyWritables._
  import utils.future.Implicits._

  override def paLoginPage(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    implicit val req = request

    val redirect = request.getQueryString("redirect")
    val clientId = authConfig.clientId
    val responseType = "code"
    val scope = "openid profile email name"

    val redirectUri = authConfig.callbackUrl + s"?desc=${descriptor.id}"
    val loginUrl = s"${authConfig.loginUrl}?scope=$scope&client_id=$clientId&response_type=$responseType&redirect_uri=$redirectUri"
    Redirect(
      loginUrl
    ).addingToSession(
      "pa-redirect-after-login" -> redirect.getOrElse(
        routes.PrivateAppsController.home().absoluteURL(env.isProd && env.exposedRootSchemeIsHttps)
      )
    ).asFuture
  }

  override def boLoginPage(request: RequestHeader, config: GlobalConfig)(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    implicit val req = request

    val redirect = request.getQueryString("redirect")
    val clientId = authConfig.clientId
    val responseType = "code"
    val scope = "openid profile email name"

    val redirectUri = authConfig.callbackUrl
    val loginUrl = s"${authConfig.loginUrl}?scope=$scope&client_id=$clientId&response_type=$responseType&redirect_uri=$redirectUri"
    Redirect(
      loginUrl
    ).addingToSession(
      "bo-redirect-after-login" -> redirect.getOrElse(
        routes.BackOfficeController.dashboard().absoluteURL(env.isProd && env.exposedRootSchemeIsHttps)
      )
    ).asFuture
  }

  override def paLogout(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    // TODO: implements if needed
    ().asFuture
  }

  override def boLogout(request: RequestHeader, config: GlobalConfig)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    // TODO: implements if needed
    ().asFuture
  }

  override def paCallback(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env): Future[Either[String, PrivateAppsUser]] = {
    val clientId = authConfig.clientId
    val clientSecret = authConfig.clientSecret
    val redirectUri = authConfig.callbackUrl + s"?desc=${descriptor.id}"
    request.getQueryString("error") match {
      case Some(error) => Left(error).asFuture
      case None => {
        request.getQueryString("code") match {
          case None => Left("No code :(").asFuture
          case Some(code) => {
            env.Ws.url(authConfig.tokenUrl)
              .post(
                Map(
                  "code" -> code,
                  "grant_type" -> "authorization_code",
                  "client_id" -> clientId,
                  "client_secret" -> clientSecret,
                  "redirect_uri" -> redirectUri
                )
              )(writeableOf_urlEncodedSimpleForm).flatMap { resp =>
              val accessToken = (resp.json \ authConfig.accessTokenField).as[String]
              env.Ws.url(authConfig.userInfoUrl)
                .post(Map(
                  "access_token" -> accessToken
                ))(writeableOf_urlEncodedSimpleForm).map(_.json)
            }.map { user =>
              val meta = PrivateAppsUser.select(user, authConfig.otoroshiDataField).asOpt[String].map(s => Json.parse(s)
                .as[Map[String, String]])
                .orElse(
                  PrivateAppsUser.select(user, authConfig.otoroshiDataField).asOpt[Map[String, String]]
                )
              Right(
                PrivateAppsUser(
                  randomId = IdGenerator.token(64),
                  name = (user \ authConfig.nameField).asOpt[String].getOrElse("No Name"),
                  email = (user \ authConfig.emailField).asOpt[String].getOrElse("no.name@foo.bar"),
                  profile = user,
                  realm = descriptor.privateAppSettings.cookieSuffix(descriptor),
                  otoroshiData = meta
                )
              )
            }
          }
        }
      }
    }
  }

  override def boCallback(request: RequestHeader, config: GlobalConfig)(implicit ec: ExecutionContext, env: Env): Future[Either[String, BackOfficeUser]] = {
    val clientId = authConfig.clientId
    val clientSecret = authConfig.clientSecret
    val redirectUri = authConfig.callbackUrl
    request.getQueryString("error") match {
      case Some(error) => Left(error).asFuture
      case None => {
        request.getQueryString("code") match {
          case None => Left("No code :(").asFuture
          case Some(code) => {
            env.Ws.url(authConfig.tokenUrl)
              .post(
                Map(
                  "code" -> code,
                  "grant_type" -> "authorization_code",
                  "client_id" -> clientId,
                  "client_secret" -> clientSecret,
                  "redirect_uri" -> redirectUri
                )
              )(writeableOf_urlEncodedSimpleForm).flatMap { resp =>
              val accessToken = (resp.json \ authConfig.accessTokenField).as[String]
              env.Ws.url(authConfig.userInfoUrl)
                .post(Map(
                  "access_token" -> accessToken
                ))(writeableOf_urlEncodedSimpleForm).map(_.json)
            }.map { user =>
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

trait GlobalOauth2AuthConfigDataStore extends BasicStore[GlobalOauth2AuthModuleConfig]
