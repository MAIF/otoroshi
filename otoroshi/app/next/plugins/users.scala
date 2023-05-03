package otoroshi.next.plugins

import akka.Done
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.models.PrivateAppsUser
import otoroshi.next.plugins.api.{NgPreRoutingError, _}
import otoroshi.security.{IdGenerator, OtoroshiClaim}
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
    profileNotMatch: Seq[String] = Seq.empty
) extends NgPluginConfig {
  override def json: JsValue = Json.obj(
    "usernames"          -> usernames,
    "emails"             -> emails,
    "email_domains"      -> emailDomains,
    "metadata_match"     -> metadataMatch,
    "metadata_not_match" -> metadataNotMatch,
    "profile_match"      -> profileMatch,
    "profile_not_match"  -> profileNotMatch
  )
}

object NgHasAllowedUsersValidatorConfig {
  val format = new Format[NgHasAllowedUsersValidatorConfig] {
    override def writes(o: NgHasAllowedUsersValidatorConfig): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgHasAllowedUsersValidatorConfig] = Try {
      NgHasAllowedUsersValidatorConfig(
        usernames = json.select("usernames").asOpt[Seq[String]].getOrElse(Seq.empty),
        emails = json.select("emails").asOpt[Seq[String]].getOrElse(Seq.empty),
        emailDomains = json.select("email_domains").asOpt[Seq[String]].getOrElse(Seq.empty),
        metadataMatch = json.select("metadata_match").asOpt[Seq[String]].getOrElse(Seq.empty),
        metadataNotMatch = json.select("metadata_not_match").asOpt[Seq[String]].getOrElse(Seq.empty),
        profileMatch = json.select("profile_match").asOpt[Seq[String]].getOrElse(Seq.empty),
        profileNotMatch = json.select("profile_not_match").asOpt[Seq[String]].getOrElse(Seq.empty)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }
}

class NgHasAllowedUsersValidator extends NgAccessValidator {

  private val logger                                       = Logger("otoroshi-plugins-hasallowedusersvalidator")
  override def name: String                                = "Allowed users only"
  override def description: Option[String]                 = "This plugin only let allowed users pass".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgHasAllowedUsersValidatorConfig().some
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.AccessControl)
  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)

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
        val config      = ctx
          .cachedConfig(internalName)(NgHasAllowedUsersValidatorConfig.format)
          .getOrElse(NgHasAllowedUsersValidatorConfig())
        val userMetaRaw = user.otoroshiData.getOrElse(Json.obj())
        if (
          config.usernames.contains(user.name) ||
          config.emails.contains(user.email) ||
          config.emailDomains.exists(domain => user.email.endsWith(domain)) ||
          (config.metadataMatch.exists(
            JsonPathUtils.matchWith(userMetaRaw, "user metadata")
          ) && !config.metadataNotMatch.exists(
            JsonPathUtils.matchWith(userMetaRaw, "user metadata")
          )) ||
          (config.profileMatch.exists(JsonPathUtils.matchWith(user.profile, "user profile")) && !config.profileNotMatch
            .exists(
              JsonPathUtils.matchWith(user.profile, "user profile")
            ))
        ) {
          NgAccess.NgAllowed.vfuture
        } else {
          forbidden(ctx)
        }
      }
      case _          => forbidden(ctx)
    }
  }
}

case class NgJwtUserExtractorConfig(
    verifier: String,
    strict: Boolean = true,
    strip: Boolean = false,
    namePath: Option[String] = None,
    emailPath: Option[String] = None,
    metaPath: Option[String] = None
) extends NgPluginConfig {
  override def json: JsValue = Json.obj(
    "verifier"   -> verifier,
    "strict"     -> strict,
    "strip"      -> strip,
    "name_path"  -> namePath,
    "email_path" -> emailPath,
    "meta_path"  -> metaPath
  )
}

object NgJwtUserExtractorConfig {
  val format = new Format[NgJwtUserExtractorConfig] {
    override def writes(o: NgJwtUserExtractorConfig): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgJwtUserExtractorConfig] = Try {
      NgJwtUserExtractorConfig(
        verifier = json.select("verifier").as[String],
        strict = json.select("strict").asOpt[Boolean].getOrElse(true),
        strip = json.select("strip").asOpt[Boolean].getOrElse(false),
        namePath = json.select("namePath").asOpt[String],
        emailPath = json.select("emailPath").asOpt[String],
        metaPath = json.select("metaPath").asOpt[String]
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }
}

class NgJwtUserExtractor extends NgPreRouting {

