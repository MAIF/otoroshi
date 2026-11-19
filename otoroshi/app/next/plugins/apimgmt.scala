package otoroshi.next.plugins

import com.auth0.jwt.interfaces.DecodedJWT
import otoroshi.env.Env
import otoroshi.auth.OAuth2ModuleConfig
import otoroshi.models.{ApiIdentifier, ApiKey, InHeader, InQueryParam, JwtTokenLocation, LocalJwtVerifier, RouteIdentifier}
import otoroshi.next.models.{ApiRef, ApiSubscriptionEnabled, ApikeyAccessModeConfiguration, OAuth2RemoteAccessModeConfiguration}
import otoroshi.next.plugins.api.*
import otoroshi.security.{IdGenerator, OtoroshiClaim}
import otoroshi.utils.Base64Codec
import org.joda.time.DateTime
import otoroshi.cluster.ClusterAgent
import otoroshi.events.{Alerts, RevokedApiKeyUsageAlert}
import otoroshi.gateway.Errors
import otoroshi.utils.{JsonPathUtils, RegexPool}
import otoroshi.utils.http.DN
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*
import play.api.mvc.{Result, Results}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.*

// Everything a plan needs to hand over so that an apikey created on the fly enforces the very same
// rules as one created through a subscription.
trait ApikeyFromPlanConfig {
  def createIfMissing: Boolean
  def apiId: Option[String]
  def planId: Option[String]
  def throttlingStrategy: Option[ThrottlingStrategyConfig]
  def apikey: ApikeyAccessModeConfiguration
  // only the consumers with an enabled subscription to the plan are identified: nothing is minted for the others.
  // Only applies with api_id and plan_id
  def subscriptionRequired: Boolean
  // the access mode of the plan when its keys are the identities of the consumers of a token or a certificate:
  // jwt, mtls or oauth2-remote. None for the keys of the expression extractor
  def accessMode: Option[String] = None
}

object ApikeyFromPlan {

  // the metadata of the key of a consumer of a jwt, mtls or oauth2-remote plan, whether the extractor of the plan
  // minted it on the fly or a subscription provisioned it: the key is the identity of a consumer, not a credential
  // handed to it, and its plan is found without parsing its client id
  def consumerMetadata(apiId: String, planId: String, accessMode: String): Map[String, String] = Map(
    "api_id"        -> apiId,
    "plan_id"       -> planId,
    "access_mode"   -> accessMode,
    "plan_consumer" -> "true"
  )

  def json(config: ApikeyFromPlanConfig): JsObject = Json.obj(
    "create_if_missing"   -> config.createIfMissing,
    "api_id"              -> config.apiId,
    "plan_id"             -> config.planId,
    "throttling_strategy" -> config.throttlingStrategy.map(_.json),
    "apikey"              -> config.apikey.json,
    "subscription_required" -> config.subscriptionRequired
  )

  def createIfMissing(json: JsValue): Boolean                            =
    json.select("create_if_missing").asOpt[Boolean].getOrElse(true)
  def apiId(json: JsValue): Option[String]                               = json.select("api_id").asOpt[String]
  def subscriptionRequired(json: JsValue): Boolean                       =
    json.select("subscription_required").asOpt[Boolean].getOrElse(false)
  def planId(json: JsValue): Option[String]                              = json.select("plan_id").asOpt[String]
  def throttlingStrategy(json: JsValue): Option[ThrottlingStrategyConfig] =
    json.select("throttling_strategy").asOpt(using ThrottlingStrategyConfig.fmt)
  def apikeyTemplate(json: JsValue): ApikeyAccessModeConfiguration       = json
    .select("apikey")
    .asOpt[JsValue]
    .flatMap(v => ApikeyAccessModeConfiguration.fmt.reads(v).asOpt)
    .getOrElse(ApikeyAccessModeConfiguration())

  // mirrors Api.generateNewApikeyFromPlan: the same plan must enforce the same quotas, restrictions
  // and rotation whether the consumer got its key through a subscription or through this plugin.
  private def apikeyFor(
      clientId: String,
      config: ApikeyFromPlanConfig,
      ctx: NgAccessContext,
      extraMetadata: Map[String, String]
  )(using
      env: Env,
      ec: ExecutionContext
  ): ApiKey = {
    val template           = config.apikey
    val attrs              = ctx.attrs.put(
      otoroshi.plugins.Keys.ElCtxKey -> (ctx.attrs
        .get(otoroshi.plugins.Keys.ElCtxKey)
        .getOrElse(Map.empty) ++ Map("client_id" -> clientId))
    )
    val authorizedEntities = if (template.authorizedEntities.nonEmpty) {
      template.authorizedEntities
    } else {
      config.apiId.map(id => Seq(ApiIdentifier(id))).getOrElse(Seq(RouteIdentifier(ctx.route.id)))
    }
    val apk = ApiKey(
      clientId = clientId,
      clientSecret = IdGenerator.token(128),
      clientName = template.clientNamePattern.map(_.evaluateEl(attrs)(using env)).getOrElse(clientId),
      description = template.description.getOrElse(""),
      enabled = template.enabled,
      validUntil = template.validUntil,
      readOnly = template.readOnly,
      allowClientIdOnly = template.allowClientIdOnly,
      constrainedServicesOnly = template.constrainedServicesOnly,
      restrictions = template.restrictions,
      rotation = template.rotation,
      authorizedEntities = authorizedEntities,
      throttlingStrategy = config.throttlingStrategy,
      apiRef = for {
        apiId  <- config.apiId
        planId <- config.planId
      } yield ApiRef(apiId, planId, ""),
      tags = template.tags,
      // the metadata of a consumer come last, so that neither the template nor the user profile overrides them
      metadata = template.metadata ++ Map("created_by" -> "apikey-extractor") ++ extraMetadata ++ (for {
        mode   <- config.accessMode
        apiId  <- config.apiId
        planId <- config.planId
      } yield consumerMetadata(apiId, planId, mode)).getOrElse(Map.empty)
    )
    if (config.createIfMissing) {
      apk.save()
      if (env.clusterConfig.mode.isWorker) {
        ClusterAgent.clusterSaveApikey(env, apk)(using ec, env.otoroshiMaterializer)
      }
    }
    apk
  }

