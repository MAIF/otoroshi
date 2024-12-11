package otoroshi.next.plugins

import akka.Done
import akka.stream.Materializer
import com.auth0.jwt.JWT
import org.joda.time.DateTime
import otoroshi.controllers.HealthController
import otoroshi.el.GlobalExpressionLanguage
import otoroshi.env.Env
import otoroshi.gateway.{Errors, StateRespInvalid}
import otoroshi.models.ApiKey.toJson
import otoroshi.models.{AlgoSettings, ApiKey, DataExporterConfigFiltering, HSAlgoSettings, SecComInfoTokenVersion, SecComVersion}
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.security.{IdGenerator, OtoroshiClaim}
import otoroshi.utils.http.Implicits._
import otoroshi.utils.infotoken.{AddFieldsSettings, InfoTokenHelper}
import otoroshi.utils.jwk.JWKSHelper
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.libs.typedmap.TypedKey
import play.api.mvc.Results._
import play.api.mvc._

import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class NgOtoroshiChallengeConfig(
    secComVersion: SecComVersion,
    secComTtl: FiniteDuration,
    requestHeaderName: Option[String],
    responseHeaderName: Option[String],
    algoOtoToBackend: AlgoSettings,
    algoBackendToOto: AlgoSettings,
    stateRespLeeway: Int
) extends NgPluginConfig {
  def json: JsObject = NgOtoroshiChallengeConfig.format.writes(this).asObject
}

object NgOtoroshiChallengeConfig {
  def apply(raw: JsValue): NgOtoroshiChallengeConfig = format.reads(raw).get
  val format                                         = new Format[NgOtoroshiChallengeConfig] {
    override def reads(raw: JsValue): JsResult[NgOtoroshiChallengeConfig] = Try {
      lazy val secComVersion: SecComVersion = {
        raw.select("version").asOpt[Int] match {
          case None    => SecComVersion(raw.select("version").asOpt[String].getOrElse("V2")).getOrElse(SecComVersion.V2)
          case Some(v) => SecComVersion(v).getOrElse(SecComVersion.V2)
        }
      }
      lazy val secComTtl: FiniteDuration          = raw.select("ttl").asOpt[Long].map(_.seconds).getOrElse(30.seconds)
      lazy val requestHeaderName: Option[String]  =
        raw.select("request_header_name").asOpt[String].filterNot(_.trim.isEmpty)
      lazy val responseHeaderName: Option[String] =
        raw.select("response_header_name").asOpt[String].filterNot(_.trim.isEmpty)
      lazy val algoOtoToBackend: AlgoSettings     = AlgoSettings
        .fromJson(raw.select("algo_to_backend").asOpt[JsObject].getOrElse(Json.obj()))
        .getOrElse(HSAlgoSettings(512, "secret", false))
      lazy val algoBackendToOto: AlgoSettings     = AlgoSettings
        .fromJson(raw.select("algo_from_backend").asOpt[JsObject].getOrElse(Json.obj()))
        .getOrElse(HSAlgoSettings(512, "secret", false))
      lazy val stateRespLeeway: Int               = raw.select("state_resp_leeway").asOpt[Int].getOrElse(10)
      NgOtoroshiChallengeConfig(
        secComVersion = secComVersion,
        secComTtl = secComTtl,
        requestHeaderName = requestHeaderName,
        responseHeaderName = responseHeaderName,
        algoOtoToBackend = algoOtoToBackend,
        algoBackendToOto = algoBackendToOto,
        stateRespLeeway = stateRespLeeway
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage())
      case Success(value) => JsSuccess(value)
    }
    override def writes(o: NgOtoroshiChallengeConfig): JsValue            = Json.obj(
      "version"              -> o.secComVersion.json,
      "ttl"                  -> o.secComTtl.toSeconds,
      "request_header_name"  -> o.requestHeaderName,
      "response_header_name" -> o.responseHeaderName,
      "algo_to_backend"      -> o.algoOtoToBackend.asJson,
      "algo_from_backend"    -> o.algoBackendToOto.asJson,
      "state_resp_leeway"    -> o.stateRespLeeway
    )
  }
}

case class NgOtoroshiInfoConfig(
    secComVersion: SecComInfoTokenVersion,
    secComTtl: FiniteDuration,
    headerName: Option[String],
    addFields: Option[AddFieldsSettings],
    projection: JsObject = Json.obj(),
    algo: AlgoSettings
) extends NgPluginConfig {
  def json: JsObject = NgOtoroshiInfoConfig.format.writes(this).asObject
}

