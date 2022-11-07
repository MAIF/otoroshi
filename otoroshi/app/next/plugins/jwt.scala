package otoroshi.next.plugins

import akka.stream.Materializer
import com.auth0.jwt.JWT
import otoroshi.env.Env
import otoroshi.models.{InCookie, InHeader, InQueryParam, OutputMode, RefJwtVerifier}
import otoroshi.next.plugins.Keys.JwtInjectionKey
import otoroshi.next.plugins.api._
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.{BetterJsValue, BetterSyntax}
import play.api.libs.json._
import play.api.libs.ws.DefaultWSCookie
import play.api.mvc.{Result, Results}
import org.apache.commons.codec.binary.{Base64 => ApacheBase64}

import java.nio.charset.StandardCharsets
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

case class NgJwtVerificationConfig(verifiers: Seq[String] = Seq.empty) extends NgPluginConfig {
  def json: JsValue = NgJwtVerificationConfig.format.writes(this)
}

object NgJwtVerificationConfig {
  val format = new Format[NgJwtVerificationConfig] {
    override def reads(json: JsValue): JsResult[NgJwtVerificationConfig] = Try {
      NgJwtVerificationConfig(
        verifiers = json.select("verifiers").asOpt[Seq[String]].getOrElse(Seq.empty)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: NgJwtVerificationConfig): JsValue             = Json.obj("verifiers" -> o.verifiers)
  }
}

class JwtVerification extends NgAccessValidator with NgRequestTransformer {

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess, NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl, NgPluginCategory.Classic)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean      = true
  override def core: Boolean               = true
  override def usesCallbacks: Boolean      = false
  override def transformsRequest: Boolean  = true
  override def transformsResponse: Boolean = false
  override def transformsError: Boolean    = false

  override def isAccessAsync: Boolean                      = true
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = true
  override def name: String                                = "Jwt verifiers"
  override def description: Option[String]                 =
    "This plugin verifies the current request with one or more jwt verifier".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgJwtVerificationConfig().some

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config   = ctx.cachedConfig(internalName)(NgJwtVerificationConfig.format).getOrElse(NgJwtVerificationConfig())

    config.verifiers match {
      case Nil => JwtVerifierUtils.onError()
      case verifierIds  => JwtVerifierUtils.verify(ctx, verifierIds)
    }
  }

  override def transformRequestSync(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    ctx.attrs.get(JwtInjectionKey) match {
      case None            => ctx.otoroshiRequest.right
      case Some(injection) => {
        ctx.otoroshiRequest
          .applyOnIf(injection.removeCookies.nonEmpty) { req =>
            req.copy(cookies = req.cookies.filterNot(c => injection.removeCookies.contains(c.name)))
          }
          .applyOnIf(injection.removeHeaders.nonEmpty) { req =>
            req.copy(headers =
              req.headers.filterNot(tuple => injection.removeHeaders.map(_.toLowerCase).contains(tuple._1.toLowerCase))
            )
          }
          .applyOnIf(injection.additionalHeaders.nonEmpty) { req =>
            req.copy(headers = req.headers ++ injection.additionalHeaders)
          }
          .applyOnIf(injection.additionalCookies.nonEmpty) { req =>
            req.copy(cookies = req.cookies ++ injection.additionalCookies.map(t => DefaultWSCookie(t._1, t._2)))
          }
          .right
      }
    }
  }
}

object JwtVerifierUtils {
  def onError = () => NgAccess.NgDenied(
    Results.BadRequest(Json.obj("error" -> "bad request"))
  ).vfuture

  def verify(ctx: NgAccessContext, verifierIds: Seq[String])
            (implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val verifier = RefJwtVerifier(verifierIds, enabled = true, Seq.empty)
    if (verifier.isAsync) {
      verifier.verifyFromCache(
        request = ctx.request,
        desc = ctx.route.serviceDescriptor.some,
        apikey = ctx.apikey,
        user = ctx.user,
        elContext = ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
        attrs = ctx.attrs
      )
        .flatMap {
          case Left(result)     => onError()
          case Right(injection) =>
            ctx.attrs.put(JwtInjectionKey -> injection)
            NgAccess.NgAllowed.vfuture
        }
    } else {
      verifier.verifyFromCacheSync(
        request = ctx.request,
        desc = ctx.route.serviceDescriptor.some,
        apikey = ctx.apikey,
        user = ctx.user,
        elContext = ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
        attrs = ctx.attrs
      ) match {
        case Left(result)     => onError()
        case Right(injection) =>
          ctx.attrs.put(JwtInjectionKey -> injection)
          NgAccess.NgAllowed.vfuture
      }
    }
  }
}


