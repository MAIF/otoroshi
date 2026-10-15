package otoroshi.next.plugins

import org.apache.pekko.stream.Materializer
import otoroshi.env.Env
import otoroshi.next.plugins.api.*
import otoroshi.plugins.hmac.HMACUtils
import otoroshi.utils.crypto.Signatures
import otoroshi.utils.syntax.implicits.{BetterJsValue, BetterSyntax}
import play.api.Logger
import play.api.libs.json.*
import play.api.mvc.Result
import play.api.mvc.Results.BadRequest

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

// maxDateSkewSeconds and signRequestTarget are opt-in, they bound the replay of a signature: the signed date header
// has to be that close to now, and the signature has to cover the (request-target) pseudo header, the method and the
// path of the request. with signRequestTarget, the signing string follows the order of the headers parameter
case class HMACValidatorConfig(
    secret: Option[String] = None,
    authorizationHeader: Option[String] = None,
    maxDateSkewSeconds: Option[Long] = None,
    signRequestTarget: Boolean = false
) extends NgPluginConfig {
  def json: JsValue = HMACValidatorConfig.format.writes(this)
}

object HMACValidatorConfig {
  val format = new Format[HMACValidatorConfig] {
    override def reads(json: JsValue): JsResult[HMACValidatorConfig] = Try {
      HMACValidatorConfig(
        secret = json.select("secret").asOpt[String],
        authorizationHeader = json.select("authorizationHeader").asOpt[String],
        maxDateSkewSeconds = json.select("maxDateSkewSeconds").asOpt[Long],
        signRequestTarget = json.select("signRequestTarget").asOpt[Boolean].getOrElse(false)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: HMACValidatorConfig): JsValue             =
      Json.obj(
        "secret"              -> o.secret,
        "authorizationHeader" -> o.authorizationHeader,
        "maxDateSkewSeconds"  -> o.maxDateSkewSeconds,
        "signRequestTarget"   -> o.signRequestTarget
      )
  }
}

class HMACValidator extends NgAccessValidator {

  private val logger = Logger("otoroshi-next-plugins-hmac-access-validator-plugin")

  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.AccessControl)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "HMAC access validator"
  override def isAccessAsync: Boolean                      = true
  override def defaultConfigObject: Option[NgPluginConfig] = HMACValidatorConfig().some
  override def description: Option[String] = {
    Some("This plugin can be used to check if a HMAC signature is present and valid in Authorization header.")
  }

  // the date header of the hmac caller carries epoch millis, the one of an http client an http date
  private def parseDate(value: String): Option[Long] = {
    if (value.nonEmpty && value.forall(_.isDigit)) value.toLongOption
    else Try(ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant.toEpochMilli).toOption
  }

  private def checkHMACSignature(
      authorization: String,
      context: NgAccessContext,
      secret: String,
      config: HMACValidatorConfig
  ): NgAccess = {
    val params = authorization
      .replace("hmac ", "")
      .replace("\"", "")
      .trim()
      .split(",")
      .toSeq
      .map(_.split("=", 2))
      .map(r => r(0).trim -> r(1).trim)
      .toMap

    val algorithm            = params.getOrElse("algorithm", "HMAC-SHA256")
    val signature            = params("signature")
    val headers: Seq[String] = params.get("headers").map(_.split(" ").toSeq).getOrElse(Seq.empty[String]).toSeq
    val signingValues        =
      if (config.signRequestTarget)
        headers.flatMap {
          case "(request-target)" => Some(s"${context.request.method.toLowerCase()} ${context.request.uri}")
          case name               => context.request.headers.get(name)
        }
      else context.request.headers.headers.filter(p => headers.contains(p._1)).map(_._2)
    val signingString        = signingValues.mkString(" ")
    val requestTargetSigned  = !config.signRequestTarget || headers.contains("(request-target)")
    val dateInWindow         = config.maxDateSkewSeconds.forall { skew =>
      headers
        .find(_.equalsIgnoreCase("date"))
        .flatMap(name => context.request.headers.get(name))
        .flatMap(parseDate)
        .exists(date => math.abs(System.currentTimeMillis() - date) <= skew * 1000L)
    }

    //if (logger.isDebugEnabled) logger.debug(s"Secret used : $secret")
    //if (logger.isDebugEnabled) logger.debug(s"Signature generated : ${Base64.getEncoder
    //  .encodeToString(Signatures.hmac(HMACUtils.Algo(algorithm.toUpperCase), signingString, secret))}")
    //if (logger.isDebugEnabled) logger.debug(s"Signature received : $signature")
    //if (logger.isDebugEnabled) logger.debug(s"Algorithm used : $algorithm")

    if (signingValues.size != headers.size || !requestTargetSigned || !dateInWindow)
      NgAccess.NgDenied(BadRequest)
    else if (
      Signatures.constantTimeEquals(
        Base64.getEncoder.encodeToString(
          Signatures.hmac(HMACUtils.Algo(algorithm.toUpperCase), signingString, secret)
        ),
        signature
      )
    )
      NgAccess.NgAllowed
    else
      NgAccess.NgDenied(BadRequest)
  }

