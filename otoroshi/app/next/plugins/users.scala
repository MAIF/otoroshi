package otoroshi.next.plugins

import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.plugins.api._
import otoroshi.utils.JsonPathUtils
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}
import scala.util._
  
case class NgHasAllowedUsersValidatorConfig(
  usernames: Seq[String] = Seq.empty,
  emails: Seq[String] = Seq.empty,
  emailDomains: Seq[String] = Seq.empty,
  metadataMatch: Seq[String] = Seq.empty,
  metadataNotMatch: Seq[String] = Seq.empty,
  profileMatch: Seq[String] = Seq.empty,
  profileNotMatch: Seq[String] = Seq.empty,
) extends NgPluginConfig {
  override def json: JsValue = Json.obj(
    "usernames" -> usernames,
    "emails" -> emails,
    "email_domains" -> emailDomains,
    "metadata_match" -> metadataMatch,
    "metadata_not_match" -> metadataNotMatch,
    "profile_match" -> profileMatch,
    "profile_not_match" -> profileNotMatch,
  )
}

object NgHasAllowedUsersValidatorConfig {
  val format = new Format[NgHasAllowedUsersValidatorConfig] {
    override def writes(o: NgHasAllowedUsersValidatorConfig): JsValue = o.json
    override def reads(json: JsValue): JsResult[NgHasAllowedUsersValidatorConfig] = Try {
      NgHasAllowedUsersValidatorConfig(
        usernames = json.select("usernames").asOpt[Seq[String]].getOrElse(Seq.empty),
        emails = json.select("emails").asOpt[Seq[String]].getOrElse(Seq.empty),
        emailDomains = json.select("email_domains").asOpt[Seq[String]].getOrElse(Seq.empty),
        metadataMatch = json.select("metadata_match").asOpt[Seq[String]].getOrElse(Seq.empty),
        metadataNotMatch = json.select("metadata_not_match").asOpt[Seq[String]].getOrElse(Seq.empty),
        profileMatch = json.select("profile_match").asOpt[Seq[String]].getOrElse(Seq.empty),
        profileNotMatch = json.select("profile_not_match").asOpt[Seq[String]].getOrElse(Seq.empty),
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }
}

class NgHasAllowedUsersValidator extends NgAccessValidator {

  private val logger = Logger("otoroshi-plugins-hasallowedusersvalidator")
  override def name: String = "Allowed users only"
  override def description: Option[String] = "This plugin only let allowed users pass".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgHasAllowedUsersValidatorConfig().some
  override def multiInstance: Boolean = true
  override def core: Boolean = true
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl)
  override def steps: Seq[NgStep] = Seq(NgStep.ValidateAccess)

  def forbidden(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    Errors
      .craftResponseResult(
        "forbidden",
        Results.Forbidden,
        ctx.request,
        None,
        None,
        duration = ctx.report.getDurationNow(),
        overhead = ctx.report.getOverheadInNow(),
        attrs = ctx.attrs,
        maybeRoute = ctx.route.some
      )
      .map(r => NgAccess.NgDenied(r))
  }

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    ctx.user match {
      case Some(user) => {
        val config = ctx.cachedConfig(internalName)(NgHasAllowedUsersValidatorConfig.format).getOrElse(NgHasAllowedUsersValidatorConfig())
        val userMetaRaw         = user.otoroshiData.getOrElse(Json.obj())
        if (
          config.usernames.contains(user.name) ||
            config.emails.contains(user.email) ||
            config.emailDomains.exists(domain => user.email.endsWith(domain)) ||
            (config.metadataMatch.exists(JsonPathUtils.matchWith(userMetaRaw, "user metadata")) && !config.metadataNotMatch.exists(
              JsonPathUtils.matchWith(userMetaRaw, "user metadata")
            )) ||
            (config.profileMatch.exists(JsonPathUtils.matchWith(user.profile, "user profile")) && !config.profileNotMatch.exists(
              JsonPathUtils.matchWith(user.profile, "user profile")
            ))
        ) {
          NgAccess.NgAllowed.vfuture
        } else {
          forbidden(ctx)
        }
      }
      case _ => forbidden(ctx)
    }
  }
}

case class NgJwtUserExtractorConfig(

) extends NgPluginConfig {
  override def json: JsValue = Json.obj(

  )
}

object NgJwtUserExtractorConfig {
  val format = new Format[NgJwtUserExtractorConfig] {
    override def writes(o: NgJwtUserExtractorConfig): JsValue = o.json
    override def reads(json: JsValue): JsResult[NgJwtUserExtractorConfig] = Try {
      NgJwtUserExtractorConfig(

      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }
}

class NgJwtUserExtractor extends NgFakePlugin {

  override def name: String = Foo
  override def description: Option[String] = Foo
  override def defaultConfigObject: Option[NgPluginConfig] = NgJwtUserExtractorConfig().some
  override def multiInstance: Boolean = true
  override def core: Boolean = true
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Foo)
  override def steps: Seq[NgStep] = Seq(NgStep.Foo)

  override def plugin(ctx: NgFakePluginContext)(implicit env: Env, ec: ExecutionContext): Future[Foo] = {
    val config = ctx.cachedConfig(internalName)(NgJwtUserExtractorConfig.format).getOrElse(NgJwtUserExtractorConfig())

  }
}
  