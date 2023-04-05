package otoroshi.next.plugins

import akka.stream.Materializer
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.plugins.hmac.HMACUtils
import otoroshi.utils.crypto.Signatures
import otoroshi.utils.syntax.implicits.{BetterJsValue, BetterSyntax}
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.Result
import play.api.mvc.Results.BadRequest

import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class HMACValidatorConfig(secret: Option[String] = None)
  extends NgPluginConfig {
  def json: JsValue = HMACValidatorConfig.format.writes(this)
}

object HMACValidatorConfig {
  val format = new Format[HMACValidatorConfig] {
    override def reads(json: JsValue): JsResult[HMACValidatorConfig] = Try {
      HMACValidatorConfig(
        secret = json.select("secret").asOpt[String]
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: HMACValidatorConfig): JsValue             =
      Json.obj("secret" -> o.secret)
  }
}

class HMACValidator extends NgAccessValidator {

  private val logger = Logger("otoroshi-next-plugins-hmac-access-validator-plugin")

  override def steps: Seq[NgStep] = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl, NgPluginCategory.Classic)
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean = true
  override def core: Boolean = true
  override def name: String = "HMAC access validator"
  override def isAccessAsync: Boolean                      = true
  override def defaultConfigObject: Option[NgPluginConfig] = HMACValidatorConfig().some
  override def description: Option[String] = {
    Some("This plugin can be used to check if a HMAC signature is present and valid in Authorization header.")
  }

  private def checkHMACSignature(authorization: String, context: NgAccessContext, secret: String): NgAccess = {
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
    val headers: Seq[String] = params.get("headers").map(_.split(" ").toSeq).getOrElse(Seq.empty[String])
    val signingValues        = context.request.headers.headers.filter(p => headers.contains(p._1)).map(_._2)
    val signingString        = signingValues.mkString(" ")

    if (logger.isDebugEnabled) logger.debug(s"Secret used : $secret")
    if (logger.isDebugEnabled) logger.debug(s"Signature generated : ${Base64.getEncoder
      .encodeToString(Signatures.hmac(HMACUtils.Algo(algorithm.toUpperCase), signingString, secret))}")
    if (logger.isDebugEnabled) logger.debug(s"Signature received : $signature")
    if (logger.isDebugEnabled) logger.debug(s"Algorithm used : $algorithm")

    if (signingValues.size != headers.size)
      NgAccess.NgDenied(BadRequest)
    else if (
      Base64.getEncoder.encodeToString(
        Signatures.hmac(HMACUtils.Algo(algorithm.toUpperCase), signingString, secret)
      ) == signature
    )
      NgAccess.NgAllowed
    else
      NgAccess.NgDenied(BadRequest)
  }

  override def access(context: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val HMACValidatorConfig(secret) =
      context.cachedConfig(internalName)(HMACValidatorConfig.format).getOrElse(HMACValidatorConfig())

    ((secret match {
      case Some(value) if value.nonEmpty => Some(value)
      case _ => context.attrs.get(otoroshi.plugins.Keys.ApiKeyKey).map(_.clientSecret)
    }) match {
      case None =>
        if (logger.isDebugEnabled) logger.debug("No api key found and no secret found in configuration of the plugin")
        NgAccess.NgDenied(BadRequest)
      case Some(secret) =>
        (context.request.headers.get("Authorization"), context.request.headers.get("Proxy-Authorization")) match {
          case (Some(authorization), None) => checkHMACSignature(authorization, context, secret)
          case (None, Some(authorization)) => checkHMACSignature(authorization, context, secret)
          case (_, _) =>
            if (logger.isDebugEnabled) logger.debug("Missing authorization header")
            NgAccess.NgDenied(BadRequest)
        }
    }).vfuture
  }
}

case class HMACCallerConfig(secret: Option[String] = None, algo: String = "HMAC-SHA512", authorizationHeader: Option[String] = None)
  extends NgPluginConfig {
  def json: JsValue = HMACCallerConfig.format.writes(this)
}

object HMACCallerConfig {
  val format = new Format[HMACCallerConfig] {
    override def reads(json: JsValue): JsResult[HMACCallerConfig] = Try {
      HMACCallerConfig(
        secret = json.select("secret").asOpt[String],
        algo = json.select("algo").asOpt[String].getOrElse("HMAC-SHA512"),
        authorizationHeader = json.select("authorizationHeader").asOpt[String]
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: HMACCallerConfig): JsValue             =
      Json.obj("secret" -> o.secret, "algo" -> o.algo, "authorizationHeader" -> o.authorizationHeader)
  }
}

class HMACCaller extends NgRequestTransformer {

  private val logger = Logger("otoroshi-next-plugins-hmac-caller-plugin")

  override def steps: Seq[NgStep] = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Authentication)
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean = false
  override def core: Boolean = true
  override def usesCallbacks: Boolean = false
  override def transformsRequest: Boolean = true
  override def transformsResponse: Boolean = false
  override def isTransformRequestAsync: Boolean = false
  override def isTransformResponseAsync: Boolean = false
  override def transformsError: Boolean = false
  override def name: String = "HMAC caller plugin"

  override def description: Option[String] = {
    Some(
      s"""This plugin can be used to call a "protected" api by an HMAC signature. It will adds a signature with the secret configured on the plugin.
         | The signature string will always the content of the header list listed in the plugin configuration.
      """.stripMargin
    )
  }
  override def defaultConfigObject: Option[NgPluginConfig] = HMACCallerConfig().some

  override def transformRequestSync(
                                     ctx: NgTransformerRequestContext)
                                   (implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    val HMACCallerConfig(secret, algo, authorizationHeader) =
      ctx.cachedConfig(internalName)(HMACCallerConfig.format).getOrElse(HMACCallerConfig())

    secret match {
      case None         =>
        if (logger.isDebugEnabled) {
          logger.debug("No api key found and no secret found in configuration of the plugin")
        }
        Left(BadRequest(Json.obj("error" -> "Bad parameters")))
      case Some(secret) =>
        val authHeader = authorizationHeader.getOrElse("Authorization")
        val signingString = System.currentTimeMillis().toString
        val signature     =
          Base64.getEncoder.encodeToString(Signatures.hmac(HMACUtils.Algo(algo), signingString, secret))

        if (logger.isDebugEnabled) {
          logger.debug(
            s"""
               |Secret used : $secret
               |Signature send : $signature
               |Algorithm used : $algo
               |Date generated : $signingString
               |Send Authorization header : ${s"$authHeader" -> s"""hmac algorithm="${algo.toLowerCase}", headers="Date", signature="$signature""""}
               |""".stripMargin)
        }

        val hmacHeaders = Map(
          "Date" -> signingString,
          authHeader -> s"""hmac algorithm="${algo.toLowerCase}", headers="Date", signature="$signature""""
        )

        ctx
          .otoroshiRequest
          .copy(headers = ctx.otoroshiRequest.headers ++ hmacHeaders)
          .right
    }
  }
}


