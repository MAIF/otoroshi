package security

import java.util.{Base64, Date}

import com.auth0.jwt.{JWT, JWTCreator}
import com.auth0.jwt.algorithms.Algorithm
import env.Env
import models.AlgoSettings
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.util.Try

case class OtoroshiClaim(
    iss: String, // issuer
    sub: String, // subject
    aud: String, // audience
    exp: Long, // date d'expiration
    iat: Long = DateTime.now().getMillis, // issued at
    jti: String, // unique id forever
    metadata: Map[String, String] = Map.empty[String, String] // private claim
) {
  def toJson: JsValue                                                 = OtoroshiClaim.format.writes(this)
  def serialize(jwtSettings: AlgoSettings)(implicit env: Env): String = OtoroshiClaim.serialize(this, jwtSettings)(env)
  def withClaims(claims: Option[Map[String, String]]): OtoroshiClaim = claims match {
    case Some(c) => withClaims(c)
    case None    => this
  }
  def withClaims(claims: Map[String, String]): OtoroshiClaim = copy(metadata = metadata ++ claims)
  def withClaim(name: String, value: String): OtoroshiClaim  = copy(metadata = metadata + (name -> value))
  def withClaim(name: String, value: Option[String]): OtoroshiClaim = value match {
    case Some(v) => copy(metadata = metadata + (name -> v))
    case None    => this
  }
}

object OtoroshiClaim {

  val encoder = Base64.getUrlEncoder
  val decoder = Base64.getUrlDecoder
  val format  = Json.format[OtoroshiClaim]

  lazy val logger = Logger("otoroshi-claim")

  def serialize(claim: OtoroshiClaim, jwtSettings: AlgoSettings)(implicit env: Env): String = {
    val algorithm = jwtSettings.asAlgorithm(models.OutputMode).get
    val builder: JWTCreator.Builder = JWT
      .create()
      .withIssuer(env.Headers.OtoroshiIssuer)
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
    // logger.trace(s"JWT: $signed")
    signed
  }

  private def validate(claim: String)(implicit env: Env): Boolean =
    Try {
      val algorithm = Algorithm.HMAC512(env.crypto.sharedKey)
      val verifier = JWT
        .require(algorithm)
        .withIssuer(env.Headers.OtoroshiIssuer)
        .build()
      verifier.verify(claim)
    } isSuccess
}