  // an apikey identified by a plan is the only thing carrying the plan settings, so the call has to
  // go through the very same checks ApiKeyHelper.passWithApiKeyFromCache applies to a credential
  // based one: restrictions, key rotation, then quotas and rate limiting through the throttling
  // strategy of the apikey. going through the strategy matters: the legacy datastore path ignores
  // it and counts on another set of keys, so a plan quota would never be enforced.
  // NgApiConsumerEnforcer is the only caller: the call is counted at the very end of the access
  // validation, so that a call turned away by anything else is never counted.
  def enforce(apikey: ApiKey, ctx: NgAccessContext)(using
      env: Env,
      ec: ExecutionContext
  ): Future[Result] = {
    val req   = ctx.request
    val attrs = ctx.attrs

    def error(
        status: Results.Status,
        message: String,
        code: String,
        extraAnalyticsMessage: Option[String]
    ): Future[Result] = {
      val finalAttrs = extraAnalyticsMessage match {
        case None          => attrs
        case Some(message) => {
          val key = "apikey_rejection_reason"
          attrs.update(otoroshi.plugins.Keys.ExtraAnalyticsDataKey) {
            case Some(obj @ JsObject(_)) => obj ++ Json.obj(key -> message)
            case None                    => Json.obj(key -> message)
          }
        }
      }
      Errors.craftResponseResult(
        message,
        status,
        req,
        None,
        code.some,
        attrs = finalAttrs,
        maybeRoute = ctx.route.some
      )
    }

    val (restricted, errResult) =
      apikey.restrictions.handleRestrictions(ctx.route.id, None, ctx.route.some, apikey.some, req, attrs)
    if (restricted) {
      errResult
    } else {
      env.datastores.apiKeyDataStore.keyRotation(apikey).map { rotationInfos =>
        rotationInfos.foreach { i =>
          attrs.put(otoroshi.plugins.Keys.ApiKeyRotationKey -> i)
        }
      }
      val quotasSettings = env.datastores.globalConfigDataStore.latest().quotasSettings
      val strategy       = env.rateLimiter.getOrCreate(
        apikey.clientId,
        attrs = attrs,
        throttlingStrategy = apikey.throttlingStrategy
      )
      strategy
        .checkAndIncrement(
          apikey.clientId,
          1,
          apikey.allowedQuota,
          env.throttlingWindow
        )
        .flatMap {
          case result if result.allowed =>
            val quotas = result.quotas.legacy()
            attrs.put(otoroshi.plugins.Keys.ApiKeyRemainingQuotasKey -> quotas)
            ApiKey.sendQuotasAlmostExceededAlerts(apikey, quotas, quotasSettings)
            Results.Ok(Json.obj()).vfuture

          case result =>
            // Quota exceeded - reject with 429
            val quotas = result.quotas.legacy()
            attrs.put(otoroshi.plugins.Keys.ErrorApiKeyKey           -> apikey)
            attrs.put(otoroshi.plugins.Keys.ApiKeyRemainingQuotasKey -> quotas)
            ApiKey.sendQuotasExceededAlerts(apikey, quotas, quotasSettings)
            error(
              Results.TooManyRequests,
              "You performed too much requests",
              "errors.too.much.requests",
              s"apikey '${apikey.clientId}' quotas exceeded".some
            )
        }
    }
  }

  private val logger = play.api.Logger("otoroshi-apikey-from-plan")

  // the keys an extractor minted for a consumer, and the ones a subscription provisioned for it
  private def mintedForConsumer(apikey: ApiKey): Boolean =
    apikey.metadata.get("created_by").contains("apikey-extractor") || apikey.metadata.get("plan_consumer").contains("true")

  def consumerKeysMigrationKey(using env: Env): String =
    s"${env.storageRoot}:migrations:apikeys:consumer-prefix-with-api"

  // the new client id of a key named <kind>_<plan id>_<client id>, before the api id was in the prefix
  def renamedConsumerClientId(apikey: ApiKey): Option[String] =
    apikey.apiRef.filter(ref => mintedForConsumer(apikey) && ref.api.nonEmpty && ref.plan.nonEmpty).flatMap { ref =>
      Seq("keyless", "jwt", "mtls", "oauth2").collectFirst {
        case kind
            if apikey.clientId.startsWith(s"${kind}_${ref.plan}_") &&
              !apikey.clientId.startsWith(s"${kind}_${ref.api}_${ref.plan}_") =>
          s"${kind}_${ref.api}_${ref.plan}_${apikey.clientId.stripPrefix(s"${kind}_${ref.plan}_")}"
      }
    }

  // the keys minted for the consumers of keyless, jwt, mtls and oauth2-remote plans used to be named
  // <kind>_<plan id>_<client id>, shared by every api with a plan of the same id. They are renamed once
  // <kind>_<api id>_<plan id>_<client id>, with everything else kept (secret, enabled flag, settings, apiRef), and the
  // token_refs of the subscription that provisioned one follow. Their quota counters, keyed by client id, start again
  // from zero. The keys without apiRef are left alone
  def migrateConsumerKeys()(using env: Env, ec: ExecutionContext): Future[Unit] = {
    env.datastores.rawDataStore.get(consumerKeysMigrationKey).flatMap {
      case Some(_) => ().vfuture
      case None    =>
        env.datastores.apiKeyDataStore
          .findAll()
          .flatMap { keys =>
            val renames = keys.flatMap(key => renamedConsumerClientId(key).map(id => (key, id)))
            renames.mapAsync { case (key, newId) => migrateConsumerKey(key, newId) }.flatMap { results =>
              if (renames.nonEmpty) {
                logger.warn(
                  s"${results.count(identity)} api keys of plan consumers renamed with the id of their api in their client id, their quotas start again from zero"
                )
              }
              if (results.exists(!_)) {
                // not recorded, so that the migration runs again at the next start
                logger.error("some api keys of plan consumers could not be renamed, will retry at the next start")
                ().vfuture
              } else {
                env.datastores.rawDataStore
                  .set(consumerKeysMigrationKey, org.apache.pekko.util.ByteString(DateTime.now().toString), None)
                  .map(_ => ())
              }
            }
          }
          .recover { case e: Throwable =>
            logger.error("error while renaming the api keys of plan consumers", e)
          }
    }
  }

