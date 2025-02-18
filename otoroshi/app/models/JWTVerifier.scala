package otoroshi.models

import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.Flow
import com.auth0.jwt.{JWT, RegisteredClaims}
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.InvalidClaimException
import com.auth0.jwt.interfaces.{DecodedJWT, Verification}
import com.github.blemale.scaffeine.Scaffeine
import com.nimbusds.jose.jwk.{ECKey, JWK, KeyType, RSAKey}
import org.apache.commons.codec.binary.{Base64 => ApacheBase64}
import otoroshi.actions.ApiActionContext
import otoroshi.api.OtoroshiEnvHolder
import otoroshi.el.{GlobalExpressionLanguage, JwtExpressionLanguage}
import otoroshi.env.Env
import otoroshi.gateway.{Errors, Retry}
import otoroshi.security.IdGenerator
import otoroshi.ssl.{DynamicSSLEngineProvider, PemUtils}
import otoroshi.storage.BasicStore
import otoroshi.utils
import otoroshi.utils.cache.Caches
import otoroshi.utils.http.MtlsConfig
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.{RegexPool, TypedMap}
import play.api.Logger
import play.api.http.websocket.{Message => PlayWSMessage}
import play.api.libs.json._
import play.api.libs.ws.WSProxyServer
import play.api.mvc.{RequestHeader, Result, Results}

import java.nio.charset.StandardCharsets
import java.security.interfaces.{ECPrivateKey, ECPublicKey, RSAPrivateKey, RSAPublicKey}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

trait AsJson {
  def asJson: JsValue
}

trait FromJson[A] {
  def fromJson(json: JsValue): Either[Throwable, A]
}

case class JwtInjection(
    decodedToken: Option[DecodedJWT] = None,
    additionalHeaders: Map[String, String] = Map.empty,
    removeHeaders: Seq[String] = Seq.empty,
    additionalCookies: Map[String, String] = Map.empty,
    removeCookies: Seq[String] = Seq.empty
) extends AsJson {
  def json: JsValue   = asJson
  def asJson: JsValue =
    Json.obj(
      "token"             -> decodedToken.map(_.getToken.json).getOrElse(JsNull).asValue,
      "additionalHeaders" -> JsObject(this.additionalHeaders.mapValues(JsString.apply)),
      "removeHeaders"     -> JsArray(this.removeHeaders.map(JsString.apply)),
      "additionalCookies" -> JsObject(this.additionalCookies.mapValues(JsString.apply)),
      "removeCookies"     -> JsArray(this.removeCookies.map(JsString.apply))
    )
}

object JwtInjection extends FromJson[JwtInjection] {
  override def fromJson(json: JsValue): Either[Throwable, JwtInjection] = {
    Try {
      JwtInjection(
        decodedToken = json.select("decodedToken").asOpt[String].map(JWT.decode),
        additionalHeaders = json.select("additionalHeaders").as[Map[String, String]],
        removeHeaders = json.select("removeHeaders").as[Seq[String]],
        additionalCookies = json.select("additionalCookies").as[Map[String, String]],
        removeCookies = json.select("removeCookies").as[Seq[String]]
      )
    }.toEither
  }
}

sealed trait JwtTokenLocation                          extends AsJson                     {
  def token(request: RequestHeader): Option[String]
  def asJwtInjection(originalToken: DecodedJWT, newToken: String): JwtInjection
}
object JwtTokenLocation                                extends FromJson[JwtTokenLocation] {
  override def fromJson(json: JsValue): Either[Throwable, JwtTokenLocation] =
    Try {
      (json \ "type").as[String] match {
        case "InQueryParam" => InQueryParam.fromJson(json)
        case "InHeader"     => InHeader.fromJson(json)
        case "InCookie"     => InCookie.fromJson(json)
      }
    } recover { case e =>
      Left(e)
    } get
}
object InQueryParam                                    extends FromJson[InQueryParam]     {
  override def fromJson(json: JsValue): Either[Throwable, InQueryParam] =
    Try {
      Right(
        InQueryParam(
          (json \ "name").as[String]
        )
      )
    } recover { case e =>
      Left(e)
    } get
}
case class InQueryParam(name: String)                  extends JwtTokenLocation           {
  def token(request: RequestHeader): Option[String]                             = request.getQueryString(name)
  def asJwtInjection(originalToken: DecodedJWT, newToken: String): JwtInjection = JwtInjection()
  override def asJson                                                           = Json.obj("type" -> "InQueryParam", "name" -> this.name)
}
object InHeader                                        extends FromJson[InHeader]         {
  override def fromJson(json: JsValue): Either[Throwable, InHeader] =
    Try {
      Right(
        InHeader(
          (json \ "name").as[String],
          (json \ "remove").as[String]
        )
      )
    } recover { case e =>
      Left(e)
    } get
}
case class InHeader(name: String, remove: String = "") extends JwtTokenLocation           {
  def token(request: RequestHeader): Option[String] = {
    request.headers.get(name).map { h =>
      h.replaceAll(remove, "")
    }
  }
  def asJwtInjection(originalToken: DecodedJWT, newToken: String): JwtInjection =
    JwtInjection(originalToken.some, additionalHeaders = Map(name -> (remove + newToken)))
  override def asJson                                                           = Json.obj("type" -> "InHeader", "name" -> this.name, "remove" -> this.remove)
}
object InCookie                                        extends FromJson[InCookie]         {
  override def fromJson(json: JsValue): Either[Throwable, InCookie] =
    Try {
      Right(
        InCookie(
          (json \ "name").as[String]
        )
      )
    } recover { case e =>
      Left(e)
    } get
}
case class InCookie(name: String)                      extends JwtTokenLocation           {
  def token(request: RequestHeader): Option[String]                             = request.cookies.get(name).map(_.value)
  def asJwtInjection(originalToken: DecodedJWT, newToken: String): JwtInjection =
    JwtInjection(originalToken.some, additionalCookies = Map(name -> newToken))
  override def asJson                                                           = Json.obj("type" -> "InCookie", "name" -> this.name)
}

sealed trait AlgoMode
case class InputMode(typ: String, kid: Option[String]) extends AlgoMode
case object OutputMode                                 extends AlgoMode

trait AlgoSettings extends AsJson {

  def name: String

  def keyId: Option[String]

  def isAsync: Boolean

  def asAlgorithm(mode: AlgoMode)(implicit env: Env): Option[Algorithm]

  def asAlgorithmF(mode: AlgoMode)(implicit env: Env, ec: ExecutionContext): Future[Option[Algorithm]] = {
    FastFuture.successful(asAlgorithm(mode)(env))
  }

  def transformValue(secret: String)(implicit env: Env): String = {
    AlgoSettings.fromCacheOrNot(
      secret,
      GlobalExpressionLanguage.apply(
        secret,
        req = None,
        service = None,
        route = None,
        apiKey = None,
        user = None,
        context = Map.empty,
        attrs = TypedMap.empty,
        env = env
      )
      // secret match {
      //   case s if s.startsWith("${config.") => {
      //     val path = s.replace("}", "").replace("${config.", "")
      //     env.configuration.get[String](path)
      //   }
      //   case s if s.startsWith("${env.") => {
      //     val envName = s.replace("}", "").replace("${env.", "")
      //     System.getenv(envName)
      //   }
      //   case s => s
      // }
    )
  }
}

object AlgoSettings                                                           extends FromJson[AlgoSettings]   {
  override def fromJson(json: JsValue): Either[Throwable, AlgoSettings] =
    Try {
      (json \ "type").as[String] match {
        case "HSAlgoSettings"    => HSAlgoSettings.fromJson(json)
        case "RSAlgoSettings"    => RSAlgoSettings.fromJson(json)
        case "ESAlgoSettings"    => ESAlgoSettings.fromJson(json)
        case "JWKSAlgoSettings"  => JWKSAlgoSettings.fromJson(json)
        case "RSAKPAlgoSettings" => RSAKPAlgoSettings.fromJson(json)
        case "ESKPAlgoSettings"  => ESKPAlgoSettings.fromJson(json)
        case "KidAlgoSettings"   => KidAlgoSettings.fromJson(json)
      }
    } recover { case e =>
      Left(e)
    } get

  private val cache = Caches.bounded[String, String](10000)

