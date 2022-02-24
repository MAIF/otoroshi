package otoroshi.next.plugins

import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.models.{ApiKey, JwtInjection, PrivateAppsUser}
import otoroshi.next.plugins.api.{NgAccess, NgAccessContext, NgAccessValidator}
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class RBACConfig(
  allow: Seq[String] = Seq.empty,
  deny: Seq[String] = Seq.empty,
  allowAll: Boolean = false,
  denyAll: Boolean = false,
  jwtPath: Option[String] = None,
  apikeyPath: Option[String] = None,
  userPath: Option[String] = None,
  rolePrefix: Option[String] = None,
  roles: String = "roles"
) {
  def json: JsValue = RBACConfig.format.writes(this)
  lazy val prefix: String = rolePrefix.map(v => s"$v:role:").getOrElse("role:")
}

object RBACConfig {
  val format = new Format[RBACConfig] {
    override def writes(o: RBACConfig): JsValue = Json.obj(
      "allow" -> o.allow,
      "deny" -> o.deny,
      "allow_all" -> o.allowAll,
      "deny_all" -> o.denyAll,
      "jwt_path" -> o.jwtPath.map(JsString.apply).getOrElse(JsNull).asValue,
      "apikey_path" -> o.apikeyPath.map(JsString.apply).getOrElse(JsNull).asValue,
      "user_path" -> o.userPath.map(JsString.apply).getOrElse(JsNull).asValue,
      "role_prefix" -> o.rolePrefix.map(JsString.apply).getOrElse(JsNull).asValue,
      "roles" -> o.roles,
    )
    override def reads(json: JsValue): JsResult[RBACConfig] = Try {
      RBACConfig(
        allow = json.select("allow").asOpt[Seq[String]].getOrElse(Seq.empty),
        deny = json.select("deny").asOpt[Seq[String]].getOrElse(Seq.empty),
        allowAll = json.select("allow_all").asOpt[Boolean].getOrElse(false),
        denyAll = json.select("deny_all").asOpt[Boolean].getOrElse(false),
        jwtPath = json.select("jwt_path").asOpt[String],
        apikeyPath = json.select("apikey_path").asOpt[String],
        userPath = json.select("user_path").asOpt[String],
        rolePrefix = json.select("role_prefix").asOpt[String],
        roles = json.select("roles").asOpt[String].getOrElse("roles"),
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value) =>  JsSuccess(value)
    }
  }
}

class RBAC extends NgAccessValidator {

  override def core: Boolean                   = true
  override def name: String                    = "RBAC"
  override def description: Option[String]     = "This plugin check if current user/apikey/jwt token has the right role".some
  override def defaultConfig: Option[JsObject] = RBACConfig().json.asObject.some

  private def matches(roles: Seq[String], config: RBACConfig): Boolean = {
    if (roles.isEmpty) {
      false
    } else {
      val isAllowed = if (config.allow.isEmpty) {
        true
      } else {
        if (config.allowAll) {
          config.allow.forall(role => roles.contains(role))
        } else {
          config.allow.exists(role => roles.contains(role))
        }
      }
      val isDenied = if (config.deny.isEmpty) {
        true
      } else {
        if (config.denyAll) {
          config.deny.forall(role => roles.contains(role))
        } else {
          config.deny.exists(role => roles.contains(role))
        }
      }
      isAllowed && !isDenied
    }
  }

  private def tryParse(value: String): Seq[String] = {
    if (value.trim.startsWith("[") && value.trim.endsWith("]")) {
      Try(Json.parse(value).asArray.value.map(_.asString)).getOrElse(Seq.empty)
    } else {
      value.split(",").map(_.trim)
    }
  }

  private def checkRightsFromJwtInjection(injection: JwtInjection, config: RBACConfig): Boolean = {
    injection.decodedToken match {
      case None => false
      case Some(token) => {
        val jsonToken = token.getPayload.fromBase64.parseJson
        val roles = jsonToken.select(config.roles).asOpt[Seq[String]].getOrElse(Seq.empty)
        matches(roles, config) || (config.jwtPath.flatMap(p => jsonToken.atPath(p).asOpt[JsValue]) match {
          case Some(JsString(value)) => {
            if (matches(Seq(value), config)) {
              true
            } else {
              matches(tryParse(value), config)
            }
          }
          case Some(JsArray(value)) => matches(value.map(_.asString), config)
          case _ => false
        })
      }
    }
  }

  private def checkRightsFromApikey(apikey: ApiKey, config: RBACConfig): Boolean = {
    val rolesTags = apikey.tags.filter(_.startsWith(config.prefix)).map(_.replaceFirst(config.prefix, ""))
    val rolesMeta = apikey.metadata.get(config.roles).map(str => Json.parse(str).asArray.value.map(_.asString)).getOrElse(Seq.empty)
    val pathMatch = config.apikeyPath.flatMap(p => apikey.json.atPath(p).asOpt[JsValue]) match {
      case Some(JsString(value)) => {
        if (matches(Seq(value), config)) {
          true
        } else {
          matches(tryParse(value), config)
        }
      }
      case Some(JsArray(value)) => matches(value.map(_.asString), config)
      case _ => false
    }
    pathMatch || matches(rolesTags, config) || matches(rolesMeta, config)
  }

  private def checkRightsFromUser(user: PrivateAppsUser, config: RBACConfig): Boolean = {
    val rolesTags = user.tags.filter(_.startsWith(config.prefix)).map(_.replaceFirst(config.prefix, ""))
    val rolesMeta = user.metadata.get(config.roles).map(str => Json.parse(str).asArray.value.map(_.asString)).getOrElse(Seq.empty)
    val dataMatch = user.otoroshiData.exists { otodata =>
      otodata.select(config.roles).asOpt[JsValue] match {
        case Some(JsString(value)) => {
          if (matches(Seq(value), config)) {
            true
          } else {
            matches(tryParse(value), config)
          }
        }
        case Some(JsArray(value)) => matches(value.map(_.asString), config)
        case _ => false
      }
    }
    val pathMatch = config.userPath.flatMap(p => user.json.atPath(p).asOpt[JsValue]) match {
      case Some(JsString(value)) => {
        if (matches(Seq(value), config)) {
          true
        } else {
          matches(tryParse(value), config)
        }
      }
      case Some(JsArray(value)) => matches(value.map(_.asString), config)
      case _ => false
    }
    pathMatch || dataMatch || matches(rolesTags, config) || matches(rolesMeta, config)
  }

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    var shouldPass = false
    val config = ctx.cachedConfig(internalName)(RBACConfig.format).getOrElse(RBACConfig())
    ctx.attrs.get(otoroshi.next.plugins.Keys.JwtInjectionKey).foreach { injection =>
      if (!shouldPass && checkRightsFromJwtInjection(injection, config)) {
        shouldPass = true
      }
    }
    ctx.apikey.foreach { apikey =>
      if (!shouldPass && checkRightsFromApikey(apikey, config)) {
        shouldPass = true
      }
    }
    ctx.user.foreach { user =>
      if (!shouldPass && checkRightsFromUser(user, config)) {
        shouldPass = true
      }
    }
    if (shouldPass) {
      NgAccess.NgAllowed.vfuture
    } else {
      Errors
        .craftResponseResult(
          "forbidden",
          Results.Forbidden,
          ctx.request,
          None,
          None,
          attrs = ctx.attrs,
          maybeRoute = ctx.route.some
        )
        .map(NgAccess.NgDenied.apply)
    }
  }
}
