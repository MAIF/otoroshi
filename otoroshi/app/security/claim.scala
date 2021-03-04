package otoroshi.security

import java.nio.charset.StandardCharsets
import java.util.{Base64, Date}

import com.auth0.jwt.algorithms.Algorithm
import env.Env
import models.AlgoSettings
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json._

case class OtoroshiClaim(
    iss: String, // issuer
    sub: String, // subject
    aud: String, // audience
    exp: Long, // date d'expiration
    iat: Long = DateTime.now().getMillis, // issued at
    jti: String, // unique id forever
    metadata: JsObject = Json.obj() // private claim
) {
  def toJson: JsValue                                                 = OtoroshiClaim.format.writes(this)
  def serialize(jwtSettings: AlgoSettings)(implicit env: Env): String = OtoroshiClaim.serialize(this, jwtSettings)(env)
  def withClaims(claims: JsValue): OtoroshiClaim =
    copy(metadata = metadata ++ claims.asOpt[JsObject].getOrElse(Json.obj()))
  def withClaims(claims: Option[JsValue]): OtoroshiClaim = claims match {
    case Some(c) => withClaims(c)
    case None    => this
  }
  def withClaim(name: String, value: String): OtoroshiClaim = copy(metadata = metadata ++ Json.obj(name -> value))
  def withClaim(name: String, value: Option[String]): OtoroshiClaim = value match {
    case Some(v) => copy(metadata = metadata ++ Json.obj(name -> v))
    case None    => this
  }
  def withJsObjectClaim(name: String, value: Option[JsObject]): OtoroshiClaim = value match {
    case Some(v) => copy(metadata = metadata ++ Json.obj(name -> v))
    case None    => this
  }
  def withJsArrayClaim(name: String, value: Option[JsArray]): OtoroshiClaim = value match {
    case Some(v) => copy(metadata = metadata ++ Json.obj(name -> v))
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
    // Here we bypass JWT lib limitations ...
    val header = Json.obj(
      "typ" -> "JWT",
      "alg" -> algorithm.getName
    )
    val payload = Json.obj(
      "iss" -> env.Headers.OtoroshiIssuer,
      "sub" -> claim.sub,
      "aud" -> claim.aud,
      "exp" -> new Date(claim.exp).getTime / 1000,
      "iat" -> new Date(claim.iat).getTime / 1000,
      "nbr" -> new Date(claim.iat).getTime / 1000,
      "jti" -> claim.jti
    ) ++ claim.metadata
    val signed = sign(algorithm, header, payload)
    logger.debug(s"signed: $signed")
    signed
  }

  private def sign(algorithm: Algorithm, headerJson: JsObject, payloadJson: JsObject): String = {
    val header: String              = org.apache.commons.codec.binary.Base64.encodeBase64URLSafeString(Json.toBytes(headerJson))
    val payload: String             = org.apache.commons.codec.binary.Base64.encodeBase64URLSafeString(Json.toBytes(payloadJson))
    val signatureBytes: Array[Byte] = algorithm.sign(header.getBytes(StandardCharsets.UTF_8), payload.getBytes(StandardCharsets.UTF_8))

    val signature: String           = org.apache.commons.codec.binary.Base64.encodeBase64URLSafeString(signatureBytes)
    String.format("%s.%s.%s", header, payload, signature)
  }
}
