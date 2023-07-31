package otoroshi.metrics.opentelemetry

import io.opentelemetry.api.metrics.{DoubleCounter, DoubleHistogram, LongCounter, LongHistogram, Meter}
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.`export`.{BatchLogRecordProcessor, LogRecordExporter}
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.`export`.{MetricExporter, PeriodicMetricReader}
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes
import otoroshi.env.Env
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{Duration, DurationLong}
import scala.util.{Failure, Success, Try}

/*object implicits {
  implicit class BetterMeter(val meter: Meter) extends AnyVal {
    def withLongCounter(sdk: OpenTelemetrySdkWrapper, name: String): LongCounter = {
      sdk.longCounters.getOrUpdate(name) {
        meter.counterBuilder(name).build()
      }
    }
    def withDoubleCounter(sdk: OpenTelemetrySdkWrapper, name: String): DoubleCounter = {
      sdk.doubleCounters.getOrUpdate(name) {
        meter.counterBuilder(name).ofDoubles().build()
      }
    }
    def withLongHistogram(sdk: OpenTelemetrySdkWrapper, name: String): LongHistogram = {
      sdk.longsHistograms.getOrUpdate(name) {
        meter.histogramBuilder(name).ofLongs().build()
      }
    }
    def withDoubleHistogram(sdk: OpenTelemetrySdkWrapper, name: String): DoubleHistogram = {
      sdk.doubleHistograms.getOrUpdate(name) {
        meter.histogramBuilder(name).build()
      }
    }
    def withTimer(sdk: OpenTelemetrySdkWrapper, name: String): LongHistogram = {
      sdk.longsHistograms.getOrUpdate(name) {
        meter.histogramBuilder(name).setUnit("nanoseconds").ofLongs().build()
      }
    }
  }
}*/

class OpenTelemetryMeter(sdk: OpenTelemetrySdkWrapper, meter: Meter) {

  def withLongCounter(name: String): LongCounter = {
    sdk.longCounters.getOrUpdate(name) {
      meter.counterBuilder(name).build()
    }
  }

  def withDoubleCounter(name: String): DoubleCounter = {
    sdk.doubleCounters.getOrUpdate(name) {
      meter.counterBuilder(name).ofDoubles().build()
    }
  }

  def withLongHistogram(name: String): LongHistogram = {
    sdk.longsHistograms.getOrUpdate(name) {
      meter.histogramBuilder(name).ofLongs().build()
    }
  }

  def withDoubleHistogram(name: String): DoubleHistogram = {
    sdk.doubleHistograms.getOrUpdate(name) {
      meter.histogramBuilder(name).build()
    }
  }

  def withTimer(name: String): LongHistogram = {
    sdk.longsHistograms.getOrUpdate(name) {
      meter.histogramBuilder(name).setUnit("nanoseconds").ofLongs().build()
    }
  }
}

case class OpenTelemetrySdkWrapper(sdk: OpenTelemetrySdk, settings: OtlpSettings) {

  private[opentelemetry] val longCounters = new UnboundedTrieMap[String, LongCounter]()
  private[opentelemetry] val doubleCounters = new UnboundedTrieMap[String, DoubleCounter]()
  private[opentelemetry] val longsHistograms = new UnboundedTrieMap[String, LongHistogram]()
  private[opentelemetry] val doubleHistograms = new UnboundedTrieMap[String, DoubleHistogram]()

  def close(): Unit = sdk.close()

  def hasChangedFrom(s: OtlpSettings): Boolean = s != settings
}