object NgOtoroshiInfoConfig {
  def apply(raw: JsValue): NgOtoroshiInfoConfig = NgOtoroshiInfoConfig.format.reads(raw).get
  val format                                    = new Format[NgOtoroshiInfoConfig] {
    override def reads(raw: JsValue): JsResult[NgOtoroshiInfoConfig] = Try {
      lazy val secComVersion: SecComInfoTokenVersion = SecComInfoTokenVersion(
        raw.select("version").asOpt[String].getOrElse("Latest")
      ).getOrElse(SecComInfoTokenVersion.Latest)
      lazy val secComTtl: FiniteDuration             = raw.select("ttl").asOpt[Long].map(_.seconds).getOrElse(30.seconds)
      lazy val headerName: Option[String]            = raw.select("header_name").asOpt[String].filterNot(_.trim.isEmpty)

      lazy val projection = (raw \ "projection").asOpt[JsObject].getOrElse(Json.obj())

      lazy val addFields: Option[AddFieldsSettings] =
        raw.select("add_fields").asOpt[Map[String, String]].map(m => AddFieldsSettings(m))
      lazy val algo: AlgoSettings                   = AlgoSettings
        .fromJson(raw.select("algo").asOpt[JsObject].getOrElse(Json.obj()))
        .getOrElse(HSAlgoSettings(512, "secret", false))
      NgOtoroshiInfoConfig(
        secComVersion = secComVersion,
        secComTtl = secComTtl,
        headerName = headerName,
        addFields = addFields,
        algo = algo,
        projection = projection
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage())
      case Success(value) => JsSuccess(value)
    }
    override def writes(o: NgOtoroshiInfoConfig): JsValue            = Json.obj(
      "version"     -> o.secComVersion.json,
      "ttl"         -> o.secComTtl.toSeconds,
      "header_name" -> o.headerName,
      "add_fields"  -> o.addFields.map(v => JsObject(v.fields.mapValues(JsString.apply))).getOrElse(JsNull).as[JsValue],
      "projection"  -> o.projection,
      "algo"        -> o.algo.asJson
    )
  }
}

object NgOtoroshiChallengeKeys {
  val ClaimKey      = TypedKey[OtoroshiClaim]("otoroshi.next.core.plugins.OtoroshiChallenge.OtoroshiClaim")
  val StateTokenKey = TypedKey[String]("otoroshi.next.core.plugins.OtoroshiChallenge.StateToken")
  val StateValueKey = TypedKey[String]("otoroshi.next.core.plugins.OtoroshiChallenge.StateValue")
  val ConfigKey     = TypedKey[NgOtoroshiChallengeConfig]("otoroshi.next.core.plugins.OtoroshiChallenge.Config")
}

class OtoroshiChallenge extends NgRequestTransformer {