  private def migrateConsumerKey(key: ApiKey, newId: String)(using env: Env, ec: ExecutionContext): Future[Boolean] =
    env.datastores.apiKeyDataStore
      .findById(newId)
      .flatMap {
        case Some(_) =>
          logger.warn(s"api key '$newId' exists already, '${key.clientId}' is not renamed")
          true.vfuture
        case None    =>
          key.copy(clientId = newId).save().flatMap {
            case false => false.vfuture
            case true  =>
              for {
                _ <- renameInSubscription(key, newId)
                _ <- env.datastores.apiKeyDataStore.delete(key.clientId)
              } yield {
                logger.warn(s"api key '${key.clientId}' renamed '$newId'")
                true
              }
          }
      }
      .recover { case e: Throwable =>
        logger.error(s"unable to rename the api key '${key.clientId}'", e)
        false
      }

  private def renameInSubscription(key: ApiKey, newId: String)(using env: Env, ec: ExecutionContext): Future[Unit] = {
    def isOld(ref: JsValue): Boolean = ref.select("apikey").asOpt[String].contains(key.clientId)
    key.apiRef.map(_.subscription).filter(_.nonEmpty) match {
      case None        => ().vfuture
      case Some(subId) =>
        env.datastores.apiSubscriptionDataStore.findById(subId).flatMap {
          case Some(sub) if sub.tokenRefs.exists(isOld) =>
            sub
              .copy(tokenRefs = sub.tokenRefs.map { ref =>
                if (isOld(ref)) ref.asOpt[JsObject].getOrElse(Json.obj()) ++ Json.obj("apikey" -> newId) else ref
              })
              .save()
              .map(_ => ())
          case _                                        => ().vfuture
        }
    }
  }

  // the key of the consumer of a plan that requires a subscription: provisioned for this client id by a subscription
  // of the plan that is enabled. A key minted on the fly, or saved by hand, is not
  private def provisionedBySubscription(apikey: ApiKey, clientId: String, apiId: String, planId: String)(using
      env: Env
  ): Boolean =
    apikey.apiRef.exists { ref =>
      ref.api == apiId && ref.plan == planId && ref.subscription.nonEmpty &&
      env.proxyState.apiSubscription(ref.subscription).exists { sub =>
        sub.status == ApiSubscriptionEnabled && sub.apiRef == apiId && sub.planRef == planId &&
        sub.tokenRefs.exists(_.select("apikey").asOpt[String].contains(clientId))
      }
    }

  // the consumers of a plan that requires a subscription: the key provisioned by an enabled subscription of the plan
  // serves the call, the others are not identified. The refusal goes to the analytics of the call: the consumer
  // enforcer tells it when no other plan identifies the caller
  private def resolveSubscribed(clientId: String, apiId: String, planId: String, ctx: NgAccessContext)(using
      env: Env,
      ec: ExecutionContext
  ): Future[Result] = {
    env.proxyState.apikey(clientId) match {
      case Some(apikey) if provisionedBySubscription(apikey, clientId, apiId, planId) =>
        if (apikey.isActive()) {
          // identification only, like any other consumer: NgApiConsumerEnforcer enforces the key
          ctx.attrs.put(otoroshi.plugins.Keys.ApiKeyKey -> apikey)
          Results.Ok(Json.obj()).vfuture
        } else {
          Alerts.send(
            RevokedApiKeyUsageAlert(
              env.snowflakeGenerator.nextIdStr(),
              DateTime.now(),
              env.env,
              ctx.request,
              apikey,
              None,
              env
            )
          )
          Results.Unauthorized(Json.obj("error" -> "unknown_apikey")).vfuture
        }
      case _                                                                         =>
        val notSubscribed = Json.obj("not_subscribed" -> Json.obj("plan" -> planId, "client_id" -> clientId))
        ctx.attrs.update(otoroshi.plugins.Keys.ExtraAnalyticsDataKey) {
          case Some(obj) => obj ++ notSubscribed
          case None      => notSubscribed
        }
        Results.Unauthorized(Json.obj("error" -> "not_subscribed")).vfuture
    }
  }

  // the key names another api or another plan than the ones of the extractor
  private def belongsElsewhere(apikey: ApiKey, config: ApikeyFromPlanConfig): Boolean =
    (config.apiId, config.planId) match {
      case (Some(apiId), Some(planId)) => apikey.apiRef.exists(ref => ref.api != apiId || ref.plan != planId)
      case _                           => false
    }

