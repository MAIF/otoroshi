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

object AuthModule {


}

class FakeAuthModule extends AuthModule {

  import play.api.libs.ws.DefaultBodyWritables._
  import utils.future.Implicits._

  override def enabled = true

  override def loginPage(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env) = {
    implicit val req = request

    val redirect = request.getQueryString("redirect")
    val clientId = "otoroshi"
    val responseType = "code"
    val scope = "openid profile email name"

    val redirectUri = s"http://privateapps.dev.opunmaif.fr:9999/privateapps/generic/${descriptor.id}/callback"
    Redirect(
      s"http://localhost:8081/auth/realms/master/protocol/openid-connect/auth?scope=$scope&client_id=$clientId&response_type=$responseType&redirect_uri=$redirectUri"
    ).addingToSession(
      "pa-redirect-after-login" -> redirect.getOrElse(
        routes.PrivateAppsController.home().absoluteURL(env.isProd && env.exposedRootSchemeIsHttps)
      )
    ).asFuture
  }

  override def logout(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env) = {
    ().asFuture
  }

  override def callback(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env): Future[Either[String, PrivateAppsUser]] = {
    val clientId = "otoroshi"
    val clientSecret = "4babd71e-fa18-4e9f-b98c-d4e6197a5c55"
    val redirectUri = s"http://privateapps.dev.opunmaif.fr:9999/privateapps/generic/${descriptor.id}/callback"
    request.getQueryString("error") match {
      case Some(error) => Left(error).asFuture
      case None => {
        request.getQueryString("code") match {
          case None => Left("No code :(").asFuture
          case Some(code) => {
            env.Ws.url("http://localhost:8081/auth/realms/master/protocol/openid-connect/token")
              .post(
                Map(
                  "code" -> code,
                  "grant_type" -> "authorization_code",
                  "client_id" -> clientId,
                  "client_secret" -> clientSecret,
                  "redirect_uri" -> redirectUri
                )
              )(writeableOf_urlEncodedSimpleForm).flatMap { resp =>
                val accessToken = (resp.json \ "access_token").as[String]
                env.Ws.url("http://localhost:8081/auth/realms/master/protocol/openid-connect/userinfo")
                .post(Map(
                  "access_token" -> accessToken
                ))(writeableOf_urlEncodedSimpleForm).map(_.json)
              }.map { user =>
                Right(PrivateAppsUser(IdGenerator.token(64),
                  (user \ "name").as[String],
                  (user \ "email").as[String],
                  user))
              }
          }
        }
      }
    }
  }
}
