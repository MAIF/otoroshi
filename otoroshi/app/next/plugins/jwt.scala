package otoroshi.next.plugins

import akka.stream.Materializer
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.github.blemale.scaffeine.Scaffeine
import com.nimbusds.jose.crypto.{AESEncrypter, RSADecrypter, RSAEncrypter}
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.{EncryptionMethod, JOSEException, JWEAlgorithm, JWEHeader, JWEObject, Payload}
import com.nimbusds.jwt.{EncryptedJWT, JWTClaimsSet}
import otoroshi.env.Env
import otoroshi.models.{ApiKey, DefaultToken, InCookie, InHeader, InQueryParam, JwtTokenLocation, LocalJwtVerifier, OutputMode, PrivateAppsUser, RefJwtVerifier, ServiceDescriptor}
import otoroshi.next.plugins.Keys.JwtInjectionKey
import otoroshi.next.plugins.api._
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.{BetterJsReadable, BetterJsValue, BetterMapOfStringAndB, BetterString, BetterSyntax}
import play.api.libs.json._
import play.api.libs.ws.DefaultWSCookie
import play.api.mvc.{RequestHeader, Result, Results}
import org.apache.commons.codec.binary.{Base64 => ApacheBase64}
import org.joda.time.DateTime
import otoroshi.auth.OAuth2ModuleConfig
import otoroshi.el.JwtExpressionLanguage
import play.api.libs.typedmap.TypedKey
import otoroshi.ssl.DynamicSSLEngineProvider
import otoroshi.ssl.pki.models.GenKeyPairQuery
import otoroshi.utils.TypedMap

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.util.{Date, UUID}
import javax.crypto.{Cipher, KeyGenerator}
import scala.collection.parallel.immutable
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.mapAsScalaMapConverter
import scala.util.{Failure, Success, Try}

case class NgJwtVerificationConfig(
    verifiers: Seq[String] = Seq.empty,
    customResponse: Boolean = false,
    customResponseStatus: Int = 401,
    customResponseHeaders: Map[String, String] = Map.empty,
    customResponseBody: String = Json.obj("error" -> "unauthorized").stringify
) extends NgPluginConfig {
  def json: JsValue = NgJwtVerificationConfig.format.writes(this)
  def asResult: Option[Result] = {
    if (customResponse) {
      val ctype          = customResponseHeaders.getIgnoreCase("Content-Type").getOrElse("application/json")
      val headersNoCtype = customResponseHeaders.filterNot(_._1.equalsIgnoreCase("content-type")).toSeq
      Some(Results.Status(customResponseStatus)(customResponseBody).withHeaders(headersNoCtype: _*).as(ctype))
    } else {
      None
    }
  }
}

