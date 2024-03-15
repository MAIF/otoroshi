import akka.stream.Materializer
import com.auth0.jwt._
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces._
import play.api.Logger
import play.api.libs.json._
import play.api.libs.typedmap._
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}
import scala.util._

object OtoroshiFilter {
  object Attrs {
    val OtoroshiClaim: TypedKey[DecodedJWT] = TypedKey("otoroshi-claim")
  }
}

class OtoroshiFilter(env: String, sharedKey: String)(implicit ec: ExecutionContext, val mat: Materializer) extends Filter {

  def apply(nextFilter: RequestHeader => Future[Result])(requestHeader: RequestHeader): Future[Result] = {
    val maybeState = requestHeader.headers.get("Otoroshi-State")
    val maybeClaim = requestHeader.headers.get("Otoroshi-Claim")
    env match {
      case "dev" =>
        nextFilter(requestHeader).map { result =>
          result.withHeaders(
            "Otoroshi-State-Resp" -> maybeState.getOrElse("--")
          )
        }
      case "prod" if maybeClaim.isEmpty && maybeState.isEmpty =>
        Future.successful(
          Results.Unauthorized(
            Json.obj("error" -> "Bad request !!!")
          )
        )
      case "prod" if maybeClaim.isEmpty =>
        Future.successful(
          Results.Unauthorized(
            Json.obj("error" -> "Bad claim !!!")
          ).withHeaders(
            "Otoroshi-State-Resp" -> maybeState.getOrElse("--")
          )
        )
      case "prod" =>
        Try {
          val algorithm = Algorithm.HMAC512(sharedKey)
          val verifier = JWT
            .require(algorithm)
            .withIssuer("Otoroshi")
            .acceptLeeway(5000)
            .build()
          val decoded = verifier.verify(maybeClaim.get)
          nextFilter(requestHeader.addAttr(OtoroshiFilter.Attrs.OtoroshiClaim, decoded)).map { result =>
            result.withHeaders(
              "Otoroshi-State-Resp" -> maybeState.getOrElse("--")
            )
          }
        } recoverWith {
          case e => Success(
            Future.successful(
              Results.Unauthorized(
                Json.obj("error" -> "Claim error !!!", "m" -> e.getMessage)
              ).withHeaders(
                "Otoroshi-State-Resp" -> maybeState.getOrElse("--")
              )
            )
          )
        } get
      case _ =>
        Future.successful(
          Results.Unauthorized(
            Json.obj("error" -> "Bad env !!!")
          ).withHeaders(
            "Otoroshi-State-Resp" -> maybeState.getOrElse("--")
          )
        )
    }
  }
}