  // resolves the apikey behind a client id. an existing one always wins, so that a revoked or
  // disabled apikey cannot be resurrected by minting a fresh one over it. otherwise the apikey is
  // built from the plan settings, and create_if_missing decides whether it reaches the datastore:
  // with it off the apikey only lives for the call, so a public api does not fill the datastore
  // with one entry per caller. quota counters are keyed by client id, so they add up across calls
  // either way.
  def resolveOrCreate(
      clientId: String,
      config: ApikeyFromPlanConfig,
      ctx: NgAccessContext,
      extraMetadata: Map[String, String] = Map.empty
  )(using
      env: Env,
      ec: ExecutionContext
  ): Future[Result] = {
    // an api can publish several plans, and every served plan puts its own extractor on every
    // route. they all write to the same attribute, so the first plan to identify the caller has to
    // win: without this an extractor that always succeeds, the keyless one typically, would
    // overwrite the identity a credential just established.
    if (ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyKey).isDefined) {
      return Results.Ok(Json.obj()).vfuture
    }
    // a plan that requires a subscription mints nothing: only the consumers it provisioned a key for are served. A
    // plugin used on its own, without api_id and plan_id, behaves as before
    (config.apiId, config.planId) match {
      case (Some(apiId), Some(planId)) if config.subscriptionRequired =>
        return resolveSubscribed(clientId, apiId, planId, ctx)
      case _                                                          =>
    }
    //env.datastores.apiKeyDataStore
    //  .findById(clientId)
    //  .map {
    env.proxyState.apikey(clientId).vfuture.map {
        case Some(apikey)                   => apikey.some
        case None                           => apikeyFor(clientId, config, ctx, extraMetadata).some
      }
      .flatMap {
        // a key of another api or of another plan under the client id of a consumer of this plan (saved by hand
        // under that id): its settings are never enforced in place of the ones of the plan
        case Some(apikey) if belongsElsewhere(apikey, config) => {
          Alerts.send(
            RevokedApiKeyUsageAlert(
              env.snowflakeGenerator.nextIdStr(),
              DateTime.now(),
              env.env,
              ctx.request,
              apikey,
              None,
              env
            )
          )
          Results.Unauthorized(Json.obj("error" -> "unknown_apikey")).vfuture
        }
        case Some(apikey) if apikey.isActive() => {
          // identification only: the restrictions and the quotas of that apikey are enforced by
          // NgApiConsumerEnforcer, once every other access validator of the route has passed
          ctx.attrs.put(otoroshi.plugins.Keys.ApiKeyKey -> apikey)
          Results.Ok(Json.obj()).vfuture
        }
        // a persisted apikey can be disabled or reach its validUntil long after the plan minted it,
        // and it is still resolved here: the caller has to be turned away just like a revoked
        // credential would be, alert included
        case Some(apikey)                      => {
          Alerts.send(
            RevokedApiKeyUsageAlert(
              env.snowflakeGenerator.nextIdStr(),
              DateTime.now(),
              env.env,
              ctx.request,
              apikey,
              None,
              env
            )
          )
          Results.Unauthorized(Json.obj("error" -> "unknown_apikey")).vfuture
        }
        case None                              => Results.Unauthorized(Json.obj("error" -> "unknown_apikey")).vfuture
      }
  }
}

trait ApikeyExtractorPlugin extends NgAccessValidator {

  override def multiInstance: Boolean            = true
  override def core: Boolean                     = true
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl)
  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess)

  protected def unauthorized(description: String): NgAccess =
    NgAccess.NgDenied(
      Results.Unauthorized(Json.obj("error" -> "unauthorized", "error_description" -> description))
    )

  // in non strict mode the call goes on without any apikey, so that a downstream plugin can still
  // decide what to do with it
  protected def failure(strict: Boolean, description: String): NgAccess =
    if (strict) unauthorized(description) else NgAccess.NgAllowed

  // failing to identify a consumer is what the non strict mode is about: the call goes on without
  // any apikey and something downstream decides, NgApiConsumerEnforcer typically. a rejection an
  // extraction step took on its own, a jwt verifier answering with its own error result, is a
  // decision taken about that caller and has to reach it as is, or a non strict plan would happily
  // serve someone its verifier just turned away.
  protected def outcome(result: Result, strict: Boolean): NgAccess = result.header.status match {
    case 200                   => NgAccess.NgAllowed
    case 403 | 404 | 429       => NgAccess.NgDenied(result)
    case _                     => failure(strict, "You have to provide a valid apikey")
  }
}

case class NgJwtApikeyExtractorConfig(
    verifier: String,
    clientIdPath: String = "client_id",
    clientIdPrefix: String = "jwt_",
    strict: Boolean = true,
    createIfMissing: Boolean = true,
    apiId: Option[String] = None,
    planId: Option[String] = None,
    throttlingStrategy: Option[ThrottlingStrategyConfig] = None,
    apikey: ApikeyAccessModeConfiguration = ApikeyAccessModeConfiguration(),
    subscriptionRequired: Boolean = false
) extends NgPluginConfig
    with ApikeyFromPlanConfig {
  override def accessMode: Option[String] = Some("jwt")
  override def json: JsValue = Json.obj(
    "verifier"         -> verifier,
    "client_id_path"   -> clientIdPath,
    "client_id_prefix" -> clientIdPrefix,
    "strict"           -> strict
  ) ++ ApikeyFromPlan.json(this)
}

object NgJwtApikeyExtractorConfig {
  val format = new Format[NgJwtApikeyExtractorConfig] {
    override def writes(o: NgJwtApikeyExtractorConfig): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgJwtApikeyExtractorConfig] = Try {
      NgJwtApikeyExtractorConfig(
        verifier = json.select("verifier").as[String],
        clientIdPath = json.select("client_id_path").asOpt[String].getOrElse("client_id"),
        clientIdPrefix = json.select("client_id_prefix").asOpt[String].getOrElse("jwt_"),
        strict = json.select("strict").asOpt[Boolean].getOrElse(true),
        createIfMissing = ApikeyFromPlan.createIfMissing(json),
        apiId = ApikeyFromPlan.apiId(json),
        planId = ApikeyFromPlan.planId(json),
        throttlingStrategy = ApikeyFromPlan.throttlingStrategy(json),
        apikey = ApikeyFromPlan.apikeyTemplate(json),
        subscriptionRequired = ApikeyFromPlan.subscriptionRequired(json)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }
}

class NgJwtApikeyExtractor extends ApikeyExtractorPlugin {

  override def name: String                                = "Jwt apikey extractor"
  override def description: Option[String]                 =
    "This plugin extracts an apikey from a JWT token claim holding its client id. The apikey is built from the plan settings when it does not exist yet, and persisted when create_if_missing is on. It only identifies the consumer: pair it with the 'Api consumer enforcer' plugin to enforce its quotas, like a published plan does".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgJwtApikeyExtractorConfig("none").some

  override def access(ctx: NgAccessContext)(using env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config =
      ctx.cachedConfig(internalName)(NgJwtApikeyExtractorConfig.format).getOrElse(NgJwtApikeyExtractorConfig("none"))
    env.datastores.globalJwtVerifierDataStore.findById(config.verifier).flatMap {
      case None           => failure(config.strict, "You have to provide a valid apikey").vfuture
      case Some(verifier) => {
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
              case None        => Results.Unauthorized(Json.obj()).future
              case Some(token) => {
                val jsonToken = new String(OtoroshiClaim.decoder.decode(token.getPayload))
                JsonPathUtils.getAt[String](jsonToken, config.clientIdPath) match {
                  case None           => Results.Unauthorized(Json.obj("error" -> "no_client_id")).future
                  case Some(clientId) =>
                    // the prefix keeps the identities of that plan in their own namespace: a client
                    // id comes from a token, so two apis behind the same idp would otherwise share
                    // one apikey, and the last one to mint it would decide the quotas of both
                    ApikeyFromPlan.resolveOrCreate(s"${config.clientIdPrefix}${clientId}", config, ctx)
                }
              }
            }
          }
          .recover { case _: Throwable => Results.Unauthorized(Json.obj()) }
          .map(result => outcome(result, config.strict))
      }
    }
  }
}

