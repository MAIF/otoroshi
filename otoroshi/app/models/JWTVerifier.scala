package models

import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.interfaces.{ECPrivateKey, ECPublicKey, RSAPrivateKey, RSAPublicKey}
import java.security.spec.{PKCS8EncodedKeySpec, X509EncodedKeySpec}

import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.Flow
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.Verification
import env.Env
import gateway.Errors
import org.apache.commons.codec.binary.{Base64 => ApacheBase64}
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{RequestHeader, Result, Results}
import play.api.http.websocket.{Message => PlayWSMessage}
import storage.BasicStore

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

trait AsJson {
  def asJson: JsValue
}

trait FromJson[A] {
  def fromJson(json: JsValue): Either[Throwable, A]
}

case class JwtInjection(
    additionalHeaders: Map[String, String] = Map.empty,
    removeHeaders: Seq[String] = Seq.empty,
    additionalCookies: Map[String, String] = Map.empty,
    removeCookies: Seq[String] = Seq.empty,
) extends AsJson {
  def asJson: JsValue = Json.obj(
    "additionalHeaders" -> JsObject(this.additionalHeaders.mapValues(JsString.apply)),
    "removeHeaders"     -> JsArray(this.removeHeaders.map(JsString.apply)),
    "additionalCookies" -> JsObject(this.additionalCookies.mapValues(JsString.apply)),
    "removeCookies"     -> JsArray(this.removeCookies.map(JsString.apply))
  )
}

object JwtInjection extends FromJson[JwtInjection] {
  override def fromJson(json: JsValue) = ???
}

sealed trait JwtTokenLocation extends AsJson {
  def token(request: RequestHeader): Option[String]
  def asJwtInjection(newToken: String): JwtInjection
}
object JwtTokenLocation extends FromJson[JwtTokenLocation] {
  override def fromJson(json: JsValue): Either[Throwable, JwtTokenLocation] =
    Try {
      (json \ "type").as[String] match {
        case "InQueryParam" => InQueryParam.fromJson(json)
        case "InHeader"     => InHeader.fromJson(json)
        case "InCookie"     => InCookie.fromJson(json)
      }
    } recover {
      case e => Left(e)
    } get
}
object InQueryParam extends FromJson[InQueryParam] {
  override def fromJson(json: JsValue): Either[Throwable, InQueryParam] =
    Try {
      Right(
        InQueryParam(
          (json \ "name").as[String]
        )
      )
    } recover {
      case e => Left(e)
    } get
}
case class InQueryParam(name: String) extends JwtTokenLocation {
  def token(request: RequestHeader): Option[String]  = request.getQueryString(name)
  def asJwtInjection(newToken: String): JwtInjection = JwtInjection()
  override def asJson                                = Json.obj("type" -> "InQueryParam", "name" -> this.name)
}
object InHeader extends FromJson[InHeader] {
  override def fromJson(json: JsValue): Either[Throwable, InHeader] =
    Try {
      Right(
        InHeader(
          (json \ "name").as[String],
          (json \ "remove").as[String]
        )
      )
    } recover {
      case e => Left(e)
    } get
}
case class InHeader(name: String, remove: String = "") extends JwtTokenLocation {
  def token(request: RequestHeader): Option[String] = {
    request.headers.get(name).map { h =>
      h.replaceAll(remove, "")
    }
  }
  def asJwtInjection(newToken: String): JwtInjection = JwtInjection(additionalHeaders = Map(name -> newToken))
  override def asJson                                = Json.obj("type" -> "InHeader", "name" -> this.name, "remove" -> this.remove)
}
object InCookie extends FromJson[InCookie] {
  override def fromJson(json: JsValue): Either[Throwable, InCookie] =
    Try {
      Right(
        InCookie(
          (json \ "name").as[String]
        )
      )
    } recover {
      case e => Left(e)
    } get
}
case class InCookie(name: String) extends JwtTokenLocation {
  def token(request: RequestHeader): Option[String]  = request.cookies.get(name).map(_.value)
  def asJwtInjection(newToken: String): JwtInjection = JwtInjection(additionalCookies = Map(name -> newToken))
  override def asJson                                = Json.obj("type" -> "InCookie", "name" -> this.name)
}

sealed trait AlgoSettings extends AsJson {

  def asAlgorithm(implicit env: Env): Option[Algorithm]

