package gateway

import akka.actor.Status.Success
import akka.http.scaladsl.util.FastFuture
import env.Env
import events._
import models.{RemainingQuotas, ServiceDescriptor}
import org.joda.time.DateTime
import play.api.libs.json.Json
import play.api.mvc.Results.Status
import play.api.mvc.{RequestHeader, Result}
import utils.RequestImplicits._

import scala.concurrent.{ExecutionContext, Future}

object Errors {

  val messages = Map(
    404 -> ("The page you're looking for does not exist", "notFound.gif")
  )

  def craftResponseResult(message: String,
                          status: Status,
                          req: RequestHeader,
                          maybeDescriptor: Option[ServiceDescriptor] = None,
                          maybeCauseId: Option[String] = None,
                          duration: Long = 0L,
                          overhead: Long = 0L,
                          cbDuration: Long = 0L,
                          callAttempts: Int = 0)(implicit ec: ExecutionContext, env: Env): Future[Result] = {

    val errorId = env.snowflakeGenerator.nextIdStr()

    def sendAnalytics(headers: Seq[Header]): Unit = {
      maybeDescriptor match {
        case Some(descriptor) => {
          val fromLbl = req.headers.get(env.Headers.OtoroshiVizFromLabel).getOrElse("internet")
          // TODO : mark as error ???
          val viz: OtoroshiViz = OtoroshiViz(
            to = descriptor.id,
            toLbl = descriptor.name,
            from = req.headers.get(env.Headers.OtoroshiVizFrom).getOrElse("internet"),
            fromLbl = fromLbl,
            fromTo = s"$fromLbl###${descriptor.name}"
          )
          GatewayEvent(
            `@id` = errorId,
            reqId = env.snowflakeGenerator.nextIdStr(),
            parentReqId = None,
            `@timestamp` = DateTime.now(),
            `@calledAt` = DateTime.now(),
            protocol = req.version,
            to = Location(
              scheme = req.headers
                .get("X-Forwarded-Protocol")
                .map(_ == "https")
                .orElse(Some(req.secure))
                .map {
                  case true  => "https"
                  case false => "http"
                }
                .getOrElse("http"),
              host = req.host,
              uri = req.relativeUri
            ),
            target = Location(
              scheme = descriptor.target.scheme,
              host = descriptor.target.host,
              uri = req.relativeUri
            ),
            duration = duration,
            overhead = overhead,
            cbDuration = cbDuration,
            overheadWoCb = overhead - cbDuration,
            callAttempts = callAttempts,
            url = s"${descriptor.target.scheme}://${descriptor.target.host}${descriptor.root}${req.relativeUri}",
            method = req.method,
            from = req.headers.get("X-Forwarded-For").getOrElse(req.remoteAddress),
            env = descriptor.env,
            data = DataInOut(
              dataIn = 0,
              dataOut = 0
            ),
            status = status.header.status,
            headers = req.headers.toSimpleMap.toSeq.map(Header.apply),
            headersOut = headers,
            identity = None,
            `@serviceId` = descriptor.id,
            `@service` = descriptor.name,
            descriptor = Some(descriptor),
            `@product` = descriptor.metadata.getOrElse("product", "--"),
            remainingQuotas = RemainingQuotas(),
            responseChunked = false,
            viz = Some(viz)
          ).toAnalytics()(env)
        }
        case None => {
          val fromLbl = req.headers.get(env.Headers.OtoroshiVizFromLabel).getOrElse("internet")
          GatewayEvent(
            `@id` = errorId,
            reqId = env.snowflakeGenerator.nextIdStr(),
            parentReqId = None,
            `@timestamp` = DateTime.now(),
            `@calledAt` = DateTime.now(),
            protocol = req.version,
            to = Location(
              scheme = req.headers
                .get("X-Forwarded-Protocol")
                .map(_ == "https")
                .orElse(Some(req.secure))
                .map {
                  case true  => "https"
                  case false => "http"
                }
                .getOrElse("http"),
              host = req.host,
              uri = req.relativeUri
            ),
            target = Location(
              scheme = req.theProtocol,
              host = req.host,
              uri = req.relativeUri
            ),
            duration = duration,
            overhead = overhead,
            cbDuration = cbDuration,
            overheadWoCb = overhead - cbDuration,
            callAttempts = callAttempts,
            url = s"${req.theProtocol}://${req.host}${req.relativeUri}",
            method = req.method,
            from = req.headers.get("X-Forwarded-For").getOrElse(req.remoteAddress),
            env = "prod",
            data = DataInOut(
              dataIn = 0,
              dataOut = 0
            ),
            status = status.header.status,
            headers = req.headers.toSimpleMap.toSeq.map(Header.apply),
            headersOut = headers,
            identity = None,
            `@serviceId` = "none",
            `@service` = "none",
            descriptor = None,
            `@product` = "--",
            remainingQuotas = RemainingQuotas(),
            responseChunked = false,
            viz = None
          ).toAnalytics()(env)
        }
      }
      ()
    }

    def standardResult(): Future[Result] = {
      val accept = req.headers.get("Accept").getOrElse("text/html").split(",").toSeq
      if (accept.contains("text/html")) { // in a browser
        if (maybeCauseId.contains("errors.service.in.maintenance")) {
          FastFuture.successful(
            status
              .apply(views.html.otoroshi.maintenance(env))
              .withHeaders(
                env.Headers.OtoroshiGatewayError -> "true",
                env.Headers.OtoroshiErrorMsg     -> message,
                env.Headers.OtoroshiStateResp    -> req.headers.get(env.Headers.OtoroshiState).getOrElse("--")
              )
          )
        } else if (maybeCauseId.contains("errors.service.under.construction")) {
          FastFuture.successful(
            status
              .apply(views.html.otoroshi.build(env))
              .withHeaders(
                env.Headers.OtoroshiGatewayError -> "true",
                env.Headers.OtoroshiErrorMsg     -> message,
                env.Headers.OtoroshiStateResp    -> req.headers.get(env.Headers.OtoroshiState).getOrElse("--")
              )
          )
        } else {
          FastFuture.successful(
            status
              .apply(
                views.html.otoroshi.error(
                  message = message,
                  _env = env
                )
              )
              .withHeaders(
                env.Headers.OtoroshiGatewayError -> "true",
                env.Headers.OtoroshiErrorMsg     -> message,
                env.Headers.OtoroshiStateResp    -> req.headers.get(env.Headers.OtoroshiState).getOrElse("--")
              )
          )
        }
      } else {
        FastFuture.successful(
          status
            .apply(Json.obj(env.Headers.OtoroshiGatewayError -> message))
            .withHeaders(
              env.Headers.OtoroshiGatewayError -> "true",
              env.Headers.OtoroshiErrorMsg     -> message,
              env.Headers.OtoroshiStateResp    -> req.headers.get(env.Headers.OtoroshiState).getOrElse("--")
            )
        )
      }
    }

    def customResult(descriptor: ServiceDescriptor): Future[Result] =
      env.datastores.errorTemplateDataStore.findById(descriptor.id).flatMap {
        case None => standardResult()
        case Some(errorTemplate) => {
          val accept = req.headers.get("Accept").getOrElse("text/html").split(",").toSeq
          if (accept.contains("text/html")) { // in a browser
            FastFuture.successful(
              status
                .apply(
                  errorTemplate
                    .renderHtml(status.header.status, maybeCauseId.getOrElse("--"), message, errorId)
                )
                .as("text/html")
                .withHeaders(
                  env.Headers.OtoroshiGatewayError -> "true",
                  env.Headers.OtoroshiErrorMsg     -> message,
                  env.Headers.OtoroshiStateResp    -> req.headers.get(env.Headers.OtoroshiState).getOrElse("--")
                )
            )
          } else {
            FastFuture.successful(
              status
                .apply(
                  errorTemplate
                    .renderJson(status.header.status, maybeCauseId.getOrElse("--"), message, errorId)
                )
                .withHeaders(
                  env.Headers.OtoroshiGatewayError -> "true",
                  env.Headers.OtoroshiStateResp    -> req.headers.get(env.Headers.OtoroshiState).getOrElse("--")
                )
            )
          }
        }
      }

    (maybeDescriptor match {
      case Some(desc) => customResult(desc)
      case None       => standardResult()
    }) andThen {
      case scala.util.Success(resp) => sendAnalytics(resp.header.headers.toSeq.map(Header.apply))
    }
  }
}
