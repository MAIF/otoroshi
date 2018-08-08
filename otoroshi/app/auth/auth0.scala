package auth

import models.{FromJson, GlobalConfig, ServiceDescriptor}
import play.api.libs.json.{JsValue, Json}
import security.Auth0Config

object GlobalConfigAuth0AuthModuleConfig extends FromJson[AuthModuleConfig] {
  override def fromJson(json: JsValue): Either[Throwable, AuthModuleConfig] = Right(RefAuth0AuthModuleConfig())
}

case class RefAuth0AuthModuleConfig() extends AuthModuleConfig {
  override def authModule(config: GlobalConfig): AuthModule = GenericOauth2Module(GlobalConfigAuth0AuthModuleConfig(config))
  override def cookieSuffix(desc: ServiceDescriptor) = "global-auth0"
  override def asJson = Json.obj(
    "type" -> "global-auth0"
  )
}

case class GlobalConfigAuth0AuthModuleConfig(config: GlobalConfig) extends OAuth2AuthModuleConfig {

  val auth0Config = config.privateAppsAuth0Config.getOrElse(Auth0Config(
    secret = "secret",
    clientId = "client",
    callbackURL = "http://privateapps.foo.bar:8080/privateapps/generic/callback",
    domain = "https://mydomain.eu.auth0.com"
  ))

  val domain = auth0Config.domain

  override def clientId: String = auth0Config.clientId
  override def clientSecret: String = auth0Config.secret
  override def authorizeUrl = s"$domain/authorize"
  override def tokenUrl = s"$domain/oauth/token"
  override def userInfoUrl = s"$domain/userinfo"
  override def loginUrl = s"$domain/authorize"
  override def logoutUrl = s"$domain/logout"
  override def accessTokenField: String = "access_token"
  override def nameField: String = "name"
  override def emailField: String = "email"
  override def callbackUrl: String = auth0Config.callbackURL
  override def authModule(config: GlobalConfig): AuthModule = GenericOauth2Module(this)
  override def cookieSuffix(desc: ServiceDescriptor) = "global-auth0"
  override def asJson: JsValue = Json.obj(
    "type" -> "actual-global-auth0"
  )
}

