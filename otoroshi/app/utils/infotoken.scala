package otoroshi.utils.infotoken

import otoroshi.env._
import otoroshi.models._

import scala.concurrent.duration._
import play.api.mvc.RequestHeader
import play.api.libs.json._
import org.joda.time.DateTime
import otoroshi.security._
import otoroshi.utils.syntax.implicits._

case class AddFieldsSettings(fields: Map[String, String])

object InfoTokenHelper {

  def generateInfoToken(
      name: String,
      secComInfoTokenVersion: SecComInfoTokenVersion,
      secComTtl: FiniteDuration,
      apiKey: Option[ApiKey],
      paUsr: Option[PrivateAppsUser],
      requestHeader: Option[RequestHeader],
      issuer: Option[String] = None,
      sub: Option[String] = None,
      addFields: Option[AddFieldsSettings],
  )(implicit env: Env): OtoroshiClaim = {
    import otoroshi.ssl.SSLImplicits._
    val clientCertChain = requestHeader
      .flatMap(_.clientCertificateChain)
      .map(chain =>
        JsArray(
          chain.map(c => c.asJson)
        )
      )
    secComInfoTokenVersion match {
      case SecComInfoTokenVersion.Legacy => {
        OtoroshiClaim(
          iss = issuer.getOrElse(env.Headers.OtoroshiIssuer),
          sub = sub.getOrElse(
            paUsr
              .map(k => s"pa:${k.email}")
              .orElse(apiKey.map(k => s"apikey:${k.clientId}"))
              .getOrElse("--")
          ),
          aud = name,
          exp = DateTime.now().plus(secComTtl.toMillis).toDate.getTime,
          iat = DateTime.now().toDate.getTime,
          jti = IdGenerator.uuid
        ).withClaim("email", paUsr.map(_.email))
          .withClaim("name", paUsr.map(_.name).orElse(apiKey.map(_.clientName)))
          .withClaim("picture", paUsr.flatMap(_.picture))
          .withClaim("user_id", paUsr.flatMap(_.userId).orElse(apiKey.map(_.clientId)))
          .withClaim("given_name", paUsr.flatMap(_.field("given_name")))
          .withClaim("family_name", paUsr.flatMap(_.field("family_name")))
          .withClaim("gender", paUsr.flatMap(_.field("gender")))
          .withClaim("locale", paUsr.flatMap(_.field("locale")))
          .withClaim("nickname", paUsr.flatMap(_.field("nickname")))
          .withClaims(paUsr.flatMap(_.otoroshiData).orElse(apiKey.map(_.metadataJson)))
          .withJsArrayClaim("clientCertChain", clientCertChain)
          .withClaim(
            "metadata",
            paUsr
              .flatMap(_.otoroshiData)
              .orElse(apiKey.map(_.metadataJson))
              .map(m => Json.stringify(Json.toJson(m)))
          )
          .withClaim("tags", apiKey.map(a => Json.stringify(JsArray(a.tags.map(JsString.apply)))))
          .withClaim("user", paUsr.map(u => Json.stringify(u.asJsonCleaned)))
          .withClaim("apikey", apiKey.map(ak => Json.stringify(ak.lightJson)))
      }
      case SecComInfoTokenVersion.Latest => {
        OtoroshiClaim(
          iss = issuer.getOrElse(env.Headers.OtoroshiIssuer),
          sub = sub.getOrElse(
            paUsr
              .map(k => k.email)
              .orElse(apiKey.map(k => k.clientName))
              .getOrElse("public")
          ),
          aud = name,
          exp = DateTime.now().plus(secComTtl.toMillis).toDate.getTime,
          iat = DateTime.now().toDate.getTime,
          jti = IdGenerator.uuid
        ).withClaim(
          "access_type",
          (apiKey, paUsr) match {
            case (Some(_), Some(_)) => "both" // should never happen
            case (None, Some(_))    => "user"
            case (Some(_), None)    => "apikey"
            case (None, None)       => "public"
          }
        ).withJsObjectClaim("user", paUsr.map(_.asJsonCleaned.as[JsObject]))
          .withJsObjectClaim("apikey", apiKey.map(ak => ak.lightJson))
          .applyOnWithOpt(addFields) { case (token, addFieldsSettings) =>
            addFieldsSettings.fields.foldLeft(token) { case (t, (key, value)) =>
              t.withClaim(key, value)
            }
          }
      }
    }
  }
}
