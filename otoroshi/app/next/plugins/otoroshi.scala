package otoroshi.next.plugins

import akka.Done
import akka.stream.Materializer
import com.auth0.jwt.JWT
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.gateway.{Errors, StateRespInvalid}
import otoroshi.models.{AlgoSettings, HSAlgoSettings, SecComInfoTokenVersion, SecComVersion}
import otoroshi.next.plugins.api._
import otoroshi.security.{IdGenerator, OtoroshiClaim}
import otoroshi.utils.http.Implicits._
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.libs.typedmap.TypedKey
import play.api.mvc.Results._
import play.api.mvc._

import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import otoroshi.models.PrivateAppsUser
import otoroshi.models.ApiKey
import otoroshi.utils.infotoken.InfoTokenHelper

case class OtoroshiChallengeConfig(raw: JsValue) {
  lazy val secComVersion: SecComVersion = SecComVersion(raw.select("version").asOpt[String].getOrElse("V2")).getOrElse(SecComVersion.V2)
  lazy val secComTtl: FiniteDuration = raw.select("ttl").asOpt[Long].map(_.seconds).getOrElse(30.seconds)
  lazy val requestHeaderName: Option[String] = raw.select("request_header_name").asOpt[String]
  lazy val responseHeaderName: Option[String] = raw.select("response_header_name").asOpt[String]
  lazy val algoOtoToBackend: AlgoSettings = AlgoSettings.fromJson(raw.select("algo_to_backend").asOpt[JsObject].getOrElse(Json.obj())).getOrElse(HSAlgoSettings(512, "secret", false))
  lazy val algoBackendToOto: AlgoSettings = AlgoSettings.fromJson(raw.select("algo_from_backend").asOpt[JsObject].getOrElse(Json.obj())).getOrElse(HSAlgoSettings(512, "secret", false))
  lazy val stateRespLeeway: Int =  raw.select("state_resp_leeway").asOpt[Int].getOrElse(10)
  def json: JsObject = Json.obj(
    "version" -> secComVersion.json,
    "ttl" -> secComTtl.toSeconds,
    "request_header_name" -> requestHeaderName,
    "response_header_name" -> responseHeaderName,
    "algo_to_backend" -> algoOtoToBackend.asJson,
    "algo_from_backend" -> algoBackendToOto.asJson,
    "state_resp_leeway" -> stateRespLeeway,
  )
}

case class OtoroshiInfoConfig(raw: JsValue) {
  lazy val secComVersion: SecComInfoTokenVersion = SecComInfoTokenVersion(raw.select("version").asOpt[String].getOrElse("Latest")).getOrElse(SecComInfoTokenVersion.Latest)
  lazy val secComTtl: FiniteDuration = raw.select("ttl").asOpt[Long].map(_.seconds).getOrElse(30.seconds)
  lazy val headerName: Option[String] = raw.select("header_name").asOpt[String]
  lazy val algo: AlgoSettings = AlgoSettings.fromJson(raw.select("algo").asOpt[JsObject].getOrElse(Json.obj())).getOrElse(HSAlgoSettings(512, "secret", false))
  def json: JsObject = Json.obj(
    "version" -> secComVersion.json,
    "ttl" -> secComTtl.toSeconds,
    "header_name" -> headerName,
    "algo" -> algo.asJson
  )
}

object OtoroshiChallengeKeys {
  val ClaimKey = TypedKey[OtoroshiClaim]("otoroshi.next.core.plugins.OtoroshiChallenge.OtoroshiClaim")
  val StateTokenKey = TypedKey[String]("otoroshi.next.core.plugins.OtoroshiChallenge.StateToken")
  val StateValueKey = TypedKey[String]("otoroshi.next.core.plugins.OtoroshiChallenge.StateValue")
  val ConfigKey = TypedKey[OtoroshiChallengeConfig]("otoroshi.next.core.plugins.OtoroshiChallenge.Config")
}

class OtoroshiChallenge extends NgRequestTransformer {

  private val logger = Logger("otoroshi-next-plugins-otoroshi-challenge")
  override def core: Boolean = true
  override def name: String = "Otoroshi challenge token"
  override def description: Option[String] = "This plugin adds a jwt challenge token to the request to a backend and expects a response with a matching token".some
  override def defaultConfig: Option[JsObject] = OtoroshiChallengeConfig(Json.obj()).json.asObject.some

  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config = ctx.cachedConfigFn(internalName)(json => OtoroshiChallengeConfig(json).some).getOrElse(OtoroshiChallengeConfig(ctx.config))
    val jti                    = IdGenerator.uuid
    val stateValue             = IdGenerator.extendedToken(128)
    val stateToken: String     = config.secComVersion match {
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
    ctx.attrs.put(OtoroshiChallengeKeys.StateValueKey -> stateValue)
    ctx.attrs.put(OtoroshiChallengeKeys.StateTokenKey -> stateToken)
    ctx.attrs.put(OtoroshiChallengeKeys.ConfigKey -> config)
    val stateRequestHeaderName = config.requestHeaderName.getOrElse(env.Headers.OtoroshiState)
    ctx.otoroshiRequest.copy(headers = ctx.otoroshiRequest.headers ++ Map(stateRequestHeaderName -> stateToken)).right.vfuture
  }

