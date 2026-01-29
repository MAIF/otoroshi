package otoroshi.utils.jwk

import java.security.interfaces.{ECPublicKey, RSAPublicKey}
import com.nimbusds.jose.jwk.{Curve, ECKey, RSAKey}
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsArray, JsValue, Json}
import play.api.mvc.RequestHeader

import java.security.PublicKey
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Try}

object JWKSHelper {

  def jwks(req: RequestHeader, certIds: Seq[String], includeExposed: Boolean, includeAlg: Boolean, rsaAlgorithms: Seq[com.nimbusds.jose.Algorithm], esAlgorithms: Seq[com.nimbusds.jose.Algorithm])(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, Seq[JsValue]]] = {
    if (req.method == "GET") {
      for {
        apikeys <- env.datastores.apiKeyDataStore.findAll()
        certs   <- env.datastores.certificatesDataStore.findAll()
      } yield {
        val ids          = apikeys.map(_.metadata.get("jwt-sign-keypair")).collect { case Some(value) => value } ++ certIds
        val exposedCerts = certs
          .applyOnIf(includeExposed)(_.filter(c => (c.exposed || ids.contains(c.id)) && c.notRevoked)) // && c.notExpired
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
            case (id, pub: RSAPublicKey) if includeAlg =>
              rsaAlgorithms.map(alg => new RSAKey.Builder(pub).keyID(id).algorithm(alg).build().toJSONString.parseJson)
              //Seq(
              //  new RSAKey.Builder(pub).keyID(id).algorithm(com.nimbusds.jose.JWSAlgorithm.RS256).build().toJSONString.parseJson,
              //  new RSAKey.Builder(pub).keyID(id).algorithm(com.nimbusds.jose.JWSAlgorithm.RS384).build().toJSONString.parseJson,
              //  new RSAKey.Builder(pub).keyID(id).algorithm(com.nimbusds.jose.JWSAlgorithm.RS512).build().toJSONString.parseJson,
              //)
            case (id, pub: ECPublicKey) if !includeAlg =>
              val curve = Curve.forECParameterSpec(pub.getParams)
              new ECKey.Builder(curve, pub).keyID(id).build().toJSONString.parseJson.some
            case (id, pub: ECPublicKey) if includeAlg =>
              val curve = Curve.forECParameterSpec(pub.getParams)
              esAlgorithms.map(alg => new ECKey.Builder(curve, pub).keyID(id).algorithm(alg).build().toJSONString.parseJson)
              //Seq(
              //  new ECKey.Builder(curve, pub).keyID(id).algorithm(com.nimbusds.jose.JWSAlgorithm.ES256).build().toJSONString.parseJson,
              //  new ECKey.Builder(curve, pub).keyID(id).algorithm(com.nimbusds.jose.JWSAlgorithm.ES384).build().toJSONString.parseJson,
              //  new ECKey.Builder(curve, pub).keyID(id).algorithm(com.nimbusds.jose.JWSAlgorithm.ES512).build().toJSONString.parseJson,
              //)
            case _                       => None
          }
        Right(exposedCerts)
      }
    } else {
      Left(Json.obj("error" -> "resource not found !")).future
    }
  }
}