  def transformValue(secret: String)(implicit env: Env): String = {
    AlgoSettings.fromCacheOrNot(
      secret,
      secret match {
        case s if s.startsWith("${config.") => {
          val path = s.replace("}", "").replace("${config.", "")
          env.configuration.get[String](path)
        }
        case s if s.startsWith("${env.") => {
          val envName = s.replace("}", "").replace("${env.", "")
          System.getenv(envName)
        }
        case s => s
      }
    )
  }
}
object AlgoSettings extends FromJson[AlgoSettings] {
  override def fromJson(json: JsValue): Either[Throwable, AlgoSettings] =
    Try {
      (json \ "type").as[String] match {
        case "HSAlgoSettings" => HSAlgoSettings.fromJson(json)
        case "RSAlgoSettings" => RSAlgoSettings.fromJson(json)
        case "ESAlgoSettings" => ESAlgoSettings.fromJson(json)
      }
    } recover {
      case e => Left(e)
    } get

  private val cache = new TrieMap[String, String]()

  def fromCacheOrNot(key: String, orElse: => String): String = {
    key match {
      case k if k.startsWith("${") => cache.getOrElseUpdate(key, orElse)
      case k                       => key
    }
  }
}
object HSAlgoSettings extends FromJson[HSAlgoSettings] {
  override def fromJson(json: JsValue): Either[Throwable, HSAlgoSettings] =
    Try {
      Right(
        HSAlgoSettings(
          (json \ "size").as[Int],
          (json \ "secret").as[String]
        )
      )
    } recover {
      case e => Left(e)
    } get
}
case class HSAlgoSettings(size: Int, secret: String) extends AlgoSettings {

  override def asAlgorithm(implicit env: Env): Option[Algorithm] = size match {
    case 256 => Some(Algorithm.HMAC256(transformValue(secret)))
    case 384 => Some(Algorithm.HMAC384(transformValue(secret)))
    case 512 => Some(Algorithm.HMAC512(transformValue(secret)))
    case _   => None
  }
  override def asJson = Json.obj(
    "type"   -> "HSAlgoSettings",
    "size"   -> this.size,
    "secret" -> this.secret
  )
}
object RSAlgoSettings extends FromJson[RSAlgoSettings] {
  override def fromJson(json: JsValue): Either[Throwable, RSAlgoSettings] =
    Try {
      Right(
        RSAlgoSettings(
          (json \ "size").as[Int],
          (json \ "publicKey").as[String],
          (json \ "privateKey").asOpt[String]
        )
      )
    } recover {
      case e => Left(e)
    } get
}
case class RSAlgoSettings(size: Int, publicKey: String, privateKey: Option[String]) extends AlgoSettings {

  def getPublicKey(value: String): RSAPublicKey = {
    val publicBytes = ApacheBase64.decodeBase64(
      value.replace("-----BEGIN PUBLIC KEY-----\n", "").replace("\n-----END PUBLIC KEY-----", "").trim()
    )
    val keySpec    = new X509EncodedKeySpec(publicBytes)
    val keyFactory = KeyFactory.getInstance("RSA")
    keyFactory.generatePublic(keySpec).asInstanceOf[RSAPublicKey]
  }

  def getPrivateKey(value: String): RSAPrivateKey = {
    if (value.trim.isEmpty) {
      null // Yeah, I know ...
    } else {
      val privateBytes = ApacheBase64.decodeBase64(
        value.replace("-----BEGIN PRIVATE KEY-----\n", "").replace("\n-----END PRIVATE KEY-----", "").trim()
      )
      val keySpec    = new PKCS8EncodedKeySpec(privateBytes)
      val keyFactory = KeyFactory.getInstance("RSA")
      keyFactory.generatePrivate(keySpec).asInstanceOf[RSAPrivateKey]
    }
  }

  override def asAlgorithm(implicit env: Env): Option[Algorithm] = size match {
    case 256 =>
      Some(
        Algorithm.RSA256(getPublicKey(transformValue(publicKey)),
                         privateKey.map(pk => getPrivateKey(transformValue(pk))).orNull)
      )
    case 384 =>
      Some(
        Algorithm.RSA384(getPublicKey(transformValue(publicKey)),
                         privateKey.map(pk => getPrivateKey(transformValue(pk))).orNull)
      )
    case 512 =>
      Some(
        Algorithm.RSA512(getPublicKey(transformValue(publicKey)),
                         privateKey.map(pk => getPrivateKey(transformValue(pk))).orNull)
      )
    case _ => None
  }

  override def asJson = Json.obj(
    "type"       -> "RSAlgoSettings",
    "size"       -> this.size,
    "publicKey"  -> this.publicKey,
    "privateKey" -> this.privateKey.map(pk => JsString(pk)).getOrElse(JsNull).as[JsValue]
  )
}
object ESAlgoSettings extends FromJson[ESAlgoSettings] {
  override def fromJson(json: JsValue): Either[Throwable, ESAlgoSettings] =
    Try {
      Right(
        ESAlgoSettings(
          (json \ "size").as[Int],
          (json \ "publicKey").as[String],
          (json \ "privateKey").asOpt[String]
        )
      )
    } recover {
      case e => Left(e)
    } get
}
case class ESAlgoSettings(size: Int, publicKey: String, privateKey: Option[String]) extends AlgoSettings {

  def getPublicKey(value: String): ECPublicKey = {
    val publicBytes = ApacheBase64.decodeBase64(
      value.replace("-----BEGIN PUBLIC KEY-----\n", "").replace("\n-----END PUBLIC KEY-----", "").trim()
    )
    val keySpec    = new X509EncodedKeySpec(publicBytes)
    val keyFactory = KeyFactory.getInstance("EC")
    keyFactory.generatePublic(keySpec).asInstanceOf[ECPublicKey]
  }

  def getPrivateKey(value: String): ECPrivateKey = {
    if (value.trim.isEmpty) {
      null // Yeah, I know ...
    } else {
      val privateBytes = ApacheBase64.decodeBase64(
        value.replace("-----BEGIN PRIVATE KEY-----\n", "").replace("\n-----END PRIVATE KEY-----", "").trim()
      )
      val keySpec    = new PKCS8EncodedKeySpec(privateBytes)
      val keyFactory = KeyFactory.getInstance("EC")
      keyFactory.generatePrivate(keySpec).asInstanceOf[ECPrivateKey]
    }
  }

  override def asAlgorithm(implicit env: Env): Option[Algorithm] = size match {
    case 256 =>
      Some(
        Algorithm.ECDSA256(getPublicKey(transformValue(publicKey)),
                           privateKey.map(pk => getPrivateKey(transformValue(pk))).orNull)
      )
    case 384 =>
      Some(
        Algorithm.ECDSA384(getPublicKey(transformValue(publicKey)),
                           privateKey.map(pk => getPrivateKey(transformValue(pk))).orNull)
      )
    case 512 =>
      Some(
        Algorithm.ECDSA512(getPublicKey(transformValue(publicKey)),
                           privateKey.map(pk => getPrivateKey(transformValue(pk))).orNull)
      )
    case _ => None
  }

  override def asJson = Json.obj(
    "type"       -> "ESAlgoSettings",
    "size"       -> this.size,
    "publicKey"  -> this.publicKey,
    "privateKey" -> this.privateKey.map(pk => JsString(pk)).getOrElse(JsNull).as[JsValue]
  )
}

