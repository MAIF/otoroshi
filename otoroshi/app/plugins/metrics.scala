package otoroshi.plugins.metrics

import java.io.StringWriter

import akka.stream.Materializer
import env.Env
import io.prometheus.client.{Collector, CollectorRegistry}
import io.prometheus.client.exporter.common.TextFormat
import otoroshi.script._
import otoroshi.utils.string.Implicits._
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc.{Result, Results}
import utils.RegexPool
import utils.RequestImplicits._
import utils.future.Implicits._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class ServiceMetrics extends RequestTransformer {

  override def name: String = "Service Metrics"

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        "ServiceMetrics" -> Json.obj(
          "accessKeyValue" -> "${config.app.health.accessKey}",
          "accessKeyQuery" -> "access_key"
        )
      )
    )

  override def description: Option[String] =
    Some(
      """This plugin expose service metrics in Otoroshi global metrics or on a special URL of the service `/.well-known/otoroshi/metrics`.
      |Metrics are exposed in json or prometheus format depending on the accept header. You can protect it with an access key defined in the configuration
      |
      |This plugin can accept the following configuration
      |
      |```json
      |{
      |  "ServiceMetrics": {
      |    "accessKeyValue": "secret", // if not defined, public access. Can be ${config.app.health.accessKey}
      |    "accessKeyQuery": "access_key"
      |  }
      |}
      |```
    """.stripMargin
    )

  override def transformRequestWithCtx(
      ctx: TransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    (ctx.rawRequest.method, ctx.rawRequest.path) match {
      case ("GET", "/.well-known/otoroshi/plugins/metrics") => {

        val format  = ctx.request.getQueryString("format")

        def result(): Future[Either[Result, HttpRequest]] = {
          val filter = Some(s"*otoroshi.service.requests.*.*.${ctx.descriptor.name.slug}*")

          def transformToArray(input: String): JsValue = {
            val metrics = Json.parse(input)
            metrics.as[JsObject].value.toSeq.foldLeft(Json.arr()) {
              case (arr, (key, JsObject(value))) =>
                arr ++ value.toSeq.foldLeft(Json.arr()) {
                  case (arr2, (key2, value2@JsObject(_))) =>
                    arr2 ++ Json.arr(value2 ++ Json.obj("name" -> key2, "type" -> key))
                  case (arr2, (key2, value2)) =>
                    arr2
                }
              case (arr, (key, value)) => arr
            }
          }

          if (format.contains("old_json") || format.contains("old")) {
            Left(Results.Ok(env.metrics.jsonExport(filter)).as("application/json")).future
          } else if (format.contains("json")) {
            Left(Results.Ok(transformToArray(env.metrics.jsonExport(filter))).as("application/json")).future
          } else if (format.contains("prometheus") || format.contains("prom")) {
            Left(Results.Ok(env.metrics.prometheusExport(filter)).as("text/plain")).future
          } else if (ctx.request.accepts("application/json")) {
            Left(Results.Ok(transformToArray(env.metrics.jsonExport(filter))).as("application/json")).future
          } else if (ctx.request.accepts("application/prometheus")) {
            Left(Results.Ok(env.metrics.prometheusExport(filter)).as("text/plain")).future
          } else {
            Left(Results.Ok(transformToArray(env.metrics.jsonExport(filter))).as("application/json")).future
          }
        }

        val config = ctx.configFor("ServiceMetrics")
        val queryName = (config \ "accessKeyQuery").asOpt[String].getOrElse("access_key")
        (config \ "accessKeyValue").asOpt[String] match {
          case None => result()
          case Some("${config.app.health.accessKey}")
              if env.healthAccessKey.isDefined && ctx.request
                .getQueryString(queryName)
                .contains(env.healthAccessKey.get) =>
            result()
          case Some(value) if ctx.request.getQueryString(queryName).contains(value) => result()
          case _                                                                    => Left(Results.Unauthorized(Json.obj("error" -> "not authorized !"))).future
        }
      }
      case _ => Right(ctx.otoroshiRequest).future
    }
  }

  override def transformResponseWithCtx(
      ctx: TransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpResponse]] = {
    val start: Long    = ctx.attrs.get(otoroshi.plugins.Keys.RequestStartKey).getOrElse(0L)
    val duration: Long = System.currentTimeMillis() - start

    // env.metrics.counter(s"otoroshi.service.requests.count.total.${ctx.descriptor.name.slug}").inc()
    // env.metrics
    //   .counter(
    //     s"otoroshi.requests.count.total.${ctx.request.theProtocol}.${ctx.request.method.toLowerCase()}.${ctx.rawResponse.status}"
    //   )
    //   .inc()
    // env.metrics.histogram(s"otoroshi.service.requests.duration.seconds.${ctx.descriptor.name.slug}").update(duration)
    // env.metrics
    //   .histogram(
    //     s"otoroshi.requests.duration.seconds.${ctx.request.theProtocol}.${ctx.request.method.toLowerCase()}.${ctx.rawResponse.status}"
    //   )
    //   .update(duration)
    env.metrics.counter(s"otoroshi.requests.total").inc()
    env.metrics.histogram(s"otoroshi.requests.duration.millis").update(duration)
    env.metrics
      .counter(
        s"otoroshi.service.requests.total.${ctx.descriptor.name.slug}.${ctx.request.theProtocol}.${ctx.request.method
          .toLowerCase()}.${ctx.rawResponse.status}"
      )
      .inc()
    env.metrics
      .histogram(
        s"otoroshi.service.requests.duration.millis.${ctx.descriptor.name.slug}.${ctx.request.theProtocol}.${ctx.request.method
          .toLowerCase()}.${ctx.rawResponse.status}"
      )
      .update(duration)
    Right(ctx.otoroshiResponse).future
  }

  override def transformErrorWithCtx(
      ctx: TransformerErrorContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Result] = {
    val start: Long    = ctx.attrs.get(otoroshi.plugins.Keys.RequestStartKey).getOrElse(0L)
    val duration: Long = System.currentTimeMillis() - start
    // env.metrics.counter(s"otoroshi.service.requests.count.total.${ctx.descriptor.name.slug}").inc()
    // env.metrics
    //   .counter(
    //     s"otoroshi.requests.count.total.${ctx.request.theProtocol}.${ctx.request.method.toLowerCase()}.${ctx.rawResponse.status}"
    //   )
    //   .inc()
    // env.metrics.histogram(s"otoroshi.service.requests.duration.seconds.${ctx.descriptor.name.slug}").update(duration)
    // env.metrics
    //   .histogram(
    //     s"otoroshi.requests.duration.seconds.${ctx.request.theProtocol}.${ctx.request.method.toLowerCase()}.${ctx.rawResponse.status}"
    //   )
    //   .update(duration)
    env.metrics.counter(s"otoroshi.requests.total").inc()
    env.metrics.histogram(s"otoroshi.requests.duration.millis").update(duration)
    env.metrics
      .counter(
        s"otoroshi.service.requests.total.${ctx.descriptor.name.slug}.${ctx.request.theProtocol}.${ctx.request.method
          .toLowerCase()}.${ctx.otoroshiResponse.status}"
      )
      .inc()
    env.metrics
      .histogram(
        s"otoroshi.service.requests.duration.millis.${ctx.descriptor.name.slug}.${ctx.request.theProtocol}.${ctx.request.method
          .toLowerCase()}.${ctx.otoroshiResponse.status}"
      )
      .update(duration)
    ctx.otoroshiResult.future
  }
}