  def fromCacheOrNot(key: String, orElse: => String): String = {
    key match {
      case k if k.startsWith("${") => cache.get(key, _ => orElse)
      case k                       => key
    }
  }
}
object HSAlgoSettings                                                         extends FromJson[HSAlgoSettings] {
  override def fromJson(json: JsValue): Either[Throwable, HSAlgoSettings] =
    Try {
      Right(
        HSAlgoSettings(
          (json \ "size").as[Int],
          (json \ "secret").as[String],
          (json \ "base64").asOpt[Boolean].getOrElse(false)
        )
      )
    } recover { case e =>
      Left(e)
    } get
}
case class HSAlgoSettings(size: Int, secret: String, base64: Boolean = false) extends AlgoSettings             {

  def keyId: Option[String] = None

  def isAsync: Boolean = false

  override def asAlgorithm(mode: AlgoMode)(implicit env: Env): Option[Algorithm] = {
    size match {
      case 256 if base64 => Some(Algorithm.HMAC256(ApacheBase64.decodeBase64(transformValue(secret))))
      case 384 if base64 => Some(Algorithm.HMAC384(ApacheBase64.decodeBase64(transformValue(secret))))
      case 512 if base64 => Some(Algorithm.HMAC512(ApacheBase64.decodeBase64(transformValue(secret))))
      case 256           => Some(Algorithm.HMAC256(transformValue(secret)))
      case 384           => Some(Algorithm.HMAC384(transformValue(secret)))
      case 512           => Some(Algorithm.HMAC512(transformValue(secret)))
      case _             => None
    }
  }

  override def name: String = "HSAlgoSettings"

  override def asJson =
    Json.obj(
      "type"   -> "HSAlgoSettings",
      "size"   -> this.size,
      "secret" -> this.secret,
      "base64" -> this.base64
    )
}
object RSAlgoSettings                                                               extends FromJson[RSAlgoSettings] {
  override def fromJson(json: JsValue): Either[Throwable, RSAlgoSettings] =
    Try {
      Right(
        RSAlgoSettings(
          (json \ "size").as[Int],
          (json \ "publicKey").as[String],
          (json \ "privateKey").asOpt[String]
        )
      )
    } recover { case e =>
      Left(e)
    } get
}
case class RSAlgoSettings(size: Int, publicKey: String, privateKey: Option[String]) extends AlgoSettings             {

  def keyId: Option[String] = None

  def isAsync: Boolean = false

  def getPublicKey(value: String): RSAPublicKey = {
    val publicBytes = ApacheBase64.decodeBase64(
      value.replace("-----BEGIN PUBLIC KEY-----\n", "").replace("\n-----END PUBLIC KEY-----", "").trim()
    )
    //val keySpec    = new X509EncodedKeySpec(publicBytes)
    //val keyFactory = KeyFactory.getInstance("RSA")
    //keyFactory.generatePublic(keySpec).asInstanceOf[RSAPublicKey]
    PemUtils.getPublicKey(publicBytes, "RSA").asInstanceOf[RSAPublicKey]
  }

  def getPrivateKey(value: String): RSAPrivateKey = {
    if (value.trim.isEmpty) {
      null // Yeah, I know ...
    } else {
      val privateBytes = ApacheBase64.decodeBase64(
        value.replace("-----BEGIN PRIVATE KEY-----\n", "").replace("\n-----END PRIVATE KEY-----", "").trim()
      )
      // val keySpec    = new PKCS8EncodedKeySpec(privateBytes)
      // val keyFactory = KeyFactory.getInstance("RSA")
      // keyFactory.generatePrivate(keySpec).asInstanceOf[RSAPrivateKey]
      PemUtils.getPrivateKey(privateBytes, "RSA").asInstanceOf[RSAPrivateKey]
    }
  }

  override def asAlgorithm(mode: AlgoMode)(implicit env: Env): Option[Algorithm] = {
    size match {
      case 256 =>
        Some(
          Algorithm.RSA256(
            getPublicKey(transformValue(publicKey)),
            privateKey.filterNot(_.trim.isEmpty).map(pk => getPrivateKey(transformValue(pk))).orNull
          )
        )
      case 384 =>
        Some(
          Algorithm.RSA384(
            getPublicKey(transformValue(publicKey)),
            privateKey.filterNot(_.trim.isEmpty).map(pk => getPrivateKey(transformValue(pk))).orNull
          )
        )
      case 512 =>
        Some(
          Algorithm.RSA512(
            getPublicKey(transformValue(publicKey)),
            privateKey.filterNot(_.trim.isEmpty).map(pk => getPrivateKey(transformValue(pk))).orNull
          )
        )
      case _   => None
    }
  }

  override def name: String = "RSAlgoSettings"

  override def asJson =
    Json.obj(
      "type"       -> "RSAlgoSettings",
      "size"       -> this.size,
      "publicKey"  -> this.publicKey,
      "privateKey" -> this.privateKey.map(pk => JsString(pk)).getOrElse(JsNull).as[JsValue]
    )
}
object ESAlgoSettings                                                               extends FromJson[ESAlgoSettings] {
  override def fromJson(json: JsValue): Either[Throwable, ESAlgoSettings] =
    Try {
      Right(
        ESAlgoSettings(
          (json \ "size").as[Int],
          (json \ "publicKey").as[String],
          (json \ "privateKey").asOpt[String]
        )
      )
    } recover { case e =>
      Left(e)
    } get
}
case class ESAlgoSettings(size: Int, publicKey: String, privateKey: Option[String]) extends AlgoSettings             {

  def keyId: Option[String] = None

  def isAsync: Boolean = false

  def getPublicKey(value: String): ECPublicKey = {
    val publicBytes = ApacheBase64.decodeBase64(
      value.replace("-----BEGIN PUBLIC KEY-----\n", "").replace("\n-----END PUBLIC KEY-----", "").trim()
    )
    //val keySpec    = new X509EncodedKeySpec(publicBytes)
    //val keyFactory = KeyFactory.getInstance("EC")
    //keyFactory.generatePublic(keySpec).asInstanceOf[ECPublicKey]
    PemUtils.getPublicKey(publicBytes, "EC").asInstanceOf[ECPublicKey]
  }

  def getPrivateKey(value: String): ECPrivateKey = {
    if (value.trim.isEmpty) {
      null // Yeah, I know ...
    } else {
      val privateBytes = ApacheBase64.decodeBase64(
        value.replace("-----BEGIN PRIVATE KEY-----\n", "").replace("\n-----END PRIVATE KEY-----", "").trim()
      )
      //val keySpec    = new PKCS8EncodedKeySpec(privateBytes)
      //val keyFactory = KeyFactory.getInstance("EC")
      //keyFactory.generatePrivate(keySpec).asInstanceOf[ECPrivateKey]
      PemUtils.getPrivateKey(privateBytes, "EC").asInstanceOf[ECPrivateKey]
    }
  }

  override def asAlgorithm(mode: AlgoMode)(implicit env: Env): Option[Algorithm] = {
    size match {
      case 256 =>
        Some(
          Algorithm.ECDSA256(
            getPublicKey(transformValue(publicKey)),
            privateKey.filterNot(_.trim.isEmpty).map(pk => getPrivateKey(transformValue(pk))).orNull
          )
        )
      case 384 =>
        Some(
          Algorithm.ECDSA384(
            getPublicKey(transformValue(publicKey)),
            privateKey.filterNot(_.trim.isEmpty).map(pk => getPrivateKey(transformValue(pk))).orNull
          )
        )
      case 512 =>
        Some(
          Algorithm.ECDSA512(
            getPublicKey(transformValue(publicKey)),
            privateKey.filterNot(_.trim.isEmpty).map(pk => getPrivateKey(transformValue(pk))).orNull
          )
        )
      case _   => None
    }
  }
  override def name: String = "ESAlgoSettings"

  override def asJson =
    Json.obj(
      "type"       -> "ESAlgoSettings",
      "size"       -> this.size,
      "publicKey"  -> this.publicKey,
      "privateKey" -> this.privateKey.map(pk => JsString(pk)).getOrElse(JsNull).as[JsValue]
    )
}
object JWKSAlgoSettings extends FromJson[JWKSAlgoSettings] {

  val cache = Caches.bounded[String, (Long, Map[String, com.nimbusds.jose.jwk.JWK], Boolean)](1000)

