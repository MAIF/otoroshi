package functional

import otoroshi.models.DataExporterConfig
import play.api.Configuration
import play.api.libs.json.*

// the template of a data exporter is a config of the type asked for, through the generic api and the legacy one
class DataExporterTemplatesSpec extends OtoroshiSpec {

  override def getTestConfiguration(configuration: Configuration): Configuration = configuration

  private val types = Seq(
    "webhook", "elastic", "pulsar", "kafka", "mailer", "file", "goreplayfile", "goreplays3", "s3", "console",
    "metrics", "custommetrics", "custom", "wasm", "otlp-logs", "otlp-metrics", "splunk", "http", "workflow",
    "datadog", "newrelic", "postgresql", "tcp", "udp", "syslog", "jms", "user-analytics"
  )

  "The template of a data exporter" should {
    "warm up" in {
      startOtoroshi()
    }
    "be of the type asked for" in {
      Seq("/apis/events.otoroshi.io/v1/data-exporters/_template", "/api/data-exporter-configs/_template").foreach {
        path =>
          types.foreach { typ =>
            val (json, status) = otoroshiApiCall("GET", s"$path?type=$typ").futureValue
            withClue(s"$path?type=$typ: $json") {
              status mustBe 200
              (json \ "type").as[String] mustBe typ
              DataExporterConfig.format.reads(json).isSuccess mustBe true
            }
          }
      }
    }
    "shutdown" in {
      stopAll()
    }
  }
}
