package otoroshi.next.analytics.alerts

import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.events.AlertEvent
import otoroshi.next.analytics.models.UserAlert
import play.api.libs.json._

/**
 * Per-condition evaluation snapshot included in the emitted alert payload so
 * the receiver (mailer/webhook/...) can explain WHY the alert fired.
 */
case class AlertConditionEval(
    query: String,
    reducer: String,
    operator: String,
    threshold: Double,
    value: Option[Double],
    matched: Boolean,
    error: Option[String] = None
) {
  def json: JsValue = Json.obj(
    "query"     -> query,
    "reducer"   -> reducer,
    "operator"  -> operator,
    "threshold" -> threshold,
    "value"     -> value.map(JsNumber(_)).getOrElse(JsNull).asInstanceOf[JsValue],
    "matched"   -> matched,
    "error"     -> error.map(JsString.apply).getOrElse(JsNull).asInstanceOf[JsValue]
  )
}

/**
 * AlertEvent emitted by the user-analytics alert evaluator when an alert fires.
 *
 * Recognisable signature for data exporters:
 *   - `@type` = `AlertEvent` (inherited)
 *   - `alert` = `UserAnalyticsAlert`
 *   - `alertSubcategory` = `user-analytics`
 *   - additional fields: `alertId`, `alertName`, `severity`, `message`,
 *     `tenant`, `windowSeconds`, `combine`, `conditions[]`
 */
case class UserAnalyticsAlertEvent(
    alertConfig: UserAlert,
    evaluations: Seq[AlertConditionEval],
    `@id`: String,
    `@timestamp`: DateTime = DateTime.now()
)(implicit _env: Env)
    extends AlertEvent {

  val alert: String                 = "UserAnalyticsAlert"
  override val `@service`: String   = "Otoroshi"
  override val `@serviceId`: String = alertConfig.id

  override val fromOrigin: Option[String]    = None
  override val fromUserAgent: Option[String] = None

  override def toJson(implicit env: Env): JsValue = {
    Json.obj(
      "@id"               -> `@id`,
      "@timestamp"        -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
      "@type"             -> `@type`,
      "@product"          -> env.eventsName,
      "@serviceId"        -> `@serviceId`,
      "@service"          -> `@service`,
      "@env"              -> env.env,
      "alert"             -> alert,
      "alertSubcategory"  -> "user-analytics",
      "alertId"           -> alertConfig.id,
      "alertName"         -> alertConfig.name,
      "severity"          -> alertConfig.severity,
      "message"           -> alertConfig.message,
      "tenant"            -> alertConfig.location.tenant.value,
      "teams"             -> JsArray(alertConfig.location.teams.map(t => JsString(t.value))),
      "windowSeconds"     -> alertConfig.windowSeconds,
      "combine"           -> alertConfig.combine,
      "conditions"        -> JsArray(evaluations.map(_.json))
    )
  }
}