case class NgExpressionApikeyExtractorConfig(
    expression: String = "${req.ip}",
    clientIdPrefix: String = "public_",
    strict: Boolean = false,
    createIfMissing: Boolean = true,
    apiId: Option[String] = None,
    planId: Option[String] = None,
    throttlingStrategy: Option[ThrottlingStrategyConfig] = None,
    apikey: ApikeyAccessModeConfiguration = ApikeyAccessModeConfiguration()
) extends NgPluginConfig
    with ApikeyFromPlanConfig {
  // the identity of a keyless consumer is an expression on the request, no subscription can be tied to it
  override def subscriptionRequired: Boolean = false
  override def json: JsValue = Json.obj(
    "expression"       -> expression,
    "client_id_prefix" -> clientIdPrefix,
    "strict"           -> strict
  ) ++ ApikeyFromPlan.json(this)
}

object NgExpressionApikeyExtractorConfig {
  val format = new Format[NgExpressionApikeyExtractorConfig] {
    override def writes(o: NgExpressionApikeyExtractorConfig): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgExpressionApikeyExtractorConfig] = Try {
      NgExpressionApikeyExtractorConfig(
        expression = json.select("expression").asOpt[String].getOrElse("${req.ip}"),
        clientIdPrefix = json.select("client_id_prefix").asOpt[String].getOrElse("public_"),
        strict = json.select("strict").asOpt[Boolean].getOrElse(false),
        createIfMissing = ApikeyFromPlan.createIfMissing(json),
        apiId = ApikeyFromPlan.apiId(json),
        planId = ApikeyFromPlan.planId(json),
        throttlingStrategy = ApikeyFromPlan.throttlingStrategy(json),
        apikey = ApikeyFromPlan.apikeyTemplate(json)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }
}

class NgExpressionApikeyExtractor extends ApikeyExtractorPlugin {

  override def name: String                                = "Expression apikey extractor"
  override def description: Option[String]                 =
    "This plugin builds a consumer identity from an expression, the caller ip address by default, and turns it into an apikey. It gives a public access the quotas and throttling of its plan without asking the caller for any credential. It only identifies the consumer: pair it with the 'Api consumer enforcer' plugin to enforce its quotas, like a published plan does".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgExpressionApikeyExtractorConfig().some

  override def access(ctx: NgAccessContext)(using env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config   = ctx
      .cachedConfig(internalName)(NgExpressionApikeyExtractorConfig.format)
      .getOrElse(NgExpressionApikeyExtractorConfig())
    // evaluated per call, so it cannot be part of the cached config
    val extracted = config.expression.evaluateEl(ctx.attrs)(using env)
    if (extracted.trim.isEmpty || extracted.contains("${")) {
      // an unresolved placeholder would mint a single apikey named after the expression itself, and
      // every anonymous caller would then share the same quotas
      failure(config.strict, "You have to provide a valid apikey").vfuture
    } else {
      // the prefix keeps generated identities in their own namespace: without it an expression fed
      // by the request could be made to resolve to the client id of a real apikey
      val clientId = s"${config.clientIdPrefix}${extracted}"
      ApikeyFromPlan.resolveOrCreate(clientId, config, ctx).map(result => outcome(result, config.strict))
    }
  }
}

case class NgClientCertApikeyExtractorConfig(
    regexSubjectDNs: Seq[String] = Seq.empty,
    regexIssuerDNs: Seq[String] = Seq.empty,
    clientIdField: Option[String] = None,
    clientIdPrefix: String = "mtls_",
    strict: Boolean = true,
    createIfMissing: Boolean = true,
    apiId: Option[String] = None,
    planId: Option[String] = None,
    throttlingStrategy: Option[ThrottlingStrategyConfig] = None,
    apikey: ApikeyAccessModeConfiguration = ApikeyAccessModeConfiguration(),
    subscriptionRequired: Boolean = false
) extends NgPluginConfig
    with ApikeyFromPlanConfig {
  override def accessMode: Option[String] = Some("mtls")
  override def json: JsValue = Json.obj(
    "regex_subject_dns" -> regexSubjectDNs,
    "regex_issuer_dns"  -> regexIssuerDNs,
    "client_id_field"   -> clientIdField,
    "client_id_prefix"  -> clientIdPrefix,
    "strict"            -> strict
  ) ++ ApikeyFromPlan.json(this)
}

object NgClientCertApikeyExtractorConfig {
  val format = new Format[NgClientCertApikeyExtractorConfig] {
    override def writes(o: NgClientCertApikeyExtractorConfig): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgClientCertApikeyExtractorConfig] = Try {
      NgClientCertApikeyExtractorConfig(
        regexSubjectDNs = json.select("regex_subject_dns").asOpt[Seq[String]].getOrElse(Seq.empty).toSeq,
        regexIssuerDNs = json.select("regex_issuer_dns").asOpt[Seq[String]].getOrElse(Seq.empty).toSeq,
        clientIdField = json.select("client_id_field").asOpt[String].filter(_.trim.nonEmpty),
        clientIdPrefix = json.select("client_id_prefix").asOpt[String].getOrElse("mtls_"),
        strict = json.select("strict").asOpt[Boolean].getOrElse(true),
        createIfMissing = ApikeyFromPlan.createIfMissing(json),
        apiId = ApikeyFromPlan.apiId(json),
        planId = ApikeyFromPlan.planId(json),
        throttlingStrategy = ApikeyFromPlan.throttlingStrategy(json),
        apikey = ApikeyFromPlan.apikeyTemplate(json),
        subscriptionRequired = ApikeyFromPlan.subscriptionRequired(json)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }
}

class NgClientCertApikeyExtractor extends ApikeyExtractorPlugin {

