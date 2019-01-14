package otoroshi.utils

import java.security.KeyFactory
import java.security.interfaces.{ECPublicKey, RSAPublicKey}
import java.security.spec.X509EncodedKeySpec

import com.auth0.jwk.{GuavaCachedJwkProvider, Jwk, JwkProvider, UrlJwkProvider}
import com.auth0.jwt.algorithms.Algorithm
import com.google.common.collect.Maps
import org.apache.commons.codec.binary.{Base64 => ApacheBase64}
import play.api.libs.json.{JsArray, JsObject, Json}

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap

object StringJwkProvider {
  def fromValues(map: java.util.Map[String, AnyRef]): Jwk = {
    val values: java.util.Map[String, AnyRef] = Maps.newHashMap(map)
    val kid: String = values.remove("kid").asInstanceOf[String]
    val kty: String = values.remove("kty").asInstanceOf[String]
    val alg: String = values.remove("alg").asInstanceOf[String]
    val use: String = values.remove("use").asInstanceOf[String]
    val keyOps: Any = values.remove("key_ops")
    val x5u: String = values.remove("x5u").asInstanceOf[String]
    val x5c: java.util.List[String] = values.remove("x5c").asInstanceOf[java.util.List[String]]
    val x5t: String = values.remove("x5t").asInstanceOf[String]
    if (kty == null) throw new IllegalArgumentException("Attributes " + map + " are not from a valid jwk")
    if (keyOps.isInstanceOf[String]) new Jwk(kid, kty, alg, use, keyOps.asInstanceOf[String], x5u, x5c, x5t, values)
    else new Jwk(kid, kty, alg, use, keyOps.asInstanceOf[java.util.List[String]], x5u, x5c, x5t, values)
  }
}

class StringJwkProvider(jwkRaw: String) extends JwkProvider {

  val json: Map[String, Jwk] = Json.parse(jwkRaw).as[JsObject].\("keys").as[JsArray].value.map { js =>
    val map: java.util.Map[String, AnyRef] = js.as[Map[String, String]].asJava.asInstanceOf[java.util.Map[String, AnyRef]]
    val jwk = StringJwkProvider.fromValues(map)
    (jwk.getId, jwk)
  }.toMap

  override def get(keyId: String): Jwk = {
    json.get(keyId).get
  }
}

object JwtVerifierHelper {

  val cache = new TrieMap[String, JwkProvider]()

  def fromBase64(key: String): Array[Byte] = {
    ApacheBase64.decodeBase64(key)
  }

  def algorithm(algo: String, base64key: String, keyId: String = "none"): Algorithm = {
    if (base64key.startsWith("https://") || base64key.startsWith("http://")) {
      val cachedProvider = cache.getOrElseUpdate(base64key, new GuavaCachedJwkProvider(new UrlJwkProvider(base64key)))
      val jwk = cachedProvider.get(keyId)
      val pkey = jwk.getPublicKey
      algo match {
        case "RS256" => Algorithm.RSA256(pkey.asInstanceOf[RSAPublicKey], null)
        case "RS384" => Algorithm.RSA384(pkey.asInstanceOf[RSAPublicKey], null)
        case "RS512" => Algorithm.RSA512(pkey.asInstanceOf[RSAPublicKey], null)
        case "EC256" => Algorithm.ECDSA256(pkey.asInstanceOf[ECPublicKey], null)
        case "EC384" => Algorithm.ECDSA384(pkey.asInstanceOf[ECPublicKey], null)
        case "EC512" => Algorithm.ECDSA512(pkey.asInstanceOf[ECPublicKey], null)
      }
    } else {
      algo match {
        case "HS256" => Algorithm.HMAC256(base64key)
        case "HS384" => Algorithm.HMAC384(base64key)
        case "HS512" => Algorithm.HMAC512(base64key)
        case "RS256" =>
          val key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(fromBase64(base64key))).asInstanceOf[RSAPublicKey]
          Algorithm.RSA256(key, null)
        case "RS384" =>
          val key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(fromBase64(base64key))).asInstanceOf[RSAPublicKey]
          Algorithm.RSA384(key, null)
        case "RS512" =>
          val key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(fromBase64(base64key))).asInstanceOf[RSAPublicKey]
          Algorithm.RSA512(key, null)
        case "EC256" =>
          val key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(fromBase64(base64key))).asInstanceOf[ECPublicKey]
          Algorithm.ECDSA256(key, null)
        case "EC384" =>
          val key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(fromBase64(base64key))).asInstanceOf[ECPublicKey]
          Algorithm.ECDSA384(key, null)
        case "EC512" =>
          val key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(fromBase64(base64key))).asInstanceOf[ECPublicKey]
          Algorithm.ECDSA512(key, null)
      }
    }
  }
}