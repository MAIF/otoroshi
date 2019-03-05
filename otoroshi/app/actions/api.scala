package actions

import java.util.Base64

import akka.http.scaladsl.util.FastFuture
import com.google.common.base.Charsets
import env.Env
import models.{ApiKey, GlobalConfig}
import play.api.Logger
import play.api.libs.json.{JsValue, Json}
import play.api.mvc._
import security.OtoroshiClaim
import utils.future.Implicits._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class ApiActionContext[A](apiKey: ApiKey, request: Request[A]) {
  def user(implicit env: Env): Option[JsValue] =
    request.headers
      .get(env.Headers.OtoroshiAdminProfile)
      .flatMap(p => Try(Json.parse(new String(Base64.getDecoder.decode(p), Charsets.UTF_8))).toOption)
  def from: String = request.headers.get("X-Forwarded-For").getOrElse(request.remoteAddress)
}

class ApiAction(val parser: BodyParser[AnyContent])(implicit env: Env)
    extends ActionBuilder[ApiActionContext, AnyContent]
    with ActionFunction[Request, ApiActionContext] {

  implicit lazy val ec = env.otoroshiExecutionContext

  lazy val logger = Logger("otoroshi-api-action")

  def decodeBase64(encoded: String): String = new String(OtoroshiClaim.decoder.decode(encoded), Charsets.UTF_8)

  def error(message: String, ex: Option[Throwable] = None)(implicit request: Request[_]): Future[Result] = {
    ex match {
      case Some(e) => logger.error(s"error message: $message", e)
      case None    => logger.error(s"error message: $message")
    }
    FastFuture.successful(
      Results
        .Unauthorized(Json.obj("error" -> message))
        .withHeaders(
          env.Headers.OtoroshiStateResp -> request.headers.get(env.Headers.OtoroshiState).getOrElse("--")
        )
    )
  }

  override def invokeBlock[A](request: Request[A], block: ApiActionContext[A] => Future[Result]): Future[Result] = {

    implicit val req = request

    val host = if (request.host.contains(":")) request.host.split(":")(0) else request.host
    host match {
      case env.adminApiHost => {
        request.headers.get(env.Headers.OtoroshiClaim).get.split("\\.").toSeq match {
          case Seq(head, body, signature) => {
            val claim = Json.parse(new String(OtoroshiClaim.decoder.decode(body), Charsets.UTF_8))
            (claim \ "sub").as[String].split(":").toSeq match {
              case Seq("apikey", clientId) => {
                env.datastores.globalConfigDataStore
                  .singleton()
                  .filter(c => request.method.toLowerCase() == "get" || !c.apiReadOnly)
                  .flatMap { _ =>
                    env.datastores.apiKeyDataStore.findById(clientId).flatMap {
                      case Some(apikey) if apikey.authorizedGroup == env.backOfficeGroup.id => {
                        block(ApiActionContext(apikey, request)).foldM {
                          case Success(res) =>
                            res
                              .withHeaders(
                                env.Headers.OtoroshiStateResp -> request.headers
                                  .get(env.Headers.OtoroshiState)
                                  .getOrElse("--")
                              )
                              .asFuture
                          case Failure(err) => error(s"Server error : $err", Some(err))
                        }
                      }
                      case _ => error("You're not authorized")
                    }
                  } recoverWith {
                  case _ => error("You're not authorized")
                }
              }
              case _ => error("You're not authorized")
            }
          }
          case _ => error("You're not authorized")
        }
      }
      case _ => error(s"Not found")
    }
  }

  override protected def executionContext: ExecutionContext = ec
}

case class UnAuthApiActionContent[A](req: Request[A])

class UnAuthApiAction(val parser: BodyParser[AnyContent])(implicit env: Env)
    extends ActionBuilder[UnAuthApiActionContent, AnyContent]
    with ActionFunction[Request, UnAuthApiActionContent] {

  implicit lazy val ec = env.otoroshiExecutionContext

  lazy val logger = Logger("otoroshi-api-action")

  def error(message: String, ex: Option[Throwable] = None)(implicit request: Request[_]): Future[Result] = {
    ex match {
      case Some(e) => logger.error(s"error message: $message", e)
      case None    => logger.error(s"error message: $message")
    }
    FastFuture.successful(
      Results
        .Unauthorized(Json.obj("error" -> message))
        .withHeaders(
          env.Headers.OtoroshiStateResp -> request.headers.get(env.Headers.OtoroshiState).getOrElse("--")
        )
    )
  }

  override def invokeBlock[A](request: Request[A],
                              block: UnAuthApiActionContent[A] => Future[Result]): Future[Result] = {

    implicit val req = request

    println("pouet")

    val host = if (request.host.contains(":")) request.host.split(":")(0) else request.host
    host match {
      case env.adminApiHost => block(UnAuthApiActionContent(request))
      case _                => error(s"Not found")
    }
  }

  override protected def executionContext: ExecutionContext = ec
}