case class OtlpSettings(
                         grpc: Boolean,
                         endpoint: String,
                         timeout: Duration,
                         gzip: Boolean,
                         clientCert: Option[String],
                         trustedCert: Option[String],
                         headers: Map[String, String],
                         maxBatch: Int,
                         maxDuration: Duration,
                       ) {

  def json: JsValue = OtlpSettings.format.writes(this)

  def logExporter(env: => Env): LogRecordExporter = {
    if (grpc) {
      OtlpGrpcLogRecordExporter.builder()
        // .setRetryPolicy() // TODO:
        .applyOnWithOpt(clientCert) {
          case (builder, id) => env.proxyState.certificate(id) match {
            case None => builder
            case Some(cert) => builder.setClientTls(cert.privateKey.getBytes, cert.chain.getBytes)
          }
        }
        .applyOnWithOpt(trustedCert) {
          case (builder, id) => env.proxyState.certificate(id) match {
            case None => builder
            case Some(cert) => builder.setTrustedCertificates(cert.chain.getBytes)
          }
        }
        .setCompression(if (gzip) "gzip" else "none")
        .setTimeout(timeout.toMillis, TimeUnit.MILLISECONDS)
        .setEndpoint(endpoint)
        .applyOnIf(headers.nonEmpty) { b =>
          headers.foreach {
            case (key, value) => b.addHeader(key, value)
          }
          b
        }
        .build()
    } else {
      OtlpHttpLogRecordExporter.builder()
        //.addHeader() // TODO:
        //.setRetryPolicy() // TODO:
        .applyOnWithOpt(clientCert) {
          case (builder, id) => env.proxyState.certificate(id) match {
            case None => builder
            case Some(cert) => builder.setClientTls(cert.privateKey.getBytes, cert.chain.getBytes)
          }
        }
        .applyOnWithOpt(trustedCert) {
          case (builder, id) => env.proxyState.certificate(id) match {
            case None => builder
            case Some(cert) => builder.setTrustedCertificates(cert.chain.getBytes)
          }
        }
        .setCompression(if (gzip) "gzip" else "none")
        .setTimeout(timeout.toMillis, TimeUnit.MILLISECONDS)
        .setEndpoint(endpoint)
        .applyOnIf(headers.nonEmpty) { b =>
          headers.foreach {
            case (key, value) => b.addHeader(key, value)
          }
          b
        }
        .build()
    }
  }

  def metricsExporter(env: => Env): MetricExporter = {
    if (grpc) {
      OtlpGrpcMetricExporter.builder()
        // .setRetryPolicy() // TODO:
        .applyOnWithOpt(clientCert) {
          case (builder, id) => env.proxyState.certificate(id) match {
            case None => builder
            case Some(cert) => builder.setClientTls(cert.privateKey.getBytes, cert.chain.getBytes)
          }
        }
        .applyOnWithOpt(trustedCert) {
          case (builder, id) => env.proxyState.certificate(id) match {
            case None => builder
            case Some(cert) => builder.setTrustedCertificates(cert.chain.getBytes)
          }
        }
        .setCompression(if (gzip) "gzip" else "none")
        .setTimeout(timeout.toMillis, TimeUnit.MILLISECONDS)
        .setEndpoint(endpoint)
        .applyOnIf(headers.nonEmpty) { b =>
          headers.foreach {
            case (key, value) => b.addHeader(key, value)
          }
          b
        }
        .build()
    } else {
      OtlpHttpMetricExporter.builder()
        //.addHeader() // TODO:
        //.setRetryPolicy() // TODO:
        .applyOnWithOpt(clientCert) {
          case (builder, id) => env.proxyState.certificate(id) match {
            case None => builder
            case Some(cert) => builder.setClientTls(cert.privateKey.getBytes, cert.chain.getBytes)
          }
        }
        .applyOnWithOpt(trustedCert) {
          case (builder, id) => env.proxyState.certificate(id) match {
            case None => builder
            case Some(cert) => builder.setTrustedCertificates(cert.chain.getBytes)
          }
        }
        .setCompression(if (gzip) "gzip" else "none")
        .setTimeout(timeout.toMillis, TimeUnit.MILLISECONDS)
        .setEndpoint(endpoint)
        .applyOnIf(headers.nonEmpty) { b =>
          headers.foreach {
            case (key, value) => b.addHeader(key, value)
          }
          b
        }
        .build()
    }
  }
}

