package events.impl
import env.Env
import events.{AnalyticEvent, AnalyticsWritesService}
import models.{GlobalConfig, HSAlgoSettings, Webhook}
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.{JsArray, JsValue, Json}
import security.{IdGenerator, OtoroshiClaim}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class WebHookAnalytics(webhook: Webhook, config: GlobalConfig) extends AnalyticsWritesService {

  import utils.http.Implicits._

  lazy val logger = Logger("otoroshi-analytics-webhook")

  def basicCall(path: String,
                service: Option[String],
                from: Option[DateTime],
                to: Option[DateTime],
                page: Option[Int] = None,
                size: Option[Int] = None)(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    env.Ws
      .url(webhook.url + path)
      .withHttpHeaders(webhook.headers.toSeq: _*)
      .withQueryStringParameters(defaultParams(service, from, to, page, size): _*)
      .withMaybeProxyServer(config.proxies.eventsWebhooks)
      .get()
      .map(r => Json.parse(r.body))
      .map(r => Some(r))

  private def defaultParams(service: Option[String],
                            from: Option[DateTime],
                            to: Option[DateTime],
                            page: Option[Int] = None,
                            size: Option[Int] = None): Seq[(String, String)] =
    Seq(
      service.map(s => "services" -> s),
      page.map(s => "page"        -> s.toString),
      size.map(s => "size"        -> s.toString),
      Some(
        "from" -> from
          .getOrElse(DateTime.now().minusHours(1))
          .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
      ),
      Some(
        "to" -> to
          .getOrElse(DateTime.now())
          .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
      )
    ).flatten

  override def publish(event: Seq[AnalyticEvent])(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val state = IdGenerator.extendedToken(128)
    val claim = OtoroshiClaim(
      iss = env.Headers.OtoroshiIssuer,
      sub = "otoroshi-analytics",
      aud = "omoikane",
      exp = DateTime.now().plusSeconds(30).toDate.getTime,
      iat = DateTime.now().toDate.getTime,
      jti = IdGenerator.uuid
    ).serialize(HSAlgoSettings(512, "${config.app.claim.sharedKey}"))(env) // TODO : maybe we need some config here ?
    val headers: Seq[(String, String)] = webhook.headers.toSeq ++ Seq(
      env.Headers.OtoroshiState -> state,
      env.Headers.OtoroshiClaim -> claim
    )

    val url = event.headOption
      .map(
        evt =>
          webhook.url
          //.replace("@product", env.eventsName)
            .replace("@service", evt.`@service`)
            .replace("@serviceId", evt.`@serviceId`)
            .replace("@id", evt.`@id`)
            .replace("@messageType", evt.`@type`)
      )
      .getOrElse(webhook.url)
    val postResponse = env.Ws
      .url(url)
      .withHttpHeaders(headers: _*)
      .withMaybeProxyServer(config.proxies.eventsWebhooks)
      .post(JsArray(event.map(_.toEnrichedJson)))
    postResponse.andThen {
      case Success(resp) => {
        logger.debug(s"SEND_TO_ANALYTICS_SUCCESS: ${resp.status} - ${resp.headers} - ${resp.body}")
      }
      case Failure(e) => {
        logger.error("SEND_TO_ANALYTICS_FAILURE: Error while sending AnalyticEvent", e)
      }
    }
    postResponse.map(_ => ())
  }

  override def init(): Unit = ()
}
