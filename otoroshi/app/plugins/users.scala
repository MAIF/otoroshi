package otoroshi.plugins.users

import akka.http.scaladsl.util.FastFuture
import env.Env
import otoroshi.script.{AccessContext, AccessValidator}
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}

import scala.concurrent.{ExecutionContext, Future}

class HasAllowedUsersValidator extends AccessValidator {

  override def name: String = "Allowed users only"

  override def defaultConfig: Option[JsObject] = Some(Json.obj(
    "HasAllowedUsersValidator" -> Json.obj(
      {
        "usernames" -> Json.arr(),
        "emails" -> Json.arr(),
        "emailDomains" -> Json.arr(),
      }
    )
  ))

  override def description: Option[String] = Some(
    """This plugin only let allowed users pass
      |
      |This plugin can accept the following configuration
      |
      |```json
      |{
      |  "HasAllowedUsersValidator": {
      |    "usernames": [],   // allowed usernames
      |    "emails": [],      // allowed user email addresses
      |    "emailDomains": [] // allowed user email domainss
      |  }
      |}
      |```
    """.stripMargin)

  def canAccess(context: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    context.user match {
      case Some(user) => {
        val config = (context.config \ "HasAllowedUsersValidator")
          .asOpt[JsValue]
          .orElse((context.config \ "GlobalHasAllowedUsersValidator").asOpt[JsValue])
          .getOrElse(context.config)
        val allowedUsernames =
          (config \ "usernames").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        val allowedEmails =
          (config \ "emails").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        val allowedEmailDomains =
          (config \ "emailDomains").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        if (allowedUsernames.contains(user.name) || allowedEmails.contains(user.email) || allowedEmailDomains.exists(
          domain => user.email.endsWith(domain)
        )) {
          FastFuture.successful(true)
        } else {
          FastFuture.successful(false)
        }
      }
      case _ => FastFuture.successful(false)
    }
  }
}
