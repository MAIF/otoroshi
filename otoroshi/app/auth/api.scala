package auth

import env.Env
import models._
import play.api.libs.json.JsValue
import play.api.mvc.{RequestHeader, Result}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

trait AuthModule {

  def loginPage(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env): Future[Result]

  def logout(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env): Future[Unit]

  def callback(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env): Future[Either[String, PrivateAppsUser]]
}

trait AuthModuleConfig extends AsJson {
  def clientId: String
  def clientSecret: String
  def authorizeUrl: String
  def tokenUrl: String
  def userInfoUrl: String
  def loginUrl: String
  def logoutUrl: String
  def accessTokenField: String
  def nameField: String
  def emailField: String
  def callbackUrl: String
  def authModule(config: GlobalConfig): AuthModule
  def cookieSuffix(desc: ServiceDescriptor): String
}

object AuthModuleConfig extends FromJson[AuthModuleConfig] {
  override def fromJson(json: JsValue): Either[Throwable, AuthModuleConfig] = Try {
    (json \ "type").as[String] match {
      case "oauth2"              => Oauth2AuthModuleConfig.fromJson(json)
      case "oauth2-ref"          => Oauth2RefAuthModuleConfig.fromJson(json)
      case "global-auth0"        => GlobalConfigAuth0AuthModuleConfig.fromJson(json)
      case "actual-global-auth0" => GlobalConfigAuth0AuthModuleConfig.fromJson(json)
    }
  } recover {
    case e => Left(e)
  } get
}