  override def name: String                                = "Jwt user extractor"
  override def description: Option[String]                 = "This plugin extract a user from a JWT token".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgJwtUserExtractorConfig("none").some
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Authentication)
  override def steps: Seq[NgStep]                          = Seq(NgStep.PreRoute)

  private val registeredClaims = Seq(
    "iss",
    "sub",
    "aud",
    "exp",
    "nbf",
    "iat",
    "jti"
  )

  override def preRoute(
      ctx: NgPreRoutingContext
  )(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    val config =
      ctx.cachedConfig(internalName)(NgJwtUserExtractorConfig.format).getOrElse(NgJwtUserExtractorConfig("none"))
    env.datastores.globalJwtVerifierDataStore.findById(config.verifier).flatMap {
      case None if !config.strict =>
        Done.rightf
      case None if config.strict  =>
        NgPreRoutingErrorWithResult(
          Results.Unauthorized(
            Json.obj("error" -> "unauthorized", "error_description" -> "You have to provide a valid user")
          )
        ).leftf
      case Some(verifier)         => {
        verifier
          .verify(
            ctx.request,
            ctx.route.legacy,
            None,
            None,
            ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).get,
            ctx.attrs
          ) { jwtInjection =>
            jwtInjection.decodedToken match {
              case None if !config.strict => Results.Unauthorized(Json.obj()).future
              case None if config.strict  => Results.Ok(Json.obj()).future
              case Some(token)            => {
                val jsonToken                         = new String(OtoroshiClaim.decoder.decode(token.getPayload))
                val parsedJsonToken                   = Json.parse(jsonToken).as[JsObject]
                val strippedJsonToken                 = JsObject(parsedJsonToken.value.filter {
                  case (key, _) if registeredClaims.contains(key) => false
                  case _                                          => true
                })
                val tokenMap: Map[String, String]     = parsedJsonToken.value.collect {
                  case (key, JsNumber(number)) => (key, number.toString())
                  case (key, JsString(value))  => (key, value)
                  case (key, JsBoolean(value)) => (key, value.toString)
                }.toMap
                val meta: Option[JsValue]             =
                  config.metaPath.flatMap(path => Try(JsonPathUtils.getAt[JsObject](jsonToken, path)).toOption.flatten)
                val user: PrivateAppsUser             = PrivateAppsUser(
                  randomId = IdGenerator.uuid,
                  name = JsonPathUtils.getAt[String](jsonToken, config.namePath.getOrElse("name")).getOrElse("--"),
                  email = JsonPathUtils.getAt[String](jsonToken, config.emailPath.getOrElse("email")).getOrElse("--"),
                  profile = if (config.strip) strippedJsonToken else parsedJsonToken,
                  token = Json.obj("jwt" -> token.getToken, "payload" -> parsedJsonToken),
                  realm = s"JwtUserExtractor@${ctx.route.id}",
                  authConfigId = s"JwtUserExtractor@${ctx.route.id}",
                  otoroshiData = meta,
                  createdAt = DateTime.now(),
                  expiredAt = DateTime.now().plusHours(1),
                  lastRefresh = DateTime.now(),
                  tags = Seq.empty,
                  metadata = Map.empty,
                  location = ctx.route.location
                )
                ctx.attrs.put(otoroshi.plugins.Keys.UserKey -> user)
                val newElContext: Map[String, String] = ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).get ++ tokenMap
                ctx.attrs.put(otoroshi.plugins.Keys.ElCtxKey -> newElContext)
                Results.Ok(Json.obj()).future
              }
            }
          }
          .recover { case e: Throwable =>
            Results.Unauthorized(Json.obj())
          }
          .flatMap { result =>
            result.header.status match {
              case 200 =>
                Done.rightf
              case _   =>
                NgPreRoutingErrorWithResult(
                  Results.Unauthorized(
                    Json.obj("error" -> "unauthorized", "error_description" -> "You have to provide a valid user")
                  )
                ).leftf
            }
          }
      }
    }
  }
}
