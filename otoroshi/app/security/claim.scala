package security

import java.util.{Base64, Date}

import com.auth0.jwt.{JWT, JWTCreator}
import com.auth0.jwt.algorithms.Algorithm
import env.Env
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.util.Try

case class OpunClaim(
    iss: String, // issuer
    sub: String, // subject
    aud: String, // audience
    exp: Long, // date d'expiration
    iat: Long = DateTime.now().getMillis, // issued at
    jti: String, // unique id forever
    metadata: Map[String, String] = Map.empty[String, String] // private claim
) {
  def toJson: JsValue                                = OpunClaim.format.writes(this)
  def serialize(implicit env: Env): String           = OpunClaim.serialize(this)(env)
  def serializeWithCrypto(implicit env: Env): String = OpunClaim.serializeWithCrypto(this)(env)
  def withClaims(claims: Option[Map[String, String]]): OpunClaim = claims match {
    case Some(c) => withClaims(c)
    case None    => this
  }
  def withClaims(claims: Map[String, String]): OpunClaim = copy(metadata = metadata ++ claims)
  def withClaim(name: String, value: String): OpunClaim  = copy(metadata = metadata + (name -> value))
  def withClaim(name: String, value: Option[String]): OpunClaim = value match {
    case Some(v) => copy(metadata = metadata + (name -> v))
    case None    => this
  }
}

object OpunClaim {

  val encoder = Base64.getUrlEncoder
  val decoder = Base64.getUrlDecoder
  val format  = Json.format[OpunClaim]

  lazy val logger = Logger("otoroshi-claim")

  def serializeWithCrypto(claim: OpunClaim)(implicit env: Env): String = {
    val algorithm = Algorithm.HMAC512(env.crypto.sharedKey)
    val builder: JWTCreator.Builder = JWT
      .create()
      .withIssuer(env.Headers.OpunGateway)
      .withSubject(claim.sub)
      .withAudience(claim.aud)
      .withExpiresAt(new Date(claim.exp))
      .withIssuedAt(new Date(claim.iat)) //(DateTime.now().toDate)
      .withJWTId(claim.jti)
    val signed = claim.metadata.toSeq
      .foldLeft[JWTCreator.Builder](builder) {
        case (build, (key, value)) => build.withClaim(key, value)
      }
      .sign(algorithm)
    logger.trace(s"JWT: $signed")
    signed
  }

  def serialize(claim: OpunClaim)(implicit env: Env): String = serializeWithCrypto(claim)(env)

  def validate(claim: String)(implicit env: Env): Boolean =
    Try {
      val algorithm = Algorithm.HMAC512(env.crypto.sharedKey)
      val verifier = JWT
        .require(algorithm)
        .withIssuer(env.Headers.OpunGateway)
        .build()
      verifier.verify(claim)
    } isSuccess
}
