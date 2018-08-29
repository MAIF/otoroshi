package events.impl
import env.Env
import events.{AnalyticEvent, AnalyticsService}
import models.{HSAlgoSettings, Webhook}
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.{JsArray, JsValue}
import security.{IdGenerator, OtoroshiClaim}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class WebHookAnalytics(webhook: Webhook) extends AnalyticsService {

  lazy val logger = Logger("otoroshi-analytics-webhook")

  def basicCall(path: String, service: Option[String], from: Option[DateTime], to: Option[DateTime], page: Option[Int] = None, size: Option[Int] = None)(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    env.Ws
      .url(webhook.url + path)
      .withHttpHeaders(webhook.headers.toSeq: _*)
      .withQueryStringParameters(defaultParams(service, from, to, page, size): _*)
      .get()
      .map(_.json)
      .map(r => Some(r))


  private def defaultParams(service: Option[String],
                            from: Option[DateTime],
                            to: Option[DateTime],
                            page: Option[Int] = None,
                            size: Option[Int] = None): Seq[(String, String)] =
    Seq(
      service.map(s => "services" -> s),
      page.map(s => "page" -> s.toString),
      size.map(s => "size" -> s.toString),
      Some("from" -> from
        .getOrElse(DateTime.now().minusHours(1))
        .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ")),
      Some("to" -> to
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
    val postResponse = env.Ws.url(url).withHttpHeaders(headers: _*).post(JsArray(event.map(_.toJson)))
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


  override def events(eventType: String,
                      service: Option[String],
                      from: Option[DateTime],
                      to: Option[DateTime],
                      page: Int,
                      size: Int)(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]] = basicCall("", service, from, to, Some(page), Some(size))

  override def fetchHits(
                          service: Option[String],
                          from: Option[DateTime],
                          to: Option[DateTime]
                        )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    basicCall("/GatewayEvent/_count", service, from, to)

  override def fetchDataIn(
                            service: Option[String],
                            from: Option[DateTime],
                            to: Option[DateTime]
                          )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    basicCall("/GatewayEvent/data.dataIn/_sum", service, from, to)

  override def fetchDataOut(
                             service: Option[String],
                             from: Option[DateTime],
                             to: Option[DateTime]
                           )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    basicCall("/GatewayEvent/data.dataOut/_sum", service, from, to)

  override def fetchAvgDuration(
                                 service: Option[String],
                                 from: Option[DateTime],
                                 to: Option[DateTime]
                               )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    basicCall("/GatewayEvent/duration/_avg", service, from, to)

  override def fetchAvgOverhead(
                                 service: Option[String],
                                 from: Option[DateTime],
                                 to: Option[DateTime]
                               )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    basicCall("/GatewayEvent/overhead/_avg", service, from, to)

  override def fetchStatusesPiechart(
                                      service: Option[String],
                                      from: Option[DateTime],
                                      to: Option[DateTime]
                                    )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    basicCall("/GatewayEvent/status/_piechart", service, from, to)

  override def fetchStatusesHistogram(
                                       service: Option[String],
                                       from: Option[DateTime],
                                       to: Option[DateTime]
                                     )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    basicCall("/httpStatus/_histogram", service, from, to)


  override def fetchDataInStatsHistogram(
                                          service: Option[String],
                                          from: Option[DateTime],
                                          to: Option[DateTime]
                                        )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    basicCall("/GatewayEvent/data.dataIn/_histogram/stats", service, from, to)

  override def fetchDataOutStatsHistogram(
                                           service: Option[String],
                                           from: Option[DateTime],
                                           to: Option[DateTime]
                                         )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    basicCall("/GatewayEvent/data.dataOut/_histogram/stats", service, from, to)


  override def fetchDurationStatsHistogram(
                                            service: Option[String],
                                            from: Option[DateTime],
                                            to: Option[DateTime]
                                          )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    basicCall("/GatewayEvent/duration/_histogram/stats", service, from, to)


  override def fetchDurationPercentilesHistogram(
                                                  service: Option[String],
                                                  from: Option[DateTime],
                                                  to: Option[DateTime]
                                                )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    basicCall("/GatewayEvent/duration/_histogram/percentiles", service, from, to)

  override def fetchOverheadPercentilesHistogram(
                                                  service: Option[String],
                                                  from: Option[DateTime],
                                                  to: Option[DateTime]
                                                )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    basicCall("/GatewayEvent/overhead/_histogram/percentiles", service, from, to)


  override def fetchOverheadStatsHistogram(
                                            service: Option[String],
                                            from: Option[DateTime],
                                            to: Option[DateTime]
                                          )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    basicCall("/GatewayEvent/overhead/_histogram/stats", service, from, to)

  override def fetchProductPiechart(service: Option[String],
                                    from: Option[DateTime],
                                    to: Option[DateTime],
                                    size: Int)(
                                     implicit env: Env,
                                     ec: ExecutionContext
                                   ): Future[Option[JsValue]] =
    basicCall("/GatewayEvent/@product/_piechart", service, from, to, size = Some(size))

  override def fetchServicePiechart(service: Option[String],
                                    from: Option[DateTime],
                                    to: Option[DateTime],
                                    size: Int)(
                                     implicit env: Env,
                                     ec: ExecutionContext
                                   ): Future[Option[JsValue]] =
    basicCall("/GatewayEvent/@service/_piechart", service, from, to, size = Some(size))
}
