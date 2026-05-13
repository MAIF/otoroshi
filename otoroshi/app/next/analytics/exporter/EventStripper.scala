package otoroshi.next.analytics.exporter

import play.api.libs.json._

/**
 * EventStripper
 *
 * This file is intentionally isolated and meant to be EDITED MANUALLY.
 * It defines how a raw GatewayEvent is reduced before being written to
 * the user-analytics PostgreSQL store.
 *
 * Goals of stripping:
 *   - drop very large or low-value fields (raw headers, full plugin chain,
 *     credentials, etc.) to keep table size and JSONB payloads small
 *   - keep all the fields needed by the analytics queries (timings, identity,
 *     status, route reference, geo/UA data, etc.)
 *
 * Edit the lists below to fit your needs. The stripped JSON is what ends up
 * in the `raw` JSONB column. Denormalized columns are extracted by
 * `EventDenormalizer` from the SAME stripped JSON, so any field they need
 * must be kept here.
 *
 * NOTE: this function must stay pure and fast. It runs once per event in
 * the exporter pipeline.
 */
object EventStripper {

  /**
   * Top-level fields removed from the event entirely.
   * These are the bulky / non-analytics-relevant fields.
   */
  private val droppedTopLevelFields: Set[String] = Set(
    "headers",
    "headersOut",
    "otoroshiHeadersIn",
    "otoroshiHeadersOut",
    "clientCertChain"
  )

  /**
   * Fields kept inside `route` (everything else removed).
   * Keep `metadata` to retain the `Otoroshi-Api-Ref` link and any user metadata.
   * Keep `frontend.domains` for domain-based queries.
   */
  private val keptRouteFields: Set[String] = Set(
    "id",
    "name",
    "_loc",
    "groups",
    "metadata",
    "tags",
    "description"
  )

  def stripGatewayEvent(event: JsValue): JsValue = event match {
    case obj: JsObject =>
      val withoutTopLevel = JsObject(obj.fields.filterNot { case (k, _) => droppedTopLevelFields.contains(k) })
      val withoutRoute    = (withoutTopLevel \ "route").asOpt[JsObject] match {
        case None        => withoutTopLevel
        case Some(route) =>
          val keptRoute     = JsObject(route.fields.filter { case (k, _) => keptRouteFields.contains(k) })
          // keep only route.frontend.domains
          val frontend      = (route \ "frontend").asOpt[JsObject].map { f =>
            val domains: JsArray = (f \ "domains").asOpt[JsArray].getOrElse(JsArray.empty)
            Json.obj("domains" -> domains)
          }
          val withFrontend  = frontend match {
            case Some(f) => keptRoute ++ Json.obj("frontend" -> f)
            case None    => keptRoute
          }
          withoutTopLevel ++ Json.obj("route" -> withFrontend)
      }
      withoutRoute
    case other         => other
  }
}
