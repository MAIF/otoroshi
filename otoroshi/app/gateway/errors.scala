package gateway

import akka.http.scaladsl.util.FastFuture
import env.Env
import events._
import models.{RemainingQuotas, ServiceDescriptor}
import org.joda.time.DateTime
import otoroshi.script.Implicits._
import otoroshi.script.{HttpResponse, TransformerErrorContext}
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.DefaultWSCookie
import play.api.mvc.Results.Status
import play.api.mvc.{RequestHeader, Result}
import utils.RequestImplicits._
import utils.TypedMap

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
                          callAttempts: Int = 0,
                          emptyBody: Boolean = false,
                          attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Result] = {

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
              scheme = req.theProtocol,
              host = req.theHost,
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
            from = req.theIpAddress,
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
            viz = Some(viz),
            err = true,
            userAgentInfo = attrs.get[JsValue](otoroshi.plugins.Keys.UserAgentInfoKey),
            geolocationInfo = attrs.get[JsValue](otoroshi.plugins.Keys.GeolocationInfoKey),
            extraAnalyticsData = attrs.get[JsValue](otoroshi.plugins.Keys.ExtraAnalyticsDataKey)
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
              scheme = req.theProtocol,
              host = req.theHost,
              uri = req.relativeUri
            ),
            target = Location(
              scheme = req.theProtocol,
              host = req.theHost,
              uri = req.relativeUri
            ),
            duration = duration,
            overhead = overhead,
            cbDuration = cbDuration,
            overheadWoCb = overhead - cbDuration,
            callAttempts = callAttempts,
            url = s"${req.theProtocol}://${req.theHost}${req.relativeUri}",
            method = req.method,
            from = req.theIpAddress,
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
            viz = None,
            err = true,
            userAgentInfo = attrs.get[JsValue](otoroshi.plugins.Keys.UserAgentInfoKey),
            geolocationInfo = attrs.get[JsValue](otoroshi.plugins.Keys.GeolocationInfoKey),
            extraAnalyticsData = attrs.get[JsValue](otoroshi.plugins.Keys.ExtraAnalyticsDataKey)
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
          val body =
            if (emptyBody) status.apply("")
            else
              status.apply(
                views.html.otoroshi.error(
                  message = message,
                  _env = env
                )
              )
          FastFuture.successful(
            body
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

    def customResult(descriptor: ServiceDescriptor): Future[Result] = {
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
    }

    (maybeDescriptor match {
      case Some(desc) =>
        customResult(desc).flatMap { res =>
          val ctx = TransformerErrorContext(
            index = -1,
            snowflake = attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).getOrElse(env.snowflakeGenerator.nextIdStr()),
            message = message,
            otoroshiResult = res,
            otoroshiResponse = HttpResponse(
              res.header.status,
              res.header.headers,
              res.newCookies.map(
                c =>
                  DefaultWSCookie(
                    name = c.name,
                    value = c.value,
                    domain = c.domain,
                    path = Option(c.path),
                    maxAge = c.maxAge.map(_.toLong),
                    secure = c.secure,
                    httpOnly = c.httpOnly
                )
              )
            ),
            request = req,
            maybeCauseId = maybeCauseId,
            callAttempts = callAttempts,
            descriptor = desc,
            apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey),
            user = attrs.get(otoroshi.plugins.Keys.UserKey),
            config = Json.obj(),
            attrs = attrs
          )
          desc.transformError(ctx)(env, ec, env.otoroshiMaterializer)
        }
      case None => standardResult()
    }) andThen {
      case scala.util.Success(resp) => sendAnalytics(resp.header.headers.toSeq.map(Header.apply))
    }
  }
}