case class NgJwtVerificationOnlyConfig(verifier: Option[String] = None, failIfAbsent: Boolean = true) extends NgPluginConfig {
  def json: JsValue = NgJwtVerificationOnlyConfig.format.writes(this)
}

object NgJwtVerificationOnlyConfig {
  val format = new Format[NgJwtVerificationOnlyConfig] {
    override def reads(json: JsValue): JsResult[NgJwtVerificationOnlyConfig] = Try {
      NgJwtVerificationOnlyConfig(
        verifier = json.select("verifier").asOpt[String],
        failIfAbsent = json.select("fail_if_absent").asOpt[Boolean].getOrElse(true)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: NgJwtVerificationOnlyConfig): JsValue             = Json.obj(
      "verifier" -> o.verifier,
      "fail_if_absent" -> o.failIfAbsent
    )
  }
}

class JwtVerificationOnly extends NgAccessValidator with NgRequestTransformer {

  override def defaultConfigObject: Option[NgPluginConfig] = NgJwtVerificationOnlyConfig().some

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl, NgPluginCategory.Classic)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean      = true
  override def core: Boolean               = true
  override def usesCallbacks: Boolean      = false
  override def transformsRequest: Boolean  = true
  override def transformsResponse: Boolean = false
  override def transformsError: Boolean    = false

  override def isAccessAsync: Boolean                      = true
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = true
  override def name: String                                = "Jwt verification only"
  override def description: Option[String]                 =
    "This plugin verifies the current request with one jwt verifier".some

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config   = ctx.cachedConfig(internalName)(NgJwtVerificationOnlyConfig.format).getOrElse(NgJwtVerificationOnlyConfig())

    if (config.failIfAbsent) {
      config.verifier match {
        case None => JwtVerifierUtils.onError()
        case Some(verifierId) =>
          env.proxyState.jwtVerifier(verifierId) match {
            case None => JwtVerifierUtils.onError()
            case Some(verifier) =>
              verifier.source.token(ctx.request) match {
                case Some(_) =>
                  config.verifier match {
                    case None => JwtVerifierUtils.onError()
                    case Some(verifierId) => JwtVerifierUtils.verify(ctx, Seq(verifierId))
                  }
                  NgAccess.NgAllowed.vfuture
                case _ => JwtVerifierUtils.onError()
              }
          }
      }
    } else {
      NgAccess.NgAllowed.vfuture
    }
  }
}

case class NgJwtSignerConfig(verifier: Option[String] = None, replaceIfPresent: Boolean = true, failIfPresent: Boolean = false) extends NgPluginConfig {
  def json: JsValue = NgJwtSignerConfig.format.writes(this)
}

object NgJwtSignerConfig {
  val format = new Format[NgJwtSignerConfig] {
    override def reads(json: JsValue): JsResult[NgJwtSignerConfig] = Try {
      NgJwtSignerConfig(
        verifier = json.select("verifier").asOpt[String],
        replaceIfPresent = json.select("replace_if_present").asOpt[Boolean].getOrElse(true),
        failIfPresent = json.select("fail_if_present").asOpt[Boolean].getOrElse(true),
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: NgJwtSignerConfig): JsValue             = Json.obj(
      "verifier" -> o.verifier,
      "replace_if_present" -> o.replaceIfPresent,
      "fail_if_present" -> o.failIfPresent
    )
  }
}

class JwtSigner extends NgAccessValidator with NgRequestTransformer {

  override def defaultConfigObject: Option[NgPluginConfig] = NgJwtSignerConfig().some

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess, NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl, NgPluginCategory.Classic)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean      = true
  override def core: Boolean               = true
  override def usesCallbacks: Boolean      = false
  override def transformsRequest: Boolean  = true
  override def transformsResponse: Boolean = false
  override def transformsError: Boolean    = false

  override def isAccessAsync: Boolean                      = true
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = true
  override def name: String                                = "Jwt signer"
  override def description: Option[String]                 = "This plugin can only generate token".some

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config   = ctx.cachedConfig(internalName)(NgJwtSignerConfig.format).getOrElse(NgJwtSignerConfig())