  private val logger = Logger("otoroshi-next-plugins-otoroshi-challenge")

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest, NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Security, NgPluginCategory.Classic)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = true
  override def transformsResponse: Boolean                 = true
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = true
  override def transformsError: Boolean                    = false
  override def name: String                                = "Otoroshi challenge token"
  override def description: Option[String]                 =
    "This plugin adds a jwt challenge token to the request to a backend and expects a response with a matching token".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgOtoroshiChallengeConfig(Json.obj()).some

  override def transformRequestSync(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    val config             = ctx
      .cachedConfigFn(internalName)(json => NgOtoroshiChallengeConfig(json).some)
      .getOrElse(NgOtoroshiChallengeConfig(ctx.config))
    val jti                = IdGenerator.uuid
    val stateValue         = IdGenerator.extendedToken(128)
    val stateToken: String = config.secComVersion match {
      case SecComVersion.V1 => stateValue
      case SecComVersion.V2 =>
        OtoroshiClaim(
          iss = env.Headers.OtoroshiIssuer,
          sub = env.Headers.OtoroshiIssuer,
          aud = ctx.route.name,
          exp = DateTime
            .now()
            .plus(config.secComTtl.toMillis)
            .toDate
            .getTime,
          iat = DateTime.now().toDate.getTime,
          jti = jti
        )
          .withClaim("state", stateValue)
          .serialize(config.algoOtoToBackend)
    }
    ctx.attrs.put(NgOtoroshiChallengeKeys.StateValueKey -> stateValue)
    ctx.attrs.put(NgOtoroshiChallengeKeys.StateTokenKey -> stateToken)
    ctx.attrs.put(NgOtoroshiChallengeKeys.ConfigKey     -> config)
    val stateRequestHeaderName = config.requestHeaderName.getOrElse(env.Headers.OtoroshiState)
    ctx.otoroshiRequest.copy(headers = ctx.otoroshiRequest.headers ++ Map(stateRequestHeaderName -> stateToken)).right
  }

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val config                                   = ctx.attrs.get(NgOtoroshiChallengeKeys.ConfigKey).get
    val stateValue                               = ctx.attrs.get(NgOtoroshiChallengeKeys.StateValueKey).get
    val stateRespHeaderName                      = config.responseHeaderName.getOrElse(env.Headers.OtoroshiStateResp)
    val isUp                                     = true
    val stateResp                                = ctx.rawResponse.headers.getIgnoreCase(stateRespHeaderName)
    val at                                       = System.currentTimeMillis()
    val respEith: Either[StateRespInvalid, Done] = stateResp match {
      case None       =>
        StateRespInvalid(
          at,
          "no state in response header",
          -1,
          -1,
          -1,
          stateValue,
          stateResp,
          None,
          ctx.route.serviceDescriptor,
          ctx.request,
          env
        ).left
      case Some(resp) => {
        config.secComVersion match {
          case SecComVersion.V1 if stateValue == resp => Done.right
          case SecComVersion.V1 if stateValue != resp =>
            StateRespInvalid(
              at,
              s"V1 - state from response does not match request one ($stateValue != $resp)",
              -1,
              -1,
              -1,
              stateValue,
              stateResp,
              None,
              ctx.route.serviceDescriptor,
              ctx.request,
              env
            ).left
          case SecComVersion.V2                       => {
            config.algoBackendToOto.asAlgorithm(otoroshi.models.OutputMode)(env) match {
              case None       =>
                StateRespInvalid(
                  at,
                  s"V2 - bad challenge algorithm",
                  -1,
                  -1,
                  -1,
                  stateValue,
                  stateResp,
                  None,
                  ctx.route.serviceDescriptor,
                  ctx.request,
                  env
                ).left
              case Some(algo) => {
                Try {
                  val jwt                            = JWT
                    .require(algo)
                    .withAudience(env.Headers.OtoroshiIssuer)
                    .withClaim("state-resp", stateValue)
                    .acceptLeeway(config.stateRespLeeway)
                    .build()
                    .verify(resp)
                  val extractedState: Option[String] =
                    Option(jwt.getClaim("state-resp")).filterNot(_.isNull).filterNot(_.isMissing).map(_.asString())
                  val exp: Option[Long]              =
                    Option(jwt.getClaim("exp")).filterNot(_.isNull).filterNot(_.isMissing).map(_.asLong())
                  val iat: Option[Long]              =
                    Option(jwt.getClaim("iat")).filterNot(_.isNull).filterNot(_.isMissing).map(_.asLong())
                  val nbf: Option[Long]              =
                    Option(jwt.getClaim("nbf")).filterNot(_.isNull).filterNot(_.isMissing).map(_.asLong())
                  if (exp.isEmpty || iat.isEmpty) {
                    StateRespInvalid(
                      at,
                      s"V2 - exp / iat is empty",
                      exp.getOrElse(-1L),
                      iat.getOrElse(-1L),
                      nbf.getOrElse(-1L),
                      stateValue,
                      stateResp,
                      extractedState,
                      ctx.route.serviceDescriptor,
                      ctx.request,
                      env
                    ).left
                  } else {
                    val expValue = exp.get
                    val iatValue = iat.get
                    val nbfValue = nbf.getOrElse(-1L)
                    if ((exp.get - iat.get) <= config.secComTtl.toSeconds) { // seconds
                      Done.right
                    } else {
                      StateRespInvalid(
                        at,
                        s"V2 - response token ttl too big - ${expValue - iatValue} seconds ((${expValue} - ${iatValue}) > ${config.secComTtl.toSeconds})",
                        expValue,
                        iatValue,
                        nbfValue,
                        stateValue,
                        stateResp,
                        extractedState,
                        ctx.route.serviceDescriptor,
                        ctx.request,
                        env
                      ).left
                    }
                  }
                } match {
                  case Success(v) => v
                  case Failure(e) => {
                    StateRespInvalid(
                      at,
                      s"V2 - error while decoding token - ${e.getMessage}",
                      -1,
                      -1,
                      -1,
                      stateValue,
                      stateResp,
                      None,
                      ctx.route.serviceDescriptor,
                      ctx.request,
                      env
                    ).left
                  }
                }
              }
            }
          }
        }
      }
    }
    respEith match {
      case Left(stateRespInvalid) => {
        ctx.response.foreach(_.ignore())
        if (
          ctx.otoroshiResponse.status == 404 && ctx.otoroshiResponse.headers
            .get("X-CleverCloudUpgrade")
            .orElse(ctx.otoroshiResponse.headers.get("x-clevercloudupgrade"))
            .contains("true")
        ) {
          Errors
            .craftResponseResult(
              "No service found for the specified target host, the service descriptor should be verified !",
              Results.NotFound,
              ctx.request,
              None,
              "errors.no.service.found".some,
              // duration = System.currentTimeMillis - start,
              // overhead = (System
              //   .currentTimeMillis() - secondStart) + firstOverhead,
              // cbDuration = cbDuration,
              // callAttempts = callAttempts,
              attrs = ctx.attrs,
              maybeRoute = ctx.route.some
            )
            .map(Left.apply)
        } else if (isUp) {
          logger.error(
            stateRespInvalid.errorMessage(
              ctx.response.map(_.status).getOrElse(ctx.rawResponse.status),
              ctx.response.map(_.headers.mapValues(_.last)).getOrElse(ctx.rawResponse.headers)
            )
          )
          val extraInfos    = ctx.attrs
            .get(otoroshi.plugins.Keys.GatewayEventExtraInfosKey)
            .map(_.as[JsObject])
            .getOrElse(Json.obj())
          val newExtraInfos =
            extraInfos ++ Json.obj(
              "stateRespInvalid" -> stateRespInvalid.exchangePayload(
                ctx.response.map(_.status).getOrElse(ctx.rawResponse.status),
                ctx.response.map(_.headers.mapValues(_.last)).getOrElse(ctx.rawResponse.headers)
              )
            )
          ctx.attrs.put(otoroshi.plugins.Keys.GatewayEventExtraInfosKey -> newExtraInfos)
          Errors
            .craftResponseResult(
              "Backend server does not seems to be secured. Cancelling request !",
              Results.BadGateway,
              ctx.request,
              None,
              Some("errors.service.not.secured"),
              attrs = ctx.attrs,
              maybeRoute = ctx.route.some
            )
            .map(Left.apply)
        } else {
          Errors
            .craftResponseResult(
              "The service seems to be down :( come back later",
              Forbidden,
              ctx.request,
              None,
              Some("errors.service.down"),
              attrs = ctx.attrs,
              maybeRoute = ctx.route.some
            )
            .map(Left.apply)
        }
      }
      case Right(_)               => ctx.otoroshiResponse.right.vfuture
    }
  }
}

