package otoroshi.next.plugins

import akka.stream.Materializer
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}

class UserProfileEndpoint extends NgBackendCall {

  override def steps: Seq[NgStep]                          = Seq(NgStep.CallBackend)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Authentication)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "User profile endpoint"
  override def description: Option[String]                 = "This plugin returns the current user profile".some
  override def defaultConfigObject: Option[NgPluginConfig] = None
  override def useDelegates: Boolean                       = false
  override def noJsForm: Boolean                           = true

  override def callBackend(
      ctx: NgbBackendCallContext,
      delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]]
  )(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    ctx.user match {
      case None       =>
        BackendCallResponse(
          NgPluginHttpResponse.fromResult(Results.Unauthorized(Json.obj("error" -> "unauthorized"))),
          None
        ).rightf
      case Some(user) => {
        BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(user.lightJson)), None).rightf
      }
    }
  }
}

class ConsumerEndpoint extends NgBackendCall {

  override def steps: Seq[NgStep]                          = Seq(NgStep.CallBackend)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Authentication)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Consumer endpoint"
  override def description: Option[String]                 = "This plugin returns the current consumer profile".some
  override def defaultConfigObject: Option[NgPluginConfig] = None
  override def useDelegates: Boolean                       = false
  override def noJsForm: Boolean                           = true

  override def callBackend(
      ctx: NgbBackendCallContext,
      delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]]
  )(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    ctx.user match {
      case None       =>
        ctx.apikey match {
          case None         =>
            BackendCallResponse(
              NgPluginHttpResponse.fromResult(Results.Ok(Json.obj("access_type" -> "public"))),
              None
            ).rightf
          case Some(apikey) => {
            BackendCallResponse(
              NgPluginHttpResponse.fromResult(
                Results.Ok(apikey.lightJson.asObject ++ Json.obj("access_type" -> "apikey"))
              ),
              None
            ).rightf
          }
        }
      case Some(user) => {
        BackendCallResponse(
          NgPluginHttpResponse.fromResult(Results.Ok(user.lightJson.asObject ++ Json.obj("access_type" -> "session"))),
          None
        ).rightf
      }
    }
  }
}
