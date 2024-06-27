package otoroshi.next.plugins

import akka.stream.Materializer
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.nimbusds.jose.crypto.{AESEncrypter, RSADecrypter, RSAEncrypter}
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.{EncryptionMethod, JOSEException, JWEAlgorithm, JWEHeader, JWEObject, Payload}
import com.nimbusds.jwt.{EncryptedJWT, JWTClaimsSet}
import otoroshi.env.Env
import otoroshi.models.{DefaultToken, InCookie, InHeader, InQueryParam, JwtTokenLocation, OutputMode, RefJwtVerifier}
import otoroshi.next.plugins.Keys.JwtInjectionKey
import otoroshi.next.plugins.api._
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.{BetterJsValue, BetterString, BetterSyntax}
import play.api.libs.json._
import play.api.libs.ws.DefaultWSCookie
import play.api.mvc.{Result, Results}
import org.apache.commons.codec.binary.{Base64 => ApacheBase64}
import org.joda.time.DateTime
import otoroshi.el.JwtExpressionLanguage
import otoroshi.ssl.DynamicSSLEngineProvider
import otoroshi.ssl.pki.models.GenKeyPairQuery

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.util.{Date, UUID}
import javax.crypto.{Cipher, KeyGenerator}
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
    val config = ctx.cachedConfig(internalName)(NgJwtVerificationConfig.format).getOrElse(NgJwtVerificationConfig())

    config.verifiers match {
      case Nil         => JwtVerifierUtils.onError()
      case verifierIds => JwtVerifierUtils.verify(ctx, verifierIds)
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

  def verify(ctx: NgAccessContext, verifierIds: Seq[String])(implicit
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
          case Left(result)     => onError(result.some)
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
        case Left(result)     => onError(result.some)
        case Right(injection) =>
          ctx.attrs.put(JwtInjectionKey -> injection)
          NgAccess.NgAllowed.vfuture
      }
    }
  }
}

case class NgJwtVerificationOnlyConfig(verifier: Option[String] = None, failIfAbsent: Boolean = true)
    extends NgPluginConfig {
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
      "verifier"       -> o.verifier,
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
              case _                            => JwtVerifierUtils.verify(ctx, Seq(verifierId))
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
  strict: Boolean = false
) extends NgPluginConfig {
  def json: JsValue = NgJweSignerConfig.format.writes(this)
}