class OtoroshiInfos extends NgRequestTransformer {

  private val logger = Logger("otoroshi-next-plugins-otoroshi-infos")

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Security, NgPluginCategory.Classic)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = true
  override def transformsResponse: Boolean                 = false
  override def transformsError: Boolean                    = false
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = true
  override def name: String                                = "Otoroshi info. token"
  override def description: Option[String]                 =
    "This plugin adds a jwt token with informations about the caller to the backend".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgOtoroshiInfoConfig(Json.obj()).some

  override def transformRequestSync(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    val config = ctx
      .cachedConfigFn(internalName)(json => NgOtoroshiInfoConfig(json).some)
      .getOrElse(NgOtoroshiInfoConfig(ctx.config))

    var claim = InfoTokenHelper.generateInfoToken(
      ctx.route.name,
      config.secComVersion,
      config.secComTtl,
      ctx.apikey,
      ctx.user,
      ctx.request.some,
      None,
      None,
      config.addFields.map(af =>
        AddFieldsSettings(
          af.fields.mapValues(str =>
            GlobalExpressionLanguage.apply(
              value = str,
              req = ctx.request.some,
              service = ctx.route.legacy.some,
              route = ctx.route.some,
              apiKey = ctx.apikey,
              user = ctx.user,
              context = ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
              attrs = ctx.attrs,
              env = env
            )
          )
        )
      )
    )

    try {
      if (config.projection.value.nonEmpty) {
        claim = claim.copy(metadata = otoroshi.utils.Projection.project(claim.metadata, config.projection, identity))
      }
    } catch {
      case t: Throwable =>
        logger.error("error while projecting apikey", t)
    }

    if (logger.isTraceEnabled) logger.trace(s"Claim is : $claim")
    ctx.attrs.put(NgOtoroshiChallengeKeys.ClaimKey  -> claim)
    ctx.attrs.put(otoroshi.plugins.Keys.OtoTokenKey -> claim.payload)
    val serialized = claim.serialize(config.algo)
    val headerName = config.headerName.getOrElse(env.Headers.OtoroshiClaim)

    ctx.otoroshiRequest.copy(headers = ctx.otoroshiRequest.headers ++ Map(headerName -> serialized)).right
  }
}

