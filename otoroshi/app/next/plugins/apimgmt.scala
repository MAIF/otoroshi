package otoroshi.next.plugins

import otoroshi.env.Env
import otoroshi.auth.OAuth2ModuleConfig
import otoroshi.models.{ApiIdentifier, ApiKey, InHeader, InQueryParam, JwtTokenLocation, LocalJwtVerifier, RouteIdentifier}
import otoroshi.next.models.{ApiRef, ApikeyAccessModeConfiguration}
import otoroshi.next.plugins.api.*
import otoroshi.security.{IdGenerator, OtoroshiClaim}
import org.apache.commons.codec.binary.Base64
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
  private def apikeyFor(clientId: String, config: ApikeyFromPlanConfig, ctx: NgAccessContext)(using
      env: Env
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
    ApiKey(
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
      metadata = template.metadata ++ Map("created_by" -> "apikey-extractor")
    )
  }

  // resolves the apikey behind a client id. an existing one always wins, so that a revoked or
  // disabled apikey cannot be resurrected by minting a fresh one over it. otherwise the apikey is
  // built from the plan settings and stays in memory: it is never saved, not even to the cluster,
  // so a public api does not fill the datastore with one entry per caller. quota counters are keyed
  // by client id, so they still add up across calls without the entity being persisted.
  def resolveOrCreate(clientId: String, config: ApikeyFromPlanConfig, ctx: NgAccessContext)(using
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
        case None if !config.createIfMissing => None
        case None                           => apikeyFor(clientId, config, ctx).some
      }
      .map {
        case Some(apikey) if apikey.isActive() => {
          ctx.attrs.put(otoroshi.plugins.Keys.ApiKeyKey -> apikey)
          Results.Ok(Json.obj())
        }
        case _                                 => Results.Unauthorized(Json.obj("error" -> "unknown_apikey"))
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
}

case class NgJwtApikeyExtractorConfig(
    verifier: String,
    clientIdPath: String = "client_id",
    strict: Boolean = true,
    createIfMissing: Boolean = true,
    apiId: Option[String] = None,
    planId: Option[String] = None,
    throttlingStrategy: Option[ThrottlingStrategyConfig] = None,
    apikey: ApikeyAccessModeConfiguration = ApikeyAccessModeConfiguration()
) extends NgPluginConfig
    with ApikeyFromPlanConfig {
  override def json: JsValue = Json.obj(
    "verifier"       -> verifier,
    "client_id_path" -> clientIdPath,
    "strict"         -> strict
  ) ++ ApikeyFromPlan.json(this)
}

object NgJwtApikeyExtractorConfig {
  val format = new Format[NgJwtApikeyExtractorConfig] {
    override def writes(o: NgJwtApikeyExtractorConfig): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgJwtApikeyExtractorConfig] = Try {
      NgJwtApikeyExtractorConfig(
        verifier = json.select("verifier").as[String],
        clientIdPath = json.select("client_id_path").asOpt[String].getOrElse("client_id"),
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
    "This plugin extracts an apikey from a JWT token claim holding its client id. The apikey is created from the plan settings when it does not exist yet, then stored for classic apikey usage".some
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
                  case Some(clientId) => ApikeyFromPlan.resolveOrCreate(clientId, config, ctx)
                }
              }
              case other       => throw new IllegalStateException(s"unreachable case: $other")
            }
          }
          .recover { case _: Throwable => Results.Unauthorized(Json.obj()) }
          .map { result =>
            result.header.status match {
              case 200 => NgAccess.NgAllowed
              case _   => failure(config.strict, "You have to provide a valid apikey")
            }
          }
      }
      case other          => throw new IllegalStateException(s"unreachable case: $other")
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
    "This plugin builds a consumer identity from an expression, the caller ip address by default, and turns it into an apikey. It gives a public access the quotas and throttling of its plan without asking the caller for any credential".some
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
      ApikeyFromPlan.resolveOrCreate(clientId, config, ctx).map { result =>
        result.header.status match {
          case 200 => NgAccess.NgAllowed
          case _   => failure(config.strict, "You have to provide a valid apikey")
        }
      }
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
    "This plugin validates the client certificate against subject and issuer DN patterns, then turns it into an apikey. The apikey is built from the plan settings when it does not exist yet".some
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
              ApikeyFromPlan.resolveOrCreate(s"${config.clientIdPrefix}${clientId}", config, ctx).map { result =>
                result.header.status match {
                  case 200 => NgAccess.NgAllowed
                  case _   => failure(config.strict, "You have to provide a valid apikey")
                }
              }
          }
        }
      }
    }
  }
}

case class NgOidcApikeyExtractorConfig(
    ref: Option[String] = None,
    clientIdPath: String = "client_id",
    source: Option[JwtTokenLocation] = None,
    strict: Boolean = true,
    createIfMissing: Boolean = true,
    apiId: Option[String] = None,
    planId: Option[String] = None,
    throttlingStrategy: Option[ThrottlingStrategyConfig] = None,
    apikey: ApikeyAccessModeConfiguration = ApikeyAccessModeConfiguration()
) extends NgPluginConfig
    with ApikeyFromPlanConfig {
  override def json: JsValue = Json.obj(
    "ref"            -> ref.map(_.json).getOrElse(JsNull).asValue,
    "client_id_path" -> clientIdPath,
    "source"         -> source.map(_.asJson).getOrElse(JsNull).asValue,
    "strict"         -> strict
  ) ++ ApikeyFromPlan.json(this)
}

object NgOidcApikeyExtractorConfig {
  val configFlow                     = Seq("ref", "client_id_path", "strict", "create_if_missing", "source")
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
        source = json.select("source").asOpt[JsObject].flatMap(o => JwtTokenLocation.fromJson(o).toOption),
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
    "This plugin verifies the request jwt token against the OIDC settings of an auth. module, then extracts an apikey from a claim holding its client id. The apikey is built from the plan settings when it does not exist yet".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgOidcApikeyExtractorConfig().some
  override def isAccessAsync: Boolean                      = true

  override def noJsForm: Boolean              = true
  override def configFlow: Seq[String]        = NgOidcApikeyExtractorConfig.configFlow
  override def configSchema: Option[JsObject] = NgOidcApikeyExtractorConfig.configSchema

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
              case None                  => failure(config.strict, "You have to provide a valid apikey").vfuture
              case Some((source, _)) =>
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
                            ApikeyFromPlan.resolveOrCreate(clientId, config, ctx).map { result =>
                              result.header.status match {
                                case 200 => NgAccess.NgAllowed.right
                                case _   => failure(config.strict, "You have to provide a valid apikey").right
                              }
                            }
                        }
                      }
                      case other       => throw new IllegalStateException(s"unreachable case: $other")
                    }
                  }
                  .map {
                    case Left(result) => failure(config.strict, "You have to provide a valid apikey")
                    case Right(r)     => r
                    case other        => throw new IllegalStateException(s"unreachable case: $other")
                  }
              case other                 => throw new IllegalStateException(s"unreachable case: $other")
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