object NgJwtVerificationConfig {
  val format = new Format[NgJwtVerificationConfig] {
    override def reads(json: JsValue): JsResult[NgJwtVerificationConfig] = Try {
      NgJwtVerificationConfig(
        verifiers = json.select("verifiers").asOpt[Seq[String]].getOrElse(Seq.empty),
        customResponse = json.select("custom_response").asOpt[Boolean].getOrElse(false),
        customResponseStatus = json.select("custom_response_status").asOpt[Int].getOrElse(401),
        customResponseHeaders = json.select("custom_response_headers").asOpt[Map[String, String]].getOrElse(Map.empty),
        customResponseBody =
          json.select("custom_response_body").asOpt[String].getOrElse(Json.obj("error" -> "unauthorized").stringify)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: NgJwtVerificationConfig): JsValue             = Json.obj(
      "verifiers"               -> o.verifiers,
      "custom_response"         -> o.customResponse,
      "custom_response_status"  -> o.customResponseStatus,
      "custom_response_headers" -> o.customResponseHeaders,
      "custom_response_body"    -> o.customResponseBody
    )
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
    val config = ctx.cachedConfig(internalName)(NgJwtVerificationConfig.format).getOrElse(NgJwtVerificationConfig())

    config.verifiers match {
      case Nil         => JwtVerifierUtils.onError()
      case verifierIds => JwtVerifierUtils.verify(ctx, verifierIds, config.asResult)
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
  def onError(result: Option[Result] = None): Future[NgAccess] = {
    NgAccess
      .NgDenied(
        result.getOrElse(Results.BadRequest(Json.obj("error" -> "bad request")))
      )
      .vfuture
  }

  def verify(ctx: NgAccessContext, verifierIds: Seq[String], customResult: Option[Result])(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[NgAccess] = {
    val verifier = RefJwtVerifier(verifierIds, enabled = true, Seq.empty)
    if (verifier.isAsync) {
      verifier
        .verifyFromCache(
          request = ctx.request,
          desc = ctx.route.serviceDescriptor.some,
          apikey = ctx.apikey,
          user = ctx.user,
          elContext = ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
          attrs = ctx.attrs
        )
        .flatMap {
          case Left(result)     => onError(customResult.orElse(result.some))
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
        case Left(result)     => onError(customResult.orElse(result.some))
        case Right(injection) =>
          ctx.attrs.put(JwtInjectionKey -> injection)
          NgAccess.NgAllowed.vfuture
      }
    }
  }
}

case class NgJwtVerificationOnlyConfig(
    verifier: Option[String] = None,
    failIfAbsent: Boolean = true,
    customResponse: Boolean = false,
    customResponseStatus: Int = 401,
    customResponseHeaders: Map[String, String] = Map.empty,
    customResponseBody: String = Json.obj("error" -> "unauthorized").stringify
) extends NgPluginConfig {
  def json: JsValue = NgJwtVerificationOnlyConfig.format.writes(this)
  def asResult: Option[Result] = {
    if (customResponse) {
      val ctype          = customResponseHeaders.getIgnoreCase("Content-Type").getOrElse("application/json")
      val headersNoCtype = customResponseHeaders.filterNot(_._1.equalsIgnoreCase("content-type")).toSeq
      Some(Results.Status(customResponseStatus)(customResponseBody).withHeaders(headersNoCtype: _*).as(ctype))
    } else {
      None
    }
  }
}

object NgJwtVerificationOnlyConfig {
  val format = new Format[NgJwtVerificationOnlyConfig] {
    override def reads(json: JsValue): JsResult[NgJwtVerificationOnlyConfig] = Try {
      NgJwtVerificationOnlyConfig(
        verifier = json.select("verifier").asOpt[String],
        failIfAbsent = json.select("fail_if_absent").asOpt[Boolean].getOrElse(true),
        customResponse = json.select("custom_response").asOpt[Boolean].getOrElse(false),
        customResponseStatus = json.select("custom_response_status").asOpt[Int].getOrElse(401),
        customResponseHeaders = json.select("custom_response_headers").asOpt[Map[String, String]].getOrElse(Map.empty),
        customResponseBody =
          json.select("custom_response_body").asOpt[String].getOrElse(Json.obj("error" -> "unauthorized").stringify)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: NgJwtVerificationOnlyConfig): JsValue             = Json.obj(
      "verifier"                -> o.verifier,
      "fail_if_absent"          -> o.failIfAbsent,
      "custom_response"         -> o.customResponse,
      "custom_response_status"  -> o.customResponseStatus,
      "custom_response_headers" -> o.customResponseHeaders,
      "custom_response_body"    -> o.customResponseBody
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

  override def isAccessAsync: Boolean            = true
  override def isTransformRequestAsync: Boolean  = false
  override def isTransformResponseAsync: Boolean = true
  override def name: String                      = "Jwt verification only"
  override def description: Option[String]       =
    "This plugin verifies the current request with one jwt verifier".some

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config =
      ctx.cachedConfig(internalName)(NgJwtVerificationOnlyConfig.format).getOrElse(NgJwtVerificationOnlyConfig())

    config.verifier match {
      case None             => JwtVerifierUtils.onError()
      case Some(verifierId) =>
        env.proxyState.jwtVerifier(verifierId) match {
          case None           => JwtVerifierUtils.onError()
          case Some(verifier) =>
            verifier.source.token(ctx.request) match {
              case None if !config.failIfAbsent => NgAccess.NgAllowed.vfuture
              case _                            => JwtVerifierUtils.verify(ctx, Seq(verifierId), config.asResult)
            }
        }
    }
  }
}

case class NgJwtSignerConfig(
    verifier: Option[String] = None,
    replaceIfPresent: Boolean = true,
    failIfPresent: Boolean = false
) extends NgPluginConfig {
  def json: JsValue = NgJwtSignerConfig.format.writes(this)
}

object NgJwtSignerConfig {
  val format = new Format[NgJwtSignerConfig] {
    override def reads(json: JsValue): JsResult[NgJwtSignerConfig] = Try {
      NgJwtSignerConfig(
        verifier = json.select("verifier").asOpt[String],
        replaceIfPresent = json.select("replace_if_present").asOpt[Boolean].getOrElse(true),
        failIfPresent = json.select("fail_if_present").asOpt[Boolean].getOrElse(true)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: NgJwtSignerConfig): JsValue             = Json.obj(
      "verifier"           -> o.verifier,
      "replace_if_present" -> o.replaceIfPresent,
      "fail_if_present"    -> o.failIfPresent
    )
  }
}

class JwtSigner extends NgAccessValidator with NgRequestTransformer {

  override def defaultConfigObject: Option[NgPluginConfig] = NgJwtSignerConfig().some

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess, NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Transformations)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean      = true
  override def core: Boolean               = true
  override def usesCallbacks: Boolean      = false
  override def transformsRequest: Boolean  = true
  override def transformsResponse: Boolean = false
  override def transformsError: Boolean    = false

  override def isAccessAsync: Boolean            = true
  override def isTransformRequestAsync: Boolean  = false
  override def isTransformResponseAsync: Boolean = true
  override def name: String                      = "Jwt signer"
  override def description: Option[String]       = "This plugin can only generate token".some

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx.cachedConfig(internalName)(NgJwtSignerConfig.format).getOrElse(NgJwtSignerConfig())

    if (config.failIfPresent) {
      config.verifier match {
        case None             => NgAccess.NgAllowed.vfuture
        case Some(verifierId) =>
          env.proxyState.jwtVerifier(verifierId) match {
            case None           => NgAccess.NgAllowed.vfuture
            case Some(verifier) =>
              verifier.source.token(ctx.request) match {
                case None => NgAccess.NgDenied(Results.BadRequest(Json.obj("error" -> "bad request"))).vfuture
                case _    => NgAccess.NgAllowed.vfuture
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
    val config = ctx.cachedConfig(internalName)(NgJwtSignerConfig.format).getOrElse(NgJwtSignerConfig())

    config.verifier match {
      case None             =>
        Results
          .BadRequest(Json.obj("error" -> "bad request"))
          .left
      case Some(verifierId) =>
        env.proxyState.jwtVerifier(verifierId) match {
          case None                 =>
            Results
              .BadRequest(Json.obj("error" -> "bad request"))
              .left
          case Some(globalVerifier) =>
            if (!config.replaceIfPresent && globalVerifier.source.token(ctx.request).isDefined) {
              ctx.otoroshiRequest.right
            } else {
              globalVerifier.algoSettings.asAlgorithm(OutputMode) match {
                case None                        =>
                  Results
                    .BadRequest(Json.obj("error" -> "bad request"))
                    .left
                case Some(tokenSigningAlgorithm) => {
                  val user                   = ctx.user.orElse(ctx.attrs.get(otoroshi.plugins.Keys.UserKey))
                  val apikey                 = ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
                  val optSub: Option[String] = apikey.map(_.clientName).orElse(user.map(_.email))

                  val token = JsObject(
                    JwtExpressionLanguage
                      .fromJson(
                        Json.obj(
                          "jti" -> IdGenerator.uuid,
                          "iat" -> Math.floor(System.currentTimeMillis() / 1000L).toLong,
                          "nbf" -> Math.floor(System.currentTimeMillis() / 1000L).toLong,
                          "iss" -> "Otoroshi",
                          "exp" -> Math.floor((System.currentTimeMillis() + 60000L) / 1000L).toLong,
                          "sub" -> JsString(optSub.getOrElse("anonymous")),
                          "aud" -> "backend"
                        ) ++ globalVerifier.strategy.asInstanceOf[DefaultToken].token.as[JsObject],
                        Some(ctx.request),
                        None,
                        ctx.route.some,
                        apikey,
                        user,
                        Map.empty,
                        ctx.attrs,
                        env
                      )
                      .as[JsObject]
                      .value
                      .map { case (key, value) =>
                        value match {
                          case JsString(v) if v == "{iat}" =>
                            (key, JsNumber(Math.floor(System.currentTimeMillis() / 1000L).toLong))
                          case JsString(v) if v == "{nbf}" =>
                            (key, JsNumber(Math.floor(System.currentTimeMillis() / 1000L).toLong))
                          case JsString(v) if v == "{exp}" =>
                            (key, JsNumber(Math.floor((System.currentTimeMillis() + 60000L) / 1000L).toLong))
                          case _                           => (key, value.as[JsValue])
                        }
                      }
                  )

                  val headerJson     = Json
                    .obj("alg" -> tokenSigningAlgorithm.getName, "typ" -> "JWT")
                    .applyOnWithOpt(globalVerifier.algoSettings.keyId)((h, id) => h ++ Json.obj("kid" -> id))
                  val header         =
                    ApacheBase64.encodeBase64URLSafeString(Json.stringify(headerJson).getBytes(StandardCharsets.UTF_8))
                  val payload        =
                    ApacheBase64.encodeBase64URLSafeString(Json.stringify(token).getBytes(StandardCharsets.UTF_8))
                  val content        = String.format("%s.%s", header, payload)
                  val signatureBytes =
                    tokenSigningAlgorithm.sign(
                      header.getBytes(StandardCharsets.UTF_8),
                      payload.getBytes(StandardCharsets.UTF_8)
                    )
                  val signature      = ApacheBase64.encodeBase64URLSafeString(signatureBytes)

                  val signedToken = s"$content.$signature"

                  val originalToken = JWT.decode(signedToken)
                  ctx.attrs.put(otoroshi.plugins.Keys.MatchedOutputTokenKey -> token)

                  (globalVerifier.source match {
                    case _: InQueryParam =>
                      globalVerifier.source.asJwtInjection(originalToken, signedToken).right[Result]
                    case InHeader(n, _)  =>
                      val inj = globalVerifier.source.asJwtInjection(originalToken, signedToken)
                      globalVerifier.source match {
                        case InHeader(nn, _) if nn == n => inj.right[Result]
                        case _                          => inj.copy(removeHeaders = Seq(n)).right[Result]
                      }
                    case InCookie(n)     =>
                      globalVerifier.source
                        .asJwtInjection(originalToken, signedToken)
                        .copy(removeCookies = Seq(n))
                        .right[Result]
                  }) match {
                    case Left(result)    => result.left
                    case Right(newValue) =>
                      ctx.otoroshiRequest
                        .applyOnIf(newValue.removeCookies.nonEmpty) { req =>
                          req.copy(cookies = req.cookies.filterNot(c => newValue.removeCookies.contains(c.name)))
                        }
                        .applyOnIf(newValue.removeHeaders.nonEmpty) { req =>
                          req.copy(headers =
                            req.headers.filterNot(tuple =>
                              newValue.removeHeaders.map(_.toLowerCase).contains(tuple._1.toLowerCase)
                            )
                          )
                        }
                        .applyOnIf(newValue.additionalHeaders.nonEmpty) { req =>
                          req.copy(headers = req.headers ++ newValue.additionalHeaders)
                        }
                        .applyOnIf(newValue.additionalCookies.nonEmpty) { req =>
                          req.copy(cookies =
                            req.cookies ++ newValue.additionalCookies.map(t => DefaultWSCookie(t._1, t._2))
                          )
                        }
                        .right
                  }
                }
              }
            }
        }
    }
  }
}

case class NgJweSignerConfig(
    keyManagementAlgorithm: JWEAlgorithm = JWEAlgorithm.RSA_OAEP_256,
    contentEncryptionAlgorithm: EncryptionMethod = EncryptionMethod.A128CBC_HS256,
    certId: Option[String] = None,
    source: JwtTokenLocation = InHeader("X-JWT-Token"),
    forwardLocation: JwtTokenLocation = InHeader("X-JWT-Token"),
    strict: Boolean = false,
    payload: Map[String, String] = Map.empty
) extends NgPluginConfig {
  def json: JsValue = NgJweSignerConfig.format.writes(this)
}

object NgJweSignerConfig {
  val format = new Format[NgJweSignerConfig] {
    override def reads(json: JsValue): JsResult[NgJweSignerConfig] = Try {
      NgJweSignerConfig(
        keyManagementAlgorithm = json
          .select("key_management_algorithm")
          .asOpt[String]
          .map(keyManagementAlgorithmFromString)
          .getOrElse(JWEAlgorithm.RSA_OAEP_256),
        contentEncryptionAlgorithm = json
          .select("content_encryption_algorithm")
          .asOpt[String]
          .map(contentEncryptionAlgorithmFromString)
          .getOrElse(EncryptionMethod.A128CBC_HS256),
        certId = json.select("certId").asOpt[String],
        source = JwtTokenLocation.fromJson((json \ "source").as[JsValue]).getOrElse(InHeader("X-JWT-Token")),
        forwardLocation =
          JwtTokenLocation.fromJson((json \ "forward_location").as[JsValue]).getOrElse(InHeader("X-JWT-Token")),
        strict = json.select("strict").asOpt[Boolean].getOrElse(false),
        payload = json.select("payload").asOpt[Map[String, String]].getOrElse(Map.empty)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: NgJweSignerConfig): JsValue             = Json.obj(
      "keyManagementAlgorithm"     -> keyManagementAlgorithmToString(o.keyManagementAlgorithm),
      "contentEncryptionAlgorithm" -> contentEncryptionAlgorithmToString(o.contentEncryptionAlgorithm),
      "certId"                     -> o.certId,
      "source"                     -> o.source.asJson,
      "forward_location"           -> o.forwardLocation.asJson,
      "strict"                     -> o.strict,
      "metadata"                   -> o.payload
    )
  }

  private def keyManagementAlgorithmToString(value: JWEAlgorithm) = value match {
    case JWEAlgorithm.RSA_OAEP_256 => "RSA_OAEP_256"
    case JWEAlgorithm.RSA_OAEP_384 => "RSA_OAEP_384"
    case JWEAlgorithm.RSA_OAEP_512 => "RSA_OAEP_512"
  }

  private def contentEncryptionAlgorithmToString(value: EncryptionMethod) = value match {
    case EncryptionMethod.A128CBC_HS256 => "A128CBC_HS256"
    case EncryptionMethod.A192CBC_HS384 => "A192CBC_HS384"
    case EncryptionMethod.A256CBC_HS512 => "A256CBC_HS512"
    case EncryptionMethod.A128GCM       => "A128GCM"
    case EncryptionMethod.A192GCM       => "A192GCM"
    case EncryptionMethod.A256GCM       => "A256GCM"
  }

  private def keyManagementAlgorithmFromString(value: String) = value match {
    case "RSA_OAEP_256" => JWEAlgorithm.RSA_OAEP_256
    case "RSA_OAEP_384" => JWEAlgorithm.RSA_OAEP_384
    case "RSA_OAEP_512" => JWEAlgorithm.RSA_OAEP_512
  }

  private def contentEncryptionAlgorithmFromString(value: String) = value match {
    case "A128CBC_HS256" => EncryptionMethod.A128CBC_HS256
    case "A192CBC_HS384" => EncryptionMethod.A192CBC_HS384
    case "A256CBC_HS512" => EncryptionMethod.A256CBC_HS512
    case "A128GCM"       => EncryptionMethod.A128GCM
    case "A192GCM"       => EncryptionMethod.A192GCM
    case "A256GCM"       => EncryptionMethod.A256GCM
  }
}

class JweSigner extends NgAccessValidator with NgRequestTransformer {

  override def defaultConfigObject: Option[NgPluginConfig] = NgJweSignerConfig().some

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Transformations)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean      = true
  override def core: Boolean               = true
  override def usesCallbacks: Boolean      = false
  override def transformsRequest: Boolean  = true
  override def transformsResponse: Boolean = false
  override def transformsError: Boolean    = false

  override def isAccessAsync: Boolean            = true
  override def isTransformRequestAsync: Boolean  = true
  override def isTransformResponseAsync: Boolean = false
  override def name: String                      = "JWE signer"
  override def description: Option[String]       = "This plugin can only generate token".some

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config = ctx.cachedConfig(internalName)(NgJweSignerConfig.format).getOrElse(NgJweSignerConfig())

    config.certId match {
      case Some(certId) =>
        val cert = DynamicSSLEngineProvider.certificates
          .get(certId)
          .orElse {
            DynamicSSLEngineProvider.certificates.values.find(_.entityMetadata.get("nextCertificate").contains(certId))
          }

        cert match {
          case Some(cert) =>
            val keyPair     = cert.cryptoKeyPair
            val jsonKeypair = new RSAKey.Builder(keyPair.getPublic.asInstanceOf[RSAPublicKey])
              .keyID(cert.id)
              .build()
              .toJSONString
              .parseJson

            val alg = config.keyManagementAlgorithm
            val enc = config.contentEncryptionAlgorithm
            val kid = jsonKeypair.select("kid").asOpt[String].orNull

            val header = new JWEHeader(
              alg,
              enc,
              null,
              "JWT",
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              kid,
              null,
              null,
              null,
              null,
              null,
              0,
              null,
              null,
              null,
              null,
              null
            )

            val claimsSet = new JWTClaimsSet.Builder()
            claimsSet.issuer(env.Headers.OtoroshiIssuer)
            claimsSet.subject(env.Headers.OtoroshiIssuer)
            claimsSet.audience(ctx.route.name)
            claimsSet.expirationTime(DateTime.now().plus(30000).toDate)
            claimsSet.notBeforeTime(DateTime.now().toDate)
            claimsSet.jwtID(IdGenerator.uuid)

            val interpolatedToken = JwtExpressionLanguage
              .fromJson(
                config.payload.foldLeft(Json.obj()) { case (acc, item) => acc.deepMerge(Json.obj(item._1 -> item._2)) },
                Some(ctx.request),
                ctx.route.serviceDescriptor.some,
                None,
                ctx.apikey,
                ctx.user,
                Map.empty[String, String],
                attrs = ctx.attrs,
                env
              )
              .as[JsObject]

            interpolatedToken.value.foreach { case (key, value) =>
              val newValue = value match {
                case JsNull             => ""
                case boolean: JsBoolean => boolean
                case JsNumber(value)    => value
                case JsString(value)    => value
                case JsArray(value)     => value
                case o @ JsObject(_)    => Json.stringify(o)
              }
              claimsSet.claim(key, newValue)
            }

            val jwe      = new EncryptedJWT(header, claimsSet.build())
            jwe.encrypt(new RSAEncrypter(keyPair.getPublic.asInstanceOf[RSAPublicKey]));
            val jweToken = jwe.serialize()

            config.source match {
              case queryParam: InQueryParam =>
                val incomingUrl = ctx.otoroshiRequest.url
                val url         = ctx.otoroshiRequest.queryParams match {
                  case arr if arr.nonEmpty =>
                    val params      = arr ++ Map(queryParam.name -> jweToken)
                    val queryString = params.mkString("&")
                    s"${incomingUrl.split("\\?").head}?$queryString"
                  case _                   => s"$incomingUrl?${queryParam.name}=$jweToken"
                }
                Right(ctx.otoroshiRequest.copy(url = url)).future
              case InHeader(n, _)           =>
                Right(
                  ctx.otoroshiRequest.copy(headers = ctx.otoroshiRequest.headers ++ Map(n -> s"Bearer $jweToken"))
                ).future
              case InCookie(n)              =>
                Right(
                  ctx.otoroshiRequest.copy(cookies = ctx.otoroshiRequest.cookies ++ Seq(DefaultWSCookie(n, jweToken)))
                ).future
            }

          case None =>
            Results
              .BadRequest(Json.obj("error" -> "invalid configuration"))
              .left
              .future
        }

      case None =>
        Results
          .BadRequest(Json.obj("error" -> "invalid configuration"))
          .left
          .future
    }
  }
}

class JweExtractor extends NgAccessValidator with NgRequestTransformer {

  override def defaultConfigObject: Option[NgPluginConfig] = NgJweSignerConfig().some

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Transformations)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean      = true
  override def core: Boolean               = true
  override def usesCallbacks: Boolean      = false
  override def transformsRequest: Boolean  = true
  override def transformsResponse: Boolean = false
  override def transformsError: Boolean    = false

  override def isAccessAsync: Boolean            = true
  override def isTransformRequestAsync: Boolean  = true
  override def isTransformResponseAsync: Boolean = false
  override def name: String                      = "JWE extractor"
  override def description: Option[String]       = "This plugin validates and extracts the payload of JWE".some

  private def onError(message: String, config: NgJweSignerConfig, ctx: NgTransformerRequestContext) = {
    if (config.strict)
      Left(Results.Unauthorized(Json.obj("error" -> "invalid token"))).future
    else
      Right(ctx.otoroshiRequest).future
  }

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config = ctx.cachedConfig(internalName)(NgJweSignerConfig.format).getOrElse(NgJweSignerConfig())

    config.certId match {
      case None         => Right(ctx.otoroshiRequest).future
      case Some(certId) =>
        val cert = DynamicSSLEngineProvider.certificates
          .get(certId)
          .orElse {
            DynamicSSLEngineProvider.certificates.values.find(_.entityMetadata.get("nextCertificate").contains(certId))
          }

        cert match {
          case None       => Right(ctx.otoroshiRequest).future
          case Some(cert) =>
            val keyPair = cert.cryptoKeyPair

            val token: Option[String] = config.source match {
              case queryParam: InQueryParam => ctx.otoroshiRequest.queryParam(queryParam.name)
              case InHeader(n, _)           => ctx.otoroshiRequest.header(n).map(_.replaceAll("Bearer ", ""))
              case InCookie(n)              => ctx.otoroshiRequest.cookies.find(_.name == n).map(_.value)
            }

            token match {
              case Some(token) =>
                Try {
                  val test  = JWEObject.parse(token)
                  test.decrypt(new RSADecrypter(keyPair.getPrivate.asInstanceOf[RSAPrivateKey]))
                  val value = test.getPayload.toString

                  config.forwardLocation match {
                    case queryParam: InQueryParam =>
                      val incomingUrl = ctx.otoroshiRequest.url
                      val url         = ctx.otoroshiRequest.queryParams match {
                        case arr if arr.nonEmpty =>
                          val params      = arr ++ Map(queryParam.name -> value)
                          val queryString = params.mkString("&")
                          s"${incomingUrl.split("\\?").head}?$queryString"
                        case _                   => s"$incomingUrl?${queryParam.name}=$value"
                      }
                      Right(ctx.otoroshiRequest.copy(url = url)).future
                    case InHeader(n, _)           =>
                      Right(ctx.otoroshiRequest.copy(headers = ctx.otoroshiRequest.headers ++ Map(n -> value))).future
                    case InCookie(n)              =>
                      Right(
                        ctx.otoroshiRequest.copy(
                          cookies =
                            ctx.otoroshiRequest.cookies ++ Seq(DefaultWSCookie(n, URLEncoder.encode(value, "UTF-8")))
                        )
                      ).future
                  }
                } recover {
                  case e: IllegalStateException => onError(e.getMessage, config, ctx)
                  case e: JOSEException         => onError(e.getMessage, config, ctx)
                  case _                        => Left(Results.Unauthorized(Json.obj("error" -> "invalid token"))).future
                } get
              case None        => Left(Results.BadRequest(Json.obj("error" -> "something wrong happened"))).future
            }
        }
    }
  }
}

case class OIDCJwtVerifierConfig(
    mandatory: Boolean = true,
    ref: Option[String] = None,
    source: Option[JwtTokenLocation] = None,
    user: Boolean = false,
    customResponse: Boolean = false,
    customResponseStatus: Int = 401,
    customResponseHeaders: Map[String, String] = Map.empty,
    customResponseBody: String = Json.obj("error" -> "unauthorized").stringify
) extends NgPluginConfig {
  def json: JsValue = OIDCJwtVerifierConfig.format.writes(this)
  def asResult: Option[Result] = {
    if (customResponse) {
      val ctype          = customResponseHeaders.getIgnoreCase("Content-Type").getOrElse("application/json")
      val headersNoCtype = customResponseHeaders.filterNot(_._1.equalsIgnoreCase("content-type")).toSeq
      Some(Results.Status(customResponseStatus)(customResponseBody).withHeaders(headersNoCtype: _*).as(ctype))
    } else {
      None
    }
  }
}

object OIDCJwtVerifierConfig {
  val configFlow                     = Seq(
    "mandatory",
    "ref",
    "user",
    "custom_response",
    "custom_response_status",
    "custom_response_headers",
    "custom_response_body",
    "source"
  )
  val configSchema: Option[JsObject] = Some(
    Json.obj(
      "mandatory"               -> Json.obj(
        "type"  -> "bool",
        "label" -> "Mandatory"
      ),
      "user"                    -> Json.obj(
        "type"  -> "bool",
        "label" -> "Use as connected user"
      ),
      "custom_response"         -> Json.obj(
        "type"  -> "bool",
        "label" -> "Custom error"
      ),
      "custom_response_status"  -> Json.obj(
        "type"  -> "number",
        "label" -> "Custom error status"
      ),
      "custom_response_headers" -> Json.obj(
        "type"  -> "object",
        "label" -> "Custom error headers"
      ),
      "custom_response_body"    -> Json.obj(
        "type"  -> "code",
        "label" -> "Custom error body",
        "props" -> Json.obj("editorOnly" -> true)
      ),
      "source"                  -> Json.obj(
        "type"  -> "any",
        "label" -> "JWT Source",
        "props" -> Json.obj("height" -> 200)
      ),
      "ref"                     -> Json.obj(
        "type"  -> "select",
        "label" -> s"Auth. module",
        "props" -> Json.obj(
          "optionsFrom"        -> "/bo/api/proxy/apis/security.otoroshi.io/v1/auth-modules",
          "optionsTransformer" -> Json.obj(
            "label" -> "name",
            "value" -> "id"
          )
        )
      )
    )
  )
  val format                         = new Format[OIDCJwtVerifierConfig] {
    override def reads(json: JsValue): JsResult[OIDCJwtVerifierConfig] = Try {
      OIDCJwtVerifierConfig(
        mandatory = json.select("mandatory").asOptBoolean.getOrElse(true),
        ref = json.select("ref").asOpt[String],
        user = json.select("user").asOptBoolean.getOrElse(false),
        source = json.select("source").asOpt[JsObject].flatMap(o => JwtTokenLocation.fromJson(o).toOption),
        customResponse = json.select("custom_response").asOpt[Boolean].getOrElse(false),
        customResponseStatus = json.select("custom_response_status").asOpt[Int].getOrElse(401),
        customResponseHeaders = json.select("custom_response_headers").asOpt[Map[String, String]].getOrElse(Map.empty),
        customResponseBody =
          json.select("custom_response_body").asOpt[String].getOrElse(Json.obj("error" -> "unauthorized").stringify)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: OIDCJwtVerifierConfig): JsValue             = Json.obj(
      "mandatory"               -> o.mandatory,
      "ref"                     -> o.ref.map(_.json).getOrElse(JsNull).asValue,
      "source"                  -> o.source.map(_.asJson).getOrElse(JsNull).asValue,
      "custom_response"         -> o.customResponse,
      "custom_response_status"  -> o.customResponseStatus,
      "custom_response_headers" -> o.customResponseHeaders,
      "custom_response_body"    -> o.customResponseBody
    )
  }
}

class OIDCJwtVerifier extends NgAccessValidator {

  override def defaultConfigObject: Option[NgPluginConfig] = OIDCJwtVerifierConfig().some
  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.AccessControl, NgPluginCategory.Classic)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def isAccessAsync: Boolean                      = true
  override def name: String                                = "OIDC JWT verification"
  override def description: Option[String]                 =
    "This plugin verifies the current request jwt token against OIDC JWT verification settings living in an OIDC auth. module".some

  override def noJsForm: Boolean              = true
  override def configFlow: Seq[String]        = OIDCJwtVerifierConfig.configFlow
  override def configSchema: Option[JsObject] = OIDCJwtVerifierConfig.configSchema

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx.cachedConfig(internalName)(OIDCJwtVerifierConfig.format).getOrElse(OIDCJwtVerifierConfig())
    config.ref match {
      case None               => NgAccess.NgDenied(Results.BadRequest(Json.obj("error" -> "no auth. module setup"))).vfuture
      case Some(authModuleId) =>
        env.proxyState.authModule(authModuleId) match {
          case None    => NgAccess.NgDenied(Results.BadRequest(Json.obj("error" -> "auth. module not found"))).vfuture
          case Some(m) =>
            m match {
              case oidcModule: OAuth2ModuleConfig if oidcModule.jwtVerifier.isDefined => {
                val customResult = config.asResult
                val verifier     = LocalJwtVerifier()
                  .copy(
                    enabled = true,
                    algoSettings = oidcModule.jwtVerifier.get
                  )
                val sources      = config.source
                  .map(s => Seq(s))
                  .getOrElse(Seq(InHeader("Authorization", "Bearer "), InQueryParam("access_token")))
                sources.iterator.map(s => s.token(ctx.request).map(t => (s, t))).collectFirst { case Some(tuple) =>
                  tuple
                } match {
                  case None if !config.mandatory => NgAccess.NgAllowed.vfuture
                  case None if config.mandatory  =>
                    NgAccess
                      .NgDenied(customResult.getOrElse(Results.BadRequest(Json.obj("error" -> "token not found"))))
                      .vfuture
                  case Some((source, token))     =>
                    verifier
                      .copy(source = source)
                      .verifyGen[NgAccess](
                        ctx.request,
                        ctx.route.legacy,
                        ctx.apikey,
                        ctx.user,
                        ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
                        ctx.attrs
                      ) { _ =>
                        if (config.user) {
                          OIDCAuthToken.getSession(
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
                        } else {
                          NgAccess.NgAllowed.rightf
                        }
                      }
                      .map {
                        case Left(result) if !config.mandatory => NgAccess.NgAllowed
                        case Left(result) if config.mandatory  => NgAccess.NgDenied(customResult.getOrElse(result))
                        case Right(r)                          => r
                      }
                }
              }
              case _                                                                  =>
                if (!config.mandatory)
                  NgAccess.NgAllowed.vfuture
                else
                  NgAccess
                    .NgDenied(
                      Results.BadRequest(
                        Json.obj(
                          "error" -> "auth. module not an oidc module or does not have jwt verification settings"
                        )
                      )
                    )
                    .vfuture
            }
        }
    }
  }
}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// OAuth2 Token Exchange (RFC 8693)
///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

case class OAuth2TokenExchangeConfig(
    ref: Option[String] = None,
    source: Option[JwtTokenLocation] = None,
    mandatory: Boolean = true,
    exchange: OAuth2TokenExchangeParams = OAuth2TokenExchangeParams(),
    clientCredentialsOverride: Option[OAuth2ClientCredentialsOverride] = None,
    cacheTtlMs: Long = 0,
    callTimeoutMs: Long = 10000,
    customResponse: Boolean = false,
    customResponseStatus: Int = 401,
    customResponseHeaders: Map[String, String] = Map.empty,
    customResponseBody: String = Json.obj("error" -> "unauthorized").stringify
) extends NgPluginConfig {
  def json: JsValue = OAuth2TokenExchangeConfig.format.writes(this)
  def asResult: Option[Result] = {
    if (customResponse) {
      val ctype          = customResponseHeaders.getIgnoreCase("Content-Type").getOrElse("application/json")
      val headersNoCtype = customResponseHeaders.filterNot(_._1.equalsIgnoreCase("content-type")).toSeq
      Some(Results.Status(customResponseStatus)(customResponseBody).withHeaders(headersNoCtype: _*).as(ctype))
    } else {
      None
    }
  }
}

case class OAuth2TokenExchangeParams(
    audience: Option[String] = None,
    resource: Option[String] = None,
    scope: Option[String] = None,
    requestedTokenType: String = "urn:ietf:params:oauth:token-type:access_token",
    actorToken: Option[String] = None,
    actorTokenType: String = "urn:ietf:params:oauth:token-type:access_token"
) {
  def json: JsValue = OAuth2TokenExchangeParams.format.writes(this)
}

object OAuth2TokenExchangeParams {
  val format = new Format[OAuth2TokenExchangeParams] {
    override def reads(json: JsValue): JsResult[OAuth2TokenExchangeParams] = Try {
      OAuth2TokenExchangeParams(
        audience = json.select("audience").asOpt[String],
        resource = json.select("resource").asOpt[String],
        scope = json.select("scope").asOpt[String],
        requestedTokenType = json
          .select("requested_token_type")
          .asOpt[String]
          .getOrElse("urn:ietf:params:oauth:token-type:access_token"),
        actorToken = json.select("actor_token").asOpt[String],
        actorTokenType = json
          .select("actor_token_type")
          .asOpt[String]
          .getOrElse("urn:ietf:params:oauth:token-type:access_token")
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: OAuth2TokenExchangeParams): JsValue             = Json.obj(
      "audience"             -> o.audience.map(JsString(_)).getOrElse(JsNull).asValue,
      "resource"             -> o.resource.map(JsString(_)).getOrElse(JsNull).asValue,
      "scope"                -> o.scope.map(JsString(_)).getOrElse(JsNull).asValue,
      "requested_token_type" -> o.requestedTokenType,
      "actor_token"          -> o.actorToken.map(JsString(_)).getOrElse(JsNull).asValue,
      "actor_token_type"     -> o.actorTokenType
    )
  }
}

case class OAuth2ClientCredentialsOverride(
    clientId: String,
    clientSecret: String
) {
  def json: JsValue = OAuth2ClientCredentialsOverride.format.writes(this)
}

object OAuth2ClientCredentialsOverride {
  val format = new Format[OAuth2ClientCredentialsOverride] {
    override def reads(json: JsValue): JsResult[OAuth2ClientCredentialsOverride] = Try {
      OAuth2ClientCredentialsOverride(
        clientId = json.select("client_id").as[String],
        clientSecret = json.select("client_secret").as[String]
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: OAuth2ClientCredentialsOverride): JsValue             = Json.obj(
      "client_id"     -> o.clientId,
      "client_secret" -> o.clientSecret
    )
  }
}

object OAuth2TokenExchangeConfig {
  val configFlow: Seq[String] = Seq(
    "ref",
    "mandatory",
    "source",
    ">>>Exchange settings",
    "exchange.audience",
    "exchange.resource",
    "exchange.scope",
    "exchange.requested_token_type",
    "exchange.actor_token",
    "exchange.actor_token_type",
    ">>>Client credentials override",
    "client_credentials_override.client_id",
    "client_credentials_override.client_secret",
    ">>>Cache",
    "cache_ttl_ms",
    ">>>Timeouts",
    "call_timeout_ms",
    ">>>Custom error response",
    "custom_response",
    "custom_response_status",
    "custom_response_headers",
    "custom_response_body"
  )

  val configSchema: Option[JsObject] = Some(
    Json.obj(
      "ref"                                    -> Json.obj(
        "type"  -> "select",
        "label" -> s"Auth. module",
        "props" -> Json.obj(
          "optionsFrom"        -> "/bo/api/proxy/apis/security.otoroshi.io/v1/auth-modules",
          "optionsTransformer" -> Json.obj(
            "label" -> "name",
            "value" -> "id"
          )
        )
      ),
      "mandatory"                              -> Json.obj(
        "type"  -> "bool",
        "label" -> "Mandatory"
      ),
      "source"                                 -> Json.obj(
        "type"  -> "any",
        "label" -> "JWT Source",
        "props" -> Json.obj("height" -> 200)
      ),
      "exchange.audience"                      -> Json.obj(
        "type"  -> "string",
        "label" -> "Target audience"
      ),
      "exchange.resource"                      -> Json.obj(
        "type"  -> "string",
        "label" -> "Resource"
      ),
      "exchange.scope"                         -> Json.obj(
        "type"  -> "string",
        "label" -> "Scope"
      ),
      "exchange.requested_token_type"          -> Json.obj(
        "type"  -> "string",
        "label" -> "Requested token type"
      ),
      "exchange.actor_token"                   -> Json.obj(
        "type"  -> "string",
        "label" -> "Actor token"
      ),
      "exchange.actor_token_type"              -> Json.obj(
        "type"  -> "string",
        "label" -> "Actor token type"
      ),
      "client_credentials_override.client_id"  -> Json.obj(
        "type"  -> "string",
        "label" -> "Client ID override"
      ),
      "client_credentials_override.client_secret" -> Json.obj(
        "type"  -> "string",
        "label" -> "Client secret override"
      ),
      "cache_ttl_ms"                           -> Json.obj(
        "type"  -> "number",
        "label" -> "Cache TTL (ms, 0 to disable)"
      ),
      "call_timeout_ms"                        -> Json.obj(
        "type"  -> "number",
        "label" -> "Call timeout (ms)"
      ),
      "custom_response"                        -> Json.obj(
        "type"  -> "bool",
        "label" -> "Custom error"
      ),
      "custom_response_status"                 -> Json.obj(
        "type"  -> "number",
        "label" -> "Custom error status"
      ),
      "custom_response_headers"                -> Json.obj(
        "type"  -> "object",
        "label" -> "Custom error headers"
      ),
      "custom_response_body"                   -> Json.obj(
        "type"  -> "code",
        "label" -> "Custom error body",
        "props" -> Json.obj("editorOnly" -> true)
      )
    )
  )

  val format = new Format[OAuth2TokenExchangeConfig] {
    override def reads(json: JsValue): JsResult[OAuth2TokenExchangeConfig] = Try {
      OAuth2TokenExchangeConfig(
        ref = json.select("ref").asOpt[String],
        source = json.select("source").asOpt[JsObject].flatMap(o => JwtTokenLocation.fromJson(o).toOption),
        mandatory = json.select("mandatory").asOptBoolean.getOrElse(true),
        exchange = json
          .select("exchange")
          .asOpt[JsObject]
          .flatMap(o => OAuth2TokenExchangeParams.format.reads(o).asOpt)
          .getOrElse(OAuth2TokenExchangeParams()),
        clientCredentialsOverride = json
          .select("client_credentials_override")
          .asOpt[JsObject]
          .flatMap(o => OAuth2ClientCredentialsOverride.format.reads(o).asOpt),
        cacheTtlMs = json.select("cache_ttl_ms").asOpt[Long].getOrElse(0L),
        callTimeoutMs = json.select("call_timeout_ms").asOpt[Long].getOrElse(10000L),
        customResponse = json.select("custom_response").asOpt[Boolean].getOrElse(false),
        customResponseStatus = json.select("custom_response_status").asOpt[Int].getOrElse(401),
        customResponseHeaders =
          json.select("custom_response_headers").asOpt[Map[String, String]].getOrElse(Map.empty),
        customResponseBody =
          json.select("custom_response_body").asOpt[String].getOrElse(Json.obj("error" -> "unauthorized").stringify)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: OAuth2TokenExchangeConfig): JsValue             = Json.obj(
      "ref"                         -> o.ref.map(_.json).getOrElse(JsNull).asValue,
      "source"                      -> o.source.map(_.asJson).getOrElse(JsNull).asValue,
      "mandatory"                   -> o.mandatory,
      "exchange"                    -> o.exchange.json,
      "client_credentials_override" -> o.clientCredentialsOverride.map(_.json).getOrElse(JsNull).asValue,
      "cache_ttl_ms"                -> o.cacheTtlMs,
      "call_timeout_ms"             -> o.callTimeoutMs,
      "custom_response"             -> o.customResponse,
      "custom_response_status"      -> o.customResponseStatus,
      "custom_response_headers"     -> o.customResponseHeaders,
      "custom_response_body"        -> o.customResponseBody
    )
  }
}

object OAuth2TokenExchange {
  lazy val logger = play.api.Logger("otoroshi-plugin-oauth2-token-exchange")
  val exchangeCache = Scaffeine()
    .maximumSize(1000)
    .build[String, (String, Long)]()
}

class OAuth2TokenExchange extends NgAccessValidator with NgRequestTransformer {

  import com.github.blemale.scaffeine.Scaffeine
  import otoroshi.utils.http.Implicits._
  import play.api.libs.ws.DefaultBodyWritables.writeableOf_urlEncodedSimpleForm

  override def defaultConfigObject: Option[NgPluginConfig] = OAuth2TokenExchangeConfig().some
  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess, NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory]           =
    Seq(NgPluginCategory.AccessControl, NgPluginCategory.Transformations)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = true
  override def transformsResponse: Boolean                 = false
  override def transformsError: Boolean                    = false
  override def isAccessAsync: Boolean                      = true
  override def isTransformRequestAsync: Boolean            = true
  override def name: String                                = "OAuth2 token exchange"
  override def description: Option[String]                 =
    "This plugin performs an OAuth 2.0 Token Exchange (RFC 8693) using an OIDC auth. module configuration, validates the incoming token, exchanges it with the IdP, and forwards the exchanged token upstream".some

  override def noJsForm: Boolean              = true
  override def configFlow: Seq[String]        = OAuth2TokenExchangeConfig.configFlow
  override def configSchema: Option[JsObject] = OAuth2TokenExchangeConfig.configSchema

  private val ExchangedTokenKey = TypedKey[String]("otoroshi.next.plugins.OAuth2TokenExchange.ExchangedToken")

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx
      .cachedConfig(internalName)(OAuth2TokenExchangeConfig.format)
      .getOrElse(OAuth2TokenExchangeConfig())
    config.ref match {
      case None               =>
        NgAccess.NgDenied(Results.BadRequest(Json.obj("error" -> "no auth. module setup"))).vfuture
      case Some(authModuleId) =>
        env.proxyState.authModule(authModuleId) match {
          case None    =>
            NgAccess.NgDenied(Results.BadRequest(Json.obj("error" -> "auth. module not found"))).vfuture
          case Some(m) =>
            m match {
              case oidcModule: OAuth2ModuleConfig if oidcModule.jwtVerifier.isDefined => {
                val customResult = config.asResult
                val verifier     = LocalJwtVerifier()
                  .copy(
                    enabled = true,
                    algoSettings = oidcModule.jwtVerifier.get
                  )
                val sources      = config.source
                  .map(s => Seq(s))
                  .getOrElse(Seq(InHeader("Authorization", "Bearer "), InQueryParam("access_token")))
                sources.iterator.map(s => s.token(ctx.request).map(t => (s, t))).collectFirst { case Some(tuple) =>
                  tuple
                } match {
                  case None if !config.mandatory => NgAccess.NgAllowed.vfuture
                  case None if config.mandatory  =>
                    NgAccess
                      .NgDenied(
                        customResult.getOrElse(Results.Unauthorized(Json.obj("error" -> "token not found")))
                      )
                      .vfuture
                  case Some((source, token))     =>
                    verifier
                      .copy(source = source)
                      .verifyGen[NgAccess](
                        ctx.request,
                        ctx.route.legacy,
                        ctx.apikey,
                        ctx.user,
                        ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
                        ctx.attrs
                      ) { _ =>
                        performTokenExchange(token, config, oidcModule, ctx.attrs).map {
                          case Left(result) => Left(result)
                          case Right(_)     => Right(NgAccess.NgAllowed)
                        }
                      }
                      .map {
                        case Left(result) if !config.mandatory => NgAccess.NgAllowed
                        case Left(result) if config.mandatory  => NgAccess.NgDenied(customResult.getOrElse(result))
                        case Right(r)                          => r
                      }
                }
              }
              case _: OAuth2ModuleConfig => {
                // no jwtVerifier: treat token as opaque, skip local validation, go straight to exchange
                val customResult = config.asResult
                val sources      = config.source
                  .map(s => Seq(s))
                  .getOrElse(Seq(InHeader("Authorization", "Bearer "), InQueryParam("access_token")))
                sources.iterator.map(s => s.token(ctx.request).map(t => (s, t))).collectFirst { case Some(tuple) =>
                  tuple
                } match {
                  case None if !config.mandatory => NgAccess.NgAllowed.vfuture
                  case None if config.mandatory  =>
                    NgAccess
                      .NgDenied(
                        customResult.getOrElse(Results.Unauthorized(Json.obj("error" -> "token not found")))
                      )
                      .vfuture
                  case Some((_, token))          =>
                    performTokenExchange(token, config, m.asInstanceOf[OAuth2ModuleConfig], ctx.attrs).map {
                      case Left(result) if !config.mandatory => NgAccess.NgAllowed
                      case Left(result) if config.mandatory  => NgAccess.NgDenied(customResult.getOrElse(result))
                      case Right(_)                          => NgAccess.NgAllowed
                    }
                }
              }
              case _                     =>
                if (!config.mandatory) NgAccess.NgAllowed.vfuture
                else
                  NgAccess
                    .NgDenied(
                      Results.BadRequest(Json.obj("error" -> "auth. module is not an OAuth2/OIDC module"))
                    )
                    .vfuture
            }
        }
    }
  }

  private def performTokenExchange(
      subjectToken: String,
      config: OAuth2TokenExchangeConfig,
      oidcModule: OAuth2ModuleConfig,
      attrs: TypedMap
  )(implicit env: Env, ec: ExecutionContext): Future[Either[Result, Unit]] = {
    val cacheKey  = s"${subjectToken.sha256}-${config.exchange.audience.getOrElse("")}-${config.exchange.scope.getOrElse("")}"
    val cacheTtl  = config.cacheTtlMs
    val now       = System.currentTimeMillis()
    val fromCache = if (cacheTtl > 0) {
      OAuth2TokenExchange.exchangeCache.getIfPresent(cacheKey).flatMap { case (cachedToken, expiresAt) =>
        if (expiresAt > now) Some(cachedToken) else { OAuth2TokenExchange.exchangeCache.invalidate(cacheKey); None }
      }
    } else None

    fromCache match {
      case Some(cachedToken) =>
        attrs.put(ExchangedTokenKey -> cachedToken)
        Right(()).vfuture
      case None              =>
        val clientId     = config.clientCredentialsOverride.map(_.clientId).getOrElse(oidcModule.clientId)
        val clientSecret = config.clientCredentialsOverride.map(_.clientSecret).getOrElse(oidcModule.clientSecret)

        val params = Map(
          "grant_type"         -> "urn:ietf:params:oauth:grant-type:token-exchange",
          "subject_token"      -> subjectToken,
          "subject_token_type" -> "urn:ietf:params:oauth:token-type:access_token",
          "client_id"          -> clientId,
          "client_secret"      -> clientSecret
        ) ++
          config.exchange.audience.map("audience"             -> _) ++
          config.exchange.resource.map("resource"             -> _) ++
          config.exchange.scope.map("scope"                   -> _) ++
          Some("requested_token_type" -> config.exchange.requestedTokenType) ++
          config.exchange.actorToken.map("actor_token" -> _) ++
          config.exchange.actorToken.map(_ => "actor_token_type" -> config.exchange.actorTokenType)

        val timeout = scala.concurrent.duration.Duration(config.callTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)

        val builder = env.MtlsWs
          .url(oidcModule.tokenUrl, oidcModule.mtlsConfig)
          .withMaybeProxyServer(oidcModule.proxy.orElse(env.datastores.globalConfigDataStore.latestSafe.flatMap(_.proxies.auth)))
          .withRequestTimeout(timeout)

        builder
          .post(params)(writeableOf_urlEncodedSimpleForm)
          .map { response =>
            if (response.status == 200) {
              val json          = response.json
              val exchangedToken = json
                .select(oidcModule.accessTokenField)
                .asOpt[String]
                .getOrElse(json.select("access_token").as[String])
              if (cacheTtl > 0) {
                val expiresIn = json.select("expires_in").asOpt[Long].map(_ * 1000).getOrElse(cacheTtl)
                val effectiveTtl = Math.min(expiresIn, cacheTtl)
                OAuth2TokenExchange.exchangeCache.put(cacheKey, (exchangedToken, now + effectiveTtl))
              }
              attrs.put(ExchangedTokenKey -> exchangedToken)
              Right(())
            } else {
              OAuth2TokenExchange.logger.error(
                s"token exchange failed with status ${response.status}: ${response.body}"
              )
              Left(
                Results.BadGateway(
                  Json.obj(
                    "error"            -> "token exchange failed",
                    "exchange_status"  -> response.status
                  )
                )
              )
            }
          }
          .recover { case e: Throwable =>
            OAuth2TokenExchange.logger.error("token exchange call failed", e)
            Left(
              Results.BadGateway(
                Json.obj("error" -> "token exchange call failed", "message" -> e.getMessage)
              )
            )
          }
    }
  }

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    ctx.attrs.get(ExchangedTokenKey) match {
      case Some(exchangedToken) =>
        Right(
          ctx.otoroshiRequest.copy(
            headers = ctx.otoroshiRequest.headers
              .filterNot(_._1.equalsIgnoreCase("Authorization")) + ("Authorization" -> s"Bearer $exchangedToken")
          )
        ).vfuture
      case None                 =>
        Right(ctx.otoroshiRequest).vfuture
    }
  }
}