case class PossibleCerts(certIds: Seq[String]) extends NgPluginConfig {
  override def json: JsValue = PossibleCerts.format.writes(this)
}

object PossibleCerts {
  val default = PossibleCerts(Seq.empty)
  val format = new Format[PossibleCerts] {
    override def writes(o: PossibleCerts): JsValue             = Json.obj(
      "cert_ids"        -> o.certIds
    )
    override def reads(json: JsValue): JsResult[PossibleCerts] = Try {
      PossibleCerts(
        certIds = json.select("cert_ids").asOpt[Seq[String]].getOrElse(Seq.empty),
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
  }
  val configFlow: Seq[String]        = Seq("cert_ids")
  val configSchema: Option[JsObject] = Some(
    Json.obj(
      "cert_ids"        -> Json.obj(
        "type" -> "select",
        "array" -> true,
        "label" -> s"Allowed certificates",
        "props" -> Json.obj(
          "optionsFrom"        -> s"/bo/api/proxy/api/certificates",
          "optionsTransformer" -> Json.obj(
            "label" -> "name",
            "value" -> "id"
          )
        )
      )
    )
  )
}

class OtoroshiOCSPResponderEndpoint extends NgBackendCall {

  override def steps: Seq[NgStep]                          = Seq(NgStep.CallBackend)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Custom("Otoroshi"))
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Otoroshi OCSP Responder endpoint"
  override def description: Option[String]                 = "This plugin provide an endpoint to act as the Otoroshi OCSP Responder".some
  override def defaultConfigObject: Option[NgPluginConfig] = PossibleCerts.default.some
  override def useDelegates: Boolean                       = false
  override def noJsForm: Boolean                           = true
  override def configFlow: Seq[String]                     = PossibleCerts.configFlow
  override def configSchema: Option[JsObject]              = PossibleCerts.configSchema

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(PossibleCerts.format).getOrElse(PossibleCerts.default)
    env.ocspResponder.respond(ctx.rawRequest, ctx.request.body, config.certIds).map { res =>
      Right(BackendCallResponse(NgPluginHttpResponse.fromResult(res), None))
    }
  }
}

class OtoroshiAIAEndpoint extends NgBackendCall {

  override def steps: Seq[NgStep]                          = Seq(NgStep.CallBackend)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Custom("Otoroshi"))
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Otoroshi AIA endpoint"
  override def description: Option[String]                 = "This plugin provide an endpoint to return Otoroshi Authority Information Access for your certificates".some
  override def defaultConfigObject: Option[NgPluginConfig] = None
  override def useDelegates: Boolean                       = false
  override def noJsForm: Boolean                           = true
  override def configFlow: Seq[String]                     = Seq.empty
  override def configSchema: Option[JsObject]              = None

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    ctx.attrs.get(otoroshi.next.plugins.Keys.MatchedRouteKey) match {
      case None => Left(NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "matched route not found")))).vfuture
      case Some(matchedRoute) => {
        matchedRoute.pathParams.get("id").orElse(matchedRoute.pathParams.get("cert_id")) match {
          case None => Right(BackendCallResponse(NgPluginHttpResponse.fromResult(BadRequest(Json.obj("error" -> "cert id not available"))), None)).vfuture
          case Some(id) => env.ocspResponder.aia(id, ctx.rawRequest).map { res =>
            Right(BackendCallResponse(NgPluginHttpResponse.fromResult(res), None))
          }
        }
      }
    }
  }
}