object PrometheusSupport {

  private[metrics] val registry = new CollectorRegistry()

  def register[T <: Collector](collector: T): T = {
    Try(registry.unregister(collector))
    collector.register(registry)
    collector
  }
}

class PrometheusEndpoint extends RequestSink {

  private val ipRegex = RegexPool.regex(
    "^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])(:\\d{2,5})?$"
  )

  override def name: String = "Prometheus Endpoint"

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        "PrometheusEndpoint" -> Json.obj(
          "accessKeyValue" -> "${config.app.health.accessKey}",
          "accessKeyQuery" -> "access_key",
          "includeMetrics" -> false
        )
      )
    )

  override def description: Option[String] =
    Some(
      """This plugin exposes metrics collected by `Prometheus Service Metrics` on a `/prometheus` endpoint.
        |You can protect it with an access key defined in the configuration
        |
        |This plugin can accept the following configuration
        |
        |```json
        |{
        |  "PrometheusEndpoint": {
        |    "accessKeyValue": "secret", // if not defined, public access. Can be ${config.app.health.accessKey}
        |    "accessKeyQuery": "access_key",
        |    "includeMetrics": false
        |  }
        |}
        |```
      """.stripMargin
    )

  override def matches(ctx: RequestSinkContext)(implicit env: Env, ec: ExecutionContext): Boolean = {
    ctx.request.headers.get("Host") match {
      case Some(v) if v == env.adminApiHost && ctx.request.uri.startsWith("/prometheus") => true
      case Some(v) if ipRegex.matches(ctx.request.theHost) && ctx.request.uri.startsWith("/prometheus") => true
      case _ => false
    }
  }

  override def handle(ctx: RequestSinkContext)(implicit env: Env, ec: ExecutionContext): Future[Result] = {

    val config = ctx.configFor("PrometheusEndpoint")
    val queryName = (config \ "accessKeyQuery").asOpt[String].getOrElse("access_key")
    val includeMetrics = (config \ "includeMetrics").asOpt[Boolean].getOrElse(false)

    def result(): Future[Result] = {
      val writer = new StringWriter()
      TextFormat.write004(writer, PrometheusSupport.registry.metricFamilySamples())
      var payload = writer.toString
      if (includeMetrics) {
        payload = payload + env.metrics.prometheusExport(None)
      }
      Results.Ok(payload).as("text/plain").future
    }

    (config \ "accessKeyValue").asOpt[String] match {
      case None => result()
      case Some("${config.app.health.accessKey}")
        if env.healthAccessKey.isDefined && ctx.request
          .getQueryString(queryName)
          .contains(env.healthAccessKey.get) =>
        result()
      case Some(value) if ctx.request.getQueryString(queryName).contains(value) => result()
      case _                                                                    => Results.Unauthorized(Json.obj("error" -> "not authorized !")).future
    }
  }
}