  override def transformResponse(ctx: NgTransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val config = ctx.attrs.get(OtoroshiChallengeKeys.ConfigKey).get
    val stateValue = ctx.attrs.get(OtoroshiChallengeKeys.StateValueKey).get
    val stateRespHeaderName = config.responseHeaderName.getOrElse(env.Headers.OtoroshiStateResp)
    val isUp = true
    val stateResp           = ctx.request.headers
      .get(stateRespHeaderName)
      .orElse(ctx.request.headers.get(stateRespHeaderName.toLowerCase))
    val at = System.currentTimeMillis()
    val respEith: Either[StateRespInvalid, Done] = (stateResp match {
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
                  val jwt = JWT
                    .require(algo)
                    .withAudience(env.Headers.OtoroshiIssuer)
                    .withClaim("state-resp", stateValue)
                    .acceptLeeway(config.stateRespLeeway)
                    .build()
                    .verify(resp)
                  val extractedState: Option[String] =
                    Option(jwt.getClaim("state-resp")).filterNot(_.isNull).map(_.asString())
                  val exp: Option[Long]              =
                    Option(jwt.getClaim("exp")).filterNot(_.isNull).map(_.asLong())
                  val iat: Option[Long]              =
                    Option(jwt.getClaim("iat")).filterNot(_.isNull).map(_.asLong())
                  val nbf: Option[Long]              =
                    Option(jwt.getClaim("nbf")).filterNot(_.isNull).map(_.asLong())
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
    })
    respEith match {
      case Left(stateRespInvalid) => {
        ctx.response.ignore()
        if (
          ctx.otoroshiResponse.status == 404 && ctx.otoroshiResponse.headers
            .get("X-CleverCloudUpgrade").orElse(ctx.otoroshiResponse.headers.get("x-clevercloudupgrade"))
            .contains("true")
        ) {
          Errors.craftResponseResult(
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
            maybeRoute = ctx.route.some,
          ).map(Left.apply)
        } else if (isUp) {
          logger.error(stateRespInvalid.errorMessage(ctx.response))
          val extraInfos = ctx.attrs
            .get(otoroshi.plugins.Keys.GatewayEventExtraInfosKey)
            .map(_.as[JsObject])
            .getOrElse(Json.obj())
          val newExtraInfos =
            extraInfos ++ Json.obj("stateRespInvalid" -> stateRespInvalid.exchangePayload(ctx.response))
          ctx.attrs.put(otoroshi.plugins.Keys.GatewayEventExtraInfosKey -> newExtraInfos)
          Errors.craftResponseResult(
            "Backend server does not seems to be secured. Cancelling request !",
            Results.BadGateway,
            ctx.request,
            None,
            Some("errors.service.not.secured"),
            attrs = ctx.attrs,
            maybeRoute = ctx.route.some,
          ).map(Left.apply)
        } else {
          Errors.craftResponseResult(
            "The service seems to be down :( come back later",
            Forbidden,
            ctx.request,
            None,
            Some("errors.service.down"),
            attrs = ctx.attrs,
            maybeRoute = ctx.route.some,
          ).map(Left.apply)
        }
      }
      case Right(_) => ctx.otoroshiResponse.right.vfuture
    }
  }
}

class OtoroshiInfos extends NgRequestTransformer {

  private val logger = Logger("otoroshi-next-plugins-otoroshi-infos")
  override def core: Boolean = true
  override def name: String = "Otoroshi info. token"
  override def description: Option[String] = "This plugin adds a jwt info. token to the request to a backend".some
  override def defaultConfig: Option[JsObject] = OtoroshiInfoConfig(Json.obj()).json.asObject.some

  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config = ctx.cachedConfigFn(internalName)(json => OtoroshiInfoConfig(json).some).getOrElse(OtoroshiInfoConfig(ctx.config))
    val claim = InfoTokenHelper.generateInfoToken(ctx.route.name, config.secComVersion, config.secComTtl, ctx.apikey, ctx.user, ctx.request.some)
    logger.trace(s"Claim is : $claim")
    ctx.attrs.put(OtoroshiChallengeKeys.ClaimKey -> claim)
    ctx.attrs.put(otoroshi.plugins.Keys.OtoTokenKey -> claim.payload)
    val serialized = claim.serialize(config.algo)
    val headerName = config.headerName.getOrElse(env.Headers.OtoroshiClaim)
    ctx.otoroshiRequest.copy(headers = ctx.otoroshiRequest.headers ++ Map(headerName -> serialized)).right.vfuture
  }
}