  override def name: String                                = "Client certificate apikey extractor"
  override def description: Option[String]                 =
    "This plugin validates the client certificate against subject and issuer DN patterns, then turns it into an apikey. The apikey is built from the plan settings when it does not exist yet. It only identifies the consumer: pair it with the 'Api consumer enforcer' plugin to enforce its quotas, like a published plan does".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgClientCertApikeyExtractorConfig().some

  // no pattern at all means the plan puts no constraint on the DNs, the mTLS handshake being the
  // only requirement. as soon as one list is filled, the certificate has to match it.
  private def matches(config: NgClientCertApikeyExtractorConfig, subject: DN, issuer: DN): Boolean = {
    if (config.regexSubjectDNs.isEmpty && config.regexIssuerDNs.isEmpty) {
      true
    } else {
      config.regexSubjectDNs.exists(s => RegexPool.regex(s).matches(subject.stringify)) ||
      config.regexIssuerDNs.exists(s => RegexPool.regex(s).matches(issuer.stringify))
    }
  }

  // when the plan names a DN attribute, the certificate authority is the one deciding the consumer
  // identity, which is the point of an mTLS plan. a certificate missing that attribute does not
  // satisfy the plan, so it fails rather than silently falling back on another identity.
  // without it the identity is derived from the whole subject plus the serial number, exactly like
  // NgCertificateAsApikey does, so a given certificate always maps to the same consumer.
  private def derivedClientId(
      config: NgClientCertApikeyExtractorConfig,
      subject: DN,
      serialNumber: String
  ): Option[String] = {
    config.clientIdField match {
      case Some(field) =>
        subject.parts.find(_.name == field.trim.toLowerCase).map(_.value).filter(_.trim.nonEmpty)
      case None        =>
        Base64Codec.encodeToString((subject.stringify + "-" + serialNumber).getBytes).some
    }
  }

  override def access(ctx: NgAccessContext)(using env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx
      .cachedConfig(internalName)(NgClientCertApikeyExtractorConfig.format)
      .getOrElse(NgClientCertApikeyExtractorConfig())
    ctx.request.clientCertificateChain.flatMap(_.headOption) match {
      case None       => failure(config.strict, "You have to provide a client certificate").vfuture
      case Some(cert) => {
        val subject = DN(cert.getSubjectX500Principal.getName)
        val issuer  = DN(cert.getIssuerX500Principal.getName)
        if (!matches(config, subject, issuer)) {
          failure(config.strict, "Your client certificate is not allowed on this api").vfuture
        } else {
          derivedClientId(config, subject, cert.getSerialNumber.toString) match {
            case None           =>
              failure(config.strict, s"Your client certificate carries no ${config.clientIdField.get} in its subject").vfuture
            case Some(clientId) =>
              ApikeyFromPlan
                .resolveOrCreate(s"${config.clientIdPrefix}${clientId}", config, ctx)
                .map(result => outcome(result, config.strict))
          }
        }
      }
    }
  }
}

case class NgOidcApikeyExtractorConfig(
    ref: Option[String] = None,
    clientIdPath: String = "client_id",
    clientIdPrefix: String = "oauth2_",
    source: Option[JwtTokenLocation] = None,
    fetchUser: Boolean = false,
    userMetadataKey: String = "user_profile",
    strict: Boolean = true,
    createIfMissing: Boolean = true,
    apiId: Option[String] = None,
    planId: Option[String] = None,
    throttlingStrategy: Option[ThrottlingStrategyConfig] = None,
    apikey: ApikeyAccessModeConfiguration = ApikeyAccessModeConfiguration(),
    // the iss a token must carry, not checked when empty
    issuer: Option[String] = None,
    // a token must carry one of them in aud, not checked when empty
    audiences: Seq[String] = Seq.empty,
    subscriptionRequired: Boolean = false
) extends NgPluginConfig
    with ApikeyFromPlanConfig {
  override def accessMode: Option[String] = Some("oauth2-remote")
  override def json: JsValue = Json.obj(
    "ref"                -> ref.map(_.json).getOrElse(JsNull).asValue,
    "client_id_path"     -> clientIdPath,
    "client_id_prefix"   -> clientIdPrefix,
    "source"             -> source.map(_.asJson).getOrElse(JsNull).asValue,
    "fetch_user"         -> fetchUser,
    "user_metadata_key"  -> userMetadataKey,
    "strict"             -> strict,
    "issuer"             -> issuer.map(_.json).getOrElse(JsNull).asValue,
    "audiences"          -> audiences
  ) ++ ApikeyFromPlan.json(this)
}

object NgOidcApikeyExtractorConfig {
  val configFlow                     =
    Seq(
      "ref",
      "client_id_path",
      "client_id_prefix",
      "fetch_user",
      "user_metadata_key",
      "issuer",
      "audiences",
      "strict",
      "create_if_missing",
      "source"
    )
  val configSchema: Option[JsObject] = Some(
    Json.obj(
      "ref"               -> Json.obj(
        "type"  -> "select",
        "label" -> "Auth. module",
        "props" -> Json.obj(
          "optionsFrom"        -> "/bo/api/proxy/apis/security.otoroshi.io/v1/auth-modules",
          "optionsTransformer" -> Json.obj("label" -> "name", "value" -> "id")
        )
      ),
      "client_id_path"    -> Json.obj("type" -> "string", "label" -> "Client id claim"),
      "client_id_prefix"  -> Json.obj("type" -> "string", "label" -> "Client id prefix"),
      "fetch_user"        -> Json.obj("type" -> "bool", "label" -> "Fetch the user profile"),
      "user_metadata_key" -> Json.obj("type" -> "string", "label" -> "Metadata key of the user profile"),
      "issuer"            -> Json.obj("type" -> "string", "label" -> "Expected issuer"),
      "audiences"         -> Json.obj("type" -> "array", "label" -> "Accepted audiences"),
      "strict"            -> Json.obj("type" -> "bool", "label" -> "Strict"),
      "create_if_missing" -> Json.obj("type" -> "bool", "label" -> "Create the apikey if missing"),
      "source"            -> Json.obj("type" -> "any", "label" -> "JWT Source", "props" -> Json.obj("height" -> 200))
    )
  )
  val format                         = new Format[NgOidcApikeyExtractorConfig] {
    override def writes(o: NgOidcApikeyExtractorConfig): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgOidcApikeyExtractorConfig] = Try {
      NgOidcApikeyExtractorConfig(
        ref = json.select("ref").asOpt[String],
        clientIdPath = json.select("client_id_path").asOpt[String].getOrElse("client_id"),
        clientIdPrefix = json.select("client_id_prefix").asOpt[String].getOrElse("oauth2_"),
        source = json.select("source").asOpt[JsObject].flatMap(o => JwtTokenLocation.fromJson(o).toOption),
        fetchUser = json.select("fetch_user").asOpt[Boolean].getOrElse(false),
        userMetadataKey = json.select("user_metadata_key").asOpt[String].getOrElse("user_profile"),
        strict = json.select("strict").asOpt[Boolean].getOrElse(true),
        createIfMissing = ApikeyFromPlan.createIfMissing(json),
        apiId = ApikeyFromPlan.apiId(json),
        planId = ApikeyFromPlan.planId(json),
        throttlingStrategy = ApikeyFromPlan.throttlingStrategy(json),
        apikey = ApikeyFromPlan.apikeyTemplate(json),
        subscriptionRequired = ApikeyFromPlan.subscriptionRequired(json),
        issuer = OAuth2RemoteAccessModeConfiguration.readIssuer(json),
        audiences = OAuth2RemoteAccessModeConfiguration.readAudiences(json)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }
}

class NgOidcApikeyExtractor extends ApikeyExtractorPlugin {

