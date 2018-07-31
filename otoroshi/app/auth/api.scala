package auth

import controllers.routes
import env.Env
import models.{GlobalConfig, PrivateAppsUser, ServiceDescriptor}
import play.api.mvc.Results._
import play.api.mvc.{RequestHeader, Result}
import security.IdGenerator

import scala.concurrent.{ExecutionContext, Future}

sealed trait AuthModule {

  def enabled: Boolean

  def loginPage(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env): Future[Result]

  def logout(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env): Future[Unit]

  def callback(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env): Future[Either[String, PrivateAppsUser]]
}

trait AuthModuleConfig {
  def clientId: String
  def clientSecret: String
  def tokenUrl: String
  def userInfoUrl: String
  def loginUrl: String
  def logoutUrl: String
  def accessTokenField: String
}

class KeyCloakConfig extends AuthModuleConfig {
  override def clientId = "otoroshi"
  override def clientSecret = "4babd71e-fa18-4e9f-b98c-d4e6197a5c55"
  override def tokenUrl = "http://localhost:8081/auth/realms/master/protocol/openid-connect/token"
  override def userInfoUrl = "http://localhost:8081/auth/realms/master/protocol/openid-connect/userinfo"
  override def loginUrl = "http://localhost:8081/auth/realms/master/protocol/openid-connect/auth"
  override def logoutUrl = "http://localhost:8080/auth/realms/master/protocol/openid-connect/logout"
  override def accessTokenField = "access_token"
}

class Auth0Config extends AuthModuleConfig {
  val domain = "https://opunapps.eu.auth0.com"
  override def clientId = "MZPfUJ9pebZrHvrFtPPCw8jaHMcXteeN"
  override def clientSecret = "MNyavE6E3BRfmYgmFEk8EsxnxelD2wubS2UecbzHRVkOHw13JEHNIg1hJHJ9bGoI"
  override def tokenUrl = s"$domain/oauth/token"
  override def userInfoUrl = s"$domain/userinfo"
  override def loginUrl = s"$domain/authorize"
  override def logoutUrl = s"$domain/ogout"
  override def accessTokenField = "access_token"
}

class GenericOauth2Module(authConfig: AuthModuleConfig) extends AuthModule {

  import play.api.libs.ws.DefaultBodyWritables._
  import utils.future.Implicits._

  override def enabled = true

  override def loginPage(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env) = {
    implicit val req = request

    val redirect = request.getQueryString("redirect")
    val clientId = authConfig.clientId
    val responseType = "code"
    val scope = "openid profile email name"

    val redirectUri = s"http://privateapps.dev.opunmaif.fr:9999/privateapps/generic/callback?desc=${descriptor.id}"
    Redirect(
      s"${authConfig.loginUrl}?scope=$scope&client_id=$clientId&response_type=$responseType&redirect_uri=$redirectUri"
    ).addingToSession(
      "pa-redirect-after-login" -> redirect.getOrElse(
        routes.PrivateAppsController.home().absoluteURL(env.isProd && env.exposedRootSchemeIsHttps)
      )
    ).asFuture
  }

  override def logout(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env) = {
    // TODO: implements
    ().asFuture
  }

  override def callback(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env): Future[Either[String, PrivateAppsUser]] = {
    val clientId = authConfig.clientId
    val clientSecret = authConfig.clientSecret
    val redirectUri = s"http://privateapps.dev.opunmaif.fr:9999/privateapps/generic/callback?desc=${descriptor.id}"
    println(request.getQueryString("desc"))
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
                PrivateAppsUser(
                  randomId = IdGenerator.token(64),
                  name = (user \ "name").as[String],
                  email = (user \ "email").as[String],
                  profile = user
                )
              )
            }
          }
        }
      }
    }
  }
}