object OtlpSettings {

  private val sdks = new UnboundedTrieMap[String, OpenTelemetrySdkWrapper]()

  val defaultLogs = OtlpSettings(
    grpc = false,
    endpoint = "http://localhost:10080/logs",
    timeout = 5000L.millis,
    gzip = false,
    clientCert = None,
    trustedCert = None,
    headers = Map.empty,
    maxBatch = 100,
    maxDuration = 10.seconds,
  )

  val defaultServerLogs = defaultLogs.copy(
    endpoint = "http://localhost:10080/server-logs",
  )

  val defaultMetrics = defaultLogs.copy(
    endpoint = "http://localhost:10080/metrics",
  )

  val format = new Format[OtlpSettings] {
    override def writes(o: OtlpSettings): JsValue = Json.obj(
      "gzip" -> o.gzip,
      "grpc" -> o.grpc,
      "endpoint" -> o.endpoint,
      "timeout" -> o.timeout.toMillis,
      "client_cert" -> o.clientCert.map(JsString.apply).getOrElse(JsNull).asValue,
      "trusted_cert" -> o.clientCert.map(JsString.apply).getOrElse(JsNull).asValue,
      "headers" -> o.headers,
      "max_batch" -> o.maxBatch,
      "max_duration" -> o.maxDuration.toMillis,
    )

    override def reads(json: JsValue): JsResult[OtlpSettings] = Try {
      OtlpSettings(
        grpc = json.select("grpc").asOpt[Boolean].getOrElse(false),
        gzip = json.select("gzip").asOpt[Boolean].getOrElse(false),
        endpoint = json.select("endpoint").asString,
        timeout = json.select("timeout").asOpt[Long].filterNot(_ == 0L).map(_.millis).getOrElse(30.seconds),
        clientCert = json.select("client_cert").asOpt[String],
        trustedCert = json.select("trusted_cert").asOpt[String],
        headers = json.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty),
        maxBatch = json.select("max_batch").asOpt[Int].getOrElse(1000),
        maxDuration = json.select("max_duration").asOpt[Long].filterNot(_ == 0L).map(_.millis).getOrElse(30.seconds)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
  }

  def sdkFor(_id: String, name: String, settings: OtlpSettings, env: => Env): OpenTelemetrySdkWrapper = sdks.synchronized {
    val id = settings.endpoint // _id

    def build(): OpenTelemetrySdkWrapper = {
      val sdk = OpenTelemetrySdk.builder()
        .setMeterProvider(
          SdkMeterProvider.builder()
            .setResource(
              Resource.getDefault().toBuilder()
                .put(ResourceAttributes.SERVICE_NAME, name)
                .build()
            )
            .registerMetricReader(PeriodicMetricReader
              .builder(settings.metricsExporter(env))
              .setInterval(settings.maxDuration.toMillis, TimeUnit.MILLISECONDS)
              .build())
            .build()
        )
        .setLoggerProvider(
          SdkLoggerProvider.builder()
            .setResource(
              Resource.getDefault().toBuilder()
                .put(ResourceAttributes.SERVICE_NAME, name)
                .build()
            )
            .addLogRecordProcessor(
              BatchLogRecordProcessor
                .builder(settings.logExporter(env))
                .setMaxExportBatchSize(settings.maxBatch)
                .setScheduleDelay(settings.maxDuration.toMillis, TimeUnit.MILLISECONDS)
                .build()
            )
            .build()
        )
        .build()
      OpenTelemetrySdkWrapper(sdk, settings)
    }

    var sdk = sdks.getOrUpdate(id) {
      build()
    }
    if (sdk.hasChangedFrom(settings)) {
      sdk.close()
      sdk = build()
      sdks.put(id, sdk)
    }
    sdk
  }
}