object MappingSettings extends FromJson[MappingSettings] {
  override def fromJson(json: JsValue): Either[Throwable, MappingSettings] =
    Try {
      Right(
        MappingSettings(
          (json \ "map").asOpt[Map[String, String]].getOrElse(Map.empty[String, String]),
          (json \ "values").asOpt[JsObject].getOrElse(Json.obj()),
          (json \ "remove").asOpt[Seq[String]].getOrElse(Seq.empty[String])
        )
      )
    } recover {
      case e => Left(e)
    } get
}
case class MappingSettings(map: Map[String, String] = Map.empty,
                           values: JsObject = Json.obj(),
                           remove: Seq[String] = Seq.empty[String])
    extends AsJson {
  override def asJson = Json.obj(
    "map"    -> JsObject(map.mapValues(JsString.apply)),
    "values" -> values,
    "remove" -> JsArray(remove.map(JsString.apply))
  )
}
object TransformSettings extends FromJson[TransformSettings] {
  override def fromJson(json: JsValue): Either[Throwable, TransformSettings] =
    Try {
      for {
        location        <- JwtTokenLocation.fromJson((json \ "location").as[JsValue])
        mappingSettings <- MappingSettings.fromJson((json \ "mappingSettings").as[JsValue])
      } yield TransformSettings(location, mappingSettings)
    } recover {
      case e => Left(e)
    } get
}
case class TransformSettings(location: JwtTokenLocation, mappingSettings: MappingSettings) extends AsJson {
  override def asJson = Json.obj(
    "location"        -> location.asJson,
    "mappingSettings" -> mappingSettings.asJson
  )
}

object VerificationSettings extends FromJson[VerificationSettings] {
  override def fromJson(json: JsValue): Either[Throwable, VerificationSettings] =
    Try {
      Right(
        VerificationSettings(
          (json \ "fields").as[Map[String, String]]
        )
      )
    } recover {
      case e => Left(e)
    } get
}

case class VerificationSettings(fields: Map[String, String] = Map.empty) extends AsJson {
  def asVerification(algorithm: Algorithm): Verification = {
    fields.foldLeft(
      JWT
        .require(algorithm)
        .acceptLeeway(10000)
    )((a, b) => a.withClaim(b._1, b._2))
  }

  override def asJson = Json.obj(
    "fields" -> JsObject(this.fields.mapValues(JsString.apply))
  )
}