class OtoroshiJWKSEndpoint extends NgBackendCall {

  override def steps: Seq[NgStep]                          = Seq(NgStep.CallBackend)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Custom("Otoroshi"))
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Otoroshi JWKS endpoint"
  override def description: Option[String]                 = "This plugin provide an endpoint to return Otoroshi JWKS data".some
  override def defaultConfigObject: Option[NgPluginConfig] = PossibleCerts.default.some
  override def useDelegates: Boolean                       = false
  override def noJsForm: Boolean                           = true
  override def configFlow: Seq[String]                     = PossibleCerts.configFlow
  override def configSchema: Option[JsObject]              = PossibleCerts.configSchema

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(PossibleCerts.format).getOrElse(PossibleCerts.default)
    JWKSHelper.jwks(ctx.rawRequest, config.certIds).map {
      case Left(body)  => Results.NotFound(body)
      case Right(body) => Results.Ok(body)
    } map { res =>
      Right(BackendCallResponse(NgPluginHttpResponse.fromResult(res), None))
    }
  }
}

class OtoroshiHealthEndpoint extends NgBackendCall {

  override def steps: Seq[NgStep]                          = Seq(NgStep.CallBackend)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Custom("Otoroshi"))
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Otoroshi Health endpoint"
  override def description: Option[String]                 = "This plugin provide an endpoint to return Otoroshi Health informations data for the current node".some
  override def defaultConfigObject: Option[NgPluginConfig] = None
  override def useDelegates: Boolean                       = false
  override def noJsForm: Boolean                           = true
  override def configFlow: Seq[String]                     = Seq.empty
  override def configSchema: Option[JsObject]              = None

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    HealthController.fetchHealth().map {
      case Left(payload) => ServiceUnavailable(payload)
      case Right(payload) => Ok(payload)
    } map { res =>
      Right(BackendCallResponse(NgPluginHttpResponse.fromResult(res), None))
    }
  }
}

case class OtoroshiMetricsEndpointConfig(filter: Option[String]) extends NgPluginConfig {
  override def json: JsValue = OtoroshiMetricsEndpointConfig.format.writes(this)
}

object OtoroshiMetricsEndpointConfig {
  val default = OtoroshiMetricsEndpointConfig(None)
  val format = new Format[OtoroshiMetricsEndpointConfig] {
    override def writes(o: OtoroshiMetricsEndpointConfig): JsValue             = Json.obj(
      "filter"        -> o.filter.map(_.json).getOrElse(JsNull).asValue
    )
    override def reads(json: JsValue): JsResult[OtoroshiMetricsEndpointConfig] = Try {
      OtoroshiMetricsEndpointConfig(
        filter = json.select("filter").asOpt[String],
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
  }
  val configFlow: Seq[String]        = Seq("filter")
  val configSchema: Option[JsObject] = Some(
    Json.obj(
      "cert_ids"        -> Json.obj(
        "type" -> "string",
        "label" -> s"Filter metrics"
      )
    )
  )
}

class OtoroshiMetricsEndpoint extends NgBackendCall {

  override def steps: Seq[NgStep]                          = Seq(NgStep.CallBackend)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Custom("Otoroshi"))
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Otoroshi Metrics endpoint"
  override def description: Option[String]                 = "This plugin provide an endpoint to return Otoroshi metrics data for the current node".some
  override def defaultConfigObject: Option[NgPluginConfig] = OtoroshiMetricsEndpointConfig.default.some
  override def useDelegates: Boolean                       = false
  override def noJsForm: Boolean                           = true
  override def configFlow: Seq[String]                     = OtoroshiMetricsEndpointConfig.configFlow
  override def configSchema: Option[JsObject]              = OtoroshiMetricsEndpointConfig.configSchema

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(OtoroshiMetricsEndpointConfig.format).getOrElse(OtoroshiMetricsEndpointConfig.default)
    val format      = ctx.rawRequest.getQueryString("format")
    val filter      = ctx.rawRequest.getQueryString("filter").orElse(config.filter)
    val acceptsJson = ctx.rawRequest.accepts("application/json")
    val acceptsProm = ctx.rawRequest.accepts("application/prometheus")
    val res = HealthController.fetchMetrics(format, acceptsJson, acceptsProm, filter)
    Right(BackendCallResponse(NgPluginHttpResponse.fromResult(res), None)).vfuture
  }
}