object NgJweSignerConfig {
  val format = new Format[NgJweSignerConfig] {
    override def reads(json: JsValue): JsResult[NgJweSignerConfig] = Try {
      NgJweSignerConfig(
        keyManagementAlgorithm = json.select("key_management_algorithm").asOpt[String]
          .map(keyManagementAlgorithmFromString).getOrElse(JWEAlgorithm.RSA_OAEP_256),
        contentEncryptionAlgorithm = json.select("content_encryption_algorithm").asOpt[String]
        .map(contentEncryptionAlgorithmFromString).getOrElse(EncryptionMethod.A128CBC_HS256),
        certId = json.select("certId").asOpt[String],
        source = JwtTokenLocation.fromJson((json \ "source").as[JsValue]).getOrElse(InHeader("X-JWT-Token")),
        forwardLocation = JwtTokenLocation.fromJson((json \ "forward_location").as[JsValue]).getOrElse(InHeader("X-JWT-Token")),
        strict = json.select("strict").asOpt[Boolean].getOrElse(false)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: NgJweSignerConfig): JsValue             = Json.obj(
      "keyManagementAlgorithm" -> keyManagementAlgorithmToString(o.keyManagementAlgorithm),
      "contentEncryptionAlgorithm" -> contentEncryptionAlgorithmToString(o.contentEncryptionAlgorithm),
      "certId" -> o.certId,
      "source" -> o.source.asJson,
      "forward_location" -> o.forwardLocation.asJson,
      "strict" -> o.strict
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
    case EncryptionMethod.A128GCM => "A128GCM"
    case EncryptionMethod.A192GCM => "A192GCM"
    case EncryptionMethod.A256GCM => "A256GCM"
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
    case "A128GCM" => EncryptionMethod.A128GCM
    case "A192GCM" => EncryptionMethod.A192GCM
    case "A256GCM" => EncryptionMethod.A256GCM
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
            val keyPair = cert.cryptoKeyPair
            val jsonKeypair = new RSAKey.Builder(keyPair.getPublic.asInstanceOf[RSAPublicKey])
              .keyID(cert.id)
              .build()
              .toJSONString
              .parseJson

            val alg = config.keyManagementAlgorithm
            val enc = config.contentEncryptionAlgorithm
            val kid = jsonKeypair.select("kid").asOpt[String].orNull

            val header = new JWEHeader(alg, enc, null, null, null, null, null, null, null, null, null, kid,
                null, null, null, null, null, 0, null, null, null, null, null)

            val claimsSet = new JWTClaimsSet.Builder()
            claimsSet.issuer(env.Headers.OtoroshiIssuer)
            claimsSet.subject(env.Headers.OtoroshiIssuer)
            claimsSet.audience(ctx.route.name)
            claimsSet.expirationTime(DateTime.now().plus(30000).toDate)
            claimsSet.notBeforeTime(DateTime.now().toDate)
            claimsSet.jwtID(IdGenerator.uuid)

            val jwe = new EncryptedJWT(header, claimsSet.build())
            jwe.encrypt(new RSAEncrypter(keyPair.getPublic.asInstanceOf[RSAPublicKey]));
            val jweToken = jwe.serialize()

            config.source match {
              case queryParam: InQueryParam =>
                  val incomingUrl = ctx.otoroshiRequest.url
                  val url = ctx.otoroshiRequest.queryParams match {
                    case arr if arr.nonEmpty  =>
                      val params = arr ++ Map(queryParam.name -> jweToken)
                      val queryString = params.mkString("&")
                      s"${incomingUrl.split("\\?").head}?$queryString"
                    case _                    => s"$incomingUrl?${queryParam.name}=$jweToken"
                  }
                  Right(ctx.otoroshiRequest.copy(url = url))
                  .future
                case InHeader(n, _)  =>
                  Right(ctx.otoroshiRequest.copy(
                    headers = ctx.otoroshiRequest.headers ++ Map(n -> s"Bearer $jweToken")))
                  .future
                case InCookie(n)     =>
                  Right(ctx.otoroshiRequest.copy(
                    cookies = ctx.otoroshiRequest.cookies ++ Seq(DefaultWSCookie(n, jweToken))))
                  .future
              }


          case None =>  Results
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
    if(config.strict)
      Left(Results.Unauthorized(Json.obj("error" -> "invalid token"))).future
    else
      Right(ctx.otoroshiRequest).future
  }

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config = ctx.cachedConfig(internalName)(NgJweSignerConfig.format).getOrElse(NgJweSignerConfig())

    config.certId match {
      case None => Right(ctx.otoroshiRequest).future
      case Some(certId) =>
        val cert = DynamicSSLEngineProvider.certificates
            .get(certId)
            .orElse {
              DynamicSSLEngineProvider.certificates.values.find(_.entityMetadata.get("nextCertificate").contains(certId))
            }

        cert match {
          case None =>  Right(ctx.otoroshiRequest).future
          case Some(cert) =>
            val keyPair = cert.cryptoKeyPair

            val token: Option[String] = config.source match {
              case queryParam: InQueryParam => ctx.otoroshiRequest.queryParam(queryParam.name)
                case InHeader(n, _)  => ctx.otoroshiRequest.header(n).map(_.replaceAll("Bearer ", ""))
                case InCookie(n)     => ctx.otoroshiRequest.cookies.find(_.name == n).map(_.value)
              }

            token match {
              case Some(token) =>
                Try {
                  val test = JWEObject.parse(token)
                  test.decrypt(new RSADecrypter(keyPair.getPrivate.asInstanceOf[RSAPrivateKey]))
                  val value = test.getPayload.toString

                  config.forwardLocation match {
                    case queryParam: InQueryParam =>
                        val incomingUrl = ctx.otoroshiRequest.url
                        val url = ctx.otoroshiRequest.queryParams match {
                          case arr if arr.nonEmpty  =>
                            val params = arr ++ Map(queryParam.name -> value)
                            val queryString = params.mkString("&")
                            s"${incomingUrl.split("\\?").head}?$queryString"
                          case _                    => s"$incomingUrl?${queryParam.name}=$value"
                        }
                        Right(ctx.otoroshiRequest.copy(url = url))
                        .future
                      case InHeader(n, _)  =>
                        Right(ctx.otoroshiRequest.copy(
                          headers = ctx.otoroshiRequest.headers ++ Map(n -> value)))
                        .future
                      case InCookie(n)     =>
                        Right(ctx.otoroshiRequest.copy(
                          cookies = ctx.otoroshiRequest.cookies ++ Seq(DefaultWSCookie(n, URLEncoder.encode(value, "UTF-8")))))
                        .future
                    }
                } recover  {
                  case e: IllegalStateException => onError(e.getMessage, config, ctx)
                  case e: JOSEException => onError(e.getMessage, config, ctx)
                  case _ => Left(Results.Unauthorized(Json.obj("error" -> "invalid token"))).future
                } get
              case None => Left(Results.BadRequest(Json.obj("error" -> "something wrong happened"))).future
            }
        }
    }
  }
}