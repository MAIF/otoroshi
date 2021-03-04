package otoroshi.events.impl

import otoroshi.env.Env
import otoroshi.events.{AnalyticEvent, AnalyticsWritesService}
import otoroshi.models.{GlobalConfig, HSAlgoSettings, Webhook}
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.{JsArray, JsValue, Json}
import otoroshi.security.{IdGenerator, OtoroshiClaim}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class WebHookAnalytics(webhook: Webhook, config: GlobalConfig) extends AnalyticsWritesService {

  import otoroshi.utils.http.Implicits._

  lazy val logger = Logger("otoroshi-analytics-webhook")

  private def defaultParams(
      service: Option[String],
      from: Option[DateTime],
      to: Option[DateTime],
      page: Option[Int] = None,
      size: Option[Int] = None
  ): Seq[(String, String)] =
    Seq(
      service.map(s => "services" -> s),
      page.map(s => "page" -> s.toString),
      size.map(s => "size" -> s.toString),
      Some(
        "from" -> from
          .getOrElse(DateTime.now().minusHours(1))
          .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
      ),
      Some(
        "to"   -> to
          .getOrElse(DateTime.now())
          .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
      )
    ).flatten

  override def publish(event: Seq[JsValue])(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val state = IdGenerator.extendedToken(128)
    val claim = OtoroshiClaim(
      iss = env.Headers.OtoroshiIssuer,
      sub = "otoroshi-analytics",
      aud = "omoikane",
      exp = DateTime.now().plusSeconds(30).toDate.getTime,
      iat = DateTime.now().toDate.getTime,
      jti = IdGenerator.uuid
    ).serialize(HSAlgoSettings(512, "${config.app.claim.sharedKey}", false))(
      env
    ) // TODO : maybe we need some config here ?
    val headers: Seq[(String, String)] = webhook.headers.toSeq ++ Seq(
        env.Headers.OtoroshiState -> state,
        env.Headers.OtoroshiClaim -> claim
      )

    val url          = event.headOption
      .map(evt =>
        webhook.url
          //.replace("@product", env.eventsName)
          .replace("@service", (evt \ "@service").as[String])
          .replace("@serviceId", (evt \ "@serviceId").as[String])
          .replace("@id", (evt \ "@id").as[String])
          .replace("@messageType", (evt \ "@type").as[String])
      )
      .getOrElse(webhook.url)
    val postResponse = env.MtlsWs
      .url(url, webhook.mtlsConfig)
      .withHttpHeaders(headers: _*)
      .withMaybeProxyServer(config.proxies.eventsWebhooks)
      .post(JsArray(event))
    postResponse.andThen {
      case Success(resp) => {
        logger.debug(s"SEND_TO_ANALYTICS_SUCCESS: ${resp.status} - ${resp.headers} - ${resp.body}")
      }
      case Failure(e)    => {
        logger.error("SEND_TO_ANALYTICS_FAILURE: Error while sending AnalyticEvent", e)
      }
    }
    postResponse.map(_ => ())
  }

  override def init(): Unit = ()
}
