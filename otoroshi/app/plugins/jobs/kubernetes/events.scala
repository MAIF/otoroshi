package otoroshi.plugins.jobs.kubernetes

import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.events.AlertEvent
import otoroshi.models.ApiKey
import play.api.libs.json.{JsValue, Json}

case class FailedCrdParsing(
    `@id`: String,
    `@env`: String,
    namespace: String,
    pluralName: String,
    crd: JsValue,
    customizedSpec: JsValue,
    error: String,
    `@timestamp`: DateTime = DateTime.now()
) extends AlertEvent {

  override def `@service`: String   = "Otoroshi"
  override def `@serviceId`: String = "--"

  override def fromOrigin: Option[String]    = None
  override def fromUserAgent: Option[String] = None

  override def toJson(implicit _env: Env): JsValue =
    Json.obj(
      "@id"            -> `@id`,
      "@timestamp"     -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
      "@type"          -> `@type`,
      "@product"       -> _env.eventsName,
      "@serviceId"     -> `@serviceId`,
      "@service"       -> `@service`,
      "@env"           -> `@env`,
      "alert"          -> "FailedCrdParsing",
      "crd"            -> crd,
      "customizedSpec" -> customizedSpec,
      "error"          -> error
    )
}