object VerifierStrategy extends FromJson[VerifierStrategy] {
  override def fromJson(json: JsValue): Either[Throwable, VerifierStrategy] =
    Try {
      (json \ "type").as[String] match {
        case "PassThrough" => PassThrough.fromJson(json)
        case "Sign"        => Sign.fromJson(json)
        case "Transform"   => Transform.fromJson(json)
      }
    } recover {
      case e => Left(e)
    } get
}

sealed trait VerifierStrategy extends AsJson {
  def verificationSettings: VerificationSettings
}

object PassThrough extends FromJson[VerifierStrategy] {
  override def fromJson(json: JsValue): Either[Throwable, VerifierStrategy] =
    Try {
      for {
        verificationSettings <- VerificationSettings.fromJson((json \ "verificationSettings").as[JsValue])
      } yield PassThrough(verificationSettings)
    } recover {
      case e => Left(e)
    } get
}

case class PassThrough(verificationSettings: VerificationSettings) extends VerifierStrategy {
  override def asJson = Json.obj(
    "type"                 -> "PassThrough",
    "verificationSettings" -> verificationSettings.asJson
  )
}

object Sign extends FromJson[VerifierStrategy] {
  override def fromJson(json: JsValue): Either[Throwable, VerifierStrategy] =
    Try {
      for {
        verificationSettings <- VerificationSettings.fromJson((json \ "verificationSettings").as[JsValue])
        algoSettings         <- AlgoSettings.fromJson((json \ "algoSettings").as[JsValue])
      } yield Sign(verificationSettings, algoSettings)
    } recover {
      case e => Left(e)
    } get
}

case class Sign(verificationSettings: VerificationSettings, algoSettings: AlgoSettings) extends VerifierStrategy {
  override def asJson = Json.obj(
    "type"                 -> "Sign",
    "verificationSettings" -> verificationSettings.asJson,
    "algoSettings"         -> algoSettings.asJson
  )
}

object Transform extends FromJson[VerifierStrategy] {
  override def fromJson(json: JsValue): Either[Throwable, VerifierStrategy] =
    Try {
      for {
        verificationSettings <- VerificationSettings.fromJson((json \ "verificationSettings").as[JsValue])
        transformSettings    <- TransformSettings.fromJson((json \ "transformSettings").as[JsValue])
        algoSettings         <- AlgoSettings.fromJson((json \ "algoSettings").as[JsValue])
      } yield Transform(verificationSettings, transformSettings, algoSettings)
    } recover {
      case e => Left(e)
    } get
}

case class Transform(verificationSettings: VerificationSettings,
                     transformSettings: TransformSettings,
                     algoSettings: AlgoSettings)
    extends VerifierStrategy {
  override def asJson = Json.obj(
    "type"                 -> "Transform",
    "verificationSettings" -> verificationSettings.asJson,
    "transformSettings"    -> transformSettings.asJson,
    "algoSettings"         -> algoSettings.asJson
  )
}

sealed trait JwtVerifier extends AsJson {

  lazy val logger = Logger("otoroshi-jwt-verifier")

