package functional

import java.nio.charset.StandardCharsets

import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import env.Env
import models.{ApiKey, PrivateAppsUser, ServiceDescriptor}
import otoroshi.script
import otoroshi.script.RequestTransformer
import play.api.libs.json.{JsObject, Json}
import play.api.mvc.Result
import org.apache.commons.codec.binary.Base64

import scala.concurrent.{ExecutionContext, Future}

class JwtTokenTransformer extends RequestTransformer {

  private def sign(algorithm: Algorithm, headerJson: JsObject, payloadJson: JsObject): String = {
    val header: String              = Base64.encodeBase64URLSafeString(Json.toBytes(headerJson))
    val payload: String             = Base64.encodeBase64URLSafeString(Json.toBytes(payloadJson))
    val signatureBytes: Array[Byte] = algorithm.sign((header + "." + payload).getBytes(StandardCharsets.UTF_8))
    val signature: String           = Base64.encodeBase64URLSafeString(signatureBytes)
    String.format("%s.%s.%s", header, payload, signature)
  }

  override def transformRequest(
      snowflake: String,
      rawRequest: script.HttpRequest,
      otoroshiRequest: script.HttpRequest,
      desc: ServiceDescriptor,
      apiKey: Option[ApiKey],
      user: Option[PrivateAppsUser]
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, script.HttpRequest]] = {
    FastFuture.successful {
      otoroshiRequest.headers.get("Otoroshi-Claim") match {
        case Some(rawJwtToken) => {
          val decoded = JWT.decode(rawJwtToken)
          val header  = Json.parse(new String(Base64.decodeBase64(decoded.getHeader))).as[JsObject]
          val payload = Json.parse(new String(Base64.decodeBase64(decoded.getPayload))).as[JsObject] ++ Json.obj(
            "the_admin" -> "true"
          )
          val jwtToken = sign(desc.secComSettings.asAlgorithm(models.OutputMode).get, header, payload)
          Right(
            otoroshiRequest.copy(
              headers = otoroshiRequest.headers ++ Map("Otoroshi-Claim" -> jwtToken)
            )
          )
        }
        case None => Right(otoroshiRequest)
      }
    }
  }
}

// new JwtTokenTransformer