  override def name: String                                = "OIDC apikey extractor"
  override def description: Option[String]                 =
    "This plugin verifies the request jwt token against the OIDC settings of an auth. module, checks its issuer and its audience when they are configured, then extracts an apikey from a claim holding its client id. Without an expected issuer nor accepted audiences, any token signed with the keys of the auth. module is accepted, the ones issued for other clients, other apis or other tenants of the same authorization server included. The apikey is built from the plan settings when it does not exist yet. It only identifies the consumer: pair it with the 'Api consumer enforcer' plugin to enforce its quotas, like a published plan does".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgOidcApikeyExtractorConfig().some
  override def isAccessAsync: Boolean                      = true

  override def noJsForm: Boolean              = true
  override def configFlow: Seq[String]        = NgOidcApikeyExtractorConfig.configFlow
  override def configSchema: Option[JsObject] = NgOidcApikeyExtractorConfig.configSchema

  // fetches the profile of the token holder and hands it over as apikey metadata. OIDCAuthToken
  // stores the user in the attributes as a side effect, which would make the call look like an
  // authenticated user session to every downstream plugin, NgExpectedConsumer included: the
  // previous value is put back so the only thing that survives here is the metadata.
  private def userMetadata(
      ctx: NgAccessContext,
      oidcModule: OAuth2ModuleConfig,
      config: NgOidcApikeyExtractorConfig,
      token: String
  )(using env: Env, ec: ExecutionContext): Future[Map[String, String]] = {
    if (!config.fetchUser) {
      Map.empty[String, String].vfuture
    } else {
      val previousUser = ctx.attrs.get(otoroshi.plugins.Keys.UserKey)
      OIDCAuthToken
        .getSession(
          ctx,
          oidcModule,
          OIDCAuthTokenConfig(
            ref = config.ref.get,
            opaque = false,
            fetchUserProfile = true,
            validateAudience = false,
            headerName = "Authorization"
          ),
          Some(token)
        )
        .map { _ =>
          val fetched = ctx.attrs.get(otoroshi.plugins.Keys.UserKey)
          previousUser match {
            case Some(user) => ctx.attrs.put(otoroshi.plugins.Keys.UserKey -> user)
            case None       => ctx.attrs.remove(otoroshi.plugins.Keys.UserKey)
          }
          fetched.map(user => Map(config.userMetadataKey -> user.profile.stringify)).getOrElse(Map.empty)
        }
        .recover { case _: Throwable => Map.empty[String, String] }
    }
  }

  // the issuer and the audience the config expects, checked on the payload of a verified token: an authorization
  // server signs the tokens of all its clients, all its resource servers and sometimes all its tenants with the same
  // keys. The reason of a refusal goes to the analytics of the call, the description to the caller in strict mode
  private def claimsRejection(ctx: NgAccessContext, config: NgOidcApikeyExtractorConfig, payload: JsValue): Option[String] = {
    def by: String = config.planId.map(id => s"plan $id").getOrElse(s"route ${ctx.route.id}")
    val rejection  = config.issuer match {
      case Some(expected) if !payload.select("iss").asOpt[String].contains(expected) =>
        val iss = payload.select("iss").asOpt[JsValue].map(_.stringify).getOrElse("none")
        Some(("token issuer not accepted", s"token issuer $iss not accepted by $by"))
      case _ if config.audiences.nonEmpty                                           =>
        // a string or an array of strings (rfc 7519, 4.1.3)
        val aud = payload.select("aud").asOpt[JsValue] match {
          case Some(JsString(value)) => Seq(value)
          case Some(JsArray(values)) => values.collect { case JsString(value) => value }.toSeq
          case _                     => Seq.empty
        }
        if (aud.exists(config.audiences.contains)) None
        else Some(("token audience not accepted", s"token audience [${aud.mkString(", ")}] not accepted by $by"))
      case _                                                                         => None
    }
    rejection.map { case (description, reason) =>
      // not under apikey_rejection_reason, which the consumer enforcer overwrites
      ctx.attrs.update(otoroshi.plugins.Keys.ExtraAnalyticsDataKey) {
        case Some(obj) => obj ++ Json.obj("token_rejection_reason" -> reason)
        case None      => Json.obj("token_rejection_reason" -> reason)
      }
      description
    }
  }