class PrometheusServiceMetrics extends RequestTransformer {

  import io.prometheus.client._

  private val requestCounterGlobal = PrometheusSupport.register(
    Counter.build()
      .name("otoroshi_requests_count_total")
      .help("How many HTTP requests processed globally")
      .create()
  )

  private val reqDurationGlobal = PrometheusSupport.register(
    Histogram.build()
      .name("otoroshi_requests_duration_millis")
      .help("How long it took to process requests globally")
      .buckets(0.1, 0.3, 1.2, 5.0, 10)
      .create()
  )

  private val reqDurationHistogram = PrometheusSupport.register(
    Histogram.build()
      .name("otoroshi_service_requests_duration_millis")
      .help("How long it took to process the request on a service, partitioned by status code, protocol, and method")
      .labelNames("code", "method", "protocol", "service")
      .buckets(0.1, 0.3, 1.2, 5.0, 10)
      .create()
  )

  private val reqTotalHistogram = PrometheusSupport.register(
    Counter.build()
      .name("otoroshi_service_requests_total")
      .help("How many HTTP requests processed on a service, partitioned by status code, protocol, and method")
      .labelNames("code", "method", "protocol", "service")
      .create()
  )

  private val reqDurationHistogramWithUri = PrometheusSupport.register(
    Histogram.build()
      .name("otoroshi_service_requests_wu_duration_millis")
      .help("How long it took to process the request on a service, partitioned by status code, protocol, method and uri")
      .labelNames("code", "method", "protocol", "service", "uri")
      .buckets(0.1, 0.3, 1.2, 5.0, 10)
      .create()
  )

