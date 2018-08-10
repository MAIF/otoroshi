package auth

import env.Env
import models._
import play.api.mvc.{RequestHeader, Result}

import scala.concurrent.{ExecutionContext, Future}

trait AuthModule {

  def paLoginPage(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env): Future[Result]
  def paLogout(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env): Future[Unit]
  def paCallback(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env): Future[Either[String, PrivateAppsUser]]

  def boLoginPage(request: RequestHeader, config: GlobalConfig)(implicit ec: ExecutionContext, env: Env): Future[Result]
  def boLogout(request: RequestHeader, config: GlobalConfig)(implicit ec: ExecutionContext, env: Env): Future[Unit]
  def boCallback(request: RequestHeader, config: GlobalConfig)(implicit ec: ExecutionContext, env: Env): Future[Either[String, BackOfficeUser]]
}

trait AuthModuleConfig extends AsJson {
  def authModule(config: GlobalConfig): AuthModule
  def cookieSuffix(desc: ServiceDescriptor): String
}

trait OAuth2ModuleConfig extends AuthModuleConfig {
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
  def otoroshiDataField: String
  def callbackUrl: String
}