  override def fromJson(json: JsValue): Either[Throwable, JWKSAlgoSettings] = {
    Try {
      Right(
        JWKSAlgoSettings(
          (json \ "url").as[String],
          (json \ "headers").asOpt[Map[String, String]].getOrElse(Map.empty[String, String]),
          (json \ "timeout")
            .asOpt[Long]
            .map(v => FiniteDuration(v, TimeUnit.MILLISECONDS))
            .getOrElse(FiniteDuration(2000, TimeUnit.MILLISECONDS)),
          (json \ "ttl")
            .asOpt[Long]
            .map(v => FiniteDuration(v, TimeUnit.MILLISECONDS))
            .getOrElse(FiniteDuration(60 * 60 * 1000, TimeUnit.MILLISECONDS)),
          (json \ "kty").asOpt[String].map(v => KeyType.parse(v)).getOrElse(KeyType.RSA),
          (json \ "proxy").asOpt[JsValue].flatMap(v => WSProxyServerJson.proxyFromJson(v)),
          MtlsConfig.read(
            (json \ "mtlsConfig")
              .asOpt[JsValue]
              .orElse((json \ "tlsConfig").asOpt[JsValue])
              .orElse((json \ "tls_config").asOpt[JsValue])
          )
        )
      )
    } recover { case e =>
      Left(e)
    } get
  }
}
case class JWKSAlgoSettings(
    url: String,
    headers: Map[String, String],
    timeout: FiniteDuration,
    ttl: FiniteDuration,
    kty: KeyType,
    proxy: Option[WSProxyServer] = None,
    tlsConfig: MtlsConfig
) extends AlgoSettings {

  val logger = Logger("otoroshi-jwks")

  def keyId: Option[String] = None

  def isAsync: Boolean = {
    JWKSAlgoSettings.cache.getIfPresent(url) match {
      case Some((stop, keys, false)) if stop > System.currentTimeMillis()  => false
      case Some((stop, keys, false)) if stop <= System.currentTimeMillis() => true
      case Some((_, keys, true))                                           => false
      case None                                                            => true
    }
  }

  def algoFromJwk(alg: String, jwk: JWK): Option[Algorithm] = {
    jwk match {
      case rsaKey: RSAKey =>
        alg match {
          case "RS256" => Some(Algorithm.RSA256(rsaKey.toRSAPublicKey, null))
          case "RS384" => Some(Algorithm.RSA384(rsaKey.toRSAPublicKey, null))
          case "RS512" => Some(Algorithm.RSA512(rsaKey.toRSAPublicKey, null))
        }
      case ecKey: ECKey   =>
        alg match {
          case "ES256" => Some(Algorithm.ECDSA256(ecKey.toECPublicKey, null))
          case "ES384" => Some(Algorithm.ECDSA384(ecKey.toECPublicKey, null))
          case "ES512" => Some(Algorithm.ECDSA512(ecKey.toECPublicKey, null))
        }
      case _              => None
    }
  }

  def fetchJWKS(alg: String, kid: String, oldStop: Long, oldKeys: Map[String, com.nimbusds.jose.jwk.JWK])(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Option[Algorithm]] = {
    import otoroshi.utils.http.Implicits._
    implicit val s = env.otoroshiScheduler
    // val protocol = url.split("://").toSeq.headOption.getOrElse("http")
    JWKSAlgoSettings.cache.put(url, (oldStop, oldKeys, true))
    Retry
      .retry(10, delay = 20, ctx = s"try to fetch JWKS at '$url'") { _ =>
        env.MtlsWs
          .url(url, tlsConfig)
          .withRequestTimeout(timeout)
          .withHttpHeaders(headers.toSeq: _*)
          .withMaybeProxyServer(
            proxy.orElse(env.datastores.globalConfigDataStore.latestSafe.flatMap(_.proxies.jwk))
          )
          .get()
          .map { resp =>
            JWKSAlgoSettings.cache.put(url, (oldStop, oldKeys, false))
            if (resp.status != 200) {
              logger.error(s"Error while reading JWKS at '$url' - ${resp.status} - ${resp.body}")
              None
            } else {
              val stop = System.currentTimeMillis() + ttl.toMillis
              val obj  = Json.parse(resp.body).as[JsObject]
              (obj \ "keys").asOpt[JsArray] match {
                case Some(values) => {
                  val keys = values.value.map { k =>
                    val jwk = JWK.parse(Json.stringify(k))
                    (jwk.getKeyID, jwk)
                  }.toMap
                  JWKSAlgoSettings.cache.put(url, (stop, keys, false))
                  keys.get(kid) match {
                    case Some(jwk) => algoFromJwk(alg, jwk)
                    case None      => None
                  }
                }
                case None         => None
              }
            }
          }
      }
      .recover { case e =>
        JWKSAlgoSettings.cache.put(url, (oldStop, oldKeys, false))
        logger.error(s"Error while reading JWKS $url", e)
        None
      }
  }

  override def asAlgorithm(mode: AlgoMode)(implicit env: Env): Option[Algorithm] = {
    if (isAsync) {
      logger.warn(s"loading JWKS content from '${url}' blocking style !")
    }
    // AWAIT: valid
    Await.result(asAlgorithmF(mode)(env, env.otoroshiExecutionContext), timeout)
  }

  override def asAlgorithmF(mode: AlgoMode)(implicit env: Env, ec: ExecutionContext): Future[Option[Algorithm]] = {
    mode match {
      case InputMode(alg, Some(kid)) => {
        JWKSAlgoSettings.cache.getIfPresent(url) match {
          case Some((stop, keys, false)) if stop > System.currentTimeMillis()  => {
            keys.get(kid) match {
              case Some(jwk) => FastFuture.successful(algoFromJwk(alg, jwk))
              case None      => FastFuture.successful(None)
            }
          }
          case Some((stop, keys, false)) if stop <= System.currentTimeMillis() => fetchJWKS(alg, kid, stop, keys)
          case Some((_, keys, true))                                           => {
            keys.get(kid) match {
              case Some(jwk) => FastFuture.successful(algoFromJwk(alg, jwk))
              case None      => FastFuture.successful(None)
            }
          }
          case None                                                            => fetchJWKS(alg, kid, System.currentTimeMillis() + ttl.toMillis, Map.empty)
        }
      }
      case _                         => FastFuture.successful(None)
    }
  }

  override def name: String = "JWKSAlgoSettings"

  override def asJson: JsValue =
    Json.obj(
      "type"       -> "JWKSAlgoSettings",
      "url"        -> url,
      "timeout"    -> timeout.toMillis,
      "headers"    -> headers,
      "ttl"        -> ttl.toMillis,
      "kty"        -> kty.getValue,
      "proxy"      -> WSProxyServerJson.maybeProxyToJson(proxy),
      "tls_config" -> tlsConfig.json,
      "mtlsConfig" -> tlsConfig.json
    )
}

object RSAKPAlgoSettings                                extends FromJson[RSAKPAlgoSettings] {
  override def fromJson(json: JsValue): Either[Throwable, RSAKPAlgoSettings] =
    Try {
      Right(
        RSAKPAlgoSettings(
          (json \ "size").as[Int],
          (json \ "certId").as[String]
        )
      )
    } recover { case e =>
      Left(e)
    } get
}
case class RSAKPAlgoSettings(size: Int, certId: String) extends AlgoSettings                {

  import scala.concurrent.duration._

  def keyId: Option[String] = certId.some

  def isAsync: Boolean = false

  override def asAlgorithm(mode: AlgoMode)(implicit env: Env): Option[Algorithm] = {
    DynamicSSLEngineProvider.certificates
      .get(certId)
      .orElse {
        DynamicSSLEngineProvider.certificates.values.find(_.entityMetadata.get("nextCertificate").contains(certId))
      }
      .flatMap { cert =>
        val keyPair = cert.cryptoKeyPair
        (keyPair.getPublic, keyPair.getPrivate) match {
          case (pk: RSAPublicKey, pkk: RSAPrivateKey) =>
            size match {
              case 256 => Some(Algorithm.RSA256(pk, pkk))
              case 384 => Some(Algorithm.RSA384(pk, pkk))
              case 512 => Some(Algorithm.RSA512(pk, pkk))
              case _   => None
            }
          case _                                      => None
        }
      }
  }

  override def name: String = "RSAKPAlgoSettings"

  override def asJson =
    Json.obj(
      "type"   -> "RSAKPAlgoSettings",
      "size"   -> this.size,
      "certId" -> this.certId
    )
}

object ESKPAlgoSettings                                extends FromJson[ESKPAlgoSettings] {
  override def fromJson(json: JsValue): Either[Throwable, ESKPAlgoSettings] =
    Try {
      Right(
        ESKPAlgoSettings(
          (json \ "size").as[Int],
          (json \ "certId").as[String]
        )
      )
    } recover { case e =>
      Left(e)
    } get
}
case class ESKPAlgoSettings(size: Int, certId: String) extends AlgoSettings               {

  import scala.concurrent.duration._

  def keyId: Option[String] = certId.some

  def isAsync: Boolean = false

  override def asAlgorithm(mode: AlgoMode)(implicit env: Env): Option[Algorithm] = {
    DynamicSSLEngineProvider.certificates
      .get(certId)
      .orElse {
        DynamicSSLEngineProvider.certificates.values.find(_.entityMetadata.get("nextCertificate").contains(certId))
      }
      .flatMap { cert =>
        val keyPair = cert.cryptoKeyPair
        (keyPair.getPublic, keyPair.getPrivate) match {
          case (pk: ECPublicKey, pkk: ECPrivateKey) =>
            size match {
              case 256 => Some(Algorithm.ECDSA256(pk, pkk))
              case 384 => Some(Algorithm.ECDSA384(pk, pkk))
              case 512 => Some(Algorithm.ECDSA512(pk, pkk))
              case _   => None
            }
          case _                                    => None
        }
      }
  }

  override def name: String = "ESKPAlgoSettings"

  override def asJson =
    Json.obj(
      "type"   -> "ESKPAlgoSettings",
      "size"   -> this.size,
      "certId" -> this.certId
    )
}

object KidAlgoSettings extends FromJson[KidAlgoSettings] {
  override def fromJson(json: JsValue): Either[Throwable, KidAlgoSettings] =
    Try {
      Right(
        KidAlgoSettings(
          (json \ "onlyExposedCerts").asOpt[Boolean].getOrElse(false)
        )
      )
    } recover { case e =>
      Left(e)
    } get
}

case class KidAlgoSettings(onlyExposedCerts: Boolean) extends AlgoSettings {

  import scala.concurrent.duration._

  def keyId: Option[String] = None

  def isAsync: Boolean = false

  override def asAlgorithm(mode: AlgoMode)(implicit env: Env): Option[Algorithm] = {
    mode match {
      case InputMode(typ, Some(kid)) => {
        val certs = DynamicSSLEngineProvider.certificates
        val certOpt = {
          certs.get(kid).orElse(certs.values.find(_.entityMetadata.get("nextCertificate").contains(kid))).filter {
            case c if !c.exposed && onlyExposedCerts => false
            case c                                   => true
          }
        }
        // logger.info(s"kid: $kid, typ: $typ, certOpt=${certOpt}")
        certOpt.flatMap { cert =>
          val keyPair = cert.cryptoKeyPair
          (keyPair.getPublic, keyPair.getPrivate) match {
            case (pk: ECPublicKey, pkk: ECPrivateKey)   =>
              typ match {
                case "ES256" => Some(Algorithm.ECDSA256(pk, pkk))
                case "ES384" => Some(Algorithm.ECDSA384(pk, pkk))
                case "ES512" => Some(Algorithm.ECDSA512(pk, pkk))
                case _       => None
              }
            case (pk: RSAPublicKey, pkk: RSAPrivateKey) =>
              typ match {
                case "RS256" => Some(Algorithm.RSA256(pk, pkk))
                case "RS384" => Some(Algorithm.RSA384(pk, pkk))
                case "RS512" => Some(Algorithm.RSA512(pk, pkk))
                case _       => None
              }
            case _                                      => None
          }
        }
      }
      case _                         => None
    }
  }

  override def name: String = "KidAlgoSettings"

  override def asJson =
    Json.obj(
      "type"             -> "KidAlgoSettings",
      "onlyExposedCerts" -> this.onlyExposedCerts
    )
}