  def isRef: Boolean
  def enabled: Boolean
  def strict: Boolean
  def shouldBeVerified(path: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def source: JwtTokenLocation
  def algoSettings: AlgoSettings
  def strategy: VerifierStrategy

  private def sign(token: JsObject, algorithm: Algorithm): String = {
    val headerJson     = Json.obj("alg" -> algorithm.getName, "typ" -> "JWT")
    val header         = ApacheBase64.encodeBase64URLSafeString(Json.stringify(headerJson).getBytes(StandardCharsets.UTF_8))
    val payload        = ApacheBase64.encodeBase64URLSafeString(Json.stringify(token).getBytes(StandardCharsets.UTF_8))
    val content        = String.format("%s.%s", header, payload)
    val signatureBytes = algorithm.sign(content.getBytes(StandardCharsets.UTF_8))
    val signature      = ApacheBase64.encodeBase64URLSafeString(signatureBytes)
    s"$content.$signature"
  }

  def verifyWs(request: RequestHeader, desc: ServiceDescriptor)(
      f: JwtInjection => Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]]
  )(implicit ec: ExecutionContext, env: Env): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] = {
    internalVerify(request, desc)(f).map {
      case Left(badResult)   => Left[Result, Flow[PlayWSMessage, PlayWSMessage, _]](badResult)
      case Right(goodResult) => goodResult
    }
  }

  def verify(request: RequestHeader, desc: ServiceDescriptor)(
      f: JwtInjection => Future[Result]
  )(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    internalVerify(request, desc)(f).map {
      case Left(badResult)   => badResult
      case Right(goodResult) => goodResult
    }
  }

  private def internalVerify[A](request: RequestHeader, desc: ServiceDescriptor)(
      f: JwtInjection => Future[A]
  )(implicit ec: ExecutionContext, env: Env): Future[Either[Result, A]] = {

    import Implicits._

    source.token(request) match {
      case None if strict =>
        Errors
          .craftResponseResult(
            "error.expected.token.not.found",
            Results.BadRequest,
            request,
            Some(desc),
            None
          )
          .left[A]
      case None if !strict => f(JwtInjection()).right[Result]
      case Some(token) =>
        algoSettings.asAlgorithm match {
          case None =>
            Errors
              .craftResponseResult(
                "error.bad.input.algorithm.name",
                Results.BadRequest,
                request,
                Some(desc),
                None
              )
              .left[A]
          case Some(algorithm) => {
            val verification = strategy.verificationSettings.asVerification(algorithm)
            Try(verification.build().verify(token)) match {
              case Failure(e) =>
                logger.error("Bad JWT token", e)
                Errors
                  .craftResponseResult(
                    "error.bad.token",
                    Results.BadRequest,
                    request,
                    Some(desc),
                    None
                  )
                  .left[A]
              case Success(decodedToken) =>
                strategy match {
                  case s @ PassThrough(_) => f(JwtInjection()).right[Result]
                  case s @ Sign(_, aSettings) =>
                    aSettings.asAlgorithm match {
                      case None =>
                        Errors
                          .craftResponseResult(
                            "error.bad.output.algorithm.name",
                            Results.BadRequest,
                            request,
                            Some(desc),
                            None
                          )
                          .left[A]
                      case Some(outputAlgorithm) => {
                        val newToken = sign(Json.parse(ApacheBase64.decodeBase64(decodedToken.getPayload)).as[JsObject],
                                            outputAlgorithm)
                        f(source.asJwtInjection(newToken)).right[Result]
                      }
                    }
                  case s @ Transform(_, tSettings, aSettings) =>
                    aSettings.asAlgorithm match {
                      case None =>
                        Errors
                          .craftResponseResult(
                            "error.bad.output.algorithm.name",
                            Results.BadRequest,
                            request,
                            Some(desc),
                            None
                          )
                          .left[A]
                      case Some(outputAlgorithm) => {
                        val jsonToken = Json.parse(ApacheBase64.decodeBase64(decodedToken.getPayload)).as[JsObject]
                        val newJsonToken: JsObject = JsObject(
                          (tSettings.mappingSettings.map.filter(a => (jsonToken \ a._1).isDefined).foldLeft(jsonToken)(
                            (a, b) => a.+(b._2, (a \ b._1).as[JsValue]).-(b._1)
                          ) ++ tSettings.mappingSettings.values).fields
                            .filterNot(f => tSettings.mappingSettings.remove.contains(f._1))
                            .toMap
                        )
                        val newToken = sign(newJsonToken, outputAlgorithm)
                        source match {
                          case _: InQueryParam => f(tSettings.location.asJwtInjection(newToken)).right[Result]
                          case InHeader(n, _) =>
                            f(tSettings.location.asJwtInjection(newToken).copy(removeHeaders = Seq(n))).right[Result]
                          case InCookie(n) =>
                            f(tSettings.location.asJwtInjection(newToken).copy(removeCookies = Seq(n))).right[Result]
                        }
                      }
                    }
                }
            }
          }
        }
    }
  }
}