    if (config.failIfPresent) {
      config.verifier match {
        case None => NgAccess.NgAllowed.vfuture
        case Some(verifierId) =>
          env.proxyState.jwtVerifier(verifierId) match {
            case None => NgAccess.NgAllowed.vfuture
            case Some(verifier) =>
              verifier.source.token(ctx.request) match {
                case None => NgAccess.NgDenied(Results.BadRequest(Json.obj("error" -> "bad request"))).vfuture
                case _ => NgAccess.NgAllowed.vfuture
              }
          }
      }
    } else {
      NgAccess.NgAllowed.vfuture
    }
  }

  override def transformRequestSync(
                                     ctx: NgTransformerRequestContext
                                   )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    val config   = ctx.cachedConfig(internalName)(NgJwtSignerConfig.format).getOrElse(NgJwtSignerConfig())

    config.verifier match {
      case None => Results
        .BadRequest(Json.obj("error" -> "bad request"))
        .left
      case Some(verifierId) =>
        env.proxyState.jwtVerifier(verifierId) match {
            case None => Results
              .BadRequest(Json.obj("error" -> "bad request"))
              .left
            case Some(globalVerifier) =>
              if(!config.replaceIfPresent && globalVerifier.source.token(ctx.request).isDefined) {
                ctx.otoroshiRequest.right
              } else {
                globalVerifier.algoSettings.asAlgorithm(OutputMode) match {
                  case None                  =>
                    Results
                      .BadRequest(Json.obj("error" -> "bad request"))
                      .left
                  case Some(tokenSigningAlgorithm) => {
                    val user = ctx.user.orElse(ctx.attrs.get(otoroshi.plugins.Keys.UserKey))
                    val apikey = ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
                    val optSub: Option[String] = apikey.map(_.clientName).orElse(user.map(_.email))

                    val token           = Json.obj(
                      "jti" -> IdGenerator.uuid,
                      "iat" -> System.currentTimeMillis(),
                      "nbf" -> System.currentTimeMillis(),
                      "iss" -> "Otoroshi",
                      "exp" -> (System.currentTimeMillis() + 60000L),
                      "sub" -> JsString(optSub.getOrElse("anonymous")),
                      "aud" -> "backend"
                    )

                    val headerJson     = Json
                      .obj("alg" -> tokenSigningAlgorithm.getName, "typ" -> "JWT")
                      .applyOnWithOpt(globalVerifier.algoSettings.keyId)((h, id) => h ++ Json.obj("kid" -> id))
                    val header         = ApacheBase64.encodeBase64URLSafeString(Json.stringify(headerJson).getBytes(StandardCharsets.UTF_8))
                    val payload        = ApacheBase64.encodeBase64URLSafeString(Json.stringify(token).getBytes(StandardCharsets.UTF_8))
                    val content        = String.format("%s.%s", header, payload)
                    val signatureBytes =
                      tokenSigningAlgorithm.sign(header.getBytes(StandardCharsets.UTF_8), payload.getBytes(StandardCharsets.UTF_8))
                    val signature      = ApacheBase64.encodeBase64URLSafeString(signatureBytes)

                    val signedToken       = s"$content.$signature"

                    val originalToken = JWT.decode(signedToken)
                    ctx.attrs.put(otoroshi.plugins.Keys.MatchedOutputTokenKey -> token)

                    (globalVerifier.source match {
                      case _: InQueryParam =>
                        globalVerifier.source.asJwtInjection(originalToken, signedToken).right[Result]
                      case InHeader(n, _) =>
                        val inj = globalVerifier.source.asJwtInjection(originalToken, signedToken)
                        globalVerifier.source match {
                          case InHeader(nn, _) if nn == n => inj.right[Result]
                          case _ => inj.copy(removeHeaders = Seq(n)).right[Result]
                        }
                      case InCookie(n) =>
                        globalVerifier.source
                          .asJwtInjection(originalToken, signedToken)
                          .copy(removeCookies = Seq(n))
                          .right[Result]
                    }) match {
                      case Left(result) => result.left
                      case Right(newValue) =>
                        ctx.attrs.put(JwtInjectionKey -> newValue)
                        ctx.otoroshiRequest.right
                    }
                  }
                }
              }
          }
    }
  }
}