object MappingSettings                                                                     extends FromJson[MappingSettings]   {
  override def fromJson(json: JsValue): Either[Throwable, MappingSettings] =
    Try {
      Right(
        MappingSettings(
          (json \ "map").asOpt[Map[String, String]].getOrElse(Map.empty[String, String]),
          (json \ "values").asOpt[JsObject].getOrElse(Json.obj()),
          (json \ "remove").asOpt[Seq[String]].getOrElse(Seq.empty[String])
        )
      )
    } recover { case e =>
      Left(e)
    } get
}
case class MappingSettings(
    map: Map[String, String] = Map.empty,
    values: JsObject = Json.obj(),
    remove: Seq[String] = Seq.empty[String]
)                                                                                          extends AsJson                      {
  override def asJson =
    Json.obj(
      "map"    -> JsObject(map.mapValues(JsString.apply)),
      "values" -> values,
      "remove" -> JsArray(remove.map(JsString.apply))
    )
}
object TransformSettings                                                                   extends FromJson[TransformSettings] {
  override def fromJson(json: JsValue): Either[Throwable, TransformSettings] =
    Try {
      for {
        location        <- JwtTokenLocation.fromJson((json \ "location").as[JsValue])
        mappingSettings <- MappingSettings.fromJson((json \ "mappingSettings").as[JsValue])
      } yield TransformSettings(location, mappingSettings)
    } recover { case e =>
      Left(e)
    } get
}
case class TransformSettings(location: JwtTokenLocation, mappingSettings: MappingSettings) extends AsJson                      {
  override def asJson =
    Json.obj(
      "location"        -> location.asJson,
      "mappingSettings" -> mappingSettings.asJson
    )
}

object VerificationSettings extends FromJson[VerificationSettings] {
  override def fromJson(json: JsValue): Either[Throwable, VerificationSettings] =
    Try {
      Right(
        VerificationSettings(
          (json \ "fields").as[Map[String, String]],
          (json \ "arrayFields").as[Map[String, String]]
        )
      )
    } recover { case e =>
      Left(e)
    } get
}

case class VerificationSettings(fields: Map[String, String] = Map.empty, arrayFields: Map[String, String] = Map.empty)
    extends AsJson {
  def additionalVerification(jwt: DecodedJWT): DecodedJWT = {
    val token: JsObject = Try(Json.parse(ApacheBase64.decodeBase64(jwt.getPayload)).as[JsObject]).getOrElse(Json.obj())
    arrayFields.foldLeft(jwt)((a, b) => {
      val values: Set[String]         = (token \ b._1)
        .as[JsArray]
        .value
        .collect {
          case JsNumber(nbr) => nbr.toString()
          case JsBoolean(b)  => b.toString
          case JsString(str) => str
        } toSet
      val expectedValues: Set[String] = if (b._2.contains(",")) {
        b._2.split(",").map(_.trim).toSet
      } else {
        Set(b._2)
      }
      if (values.intersect(expectedValues) != expectedValues)
        throw new InvalidClaimException(String.format("The Claim '%s' value doesn't match the required one.", b._1))
      jwt
    })
  }
  def asVerification(algorithm: Algorithm): Verification = {
    val verification = fields.foldLeft(
      JWT
        .require(algorithm)
        .acceptLeeway(10)
    ) {
      case (a, b) if b._1 == RegisteredClaims.AUDIENCE => a.withAudience(b._2)
      case (a, b) if b._1 == RegisteredClaims.ISSUER   => a.withIssuer(b._2)
      case (a, b) if b._1 == RegisteredClaims.JWT_ID   => a.withJWTId(b._2)
      case (a, b) if b._1 == RegisteredClaims.SUBJECT  => a.withSubject(b._2)
      case (a, b)                                      => a.withClaim(b._1, b._2)
    }
    arrayFields.foldLeft(verification)((a, b) => {
      if (b._2.contains(",")) {
        val values = b._2.split(",").map(_.trim)
        a.withArrayClaim(b._1, values: _*)
      } else {
        a.withArrayClaim(b._1, b._2)
      }
    })
  }

  override def asJson =
    Json.obj(
      "fields"      -> JsObject(this.fields.mapValues(JsString.apply)),
      "arrayFields" -> JsObject(this.arrayFields.mapValues(JsString.apply))
    )
}

object VerifierStrategy extends FromJson[VerifierStrategy] {
  override def fromJson(json: JsValue): Either[Throwable, VerifierStrategy] =
    Try {
      (json \ "type").as[String] match {
        case "PassThrough"  => PassThrough.fromJson(json)
        case "Sign"         => Sign.fromJson(json)
        case "Transform"    => Transform.fromJson(json)
        case "DefaultToken" => DefaultToken.fromJson(json)
      }
    } recover { case e =>
      Left(e)
    } get
}

sealed trait VerifierStrategy extends AsJson {
  def name: String
  def verificationSettings: VerificationSettings
}

object DefaultToken extends FromJson[VerifierStrategy] {
  override def fromJson(json: JsValue): Either[Throwable, VerifierStrategy] =
    Try {
      Right(
        DefaultToken(
          strict = (json \ "strict").asOpt[Boolean].getOrElse(true),
          token = (json \ "token").asOpt[JsValue].getOrElse(Json.obj()),
          verificationSettings = VerificationSettings
            .fromJson((json \ "verificationSettings").as[JsValue])
            .toOption
            .getOrElse(VerificationSettings())
        )
      )
    } recover { case e =>
      Left(e)
    } get
}

case class DefaultToken(
    strict: Boolean = true,
    token: JsValue, // TODO.next: we need to use a string here !
    verificationSettings: VerificationSettings = VerificationSettings()
) extends VerifierStrategy {
  def name: String    = "default_token"
  override def asJson =
    Json.obj(
      "type"                 -> "DefaultToken",
      "strict"               -> strict,
      "token"                -> token,
      "verificationSettings" -> verificationSettings.asJson
    )
}

object PassThrough extends FromJson[VerifierStrategy] {
  override def fromJson(json: JsValue): Either[Throwable, VerifierStrategy] =
    Try {
      for {
        verificationSettings <- VerificationSettings.fromJson((json \ "verificationSettings").as[JsValue])
      } yield PassThrough(verificationSettings)
    } recover { case e =>
      Left(e)
    } get
}

case class PassThrough(verificationSettings: VerificationSettings) extends VerifierStrategy {
  def name: String    = "pass_through"
  override def asJson =
    Json.obj(
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
    } recover { case e =>
      Left(e)
    } get
}

case class Sign(verificationSettings: VerificationSettings, algoSettings: AlgoSettings) extends VerifierStrategy {
  def name: String    = "sign"
  override def asJson =
    Json.obj(
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
    } recover { case e =>
      Left(e)
    } get
}