  override def access(ctx: NgAccessContext)(using env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx.cachedConfig(internalName)(NgOidcApikeyExtractorConfig.format).getOrElse(NgOidcApikeyExtractorConfig())
    config.ref match {
      case None               => NgAccess.NgDenied(Results.BadRequest(Json.obj("error" -> "no auth. module setup"))).vfuture
      case Some(authModuleId) =>
        env.proxyState.authModule(authModuleId) match {
          case None                                                              =>
            NgAccess.NgDenied(Results.BadRequest(Json.obj("error" -> "auth. module not found"))).vfuture
          case Some(oidcModule: OAuth2ModuleConfig) if oidcModule.jwtVerifier.isDefined => {
            // a verified token whose issuer or audience the config does not accept, with the description of the refusal
            object RejectedClaims {
              def unapply(token: DecodedJWT): Option[String] =
                if (config.issuer.isEmpty && config.audiences.isEmpty) None
                else {
                  val payload = Try(Json.parse(new String(OtoroshiClaim.decoder.decode(token.getPayload))))
                  claimsRejection(ctx, config, payload.getOrElse(Json.obj()))
                }
            }
            val verifier = LocalJwtVerifier().copy(enabled = true, algoSettings = oidcModule.jwtVerifier.get)
            val sources  = config.source
              .map(s => Seq(s))
              .getOrElse(Seq(InHeader("Authorization", "Bearer "), InQueryParam("access_token")))
            sources.iterator.map(s => s.token(ctx.request).map(t => (s, t))).collectFirst { case Some(tuple) =>
              tuple
            } match {
              case None                        => failure(config.strict, "You have to provide a valid apikey").vfuture
              case Some((source, currentToken)) =>
                verifier
                  .copy(source = source)
                  .verifyGen[NgAccess](
                    ctx.request,
                    ctx.route.legacy,
                    ctx.apikey,
                    ctx.user,
                    ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
                    ctx.attrs
                  ) { jwtInjection =>
                    jwtInjection.decodedToken match {
                      case None        => failure(config.strict, "You have to provide a valid apikey").rightf
                      // before the client id is read, so that nothing is minted for a refused token
                      case Some(RejectedClaims(description)) => failure(config.strict, description).rightf
                      case Some(token) => {
                        val jsonToken = new String(OtoroshiClaim.decoder.decode(token.getPayload))
                        JsonPathUtils.getAt[String](jsonToken, config.clientIdPath) match {
                          case None           => failure(config.strict, "No client id in the token").rightf
                          case Some(clientId) =>
                            userMetadata(ctx, oidcModule, config, currentToken).flatMap { extraMetadata =>
                              // prefixed for the very same reason as the jwt extractor: the client id
                              // comes from a token, and every plan needs its own namespace
                              ApikeyFromPlan
                                .resolveOrCreate(s"${config.clientIdPrefix}${clientId}", config, ctx, extraMetadata)
                                .map(result => outcome(result, config.strict).right)
                            }
                        }
                      }
                    }
                  }
                  .map {
                    case Left(result) => failure(config.strict, "You have to provide a valid apikey")
                    case Right(r)     => r
                  }
            }
          }
          case _                                                                 =>
            NgAccess
              .NgDenied(
                Results.BadRequest(
                  Json.obj("error" -> "auth. module not an oidc module or does not have jwt verification settings")
                )
              )
              .vfuture
        }
    }
  }
}

// the last access validator every published plan puts on a route, and the only place where a call
// identified by a plan is turned into a consumed call. the extractors above, and the ApikeyCalls an
// apikey plan relies on, only resolve an identity: counting there would count calls that the rest of
// the chain still rejects, quotas included.
class NgApiConsumerEnforcer extends NgAccessValidator {

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def isAccessAsync: Boolean                      = true
  // counting twice is exactly what this plugin exists to avoid, so a single instance per route
  override def multiInstance: Boolean                      = false
  override def core: Boolean                               = true
  override def name: String                                = "Api consumer enforcer"
  override def description: Option[String]                 =
    "This plugin expects that a consumer made the call, then enforces the restrictions, the rotation and the quotas of its apikey. It is the only place where a call identified by a plan is counted".some
  override def defaultConfigObject: Option[NgPluginConfig] = None
  override def noJsForm: Boolean                           = true

  override def access(ctx: NgAccessContext)(using env: Env, ec: ExecutionContext): Future[NgAccess] = {

    def error(status: Results.Status, message: String, code: String, reason: String): Future[NgAccess] = {
      val key        = "apikey_rejection_reason"
      val finalAttrs = ctx.attrs.update(otoroshi.plugins.Keys.ExtraAnalyticsDataKey) {
        case Some(obj @ JsObject(_)) => obj ++ Json.obj(key -> reason)
        case None                    => Json.obj(key -> reason)
      }
      Errors
        .craftResponseResult(
          message,
          status,
          ctx.request,
          None,
          code.some,
          attrs = finalAttrs,
          maybeRoute = ctx.route.some
        )
        .map(NgAccess.NgDenied.apply)
    }

    // an apikey wins over a user session: it is the one carrying the plan quotas, so it has to be
    // enforced even when the caller also went through an auth. module
    ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyKey) match {
      case Some(apikey) =>
        ApikeyFromPlan.enforce(apikey, ctx).map {
          case result if result.header.status == 200 => NgAccess.NgAllowed
          case result                                => NgAccess.NgDenied(result)
        }
      case None         =>
        ctx.attrs.get(otoroshi.plugins.Keys.UserKey) match {
          case Some(_) => NgAccess.NgAllowed.vfuture
          case None    =>
            // a plan that requires a subscription refused the caller: the reason says who and on which plan
            val reason = ctx.attrs
              .get(otoroshi.plugins.Keys.ExtraAnalyticsDataKey)
              .flatMap(_.select("not_subscribed").asOpt[JsObject])
              .map { refused =>
                s"client id '${refused.select("client_id").asString}' is not subscribed to plan '${refused.select("plan").asString}'"
              }
              .getOrElse("no consumer identified on the call")
            error(
              Results.Unauthorized,
              "You're not authorized here !",
              "errors.auth.unauthorized",
              reason
            )
        }
    }
  }
}
