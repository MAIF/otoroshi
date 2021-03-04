package otoroshi.plugins.users

import akka.http.scaladsl.util.FastFuture
import otoroshi.env.Env
import otoroshi.plugins.JsonPathUtils
import otoroshi.script.{AccessContext, AccessValidator}
import play.api.Logger
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}

import scala.concurrent.{ExecutionContext, Future}

class HasAllowedUsersValidator extends AccessValidator {

  private val logger = Logger("otoroshi-plugins-hasallowedusersvalidator")

  override def name: String = "Allowed users only"

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        "HasAllowedUsersValidator" -> Json.obj(
          "usernames"        -> Json.arr(),
          "emails"           -> Json.arr(),
          "emailDomains"     -> Json.arr(),
          "metadataMatch"    -> Json.arr(),
          "metadataNotMatch" -> Json.arr(),
          "profileMatch"     -> Json.arr(),
          "profileNotMatch"  -> Json.arr()
        )
      )
    )

  override def description: Option[String] =
    Some("""This plugin only let allowed users pass
      |
      |This plugin can accept the following configuration
      |
      |```json
      |{
      |  "HasAllowedUsersValidator": {
      |    "usernames": [],   // allowed usernames
      |    "emails": [],      // allowed user email addresses
      |    "emailDomains": [], // allowed user email domains
      |    "metadataMatch": [], // json path expressions to match against user metadata. passes if one match
      |    "metadataNotMatch": [], // json path expressions to match against user metadata. passes if none match
      |    "profileMatch": [], // json path expressions to match against user profile. passes if one match
      |    "profileNotMatch": [], // json path expressions to match against user profile. passes if none match
      |  }
      |}
      |```
    """.stripMargin)

  override def canAccess(context: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    context.user match {
      case Some(user) => {
        val config = (context.config \ "HasAllowedUsersValidator")
          .asOpt[JsValue]
          .orElse((context.config \ "HasAllowedUsersValidator").asOpt[JsValue])
          .getOrElse(context.config)
        val allowedUsernames =
          (config \ "usernames").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        val allowedEmails =
          (config \ "emails").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        val allowedEmailDomains =
          (config \ "emailDomains").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        val metadataMatch =
          (config \ "metadataMatch").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        val metadataNotMatch =
          (config \ "metadataNotMatch").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        val profileMatch =
          (config \ "profileMatch").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        val profileNotMatch =
          (config \ "profileNotMatch").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        val userMetaRaw = user.otoroshiData.getOrElse(Json.obj())
        if (allowedUsernames.contains(user.name) ||
            allowedEmails.contains(user.email) ||
            allowedEmailDomains.exists(domain => user.email.endsWith(domain)) ||
            (metadataMatch.exists(JsonPathUtils.matchWith(userMetaRaw, "user metadata")) && !metadataNotMatch.exists(
              JsonPathUtils.matchWith(userMetaRaw, "user metadata")
            )) ||
            (profileMatch.exists(JsonPathUtils.matchWith(user.profile, "user profile")) && !profileNotMatch.exists(
              JsonPathUtils.matchWith(user.profile, "user profile")
            ))) {
          FastFuture.successful(true)
        } else {
          FastFuture.successful(false)
        }
      }
      case _ => FastFuture.successful(false)
    }
  }
}
