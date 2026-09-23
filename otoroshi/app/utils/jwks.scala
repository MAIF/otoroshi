package otoroshi.utils.jwk

import java.security.interfaces.{ECPublicKey, RSAPublicKey}
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import com.nimbusds.jose.jwk.{Curve, ECKey, RSAKey}
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.RequestHeader

import java.security.PublicKey
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Try}

object JWKSHelper {

  private case class CachedKeys(computedAt: Long, keys: Seq[JsValue])

  // the computed keys only depend on the call parameters and on the proxy state, so entries are shared between
  // callers and each caller decides, with its own ttl, whether an entry is fresh enough
  private val cache: Cache[String, CachedKeys] = Scaffeine()
    .expireAfterWrite(1.hour)
    .maximumSize(1000)
    .build[String, CachedKeys]()

  def clearCache(): Unit = cache.invalidateAll()

  /**
   * @param cacheTtl how long a previously computed set of keys can be reused. `None` falls back to the global
   *                 `otoroshi.jwks.cache-ttl` setting, a zero ttl disables the cache for this call.
   */
  def jwks(
      req: RequestHeader,
      certIds: Seq[String],
      includeExposed: Boolean,
      includeAlg: Boolean,
      rsaAlgorithms: Seq[com.nimbusds.jose.Algorithm],
      esAlgorithms: Seq[com.nimbusds.jose.Algorithm],
      cacheTtl: Option[FiniteDuration] = None
  )(using
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, Seq[JsValue]]] = {
    if (req.method == "GET") {
      val ttl = cacheTtl.getOrElse(env.confJwksCacheTtl)
      if (ttl.toMillis <= 0) {
        Right(computeKeys(certIds, includeExposed, includeAlg, rsaAlgorithms, esAlgorithms)).future
      } else {
        val key = Seq(
          certIds.distinct.sorted.mkString(","),
          includeExposed.toString,
          includeAlg.toString,
          rsaAlgorithms.map(_.getName).mkString(","),
          esAlgorithms.map(_.getName).mkString(",")
        ).mkString("|")
        val now = System.currentTimeMillis()
        cache.getIfPresent(key) match {
          case Some(cached) if (now - cached.computedAt) < ttl.toMillis => Right(cached.keys).future
          case _                                                        =>
            val keys = computeKeys(certIds, includeExposed, includeAlg, rsaAlgorithms, esAlgorithms)
            cache.put(key, CachedKeys(now, keys))
            Right(keys).future
        }
      }
    } else {
      Left(Json.obj("error" -> "resource not found !")).future
    }
  }

  private def computeKeys(
      certIds: Seq[String],
      includeExposed: Boolean,
      includeAlg: Boolean,
      rsaAlgorithms: Seq[com.nimbusds.jose.Algorithm],
      esAlgorithms: Seq[com.nimbusds.jose.Algorithm]
  )(using env: Env): Seq[JsValue] = {
    val ids   = env.proxyState.allApikeys().flatMap(_.metadata.get("jwt-sign-keypair")).toSet ++ certIds
    val certs = env.proxyState.allCertificates()
    certs
      .applyOnIf(includeExposed)(
        _.filter(c => (c.exposed || ids.contains(c.id)) && c.notRevoked)
      )                                                                              // && c.notExpired
      .applyOnIf(!includeExposed)(_.filter(c => ids.contains(c.id) && c.notRevoked)) // && c.notExpired
      .filterNot(_.chain.trim.isEmpty)
      .filterNot(_.privateKey.trim.isEmpty)
      .flatMap(c =>
        Try((c.id, c.cryptoKeyPair.getPublic))
          .seffectOnWithPredicate(t => t.isFailure)(t =>
            t.asInstanceOf[Failure[Tuple2[String, PublicKey]]].exception.printStackTrace()
          )
          .toOption
      )
      .flatMap {
        case (id, pub: RSAPublicKey) if !includeAlg =>
          new RSAKey.Builder(pub).keyID(id).build().toJSONString.parseJson.some
        case (id, pub: RSAPublicKey) if includeAlg  =>
          rsaAlgorithms.map(alg => new RSAKey.Builder(pub).keyID(id).algorithm(alg).build().toJSONString.parseJson)
        case (id, pub: ECPublicKey) if !includeAlg  =>
          val curve = Curve.forECParameterSpec(pub.getParams)
          new ECKey.Builder(curve, pub).keyID(id).build().toJSONString.parseJson.some
        case (id, pub: ECPublicKey) if includeAlg   =>
          val curve = Curve.forECParameterSpec(pub.getParams)
          esAlgorithms
            .map(alg => new ECKey.Builder(curve, pub).keyID(id).algorithm(alg).build().toJSONString.parseJson)
        case _                                      => None
      }
  }
}
