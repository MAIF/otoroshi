package otoroshi.next.plugins

import otoroshi.env.Env
import otoroshi.auth.OAuth2ModuleConfig
import otoroshi.models.{ApiIdentifier, ApiKey, InHeader, InQueryParam, JwtTokenLocation, LocalJwtVerifier, RouteIdentifier}
import otoroshi.next.models.{ApiRef, ApikeyAccessModeConfiguration}
import otoroshi.next.plugins.api.*
import otoroshi.security.{IdGenerator, OtoroshiClaim}
import org.apache.commons.codec.binary.Base64
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
}

object ApikeyFromPlan {

  def json(config: ApikeyFromPlanConfig): JsObject = Json.obj(
    "create_if_missing"   -> config.createIfMissing,
    "api_id"              -> config.apiId,
    "plan_id"             -> config.planId,
    "throttling_strategy" -> config.throttlingStrategy.map(_.json),
    "apikey"              -> config.apikey.json
  )

  def createIfMissing(json: JsValue): Boolean                            =
    json.select("create_if_missing").asOpt[Boolean].getOrElse(true)
  def apiId(json: JsValue): Option[String]                               = json.select("api_id").asOpt[String]
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
      metadata = template.metadata ++ Map("created_by" -> "apikey-extractor") ++ extraMetadata
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
    // an api can publish several plans, and every published plan puts its own extractor on every
    // route. they all write to the same attribute, so the first plan to identify the caller has to
    // win: without this an extractor that always succeeds, the keyless one typically, would
    // overwrite the identity a credential just established.
    if (ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyKey).isDefined) {
      return Results.Ok(Json.obj()).vfuture
    }
    //env.datastores.apiKeyDataStore
    //  .findById(clientId)
    //  .map {
    env.proxyState.apikey(clientId).vfuture.map {
        case Some(apikey)                   => apikey.some
        case None                           => apikeyFor(clientId, config, ctx, extraMetadata).some
      }
      .flatMap {
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
    apikey: ApikeyAccessModeConfiguration = ApikeyAccessModeConfiguration()
) extends NgPluginConfig
    with ApikeyFromPlanConfig {
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
        apikey = ApikeyFromPlan.apikeyTemplate(json)
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
    apikey: ApikeyAccessModeConfiguration = ApikeyAccessModeConfiguration()
) extends NgPluginConfig
    with ApikeyFromPlanConfig {
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
        apikey = ApikeyFromPlan.apikeyTemplate(json)
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
        Base64.encodeBase64String((subject.stringify + "-" + serialNumber).getBytes).some
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
    apikey: ApikeyAccessModeConfiguration = ApikeyAccessModeConfiguration()
) extends NgPluginConfig
    with ApikeyFromPlanConfig {
  override def json: JsValue = Json.obj(
    "ref"                -> ref.map(_.json).getOrElse(JsNull).asValue,
    "client_id_path"     -> clientIdPath,
    "client_id_prefix"   -> clientIdPrefix,
    "source"             -> source.map(_.asJson).getOrElse(JsNull).asValue,
    "fetch_user"         -> fetchUser,
    "user_metadata_key"  -> userMetadataKey,
    "strict"             -> strict
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
        apikey = ApikeyFromPlan.apikeyTemplate(json)
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
    "This plugin verifies the request jwt token against the OIDC settings of an auth. module, then extracts an apikey from a claim holding its client id. The apikey is built from the plan settings when it does not exist yet. It only identifies the consumer: pair it with the 'Api consumer enforcer' plugin to enforce its quotas, like a published plan does".some
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

  override def access(ctx: NgAccessContext)(using env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx.cachedConfig(internalName)(NgOidcApikeyExtractorConfig.format).getOrElse(NgOidcApikeyExtractorConfig())
    config.ref match {
      case None               => NgAccess.NgDenied(Results.BadRequest(Json.obj("error" -> "no auth. module setup"))).vfuture
      case Some(authModuleId) =>
        env.proxyState.authModule(authModuleId) match {
          case None                                                              =>
            NgAccess.NgDenied(Results.BadRequest(Json.obj("error" -> "auth. module not found"))).vfuture
          case Some(oidcModule: OAuth2ModuleConfig) if oidcModule.jwtVerifier.isDefined => {
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
            error(
              Results.Unauthorized,
              "You're not authorized here !",
              "errors.auth.unauthorized",
              "no consumer identified on the call"
            )
        }
    }
  }
}
