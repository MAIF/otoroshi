package otoroshi.next.analytics.defaults

import otoroshi.env.Env
import otoroshi.models.EntityLocation
import otoroshi.next.analytics.models.{UserDashboard, Widget}
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}

/**
 * Default user-analytics dashboards installed on first activation of a
 * user-analytics exporter. Each default carries a stable
 * `otoroshi-default-id` in its metadata so that:
 *   - subsequent boots don't recreate dashboards already present (seedIfMissing)
 *   - the "Restore default dashboards" button only recreates the missing ones
 *     without overwriting user customisations.
 */
object DefaultDashboards {

  private val logger = Logger("otoroshi-user-analytics-defaults")

  val DefaultIdMetaKey: String      = "otoroshi-default-id"
  val DefaultVersionMetaKey: String = "otoroshi-default-version"
  val DefaultVersion: String        = "1"

  // ---------------------------------------------------------------------------
  // Widgets builders
  // ---------------------------------------------------------------------------

  private def w(
      id: String,
      title: String,
      query: String,
      `type`: String,
      width: Int,
      height: Int,
      params: JsObject = Json.obj(),
      options: JsObject = Json.obj()
  ): Widget = Widget(id, title, query, params, `type`, width, height, options)

  // ---------------------------------------------------------------------------
  // Default dashboards definitions
  // ---------------------------------------------------------------------------

  private case class Spec(
      defaultId: String,
      name: String,
      description: String,
      widgets: Seq[Widget]
  )

  private val specs: Seq[Spec] = Seq(
    Spec(
      "analytics-default-overview",
      "Global Overview",
      "Default global traffic dashboard",
      Seq(
        w("requests-total", "Total requests", "requests_total", "metric", 1, 1,
          options = Json.obj("format" -> "count")),
        w("errors-total", "Total errors", "errors_total", "metric", 1, 1,
          options = Json.obj("format" -> "count")),
        w("rps", "Requests per second", "requests_per_second", "line", 4, 2,
          options = Json.obj("format" -> "rps")),
        w("avg-duration", "Average response time", "duration_avg_ts", "line", 2, 2,
          options = Json.obj("format" -> "ms")),
        w("status-pie", "Status code distribution", "requests_by_status", "pie", 2, 2),
        w("status-classes", "Status classes over time", "requests_by_status_class_ts", "area", 4, 2,
          options = Json.obj("stacked" -> true))
      )
    ),
    Spec(
      "analytics-default-performance",
      "Performance",
      "Latency, overhead and response time distribution",
      Seq(
        w("latency-p", "Latency percentiles", "duration_percentiles_ts", "line", 4, 2,
          options = Json.obj("format" -> "ms")),
        w("avg-overhead", "Average overhead", "overhead_avg_ts", "line", 2, 2,
          options = Json.obj("format" -> "ms")),
        w("avg-duration", "Average response time", "duration_avg_ts", "line", 2, 2,
          options = Json.obj("format" -> "ms")),
        w("heatmap", "Latency × time heatmap", "response_time_heatmap", "heatmap", 4, 3)
      )
    ),
    Spec(
      "analytics-default-errors",
      "Errors",
      "Error rate, top error routes, status classes",
      Seq(
        w("err-rate", "Error rate over time", "error_rate_ts", "line", 4, 2,
          options = Json.obj("format" -> "percent")),
        w("err-total", "Total errors", "errors_total", "metric", 1, 1),
        w("top-err-routes", "Top error routes", "top_error_routes", "bar", 3, 2,
          params = Json.obj("top_n" -> 10)),
        w("status-classes", "Status classes over time", "requests_by_status_class_ts", "area", 4, 2,
          options = Json.obj("stacked" -> true))
      )
    ),
    Spec(
      "analytics-default-apis-routes",
      "APIs & Routes",
      "Top routes, top APIs, traffic per API and domain",
      Seq(
        w("top-routes", "Top routes", "requests_by_route", "bar", 2, 2,
          params = Json.obj("top_n" -> 10)),
        w("top-apis", "Top APIs", "requests_by_api", "bar", 2, 2,
          params = Json.obj("top_n" -> 10)),
        w("top-domains", "Top domains", "requests_by_domain", "bar", 2, 2,
          params = Json.obj("top_n" -> 10)),
        w("traffic-io", "Data in / out over time", "traffic_in_out_ts", "area", 2, 2,
          options = Json.obj("format" -> "bytes"))
      )
    ),
    Spec(
      "analytics-default-consumers",
      "Consumers",
      "API key usage, users, geo and HTTP methods",
      Seq(
        w("top-apikeys", "Top API keys", "requests_by_apikey", "bar", 2, 2,
          params = Json.obj("top_n" -> 10)),
        w("top-users", "Top users", "requests_by_user", "bar", 2, 2,
          params = Json.obj("top_n" -> 10)),
        w("top-countries", "Top countries", "requests_by_country", "bar", 2, 2,
          params = Json.obj("top_n" -> 10)),
        w("methods-pie", "Requests by method", "requests_by_method", "pie", 2, 2)
      )
    )
  )

  // ---------------------------------------------------------------------------
  // Build & seed
  // ---------------------------------------------------------------------------

  private def buildDashboard(spec: Spec)(implicit env: Env): UserDashboard = UserDashboard(
    location = EntityLocation.default,
    id = IdGenerator.namedId("dashboard", env),
    name = spec.name,
    description = spec.description,
    tags = Seq("user-analytics", "default"),
    metadata = Map(
      DefaultIdMetaKey      -> spec.defaultId,
      DefaultVersionMetaKey -> DefaultVersion
    ),
    enabled = true,
    widgets = spec.widgets
  )

  /**
   * Creates the default dashboards that don't exist yet (matching by metadata
   * `otoroshi-default-id`). Existing dashboards (default or user-customised) are
   * never overwritten.
   * Returns the list of `defaultId`s that were created.
   */
  def seedIfMissing()(implicit env: Env, ec: ExecutionContext): Future[Seq[String]] = {
    env.datastores.userDashboardDataStore.findAll().flatMap { existing =>
      val present  = existing.flatMap(_.metadata.get(DefaultIdMetaKey)).toSet
      val missing  = specs.filterNot(s => present.contains(s.defaultId))
      if (missing.isEmpty) {
        logger.info(s"[user-analytics-defaults] all ${specs.size} default dashboards already present")
        Future.successful(Seq.empty)
      } else {
        logger.info(s"[user-analytics-defaults] seeding ${missing.size} missing default dashboard(s)")
        Future
          .sequence(missing.map { s =>
            val d = buildDashboard(s)
            env.datastores.userDashboardDataStore.set(d).map(_ => s.defaultId)
          })
      }
    }
  }

  /**
   * Same as `seedIfMissing` — re-installs only the defaults that are no longer
   * present. Existing user dashboards are never touched.
   */
  def restoreAll()(implicit env: Env, ec: ExecutionContext): Future[Seq[String]] = seedIfMissing()
}
