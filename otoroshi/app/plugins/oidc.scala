package otoroshi.plugins.oidc

import akka.stream.Materializer
import env.Env
import otoroshi.script.{HttpRequest, RequestTransformer, TransformerRequestContext}
import play.api.libs.json._
import play.api.mvc.Result
import utils.future.Implicits._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class OIDCHeaders extends RequestTransformer {

  private def extract(payload: JsValue, name: String, jwt: Boolean): String = {
    (payload \ name).asOpt[String] match {
      case None => "--"
      case Some(value) if jwt =>
        Try(new String(org.apache.commons.codec.binary.Base64.decodeBase64(value.split("\\.")(1)))).getOrElse("--")
      case Some(value) => value
    }
  }

  override def transformRequestWithCtx(ctx: TransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    ctx.user match {
      case Some(user) if user.token.asOpt[JsObject].exists(_.value.nonEmpty) => {

        val config = (ctx.config \ "OIDCHeaders").asOpt[JsObject].getOrElse(Json.obj())

        val sendProfile = (config \ "profile" \ "send").asOpt[Boolean].getOrElse(true)
        val profileHeaderName = (config \ "profile" \ "headerName").asOpt[String].getOrElse("X-OIDC-User")

        val sendIdToken = (config \ "idtoken" \ "send").asOpt[Boolean].getOrElse(false)
        val idTokenName = (config \ "idtoken" \ "name").asOpt[String].getOrElse("id_token")
        val idTokenHeaderName = (config \ "idtoken" \ "headerName").asOpt[String].getOrElse("X-OIDC-Id-token")
        val idTokenJwt = (config \ "idtoken" \ "jwt").asOpt[Boolean].getOrElse(true)

        val sendAccessToken = (config \ "accesstoken" \ "send").asOpt[Boolean].getOrElse(false)
        val accessTokenName = (config \ "accesstoken" \ "name").asOpt[String].getOrElse("access_token")
        val accessTokenHeaderName = (config \ "accesstoken" \ "headerName").asOpt[String].getOrElse("X-OIDC-sAccess-token")
        val accessTokenJwt = (config \ "accesstoken" \ "jwt").asOpt[Boolean].getOrElse(true)

        val profileMap = if (sendProfile) Map(profileHeaderName -> Json.stringify(user.profile)) else Map.empty
        val idTokenMap = if (sendIdToken) Map(idTokenHeaderName -> extract(user.token, idTokenName, idTokenJwt)) else Map.empty
        val accessTokenMap = if (sendAccessToken) Map(accessTokenHeaderName -> extract(user.token, accessTokenName, accessTokenJwt)) else Map.empty

        Right(ctx.otoroshiRequest.copy(
          headers = ctx.otoroshiRequest.headers ++ profileMap ++ idTokenMap ++ accessTokenMap
        )).future
      }
      case None => Right(ctx.otoroshiRequest).future
    }
  }
}