  override def access(ctx: NgAccessContext)(using env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config                                                 =
      ctx.cachedConfig(internalName)(HMACValidatorConfig.format).getOrElse(HMACValidatorConfig())
    val HMACValidatorConfig(secret, authorizationHeader, _, _) = config

    ((secret match {
      case Some(value) if value.nonEmpty => Some(value)
      case _                             => ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyKey).map(_.clientSecret)
    }) match {
      case None         =>
        if (logger.isDebugEnabled) logger.debug("No api key found and no secret found in configuration of the plugin")
        NgAccess.NgDenied(BadRequest)
      case Some(secret) =>
        Try {
          authorizationHeader match {
            case Some(authorization) if ctx.request.headers.get(authorization).isDefined =>
              checkHMACSignature(ctx.request.headers.get(authorization).get, ctx, secret, config)
            case _                                                                    =>
              (ctx.request.headers.get("Authorization"), ctx.request.headers.get("Proxy-Authorization")) match {
                case (Some(authorization), None) => checkHMACSignature(authorization, ctx, secret, config)
                case (None, Some(authorization)) => checkHMACSignature(authorization, ctx, secret, config)
                case (_, _)                      =>
                  if (logger.isDebugEnabled) logger.debug("Missing authorization header")
                  NgAccess.NgDenied(BadRequest)
              }
          }
        } recover { case _ =>
          NgAccess.NgDenied(BadRequest)
        } get
    }).vfuture
  }
}

// signRequestTarget also signs the (request-target) pseudo header, for a validator that requires it
case class HMACCallerConfig(
    secret: Option[String] = None,
    algo: String = "HMAC-SHA512",
    authorizationHeader: Option[String] = None,
    signRequestTarget: Boolean = false
) extends NgPluginConfig {
  def json: JsValue = HMACCallerConfig.format.writes(this)
}

object HMACCallerConfig {
  val format = new Format[HMACCallerConfig] {
    override def reads(json: JsValue): JsResult[HMACCallerConfig] = Try {
      HMACCallerConfig(
        secret = json.select("secret").asOpt[String],
        algo = json.select("algo").asOpt[String].getOrElse("HMAC-SHA512"),
        authorizationHeader = json.select("authorizationHeader").asOpt[String],
        signRequestTarget = json.select("signRequestTarget").asOpt[Boolean].getOrElse(false)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: HMACCallerConfig): JsValue             =
      Json.obj(
        "secret"              -> o.secret,
        "algo"                -> o.algo,
        "authorizationHeader" -> o.authorizationHeader,
        "signRequestTarget"   -> o.signRequestTarget
      )
  }
}

class HMACCaller extends NgRequestTransformer {

  private val logger = Logger("otoroshi-next-plugins-hmac-caller-plugin")

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Authentication)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean            = true
  override def core: Boolean                     = true
  override def usesCallbacks: Boolean            = false
  override def transformsRequest: Boolean        = true
  override def transformsResponse: Boolean       = false
  override def isTransformRequestAsync: Boolean  = false
  override def isTransformResponseAsync: Boolean = false
  override def transformsError: Boolean          = false
  override def name: String                      = "HMAC caller plugin"

  override def description: Option[String] = {
    Some(
      s"""This plugin can be used to call a "protected" api by an HMAC signature. It will adds a signature with the secret configured on the plugin.
         | The signature string will always the content of the header list listed in the plugin configuration.
      """.stripMargin
    )
  }
  override def defaultConfigObject: Option[NgPluginConfig] = HMACCallerConfig().some

  override def transformRequestSync(
      ctx: NgTransformerRequestContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    val HMACCallerConfig(secret, algo, authorizationHeader, signRequestTarget) =
      ctx.cachedConfig(internalName)(HMACCallerConfig.format).getOrElse(HMACCallerConfig())

    secret match {
      case None         =>
        if (logger.isDebugEnabled) {
          logger.debug("No api key found and no secret found in configuration of the plugin")
        }
        Left(BadRequest(Json.obj("error" -> "Bad parameters")))
      case Some(secret) =>
        val authHeader    = authorizationHeader.getOrElse("Authorization")
        val date          = System.currentTimeMillis().toString
        val signedHeaders = if (signRequestTarget) "(request-target) Date" else "Date"
        val signingString =
          if (signRequestTarget) s"${ctx.otoroshiRequest.method.toLowerCase()} ${ctx.otoroshiRequest.relativeUri} $date"
          else date
        val signature     =
          Base64.getEncoder.encodeToString(Signatures.hmac(HMACUtils.Algo(algo), signingString, secret))

        if (logger.isDebugEnabled) {
          logger.debug(s"""
               |Signature send : $signature
               |Algorithm used : $algo
               |Date generated : $signingString
               |Send Authorization header : ${s"$authHeader" -> s"""hmac algorithm="${algo.toLowerCase}", headers="$signedHeaders", signature="$signature""""}
               |""".stripMargin)
        }

        val hmacHeaders = Map(
          "Date"     -> date,
          authHeader -> s"""hmac algorithm="${algo.toLowerCase}", headers="$signedHeaders", signature="$signature""""
        )

        ctx.otoroshiRequest
          .copy(headers = ctx.otoroshiRequest.headers ++ hmacHeaders)
          .right
    }
  }
}