case class LocalJwtVerifier(
    enabled: Boolean = false,
    strict: Boolean = true,
    excludedPatterns: Seq[String] = Seq.empty[String],
    source: JwtTokenLocation = InHeader("X-JWT-Token"),
    algoSettings: AlgoSettings = HSAlgoSettings(512, "secret"),
    strategy: VerifierStrategy = PassThrough(VerificationSettings(Map("iss" -> "The Issuer")))
) extends JwtVerifier
    with AsJson {

  def asJson: JsValue = Json.obj(
    "type"             -> "local",
    "enabled"          -> this.enabled,
    "strict"           -> this.strict,
    "excludedPatterns" -> JsArray(this.excludedPatterns.map(JsString.apply)),
    "source"           -> this.source.asJson,
    "algoSettings"     -> this.algoSettings.asJson,
    "strategy"         -> this.strategy.asJson
  )

  override def isRef = false

  override def shouldBeVerified(path: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    FastFuture.successful(!excludedPatterns.exists(p => utils.RegexPool.regex(p).matches(path)))
}

case class RefJwtVerifier(id: Option[String], enabled: Boolean) extends JwtVerifier with AsJson {

  def asJson: JsValue = Json.obj(
    "type"    -> "ref",
    "id"      -> this.id.map(JsString.apply).getOrElse(JsNull).as[JsValue],
    "enabled" -> this.enabled
  )

  override def isRef        = true
  override def strict       = throw new RuntimeException("Should never be called ...")
  override def source       = throw new RuntimeException("Should never be called ...")
  override def algoSettings = throw new RuntimeException("Should never be called ...")
  override def strategy     = throw new RuntimeException("Should never be called ...")

  override def verify(request: RequestHeader, desc: ServiceDescriptor)(
      f: JwtInjection => Future[Result]
  )(implicit ec: ExecutionContext, env: Env) = {
    id match {
      case None => f(JwtInjection())
      case Some(ref) => env.datastores.globalJwtVerifierDataStore.findById(ref).flatMap {
        case Some(verifier) => verifier.verify(request, desc)(f)
        case None =>
          Errors.craftResponseResult(
            "error.bad.globaljwtverifier.id",
            Results.InternalServerError,
            request,
            Some(desc),
            None
          )
      }
    }
  }

  override def verifyWs(request: RequestHeader, desc: ServiceDescriptor)(
      f: JwtInjection => Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]]
  )(implicit ec: ExecutionContext, env: Env) = {
    id match {
      case None => f(JwtInjection())
      case Some(ref) => env.datastores.globalJwtVerifierDataStore.findById(ref).flatMap {
        case Some(verifier) => verifier.verifyWs(request, desc)(f)
        case None =>
          Errors
            .craftResponseResult(
              "error.bad.globaljwtverifier.id",
              Results.InternalServerError,
              request,
              Some(desc),
              None
            )
            .map(a => Left(a))
      }
    }
  }

  override def shouldBeVerified(path: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean] = {
    id match {
      case None => FastFuture.successful(false)
      case Some(ref) => env.datastores.globalJwtVerifierDataStore.findById(ref).flatMap {
        case Some(verifier) => verifier.shouldBeVerified(path)
        case None => Future.failed(new RuntimeException("Jwt verifier not found ..."))
      }
    }
  }
}

object RefJwtVerifier extends FromJson[RefJwtVerifier] {
  override def fromJson(json: JsValue): Either[Throwable, RefJwtVerifier] =
    Try {
      Right[Throwable, RefJwtVerifier](
        RefJwtVerifier(
          id = (json \ "id").asOpt[String],
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false)
        )
      )
    } recover {
      case e => Left[Throwable, RefJwtVerifier](e)
    } get
}

object LocalJwtVerifier extends FromJson[LocalJwtVerifier] {
  override def fromJson(json: JsValue): Either[Throwable, LocalJwtVerifier] =
    Try {
      for {
        source       <- JwtTokenLocation.fromJson((json \ "source").as[JsValue])
        algoSettings <- AlgoSettings.fromJson((json \ "algoSettings").as[JsValue])
        strategy     <- VerifierStrategy.fromJson((json \ "strategy").as[JsValue])
      } yield {
        LocalJwtVerifier(
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
          strict = (json \ "strict").asOpt[Boolean].getOrElse(false),
          excludedPatterns = (json \ "excludedPatterns").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          source = source,
          algoSettings = algoSettings,
          strategy = strategy
        )
      }
    } recover {
      case e => Left.apply[Throwable, LocalJwtVerifier](e)
    } get
}

