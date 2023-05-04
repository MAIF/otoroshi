package otoroshi.next.plugins

import akka.Done
import akka.stream.Materializer
import com.auth0.jwt.JWT
import org.joda.time.DateTime
import otoroshi.el.GlobalExpressionLanguage
import otoroshi.env.Env
import otoroshi.gateway.{Errors, StateRespInvalid}
import otoroshi.models.{AlgoSettings, HSAlgoSettings, SecComInfoTokenVersion, SecComVersion}
import otoroshi.next.plugins.api._
import otoroshi.security.{IdGenerator, OtoroshiClaim}
import otoroshi.utils.http.Implicits._
import otoroshi.utils.infotoken.{AddFieldsSettings, InfoTokenHelper}
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
      lazy val addFields: Option[AddFieldsSettings] = raw.select("add_fields").asOpt[Map[String, String]].map(m => AddFieldsSettings(m))
      lazy val algo: AlgoSettings                    = AlgoSettings
        .fromJson(raw.select("algo").asOpt[JsObject].getOrElse(Json.obj()))
        .getOrElse(HSAlgoSettings(512, "secret", false))
      NgOtoroshiInfoConfig(
        secComVersion = secComVersion,
        secComTtl = secComTtl,
        headerName = headerName,
        addFields = addFields,
        algo = algo
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

  override def multiInstance: Boolean                      = false
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

  override def multiInstance: Boolean                      = false
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
    val claim  = InfoTokenHelper.generateInfoToken(
      ctx.route.name,
      config.secComVersion,
      config.secComTtl,
      ctx.apikey,
      ctx.user,
      ctx.request.some,
      None,
      None,
      config.addFields.map(af => AddFieldsSettings(af.fields.mapValues(str => GlobalExpressionLanguage.apply(
        value = str,
        req = ctx.request.some,
        service = ctx.route.legacy.some,
        apiKey = ctx.apikey,
        user = ctx.user,
        context = ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
        attrs = ctx.attrs,
        env = env,
      ))))
    )
    if (logger.isTraceEnabled) logger.trace(s"Claim is : $claim")
    ctx.attrs.put(NgOtoroshiChallengeKeys.ClaimKey  -> claim)
    ctx.attrs.put(otoroshi.plugins.Keys.OtoTokenKey -> claim.payload)
    val serialized = claim.serialize(config.algo)
    val headerName = config.headerName.getOrElse(env.Headers.OtoroshiClaim)
    ctx.otoroshiRequest.copy(headers = ctx.otoroshiRequest.headers ++ Map(headerName -> serialized)).right
  }
}
