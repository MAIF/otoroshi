package models

import java.security.KeyFactory
import java.security.interfaces.{ECPrivateKey, ECPublicKey, RSAPrivateKey, RSAPublicKey}
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.Verification
import env.Env
import gateway.Errors
import play.api.Logger
import play.api.mvc.{RequestHeader, Result, Results}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class JWTInjection()

sealed trait JWTTokenLocation {
  def token(request: RequestHeader): Option[String]
  def asJWTInjection(newToken: String): JWTInjection
}
case class InQueryParam(name: String) extends JWTTokenLocation {
  def token(request: RequestHeader): Option[String] = request.getQueryString(name)
}
case class InHeader(name: String, remove: String = "") extends JWTTokenLocation {
  def token(request: RequestHeader): Option[String] = request.headers.get(name).map(_.replaceAll(remove, ""))
}
case class InCookie(name: String) extends JWTTokenLocation {
  def token(request: RequestHeader): Option[String] = request.cookies.get(name).map(_.value)
}

sealed trait AlgoSettings {
  def asAlgorithm: Option[Algorithm]
}
case class HSAlgoSettings(size: Int, secret: String) extends AlgoSettings {
  override def asAlgorithm: Option[Algorithm] = size match {
    case 256 => Some(Algorithm.HMAC256(secret))
    case 384 => Some(Algorithm.HMAC384(secret))
    case 512 => Some(Algorithm.HMAC512(secret))
    case _ => None
  }
}
case class RSAlgoSettings(size: Int, publicKey: String, privateRsaKey: String) extends AlgoSettings {

  def getPublicKey(value: String): RSAPublicKey = {
    val publicBytes = Base64.getDecoder.decode(value)
    val keySpec = new X509EncodedKeySpec(publicBytes)
    val keyFactory = KeyFactory.getInstance("RSA")
    keyFactory.generatePublic(keySpec).asInstanceOf[RSAPublicKey]
  }

  def getPrivateKey(value: String): RSAPrivateKey = {
    val publicBytes = Base64.getDecoder.decode(value)
    val keySpec = new X509EncodedKeySpec(publicBytes)
    val keyFactory = KeyFactory.getInstance("RSA")
    keyFactory.generatePrivate(keySpec).asInstanceOf[RSAPrivateKey]
  }

  override def asAlgorithm: Option[Algorithm] = size match {
    case 256 => Some(Algorithm.RSA256(getPublicKey(publicKey), getPrivateKey(privateRsaKey)))
    case 384 => Some(Algorithm.RSA384(getPublicKey(publicKey), getPrivateKey(privateRsaKey)))
    case 512 => Some(Algorithm.RSA512(getPublicKey(publicKey), getPrivateKey(privateRsaKey)))
    case _ => None
  }
}
case class ESAlgoSettings(size: Int, publicKey: String, privateRsaKey: String) extends AlgoSettings {

  def getPublicKey(value: String): ECPublicKey = {
    val publicBytes = Base64.getDecoder.decode(value)
    val keySpec = new X509EncodedKeySpec(publicBytes)
    val keyFactory = KeyFactory.getInstance("ECDH")
    keyFactory.generatePublic(keySpec).asInstanceOf[ECPublicKey]
  }

  def getPrivateKey(value: String): ECPrivateKey = {
    val publicBytes = Base64.getDecoder.decode(value)
    val keySpec = new X509EncodedKeySpec(publicBytes)
    val keyFactory = KeyFactory.getInstance("ECDH")
    keyFactory.generatePrivate(keySpec).asInstanceOf[ECPrivateKey]
  }

  override def asAlgorithm: Option[Algorithm] = size match {
    case 256 => Some(Algorithm.ECDSA256(getPublicKey(publicKey), getPrivateKey(privateRsaKey)))
    case 384 => Some(Algorithm.ECDSA384(getPublicKey(publicKey), getPrivateKey(privateRsaKey)))
    case 512 => Some(Algorithm.ECDSA512(getPublicKey(publicKey), getPrivateKey(privateRsaKey)))
    case _ => None
  }
}

case class MappingSettings(map: Map[String, String] = Map.empty, values: Map[String, AnyRef] = Map.empty)
case class TransformSettings(location: JWTTokenLocation, mappingSettings: MappingSettings)

case class VerificationSettings(fields: Map[String, String] = Map.empty) {
  def asVerification(algorithm: Algorithm): Verification = {
    fields.foldLeft(JWT
      .require(algorithm)
      .acceptLeeway(10000))((a, b) => a.withClaim(b._1, b._2))
  }
}

sealed trait VerifierStrategy {
  def verificationSettings: VerificationSettings
}
case class PassThrough(verificationSettings: VerificationSettings) extends VerifierStrategy
case class Sign(verificationSettings: VerificationSettings, algorithm: AlgoSettings) extends VerifierStrategy
case class Transform(verificationSettings: VerificationSettings, transformSettings: TransformSettings, algoSettings: AlgoSettings) extends VerifierStrategy
case class AddToOtoroshiToken(verificationSettings: VerificationSettings, settings: MappingSettings) extends VerifierStrategy

case class JWTVerifier(id: String, name: String, strict: Boolean = true, source: JWTTokenLocation, algoSettings: AlgoSettings, strategy: VerifierStrategy) {

  lazy val logger = Logger("otoroshi-jwt-verifier")

  def verify(request: RequestHeader, desc: ServiceDescriptor)(f: JWTInjection => Future[Result])(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    source.token(request) match {
      case None if strict => Errors.craftResponseResult(
        "error.expected.token.not.found",
        Results.BadRequest,
        request,
        Some(desc),
        None
      )
      case None if !strict => f(JWTInjection())
      case Some(token) => algoSettings.asAlgorithm match {
        case None => Errors.craftResponseResult(
          "error.bad.input.algorithm.name",
          Results.BadRequest,
          request,
          Some(desc),
          None
        )
        case Some(algorithm) => {
          val verification = strategy.verificationSettings.asVerification(algorithm)
          Try(verification.build().verify(token)) match {
            case Failure(e) =>
              logger.error("Bad JWT token", e)
              Errors.craftResponseResult(
                "error.bad.token",
                Results.BadRequest,
                request,
                Some(desc),
                None
              )
            case Success(decodedToken) => strategy match {
              case s @ PassThrough(_) => f(JWTInjection())
              case s @ Sign(_, aSettings) => aSettings.asAlgorithm match {
                case None => Errors.craftResponseResult(
                  "error.bad.output.algorithm.name",
                  Results.BadRequest,
                  request,
                  Some(desc),
                  None
                )
                case Some(outputAlgorithm) => {
                  import collection.JavaConverters._
                  val newToken = decodedToken.getClaims.asScala.foldLeft(JWT.create())((a, b) => a.withClaim(b._1, b._2)).sign(outputAlgorithm)
                  f(source.asJWTInjection(newToken))
                }
              }
              case s @ Transform(_, tSettings, aSettings) => ???
              case s @ AddToOtoroshiToken(_, mSettings) => ???
            }
          }
        }
      }
    }
  }
}