case class GlobalJwtVerifier(
    id: String,
    name: String,
    desc: String,
    strict: Boolean = true,
    excludedPatterns: Seq[String] = Seq.empty[String],
    source: JwtTokenLocation = InHeader("X-JWT-Token"),
    algoSettings: AlgoSettings = HSAlgoSettings(512, "secret"),
    strategy: VerifierStrategy = PassThrough(VerificationSettings(Map("iss" -> "The Issuer")))
) extends JwtVerifier
    with AsJson {

  def asJson: JsValue = Json.obj(
    "type"             -> "global",
    "id"               -> this.id,
    "name"             -> this.name,
    "desc"             -> this.desc,
    "strict"           -> this.strict,
    "excludedPatterns" -> JsArray(this.excludedPatterns.map(JsString.apply)),
    "source"           -> this.source.asJson,
    "algoSettings"     -> this.algoSettings.asJson,
    "strategy"         -> this.strategy.asJson
  )

  override def isRef = false

  def save()(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    env.datastores.globalJwtVerifierDataStore.set(this)

  override def shouldBeVerified(path: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    FastFuture.successful(!excludedPatterns.exists(p => utils.RegexPool.regex(p).matches(path)))

  override def enabled = true
}

object GlobalJwtVerifier extends FromJson[GlobalJwtVerifier] {

  lazy val logger = Logger("otoroshi-global-jwt-verifier")

  def fromJsons(value: JsValue): GlobalJwtVerifier =
    try {
      _fmt.reads(value).get
    } catch {
      case e: Throwable => {
        logger.error(s"Try to deserialize ${Json.prettyPrint(value)}")
        throw e
      }
    }

  val _fmt = new Format[GlobalJwtVerifier] {

    override def reads(json: JsValue) = fromJson(json) match {
      case Left(e)  => JsError(e.getMessage)
      case Right(v) => JsSuccess(v)
    }

    override def writes(o: GlobalJwtVerifier) = o.asJson
  }

  override def fromJson(json: JsValue): Either[Throwable, GlobalJwtVerifier] =
    Try {
      for {
        source       <- JwtTokenLocation.fromJson((json \ "source").as[JsValue])
        algoSettings <- AlgoSettings.fromJson((json \ "algoSettings").as[JsValue])
        strategy     <- VerifierStrategy.fromJson((json \ "strategy").as[JsValue])
      } yield {
        GlobalJwtVerifier(
          id = (json \ "id").as[String],
          name = (json \ "name").as[String],
          desc = (json \ "desc").asOpt[String].getOrElse("--"),
          strict = (json \ "strict").asOpt[Boolean].getOrElse(false),
          excludedPatterns = (json \ "excludedPatterns").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          source = source,
          algoSettings = algoSettings,
          strategy = strategy
        )
      }
    } recover {
      case e => Left.apply[Throwable, GlobalJwtVerifier](e)
    } get
}

object JwtVerifier extends FromJson[JwtVerifier] {

  override def fromJson(json: JsValue): Either[Throwable, JwtVerifier] = {
    Try {
      (json \ "type").as[String] match {
        case "global" => GlobalJwtVerifier.fromJson(json)
        case "local"  => LocalJwtVerifier.fromJson(json)
        case "ref"    => RefJwtVerifier.fromJson(json)
      }
    } recover {
      case e => Left(e)
    } get
  }

  def mock1: JwtVerifier = LocalJwtVerifier(
    strict = true,
    source = InHeader("Authorization", "Bearer "),
    algoSettings = HSAlgoSettings(256, "secret"),
    strategy = Transform(
      transformSettings = TransformSettings(
        location = InHeader("X-Fuuuuu"),
        mappingSettings = MappingSettings(
          map = Map("name" -> "MyNameIs"),
          values = Json.obj(
            "fuuu" -> 123
          )
        )
      ),
      verificationSettings = VerificationSettings(
        Map(
          "iss" -> "Billy"
        )
      ),
      //algoSettings = RSAlgoSettings(
      //  512,
      //  """MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuGbXWiK3dQTyCbX5xdE4
      //    |yCuYp0AF2d15Qq1JSXT/lx8CEcXb9RbDddl8jGDv+spi5qPa8qEHiK7FwV2KpRE9
      //    |83wGPnYsAm9BxLFb4YrLYcDFOIGULuk2FtrPS512Qea1bXASuvYXEpQNpGbnTGVs
      //    |WXI9C+yjHztqyL2h8P6mlThPY9E9ue2fCqdgixfTFIF9Dm4SLHbphUS2iw7w1JgT
      //    |69s7of9+I9l5lsJ9cozf1rxrXX4V1u/SotUuNB3Fp8oB4C1fLBEhSlMcUJirz1E8
      //    |AziMCxS+VrRPDM+zfvpIJg3JljAh3PJHDiLu902v9w+Iplu1WyoB2aPfitxEhRN0
      //    |YwIDAQAB""".stripMargin,
      //  """MIIEvwIBADANBgkqhkiG9w0BAQEFAASCBKkwggSlAgEAAoIBAQC4ZtdaIrd1BPIJ
      //    |tfnF0TjIK5inQAXZ3XlCrUlJdP+XHwIRxdv1FsN12XyMYO/6ymLmo9ryoQeIrsXB
      //    |XYqlET3zfAY+diwCb0HEsVvhisthwMU4gZQu6TYW2s9LnXZB5rVtcBK69hcSlA2k
      //    |ZudMZWxZcj0L7KMfO2rIvaHw/qaVOE9j0T257Z8Kp2CLF9MUgX0ObhIsdumFRLaL
      //    |DvDUmBPr2zuh/34j2XmWwn1yjN/WvGtdfhXW79Ki1S40HcWnygHgLV8sESFKUxxQ
      //    |mKvPUTwDOIwLFL5WtE8Mz7N++kgmDcmWMCHc8kcOIu73Ta/3D4imW7VbKgHZo9+K
      //    |3ESFE3RjAgMBAAECggEBAJTEIyjMqUT24G2FKiS1TiHvShBkTlQdoR5xvpZMlYbN
      //    |tVWxUmrAGqCQ/TIjYnfpnzCDMLhdwT48Ab6mQJw69MfiXwc1PvwX1e9hRscGul36
      //    |ryGPKIVQEBsQG/zc4/L2tZe8ut+qeaK7XuYrPp8bk/X1e9qK5m7j+JpKosNSLgJj
      //    |NIbYsBkG2Mlq671irKYj2hVZeaBQmWmZxK4fw0Istz2WfN5nUKUeJhTwpR+JLUg4
      //    |ELYYoB7EO0Cej9UBG30hbgu4RyXA+VbptJ+H042K5QJROUbtnLWuuWosZ5ATldwO
      //    |u03dIXL0SH0ao5NcWBzxU4F2sBXZRGP2x/jiSLHcqoECgYEA4qD7mXQpu1b8XO8U
      //    |6abpKloJCatSAHzjgdR2eRDRx5PMvloipfwqA77pnbjTUFajqWQgOXsDTCjcdQui
      //    |wf5XAaWu+TeAVTytLQbSiTsBhrnoqVrr3RoyDQmdnwHT8aCMouOgcC5thP9vQ8Us
      //    |rVdjvRRbnJpg3BeSNimH+u9AHgsCgYEA0EzcbOltCWPHRAY7B3Ge/AKBjBQr86Kv
      //    |TdpTlxePBDVIlH+BM6oct2gaSZZoHbqPjbq5v7yf0fKVcXE4bSVgqfDJ/sZQu9Lp
      //    |PTeV7wkk0OsAMKk7QukEpPno5q6tOTNnFecpUhVLLlqbfqkB2baYYwLJR3IRzboJ
      //    |FQbLY93E8gkCgYB+zlC5VlQbbNqcLXJoImqItgQkkuW5PCgYdwcrSov2ve5r/Acz
      //    |FNt1aRdSlx4176R3nXyibQA1Vw+ztiUFowiP9WLoM3PtPZwwe4bGHmwGNHPIfwVG
      //    |m+exf9XgKKespYbLhc45tuC08DATnXoYK7O1EnUINSFJRS8cezSI5eHcbQKBgQDC
      //    |PgqHXZ2aVftqCc1eAaxaIRQhRmY+CgUjumaczRFGwVFveP9I6Gdi+Kca3DE3F9Pq
      //    |PKgejo0SwP5vDT+rOGHN14bmGJUMsX9i4MTmZUZ5s8s3lXh3ysfT+GAhTd6nKrIE
      //    |kM3Nh6HWFhROptfc6BNusRh1kX/cspDplK5x8EpJ0QKBgQDWFg6S2je0KtbV5PYe
      //    |RultUEe2C0jYMDQx+JYxbPmtcopvZQrFEur3WKVuLy5UAy7EBvwMnZwIG7OOohJb
      //    |vkSpADK6VPn9lbqq7O8cTedEHttm6otmLt8ZyEl3hZMaL3hbuRj6ysjmoFKx6CrX
      //    |rK0/Ikt5ybqUzKCMJZg2VKGTxg==""".stripMargin
      //)
      algoSettings = ESAlgoSettings(
        512,
        """MIGbMBAGByqGSM49AgEGBSuBBAAjA4GGAAQAmG8JrpLz14+qUs7oxFX0pCoe90Ah
          |MMB/9ZENy8KZ+us26i/6PiBBc7XaiEi6Q8Icz2tiazwSpyLPeBrFVPFkPgIADyLa
          |T0fp7D2JKHWpdrWQvGLLMwGqYCaaDi79KugPo6V4bnpLBlVtbH4ogg0Hqv89BVyI
          |ZfwWPCBH+Zssei1VlgM=""".stripMargin,
        Some("""MIHtAgEAMBAGByqGSM49AgEGBSuBBAAjBIHVMIHSAgEBBEHzl1DpZSQJ8YhCbN/u
          |vo5SOu0BjDDX9Gub6zsBW6B2TxRzb5sBeQaWVscDUZha4Xr1HEWpVtua9+nEQU/9
          |Aq9Pl6GBiQOBhgAEAJhvCa6S89ePqlLO6MRV9KQqHvdAITDAf/WRDcvCmfrrNuov
          |+j4gQXO12ohIukPCHM9rYms8Eqciz3gaxVTxZD4CAA8i2k9H6ew9iSh1qXa1kLxi
          |yzMBqmAmmg4u/SroD6OleG56SwZVbWx+KIINB6r/PQVciGX8FjwgR/mbLHotVZYD""".stripMargin)
      )
      //algoSettings = HSAlgoSettings(
      //  512,
      //  "secret"
      //)
    )
  )
}

object Implicits {
  implicit class EnhancedFuture[A](val fu: Future[A]) extends AnyVal {
    def left[B](implicit ec: ExecutionContext): Future[Either[A, B]]  = fu.map(a => Left[A, B](a))
    def right[B](implicit ec: ExecutionContext): Future[Either[B, A]] = fu.map(a => Right[B, A](a))
  }
}

trait GlobalJwtVerifierDataStore extends BasicStore[GlobalJwtVerifier]
