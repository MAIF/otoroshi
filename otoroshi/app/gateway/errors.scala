package gateway

import akka.http.scaladsl.util.FastFuture
import env.Env
import events._
import models.{RemainingQuotas, ServiceDescriptor}
import org.joda.time.DateTime
import play.api.libs.json.Json
import play.api.mvc.Results.Status
import play.api.mvc.{RequestHeader, Result}

import scala.concurrent.{ExecutionContext, Future}

object Errors {

  val messages = Map(
    404 -> ("The page you're looking for does not exist", "notFound.gif")
  )

  def craftResponseResult(message: String,
                          status: Status,
                          req: RequestHeader,
                          maybeDescriptor: Option[ServiceDescriptor] = None,
                          maybeCauseId: Option[String])(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    val errorId = env.snowflakeGenerator.nextId()
    maybeDescriptor.foreach { descriptor =>
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
        `@id` = errorId.toString,
        reqId = env.snowflakeGenerator.nextId(),
        parentReqId = None,
        `@timestamp` = DateTime.now(),
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
          uri = req.uri
        ),
        target = Location(
          scheme = descriptor.target.scheme,
          host = descriptor.target.host,
          uri = req.uri
        ),
        duration = 0,
        overhead = 0,
        url = s"${descriptor.target.scheme}://${descriptor.target.host}${descriptor.root}${req.uri}",
        from = req.headers.get("X-Forwarded-For").getOrElse(req.remoteAddress),
        env = descriptor.env,
        data = DataInOut(
          dataIn = 0,
          dataOut = 0
        ),
        status = status.header.status,
        headers = req.headers.toSimpleMap.toSeq.map(Header.apply),
        identity = None,
        `@serviceId` = descriptor.id,
        `@service` = descriptor.name,
        descriptor = descriptor,
        `@product` = descriptor.metadata.getOrElse("product", "--"),
        remainingQuotas = RemainingQuotas(),
        viz = Some(viz)
      ).toAnalytics()(env)
    }

    def standardResult(): Future[Result] = {
      val accept = req.headers.get("Accept").getOrElse("text/html").split(",").toSeq
      if (accept.contains("text/html")) { // in a browser
        if (maybeCauseId.contains("errors.service.in.maintenance")) {
          FastFuture.successful(
            status
              .apply(views.html.otoroshiapps.maintenance(env))
              .withHeaders(
                env.Headers.OtoroshiGatewayError -> "true",
                env.Headers.OtoroshiErrorMsg     -> message,
                env.Headers.OtoroshiStateResp    -> req.headers.get(env.Headers.OtoroshiState).getOrElse("--")
              )
          )
        } else if (maybeCauseId.contains("errors.service.under.construction")) {
          FastFuture.successful(
            status
              .apply(views.html.otoroshiapps.build(env))
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
                views.html.otoroshiapps.error(
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
                    .renderHtml(status.header.status, maybeCauseId.getOrElse("--"), message, errorId.toString)
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
                    .renderJson(status.header.status, maybeCauseId.getOrElse("--"), message, errorId.toString)
                )
                .withHeaders(
                  env.Headers.OtoroshiGatewayError -> "true",
                  env.Headers.OtoroshiStateResp    -> req.headers.get(env.Headers.OtoroshiState).getOrElse("--")
                )
            )
          }
        }
      }

    maybeDescriptor match {
      case Some(desc) => customResult(desc)
      case None       => standardResult()
    }
  }
}