  private val reqTotalHistogramWithUri = PrometheusSupport.register(
    Counter.build()
      .name("otoroshi_service_requests_wu_total")
      .help("How many HTTP requests processed on a service, partitioned by status code, protocol, method and uri")
      .labelNames("code", "method", "protocol", "service", "uri")
      .create()
  )

  override def name: String = "Prometheus Service Metrics"

  override def defaultConfig: Option[JsObject] = Some(Json.obj(
    "PrometheusServiceMetrics" -> Json.obj(
      "includeUri" -> false
    )
  ))

  override def description: Option[String] =
    Some(
      """This plugin collects service metrics and can be used with the `Prometheus Endpoint` (in the Danger Zone) plugin to expose those metrics
        |
        |This plugin can accept the following configuration
        |
        |```json
        |{
        |  "PrometheusServiceMetrics": {
        |    "includeUri": false // include http uri in metrics. WARNING this could implies serious performance issuess
        |  }
        |}
        |```
      """.stripMargin
    )

  override def transformResponseWithCtx(ctx: TransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpResponse]] = {
    val start: Long    = ctx.attrs.get(otoroshi.plugins.Keys.RequestStartKey).getOrElse(0L)
    val duration: Long = System.currentTimeMillis() - start
    val config = ctx.configFor("PrometheusServiceMetrics")
    val includeUri = (config \ "includeUri").asOpt[Boolean].getOrElse(false)

    requestCounterGlobal.inc()
    reqDurationGlobal.observe(duration)
    if (includeUri) {
      reqDurationHistogramWithUri.labels(ctx.otoroshiResponse.status.toString, ctx.request.method.toLowerCase(), ctx.request.theProtocol, ctx.descriptor.name.slug, ctx.request.relativeUri).observe(duration)
      reqTotalHistogramWithUri.labels(ctx.otoroshiResponse.status.toString, ctx.request.method.toLowerCase(), ctx.request.theProtocol, ctx.descriptor.name.slug, ctx.request.relativeUri).inc()
    } else {
      reqDurationHistogram.labels(ctx.otoroshiResponse.status.toString, ctx.request.method.toLowerCase(), ctx.request.theProtocol, ctx.descriptor.name.slug).observe(duration)
      reqTotalHistogram.labels(ctx.otoroshiResponse.status.toString, ctx.request.method.toLowerCase(), ctx.request.theProtocol, ctx.descriptor.name.slug).inc()
    }
    Right(ctx.otoroshiResponse).future
  }

  override def transformErrorWithCtx(
                                      ctx: TransformerErrorContext
                                    )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Result] = {
    val start: Long    = ctx.attrs.get(otoroshi.plugins.Keys.RequestStartKey).getOrElse(0L)
    val duration: Long = System.currentTimeMillis() - start
    val config = ctx.configFor("PrometheusServiceMetrics")
    val includeUri = (config \ "includeUri").asOpt[Boolean].getOrElse(false)

    requestCounterGlobal.inc()
    reqDurationGlobal.observe(duration)
    if (includeUri) {
      reqDurationHistogramWithUri.labels(ctx.otoroshiResponse.status.toString, ctx.request.method.toLowerCase(), ctx.request.theProtocol, ctx.descriptor.name.slug, ctx.request.relativeUri).observe(duration)
      reqTotalHistogramWithUri.labels(ctx.otoroshiResponse.status.toString, ctx.request.method.toLowerCase(), ctx.request.theProtocol, ctx.descriptor.name.slug, ctx.request.relativeUri).inc()
    } else {
      reqDurationHistogram.labels(ctx.otoroshiResponse.status.toString, ctx.request.method.toLowerCase(), ctx.request.theProtocol, ctx.descriptor.name.slug).observe(duration)
      reqTotalHistogram.labels(ctx.otoroshiResponse.status.toString, ctx.request.method.toLowerCase(), ctx.request.theProtocol, ctx.descriptor.name.slug).inc()
    }
    ctx.otoroshiResult.future
  }
}
