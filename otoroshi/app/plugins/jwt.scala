package otoroshi.plugins.jwt

import env.Env
import models.PrivateAppsUser
import org.joda.time.DateTime
import otoroshi.plugins.JsonPathUtils
import otoroshi.script.{PreRouting, PreRoutingContext, PreRoutingError}
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsNull, JsObject, JsValue, Json}
import play.api.mvc.Results
import security.{IdGenerator, OtoroshiClaim}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class JwtUserExtractor extends PreRouting {

  override def name: String = "Jwt user extractor"

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        "JwtUserExtractor" -> Json.obj(
          "verifier" -> "",
          "strict"  -> true,
          "namePath"  -> "name",
          "emailPath"  -> "email",
          "metaPath"  -> JsNull,
        )
      )
    )

  override def configSchema: Option[JsObject] =
    super.configSchema.map(
      _ ++ Json.obj(
        "verifier" -> Json.obj(
          "type" -> "select",
          "props" -> Json.obj(
            "label"              -> "JWT Verifier",
            "placeholer"         -> "JWT verifier to use to validate token",
            "valuesFrom"         -> "/bo/api/proxy/api/verifiers",
            "transformerMapping" -> Json.obj("label" -> "name", "value" -> "id")
          )
        )
      )
    )

  override def description: Option[String] =
    Some(
      s"""This plugin extract a user from a JWT token
        |
        |This plugin can accept the following configuration
        |
        |```json
        |${defaultConfig.get.prettify}
        |```
      """.stripMargin
    )

  private val registeredClaims = Seq(
    "iss", "sub", "aud", "exp", "nbf", "iat", "jti"
  )

  override def preRoute(ctx: PreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val config = ctx.configFor("JwtUserExtractor")
    val jwtVerifierId = (config \ "verifier").as[String]
    val strict = (config \ "strict").asOpt[Boolean].getOrElse(true)
    val strip = (config \ "strip").asOpt[Boolean].getOrElse(false)
    val namePath = (config \ "namePath").asOpt[String].getOrElse("name")
    val emailPath = (config \ "emailPath").asOpt[String].getOrElse("email")
    val metaPath = (config \ "metaPath").asOpt[String]
    env.datastores.globalJwtVerifierDataStore.findById(jwtVerifierId).flatMap {
      case None if !strict =>
        ().future
      case None if strict =>
        Future.failed(PreRoutingError.fromJson(Json.obj("error" -> "unauthorized", "error_description" -> "You have to provide a valid user"), 401))
      case Some(verifier) => {
        verifier.verify(ctx.request, ctx.descriptor, None, None, ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).get, ctx.attrs) { jwtInjection =>
          jwtInjection.decodedToken match {
            case None if !strict => Results.Unauthorized(Json.obj()).future
            case None if strict => Results.Ok(Json.obj()).future
            case Some(token) => {
              val jsonToken = new String(OtoroshiClaim.decoder.decode(token.getPayload))
              val parsedJsonToken = Json.parse(jsonToken).as[JsObject]
              val strippedJsonToken = JsObject(parsedJsonToken.value.filter {
                case (key, _) if registeredClaims.contains(key) => false
                case _ => true
              })
              val meta: Option[JsValue] = metaPath.flatMap(path => Try(JsonPathUtils.getAt[net.minidev.json.JSONObject](jsonToken, path)).toOption.flatten).map(o => Json.parse(o.toJSONString))
              val user: PrivateAppsUser = PrivateAppsUser(
                randomId = IdGenerator.uuid,
                name = JsonPathUtils.getAt[String](jsonToken, namePath).getOrElse("--"),
                email = JsonPathUtils.getAt[String](jsonToken, emailPath).getOrElse("--"),
                profile = if (strip) strippedJsonToken else parsedJsonToken,
                token = Json.obj("jwt" -> token.getToken, "payload" -> parsedJsonToken),
                realm = s"JwtUserExtractor@${ctx.descriptor.id}",
                authConfigId = s"JwtUserExtractor@${ctx.descriptor.id}",
                otoroshiData = meta,
                createdAt = DateTime.now(),
                expiredAt = DateTime.now().plusHours(1),
                lastRefresh = DateTime.now(),
                metadata = Map.empty
              )
              ctx.attrs.put(otoroshi.plugins.Keys.UserKey -> user)
              Results.Ok(Json.obj()).future
            }
          }
        }.recover {
          case e: Throwable => Results.Unauthorized(Json.obj())
        }.flatMap { result =>
          result.header.status match {
            case 200 =>
              ().future
            case _ =>
              Future.failed(PreRoutingError.fromJson(Json.obj("error" -> "unauthorized", "error_description" -> "You have to provide a valid user"), 401))
          }
        }
      }
    }
  }
}