case class Transform(
    verificationSettings: VerificationSettings,
    transformSettings: TransformSettings,
    algoSettings: AlgoSettings
) extends VerifierStrategy {
  def name: String    = "transform"
  override def asJson =
    Json.obj(
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
  def asGlobal: GlobalJwtVerifier = this.asInstanceOf[GlobalJwtVerifier]

  def isAsync: Boolean = algoSettings.isAsync && (strategy match {
    case DefaultToken(_, _, _)         => false
    case PassThrough(_)                => false
    case Sign(_, algoSettings)         => algoSettings.isAsync
    case Transform(_, _, algoSettings) => algoSettings.isAsync
  })

  private def sign(token: JsObject, algorithm: Algorithm, kid: Option[String]): String = {
    val headerJson     = Json
      .obj("alg" -> algorithm.getName, "typ" -> "JWT")
      .applyOnWithOpt(kid)((h, id) => h ++ Json.obj("kid" -> id))
    val header         = ApacheBase64.encodeBase64URLSafeString(Json.stringify(headerJson).getBytes(StandardCharsets.UTF_8))
    val payload        = ApacheBase64.encodeBase64URLSafeString(Json.stringify(token).getBytes(StandardCharsets.UTF_8))
    val content        = String.format("%s.%s", header, payload)
    val signatureBytes =
      algorithm.sign(header.getBytes(StandardCharsets.UTF_8), payload.getBytes(StandardCharsets.UTF_8))
    val signature      = ApacheBase64.encodeBase64URLSafeString(signatureBytes)
    s"$content.$signature"
  }

  def verifyWs(
      request: RequestHeader,
      desc: ServiceDescriptor,
      apikey: Option[ApiKey],
      user: Option[PrivateAppsUser],
      elContext: Map[String, String],
      attrs: TypedMap
  )(
      f: JwtInjection => Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]]
  )(implicit ec: ExecutionContext, env: Env): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] = {
    internalVerify(request, desc.some, apikey, user, elContext, attrs, sendEvent = true)(f).map {
      case Left(badResult)   => Left[Result, Flow[PlayWSMessage, PlayWSMessage, _]](badResult)
      case Right(goodResult) => goodResult
    }
  }

  def verifyGen[A](
      request: RequestHeader,
      desc: ServiceDescriptor,
      apikey: Option[ApiKey],
      user: Option[PrivateAppsUser],
      elContext: Map[String, String],
      attrs: TypedMap
  )(
      f: JwtInjection => Future[Either[Result, A]]
  )(implicit ec: ExecutionContext, env: Env): Future[Either[Result, A]] = {
    internalVerify(request, desc.some, apikey, user, elContext, attrs, sendEvent = true)(f).map {
      case Left(badResult)   => Left[Result, A](badResult)
      case Right(goodResult) => goodResult
    }
  }

  def verify(
      request: RequestHeader,
      desc: ServiceDescriptor,
      apikey: Option[ApiKey],
      user: Option[PrivateAppsUser],
      elContext: Map[String, String],
      attrs: TypedMap
  )(
      f: JwtInjection => Future[Result]
  )(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    internalVerify(request, desc.some, apikey, user, elContext, attrs, sendEvent = true)(f).map {
      case Left(badResult)   => badResult
      case Right(goodResult) => goodResult
    }
  }

  private[models] def internalVerify[A](
      request: RequestHeader,
      descOpt: Option[ServiceDescriptor],
      apikey: Option[ApiKey],
      user: Option[PrivateAppsUser],
      elContext: Map[String, String],
      attrs: TypedMap,
      sendEvent: Boolean
  )(
      f: JwtInjection => Future[A]
  )(implicit ec: ExecutionContext, env: Env): Future[Either[Result, A]] = {
    if (isAsync) {
      internalVerifyAsync(request, descOpt, apikey, user, elContext, attrs, sendEvent)(f)
    } else {
      internalVerifySync(request, descOpt, apikey, user, elContext, attrs, sendEvent) match {
        case Left(result)     => result.left.vfuture
        case Right(injection) => f(injection).map(a => a.right)
      }
    }
  }

  private[models] def internalVerifySync[A](
      request: RequestHeader,
      descOpt: Option[ServiceDescriptor],
      apikey: Option[ApiKey],
      user: Option[PrivateAppsUser],
      elContext: Map[String, String],
      attrs: TypedMap,
      sendEvent: Boolean
  )(implicit ec: ExecutionContext, env: Env): Either[Result, JwtInjection] = env.metrics.withTimer(
    "ng-report-call-access-validator-plugins-plugin-cp:otoroshi.next.plugins.JwtVerification-int-sync"
  ) {

    import Implicits._

    source.token(request) match {
      case None        =>
        strategy match {
          case DefaultToken(true, newToken, _) => {
            // it's okay to use algoSettings here as it's the default token, so it's not used as an input but as output algo
            algoSettings.asAlgorithm(OutputMode) match {
              case None                  =>
                Errors
                  .craftResponseResultSync(
                    "error.bad.output.algorithm.name",
                    Results.BadRequest,
                    request,
                    descOpt,
                    None,
                    attrs = attrs,
                    sendEvent = sendEvent
                  )
                  .left[JwtInjection]
              case Some(outputAlgorithm) => {
                val moreCtx           = Map(
                  "jti" -> IdGenerator.uuid,
                  "iat" -> s"${Math.floor(System.currentTimeMillis() / 1000L).toLong}",
                  "nbf" -> s"${Math.floor(System.currentTimeMillis() / 1000L).toLong}",
                  "iss" -> "Otoroshi",
                  "exp" -> s"${Math.floor((System.currentTimeMillis() + 60000L) / 1000L).toLong}",
                  "sub" -> apikey.map(_.clientName).orElse(user.map(_.email)).getOrElse("anonymous"),
                  "aud" -> "backend"
                )
                val interpolatedToken = JwtExpressionLanguage
                  .fromJson(
                    newToken,
                    Some(request),
                    descOpt,
                    None,
                    apikey,
                    user,
                    elContext ++ moreCtx,
                    attrs = attrs,
                    env
                  )
                  .as[JsObject]
                val correctedToken    = interpolatedToken
                  .applyOnIf(interpolatedToken.select("nbf").asOpt[String].isDefined) { obj =>
                    Try(interpolatedToken.select("nbf").asString.toLong) match {
                      case Failure(e)   => obj
                      case Success(lng) => obj ++ Json.obj("nbf" -> lng)
                    }
                  }
                  .applyOnIf(interpolatedToken.select("iat").asOpt[String].isDefined) { obj =>
                    Try(interpolatedToken.select("iat").asString.toLong) match {
                      case Failure(e)   => obj
                      case Success(lng) => obj ++ Json.obj("iat" -> lng)
                    }
                  }
                  .applyOnIf(interpolatedToken.select("exp").asOpt[String].isDefined) { obj =>
                    Try(interpolatedToken.select("exp").asString.toLong) match {
                      case Failure(e)   => obj
                      case Success(lng) => obj ++ Json.obj("exp" -> lng)
                    }
                  }
                // it's okay to use algoSettings here as it's the default token, so it's not used as an input but as output algo
                val signedToken       = sign(correctedToken, outputAlgorithm, algoSettings.keyId)
                val decodedToken      = JWT.decode(signedToken)
                attrs.put(otoroshi.plugins.Keys.MatchedInputTokenKey  -> correctedToken)
                attrs.put(otoroshi.plugins.Keys.MatchedOutputTokenKey -> correctedToken)
                source.asJwtInjection(decodedToken, signedToken).right
              }
            }
          }
          case DefaultToken(false, _, _)       => {
            JwtInjection().right
          }
          case _ if strict                     => {
            Errors
              .craftResponseResultSync(
                "error.expected.token.not.found",
                Results.BadRequest,
                request,
                descOpt,
                None,
                attrs = attrs,
                sendEvent = sendEvent
              )
              .left[JwtInjection]
          }
          case _ if !strict                    => JwtInjection().right[Result]
        }
      case Some(token) =>
        val tokenParts  = token.split("\\.")
        val signature   = tokenParts.last
        val tokenHeader = Try(Json.parse(ApacheBase64.decodeBase64(tokenParts(0)))).getOrElse(Json.obj())
        val kid         = (tokenHeader \ "kid").asOpt[String]
        val alg         = (tokenHeader \ "alg").asOpt[String].getOrElse("RS256")
        algoSettings.asAlgorithm(InputMode(alg, kid)) match {
          case None            =>
            Errors
              .craftResponseResultSync(
                "error.bad.input.algorithm.name",
                Results.BadRequest,
                request,
                descOpt,
                None,
                attrs = attrs
              )
              .left[JwtInjection]
          case Some(algorithm) => {
            val verification       = strategy.verificationSettings.asVerification(algorithm)
            val id: String         = this match {
              case v: RefJwtVerifier    => v.ids.mkString("-")
              case v: GlobalJwtVerifier => v.id
              case v: LocalJwtVerifier  => descOpt.map(_.id).getOrElse(request.id.toString)
            }
            val key                = s"${id}-${signature}"
            val verificationResult = JwtVerifier.signatureCache.get(key, _ => Try(verification.build().verify(token)))
            verificationResult match {
              case Failure(e)            =>
                logger.error("Bad JWT token", e)
                Errors
                  .craftResponseResultSync(
                    "error.bad.token",
                    Results.BadRequest,
                    request,
                    descOpt,
                    None,
                    attrs = attrs,
                    sendEvent = sendEvent
                  )
                  .left[JwtInjection]
              case Success(decodedToken) =>
                strategy match {
                  case s @ DefaultToken(true, _, _)           => {
                    Errors
                      .craftResponseResultSync(
                        "error.token.already.present",
                        Results.BadRequest,
                        request,
                        descOpt,
                        None,
                        attrs = attrs,
                        sendEvent = sendEvent
                      )
                      .left[JwtInjection]
                  }
                  case s @ DefaultToken(false, _, _)          =>
                    val jsonToken = Json.parse(ApacheBase64.decodeBase64(decodedToken.getPayload)).as[JsObject]
                    attrs.put(otoroshi.plugins.Keys.MatchedInputTokenKey  -> jsonToken)
                    attrs.put(otoroshi.plugins.Keys.MatchedOutputTokenKey -> jsonToken)
                    JwtInjection(decodedToken.some).right[Result]
                  case s @ PassThrough(_)                     =>
                    val jsonToken = Json.parse(ApacheBase64.decodeBase64(decodedToken.getPayload)).as[JsObject]
                    attrs.put(otoroshi.plugins.Keys.MatchedInputTokenKey  -> jsonToken)
                    attrs.put(otoroshi.plugins.Keys.MatchedOutputTokenKey -> jsonToken)
                    JwtInjection(decodedToken.some).right[Result]
                  case s @ Sign(_, aSettings)                 =>
                    aSettings.asAlgorithm(OutputMode) match {
                      case None                  =>
                        Errors
                          .craftResponseResultSync(
                            "error.bad.output.algorithm.name",
                            Results.BadRequest,
                            request,
                            descOpt,
                            None,
                            attrs = attrs,
                            sendEvent = sendEvent
                          )
                          .left[JwtInjection]
                      case Some(outputAlgorithm) => {
                        val jsonToken = Json.parse(ApacheBase64.decodeBase64(decodedToken.getPayload)).as[JsObject]
                        val newToken  = sign(
                          jsonToken,
                          outputAlgorithm,
                          aSettings.keyId
                        )
                        attrs.put(otoroshi.plugins.Keys.MatchedInputTokenKey  -> jsonToken)
                        attrs.put(otoroshi.plugins.Keys.MatchedOutputTokenKey -> jsonToken)
                        source.asJwtInjection(decodedToken, newToken).right[Result]
                      }
                    }
                  case s @ Transform(_, tSettings, aSettings) =>
                    aSettings.asAlgorithm(OutputMode) match {
                      case None                  =>
                        Errors
                          .craftResponseResultSync(
                            "error.bad.output.algorithm.name",
                            Results.BadRequest,
                            request,
                            descOpt,
                            None,
                            attrs = attrs,
                            sendEvent = sendEvent
                          )
                          .left[JwtInjection]
                      case Some(outputAlgorithm) => {
                        val jsonToken                    = Json.parse(ApacheBase64.decodeBase64(decodedToken.getPayload)).as[JsObject]
                        val context: Map[String, String] = jsonToken.value.toSeq.collect {
                          case (key, JsString(str))     => (key, str)
                          case (key, JsBoolean(bool))   => (key, bool.toString)
                          case (key, JsNumber(nbr))     => (key, nbr.toString())
                          case (key, arr @ JsArray(_))  => (key, Json.stringify(arr))
                          case (key, obj @ JsObject(_)) => (key, Json.stringify(obj))
                          case (key, JsNull)            => (key, "null")
                        } toMap
                        val evaluatedValues: JsObject    =
                          JwtExpressionLanguage
                            .fromJson(
                              tSettings.mappingSettings.values,
                              Some(request),
                              descOpt,
                              None,
                              apikey,
                              user,
                              context,
                              attrs,
                              env
                            )
                            .as[JsObject]
                        val newJsonToken: JsObject       = JsObject(
                          (tSettings.mappingSettings.map
                            .filter(a => (jsonToken \ a._1).isDefined)
                            .foldLeft(jsonToken)((a, b) =>
                              a.+(
                                b._2,
                                JwtExpressionLanguage.fromJson(
                                  (a \ b._1).as[JsValue],
                                  Some(request),
                                  descOpt,
                                  None,
                                  apikey,
                                  user,
                                  context,
                                  attrs,
                                  env
                                )
                              ).-(b._1)
                            ) ++ evaluatedValues).fields
                            .filterNot {
                              case (_, JsNull)           => true
                              case (_, JsString("null")) => true
                              case _                     => false
                            }
                            .filterNot(f => tSettings.mappingSettings.remove.contains(f._1))
                            .toMap
                        )
                        val newToken                     = sign(newJsonToken, outputAlgorithm, aSettings.keyId)
                        attrs.put(otoroshi.plugins.Keys.MatchedInputTokenKey  -> jsonToken)
                        attrs.put(otoroshi.plugins.Keys.MatchedOutputTokenKey -> newJsonToken)
                        source match {
                          case _: InQueryParam =>
                            tSettings.location.asJwtInjection(decodedToken, newToken).right[Result]
                          case InHeader(n, _)  =>
                            val inj = tSettings.location.asJwtInjection(decodedToken, newToken)
                            tSettings.location match {
                              case InHeader(nn, _) if nn == n => inj.right[Result]
                              case _                          => inj.copy(removeHeaders = Seq(n)).right[Result]
                            }
                          case InCookie(n)     =>
                            tSettings.location
                              .asJwtInjection(decodedToken, newToken)
                              .copy(removeCookies = Seq(n))
                              .right[Result]
                        }
                      }
                    }
                }
            }
          }
        }
    }
  }

  private[models] def internalVerifyAsync[A](
      request: RequestHeader,
      descOpt: Option[ServiceDescriptor],
      apikey: Option[ApiKey],
      user: Option[PrivateAppsUser],
      elContext: Map[String, String],
      attrs: TypedMap,
      sendEvent: Boolean
  )(
      f: JwtInjection => Future[A]
  )(implicit ec: ExecutionContext, env: Env): Future[Either[Result, A]] = env.metrics.withTimerAsync(
    "ng-report-call-access-validator-plugins-plugin-cp:otoroshi.next.plugins.JwtVerification-int-async"
  ) {

    import Implicits._

    source.token(request) match {
      case None        =>
        strategy match {
          case DefaultToken(true, newToken, _) => {
            // it's okay to use algoSettings here as it's the default token, so it's not used as an input but as output algo
            algoSettings.asAlgorithmF(OutputMode) flatMap {
              case None                  =>
                Errors
                  .craftResponseResult(
                    "error.bad.output.algorithm.name",
                    Results.BadRequest,
                    request,
                    descOpt,
                    None,
                    attrs = attrs,
                    sendEvent = sendEvent
                  )
                  .left[A]
              case Some(outputAlgorithm) => {
                val moreCtx           = Map(
                  "jti" -> IdGenerator.uuid,
                  "iat" -> s"${Math.floor(System.currentTimeMillis() / 1000L).toLong}",
                  "nbf" -> s"${Math.floor(System.currentTimeMillis() / 1000L).toLong}",
                  "iss" -> "Otoroshi",
                  "exp" -> s"${Math.floor((System.currentTimeMillis() + 60000L) / 1000L).toLong}",
                  "sub" -> apikey.map(_.clientName).orElse(user.map(_.email)).getOrElse("anonymous"),
                  "aud" -> "backend"
                )
                val interpolatedToken = JwtExpressionLanguage
                  .fromJson(
                    newToken,
                    Some(request),
                    descOpt,
                    None,
                    apikey,
                    user,
                    elContext ++ moreCtx,
                    attrs = attrs,
                    env
                  )
                  .as[JsObject]
                val correctedToken    = interpolatedToken
                  .applyOnIf(interpolatedToken.select("nbf").asOpt[String].isDefined) { obj =>
                    Try(interpolatedToken.select("nbf").asString.toLong) match {
                      case Failure(e)   => obj
                      case Success(lng) => obj ++ Json.obj("nbf" -> lng)
                    }
                  }
                  .applyOnIf(interpolatedToken.select("iat").asOpt[String].isDefined) { obj =>
                    Try(interpolatedToken.select("iat").asString.toLong) match {
                      case Failure(e)   => obj
                      case Success(lng) => obj ++ Json.obj("iat" -> lng)
                    }
                  }
                  .applyOnIf(interpolatedToken.select("exp").asOpt[String].isDefined) { obj =>
                    Try(interpolatedToken.select("exp").asString.toLong) match {
                      case Failure(e)   => obj
                      case Success(lng) => obj ++ Json.obj("exp" -> lng)
                    }
                  }
                // it's okay to use algoSettings here as it's the default token, so it's not used as an input but as output algo
                val signedToken       = sign(correctedToken, outputAlgorithm, algoSettings.keyId)
                val decodedToken      = JWT.decode(signedToken)
                attrs.put(otoroshi.plugins.Keys.MatchedInputTokenKey  -> correctedToken)
                attrs.put(otoroshi.plugins.Keys.MatchedOutputTokenKey -> correctedToken)
                f(source.asJwtInjection(decodedToken, signedToken)).right[Result]
              }
            }
          }
          case DefaultToken(false, _, _)       => {
            f(JwtInjection()).right[Result]
          }
          case _ if strict                     => {
            Errors
              .craftResponseResult(
                "error.expected.token.not.found",
                Results.BadRequest,
                request,
                descOpt,
                None,
                attrs = attrs,
                sendEvent = sendEvent
              )
              .left[A]
          }
          case _ if !strict                    => f(JwtInjection()).right[Result]
        }
      // case None if strict =>
      //   Errors
      //     .craftResponseResult(
      //       "error.expected.token.not.found",
      //       Results.BadRequest,
      //       request,
      //       Some(desc),
      //       None
      //     )
      //     .left[A]
      // case None if !strict => f(JwtInjection()).right[Result]
      case Some(token) =>
        val tokenParts  = token.split("\\.")
        val signature   = tokenParts.last
        val tokenHeader = Try(Json.parse(ApacheBase64.decodeBase64(tokenParts(0)))).getOrElse(Json.obj())
        val kid         = (tokenHeader \ "kid").asOpt[String]
        val alg         = (tokenHeader \ "alg").asOpt[String].getOrElse("RS256")
        algoSettings.asAlgorithmF(InputMode(alg, kid)) flatMap {
          case None            =>
            Errors
              .craftResponseResult(
                "error.bad.input.algorithm.name",
                Results.BadRequest,
                request,
                descOpt,
                None,
                attrs = attrs
              )
              .left[A]
          case Some(algorithm) => {
            val verification       = strategy.verificationSettings.asVerification(algorithm)
            val key                = s"${this.asInstanceOf[GlobalJwtVerifier].id}-${signature}"
            val verificationResult = JwtVerifier.signatureCache.get(key, _ => Try(verification.build().verify(token)))
            verificationResult match {
              case Failure(e)            =>
                // logger.error("Bad JWT token 2", e)
                Errors
                  .craftResponseResult(
                    "error.bad.token",
                    Results.BadRequest,
                    request,
                    descOpt,
                    None,
                    attrs = attrs,
                    sendEvent = sendEvent
                  )
                  .left[A]
              case Success(decodedToken) =>
                strategy match {
                  case s @ DefaultToken(true, _, _)           => {
                    Errors
                      .craftResponseResult(
                        "error.token.already.present",
                        Results.BadRequest,
                        request,
                        descOpt,
                        None,
                        attrs = attrs,
                        sendEvent = sendEvent
                      )
                      .left[A]
                  }
                  case s @ DefaultToken(false, _, _)          =>
                    val jsonToken = Json.parse(ApacheBase64.decodeBase64(decodedToken.getPayload)).as[JsObject]
                    attrs.put(otoroshi.plugins.Keys.MatchedInputTokenKey  -> jsonToken)
                    attrs.put(otoroshi.plugins.Keys.MatchedOutputTokenKey -> jsonToken)
                    f(JwtInjection(decodedToken.some)).right[Result]
                  case s @ PassThrough(_)                     =>
                    val jsonToken = Json.parse(ApacheBase64.decodeBase64(decodedToken.getPayload)).as[JsObject]
                    attrs.put(otoroshi.plugins.Keys.MatchedInputTokenKey  -> jsonToken)
                    attrs.put(otoroshi.plugins.Keys.MatchedOutputTokenKey -> jsonToken)
                    f(JwtInjection(decodedToken.some)).right[Result]
                  case s @ Sign(_, aSettings)                 =>
                    aSettings.asAlgorithmF(OutputMode) flatMap {
                      case None                  =>
                        Errors
                          .craftResponseResult(
                            "error.bad.output.algorithm.name",
                            Results.BadRequest,
                            request,
                            descOpt,
                            None,
                            attrs = attrs,
                            sendEvent = sendEvent
                          )
                          .left[A]
                      case Some(outputAlgorithm) => {
                        val jsonToken = Json.parse(ApacheBase64.decodeBase64(decodedToken.getPayload)).as[JsObject]
                        val newToken  = sign(
                          jsonToken,
                          outputAlgorithm,
                          aSettings.keyId
                        )
                        attrs.put(otoroshi.plugins.Keys.MatchedInputTokenKey  -> jsonToken)
                        attrs.put(otoroshi.plugins.Keys.MatchedOutputTokenKey -> jsonToken)
                        f(source.asJwtInjection(decodedToken, newToken)).right[Result]
                      }
                    }
                  case s @ Transform(_, tSettings, aSettings) =>
                    aSettings.asAlgorithmF(OutputMode) flatMap {
                      case None                  =>
                        Errors
                          .craftResponseResult(
                            "error.bad.output.algorithm.name",
                            Results.BadRequest,
                            request,
                            descOpt,
                            None,
                            attrs = attrs,
                            sendEvent = sendEvent
                          )
                          .left[A]
                      case Some(outputAlgorithm) => {
                        val jsonToken                    = Json.parse(ApacheBase64.decodeBase64(decodedToken.getPayload)).as[JsObject]
                        val context: Map[String, String] = jsonToken.value.toSeq.collect {
                          case (key, JsString(str))     => (key, str)
                          case (key, JsBoolean(bool))   => (key, bool.toString)
                          case (key, JsNumber(nbr))     => (key, nbr.toString())
                          case (key, arr @ JsArray(_))  => (key, Json.stringify(arr))
                          case (key, obj @ JsObject(_)) => (key, Json.stringify(obj))
                          case (key, JsNull)            => (key, "null")
                        } toMap
                        val evaluatedValues: JsObject    =
                          JwtExpressionLanguage
                            .fromJson(
                              tSettings.mappingSettings.values,
                              Some(request),
                              descOpt,
                              None,
                              apikey,
                              user,
                              context,
                              attrs,
                              env
                            )
                            .as[JsObject]
                        val newJsonToken: JsObject       = JsObject(
                          (tSettings.mappingSettings.map
                            .filter(a => (jsonToken \ a._1).isDefined)
                            .foldLeft(jsonToken)((a, b) =>
                              a.+(
                                b._2,
                                JwtExpressionLanguage.fromJson(
                                  (a \ b._1).as[JsValue],
                                  Some(request),
                                  descOpt,
                                  None,
                                  apikey,
                                  user,
                                  context,
                                  attrs,
                                  env
                                )
                              ).-(b._1)
                            ) ++ evaluatedValues).fields
                            .filterNot {
                              case (_, JsNull)           => true
                              case (_, JsString("null")) => true
                              case _                     => false
                            }
                            .filterNot(f => tSettings.mappingSettings.remove.contains(f._1))
                            .toMap
                        )
                        val newToken                     = sign(newJsonToken, outputAlgorithm, aSettings.keyId)
                        attrs.put(otoroshi.plugins.Keys.MatchedInputTokenKey  -> jsonToken)
                        attrs.put(otoroshi.plugins.Keys.MatchedOutputTokenKey -> newJsonToken)
                        source match {
                          case _: InQueryParam =>
                            f(tSettings.location.asJwtInjection(decodedToken, newToken)).right[Result]
                          case InHeader(n, _)  =>
                            val inj = tSettings.location.asJwtInjection(decodedToken, newToken)
                            tSettings.location match {
                              case InHeader(nn, _) if nn == n => f(inj).right[Result]
                              case _                          => f(inj.copy(removeHeaders = Seq(n))).right[Result]
                            }
                          case InCookie(n)     =>
                            f(tSettings.location.asJwtInjection(decodedToken, newToken).copy(removeCookies = Seq(n)))
                              .right[Result]
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
    algoSettings: AlgoSettings = HSAlgoSettings(512, "secret", false),
    strategy: VerifierStrategy = PassThrough(VerificationSettings(Map.empty))
) extends JwtVerifier
    with AsJson {

  def asJson: JsValue =
    Json.obj(
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

case class RefJwtVerifier(
    ids: Seq[String] = Seq.empty,
    enabled: Boolean = false,
    excludedPatterns: Seq[String] = Seq.empty[String]
) extends JwtVerifier
    with AsJson {

  def asJson: JsValue =
    Json.obj(
      "type"             -> "ref",
      "ids"              -> JsArray(this.ids.map(JsString.apply)),
      "id"               -> this.ids.headOption.map(JsString.apply).getOrElse(JsNull).as[JsValue], // for compat only
      "enabled"          -> this.enabled,
      "excludedPatterns" -> JsArray(this.excludedPatterns.map(JsString.apply))
    )

  override def isRef        = true
  override def strict       = throw new RuntimeException("Should never be called ...")
  override def source       = throw new RuntimeException("Should never be called ...")
  override def algoSettings = throw new RuntimeException("Should never be called ...")
  override def strategy     = throw new RuntimeException("Should never be called ...")

  private def id: Option[String] = ids.headOption

  def verifiersSync(implicit env: Env): Seq[GlobalJwtVerifier] = ids.flatMap(env.proxyState.jwtVerifier)

  override def isAsync: Boolean = {
    verifiersSync(OtoroshiEnvHolder.get()).forall(_.isAsync)
  }

  override def verify(
      request: RequestHeader,
      desc: ServiceDescriptor,
      apikey: Option[ApiKey],
      user: Option[PrivateAppsUser],
      elContext: Map[String, String],
      attrs: TypedMap
  )(
      f: JwtInjection => Future[Result]
  )(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    verifyGen(request, desc, apikey, user, elContext, attrs)(c => f(c).map(Right.apply)).map {
      case Left(r)  => r
      case Right(r) => r
    }
  }

  override def verifyWs(
      request: RequestHeader,
      desc: ServiceDescriptor,
      apikey: Option[ApiKey],
      user: Option[PrivateAppsUser],
      elContext: Map[String, String],
      attrs: TypedMap
  )(
      f: JwtInjection => Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]]
  )(implicit ec: ExecutionContext, env: Env): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] = {
    verifyGen(request, desc, apikey, user, elContext, attrs)(f)
  }

  override def verifyGen[A](
      request: RequestHeader,
      desc: ServiceDescriptor,
      apikey: Option[ApiKey],
      user: Option[PrivateAppsUser],
      elContext: Map[String, String],
      attrs: TypedMap
  )(
      f: JwtInjection => Future[Either[Result, A]]
  )(implicit ec: ExecutionContext, env: Env): Future[Either[Result, A]] = {
    implicit val mat = env.otoroshiMaterializer
    ids match {
      case s if s.isEmpty => f(JwtInjection())
      case _              => {

        val promise                                       = Promise[Either[Result, A]]
        val last                                          = new AtomicReference[Either[Result, A]](
          Left(Results.InternalServerError(Json.obj("Otoroshi-Error" -> "error.missing.globaljwtverifier.id")))
        )
        val queue: scala.collection.mutable.Queue[String] = scala.collection.mutable.Queue(ids: _*)

        def dequeueNext(): Unit = {
          queue.dequeueFirst(_ => true) match {
            case None      =>
              Option(last.get()) match {
                case None         =>
                  promise.tryFailure(new RuntimeException("Should have last result set ..."))
                case Some(result) =>
                  promise.trySuccess(result)
              }
            case Some(ref) =>
              env.datastores.globalJwtVerifierDataStore
                .findById(ref)
                .flatMap {
                  case Some(verifier) =>
                    verifier
                      .internalVerify(request, desc.some, apikey, user, elContext, attrs, queue.isEmpty)(f)
                      .map {
                        case Left(result)  =>
                          last.set(Left(result))
                          dequeueNext()
                        case Right(result) =>
                          result match {
                            case Left(result) =>
                              last.set(Left(result))
                              dequeueNext()
                            case Right(flow)  =>
                              // the first that passes win !
                              promise.trySuccess(result)
                          }
                      }
                      .andThen { case Failure(e) => promise.tryFailure(e) }
                  case None           =>
                    Errors
                      .craftResponseResult(
                        s"error.bad.globaljwtverifier.id",
                        Results.InternalServerError,
                        request,
                        Some(desc),
                        None,
                        attrs = attrs
                      )
                      .map { result =>
                        last.set(Left(result))
                        dequeueNext()
                      }
                      .andThen { case Failure(e) => promise.tryFailure(e) }
                }
                .andThen { case Failure(e) => promise.tryFailure(e) }
          }
        }

        dequeueNext()

        promise.future
      }
    }
  }

  def verifyFromCache(
      request: RequestHeader,
      desc: Option[ServiceDescriptor],
      apikey: Option[ApiKey],
      user: Option[PrivateAppsUser],
      elContext: Map[String, String],
      attrs: TypedMap
  )(implicit ec: ExecutionContext, env: Env): Future[Either[Result, JwtInjection]] = {
    ids match {
      case s if s.isEmpty => JwtInjection().right.future
      case _              => {
        val promise = Promise[Either[Result, JwtInjection]]
        def dequeueNext(all: Seq[String], last: Either[Result, JwtInjection]): Unit = {
          all.headOption match {
            case None      => promise.trySuccess(last)
            case Some(ref) =>
              val key = s"${request.id}-${ref}-queue"
              JwtVerifier.verificationCache.getIfPresent(key) match {
                case Some(JwtVerificationResult.FailedJwtVerificationResult(result)) =>
                  dequeueNext(all.tail, Left(result)) // weird use case where same verifier is used and fails
                case _                                                               =>
                  env.metrics.withTimerAsync(
                    s"ng-report-call-access-validator-plugins-plugin-cp:otoroshi.next.plugins.JwtVerification-single-async"
                  ) {
                    env.proxyState.jwtVerifier(ref) match {
                      case Some(verifier) =>
                        verifier
                          .internalVerify(request, desc, apikey, user, elContext, attrs, all.size == 1)(injection =>
                            injection.right.future
                          )
                          .map {
                            case Left(result) if all.size == 1 =>
                              JwtVerifier.verificationCache.put(
                                key,
                                JwtVerificationResult.FailedJwtVerificationResult(result)
                              )
                              promise.trySuccess(Left(result))
                            case Left(result)                  =>
                              JwtVerifier.verificationCache.put(
                                key,
                                JwtVerificationResult.FailedJwtVerificationResult(result)
                              )
                              dequeueNext(all.tail, Left(result))
                            case Right(result)                 =>
                              result match {
                                case Left(result) if all.size == 1 =>
                                  JwtVerifier.verificationCache.put(
                                    key,
                                    JwtVerificationResult.FailedJwtVerificationResult(result)
                                  )
                                  promise.trySuccess(Left(result))
                                case Left(result)                  =>
                                  JwtVerifier.verificationCache.put(
                                    key,
                                    JwtVerificationResult.FailedJwtVerificationResult(result)
                                  )
                                  dequeueNext(all.tail, Left(result))
                                case Right(flow)                   =>
                                  // the first that passes win !
                                  promise.trySuccess(Right(flow))
                              }
                          }
                          .andThen { case Failure(e) => promise.tryFailure(e) }

                      case None           => {
                        Errors
                          .craftResponseResult(
                            s"error.bad.globaljwtverifier.id",
                            Results.InternalServerError,
                            request,
                            desc,
                            None,
                            attrs = attrs
                          )
                          .map { result =>
                            JwtVerifier.verificationCache.put(
                              key,
                              JwtVerificationResult.FailedJwtVerificationResult(result)
                            )
                            if (all.size == 1) {
                              promise.trySuccess(Left(result))
                            } else {
                              dequeueNext(all.tail, Left(result))
                            }
                          }
                          .andThen { case Failure(e) => promise.tryFailure(e) }
                      }
                    }
                  }
              }
          }
        }
        dequeueNext(
          ids,
          Left(Results.InternalServerError(Json.obj("Otoroshi-Error" -> "error.missing.globaljwtverifier.id")))
        )
        promise.future
      }
    }
  }

  def verifyFromCacheSync(
      request: RequestHeader,
      desc: Option[ServiceDescriptor],
      apikey: Option[ApiKey],
      user: Option[PrivateAppsUser],
      elContext: Map[String, String],
      attrs: TypedMap
  )(implicit ec: ExecutionContext, env: Env): Either[Result, JwtInjection] = {
    ids match {
      case s if s.isEmpty => JwtInjection().right
      case _              => {

        def dequeueNext(all: Seq[String], last: Either[Result, JwtInjection]): Either[Result, JwtInjection] = {
          all.headOption match {
            case None      => last
            case Some(ref) =>
              val key = s"${request.id}-${ref}-queue"
              JwtVerifier.verificationCache.getIfPresent(key) match {
                case Some(JwtVerificationResult.FailedJwtVerificationResult(result)) =>
                  dequeueNext(all.tail, Left(result)) // weird use case where same verifier is used and fails
                case _                                                               =>
                  env.metrics.withTimer(
                    s"ng-report-call-access-validator-plugins-plugin-cp:otoroshi.next.plugins.JwtVerification-single-sync"
                  ) {
                    env.proxyState.jwtVerifier(ref) match {
                      case Some(verifier) =>
                        verifier.internalVerifySync(
                          request,
                          desc,
                          apikey,
                          user,
                          elContext,
                          attrs,
                          all.size == 1
                        ) match {
                          case Left(result) if all.size == 1 =>
                            JwtVerifier.verificationCache.put(
                              key,
                              JwtVerificationResult.FailedJwtVerificationResult(result)
                            )
                            Left(result)
                          case Left(result)                  =>
                            JwtVerifier.verificationCache.put(
                              key,
                              JwtVerificationResult.FailedJwtVerificationResult(result)
                            )
                            dequeueNext(all.tail, Left(result))
                          case Right(result)                 =>
                            Right(result)
                        }
                      case None           => {
                        val result = Errors
                          .craftResponseResultSync(
                            s"error.bad.globaljwtverifier.id",
                            Results.InternalServerError,
                            request,
                            desc,
                            None,
                            attrs = attrs
                          )
                        JwtVerifier.verificationCache.put(
                          key,
                          JwtVerificationResult.FailedJwtVerificationResult(result)
                        )
                        if (all.size == 1) {
                          Left(result)
                        } else {
                          dequeueNext(all.tail, Left(result))
                        }
                      }
                    }
                  }
              }
          }
        }

        dequeueNext(
          ids,
          Left(Results.InternalServerError(Json.obj("Otoroshi-Error" -> "error.missing.globaljwtverifier.id")))
        )
      }
    }
  }

  override def shouldBeVerified(path: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean] = {
    ids match {
      case s if s.isEmpty => FastFuture.successful(false)
      case _              => FastFuture.successful(!excludedPatterns.exists(p => RegexPool.regex(p).matches(path)))
    }
  }
}

object RefJwtVerifier extends FromJson[RefJwtVerifier] {
  override def fromJson(json: JsValue): Either[Throwable, RefJwtVerifier] =
    Try {
      val refs: Seq[String] = (json \ "ids")
        .asOpt[JsArray]
        .map(_.value.map(_.as[String]))
        .orElse((json \ "id").asOpt[String].map(v => Seq(v)))
        .getOrElse(Seq.empty)
      Right[Throwable, RefJwtVerifier](
        RefJwtVerifier(
          ids = refs,
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
          excludedPatterns = (json \ "excludedPatterns").asOpt[Seq[String]].getOrElse(Seq.empty[String])
        )
      )
    } recover { case e =>
      Left[Throwable, RefJwtVerifier](e)
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
    } recover { case e =>
      Left.apply[Throwable, LocalJwtVerifier](e)
    } get
}

case class GlobalJwtVerifier(
    id: String,
    name: String,
    desc: String,
    strict: Boolean = true,
    source: JwtTokenLocation = InHeader("X-JWT-Token"),
    algoSettings: AlgoSettings = HSAlgoSettings(512, "secret", false),
    strategy: VerifierStrategy = PassThrough(VerificationSettings(Map.empty)),
    tags: Seq[String] = Seq.empty,
    metadata: Map[String, String] = Map.empty,
    location: otoroshi.models.EntityLocation = otoroshi.models.EntityLocation()
) extends JwtVerifier
    with AsJson
    with otoroshi.models.EntityLocationSupport {

  def json: JsValue                    = asJson
  def internalId: String               = id
  def theDescription: String           = desc
  def theMetadata: Map[String, String] = metadata
  def theName: String                  = name
  def theTags: Seq[String]             = tags

  def asJson: JsValue =
    location.jsonWithKey ++ Json.obj(
      "type"         -> "global",
      "id"           -> id,
      "name"         -> name,
      "desc"         -> desc,
      "strict"       -> strict,
      "source"       -> source.asJson,
      "algoSettings" -> algoSettings.asJson,
      "strategy"     -> strategy.asJson,
      "metadata"     -> metadata,
      "tags"         -> JsArray(tags.map(JsString.apply))
    )

  override def isRef = false

  def save()(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    env.datastores.globalJwtVerifierDataStore.set(this)

  override def shouldBeVerified(path: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    FastFuture.successful(true)

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

    override def reads(json: JsValue) =
      fromJson(json) match {
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
          location = otoroshi.models.EntityLocation.readFromKey(json),
          id = (json \ "id").as[String],
          name = (json \ "name").as[String],
          desc = (json \ "desc").asOpt[String].getOrElse("--"),
          strict = (json \ "strict").asOpt[Boolean].getOrElse(false),
          metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
          tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          source = source,
          algoSettings = algoSettings,
          strategy = strategy
        )
      }
    } recover { case e =>
      Left.apply[Throwable, GlobalJwtVerifier](e)
    } get
}

sealed trait JwtVerificationResult
object JwtVerificationResult {
  case class FailedJwtVerificationResult(result: Result) extends JwtVerificationResult
}

object JwtVerifier extends FromJson[JwtVerifier] {

  val verificationCache = Scaffeine()
    .expireAfterWrite(5.seconds)
    .maximumSize(500)
    .build[String, JwtVerificationResult]()

  val signatureCache = Scaffeine()
    .expireAfterWrite(5.seconds)
    .maximumSize(500)
    .build[String, Try[DecodedJWT]]()

  val fmt = new Format[JwtVerifier] {
    override def writes(o: JwtVerifier): JsValue             = o.asJson
    override def reads(json: JsValue): JsResult[JwtVerifier] =
      fromJson(json) match {
        case Left(e)  => JsError(e.getMessage)
        case Right(j) => JsSuccess(j)
      }
  }

  override def fromJson(json: JsValue): Either[Throwable, JwtVerifier] = {
    Try {
      (json \ "type").as[String] match {
        case "global" => GlobalJwtVerifier.fromJson(json)
        case "local"  => LocalJwtVerifier.fromJson(json)
        case "ref"    => RefJwtVerifier.fromJson(json)
      }
    } recover { case e =>
      Left(e)
    } get
  }

  def mock1: JwtVerifier =
    LocalJwtVerifier(
      strict = true,
      source = InHeader("Authorization", "Bearer "),
      algoSettings = HSAlgoSettings(256, "secret", false),
      strategy = Transform(
        transformSettings = TransformSettings(
          location = InHeader("X-Fuuuuu"),
          mappingSettings = MappingSettings(
            map = Map("name" -> "MyNameIs"),
            values = Json.obj(
              "foo" -> 123
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

trait GlobalJwtVerifierDataStore extends BasicStore[GlobalJwtVerifier] {
  def template(env: Env, ctx: Option[ApiActionContext[_]] = None): GlobalJwtVerifier = {
    val defaultJwt = GlobalJwtVerifier(
      id = IdGenerator.namedId("jwt_verifier", env),
      name = "New jwt verifier",
      desc = "New jwt verifier",
      metadata = Map.empty
    ).copy(location = EntityLocation.ownEntityLocation(ctx)(env))
    env.datastores.globalConfigDataStore
      .latest()(env.otoroshiExecutionContext, env)
      .templates
      .verifier
      .map { template =>
        GlobalJwtVerifier._fmt.reads(defaultJwt.json.asObject.deepMerge(template)).get
      }
      .getOrElse {
        defaultJwt
      }
  }
}
