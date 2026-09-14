package otoroshi.next.analytics.exporter

import io.vertx.sqlclient.{Tuple => VertxTuple}
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*

/**
 * How one family of events becomes rows in the user-analytics database.
 *
 * The exporter used to know about exactly two families, in four hardcoded places: which events it
 * accepted, how it partitioned a batch, how each half was denormalised, and which table each half
 * was inserted into. An extension emitting its own events therefore had nowhere to put them — and
 * making only the first of those four extensible would have been worse than leaving it alone, since
 * an unrecognised event reaching the partition falls into the alert branch and gets written with
 * the wrong denormaliser.
 *
 * A projection owns all four, plus its own DDL, so adding a family is adding one object.
 *
 * `strip` is applied by the exporter, never by the caller: an event that is cheap to emit is not
 * necessarily cheap to store, and forgetting to prune before insert is the kind of thing that is
 * only noticed once the table is large.
 */
trait AnalyticsProjection {

  /** Stable identifier, namespaced by whoever owns it. Used in logs, to deduplicate and to exclude. */
  def id: String

  /** Human readable name, shown in the exporter config where projections can be excluded. */
  def name: String = id

  /** What these events are and what stops working without them, shown next to [[name]]. */
  def description: String = ""

  /** Whether this projection is the one that stores that event. */
  def accepts(event: JsValue): Boolean

  /** Fully qualified table name, schema included. */
  def table(settings: UserAnalyticsExporterSettings): String

  def createTableSql(settings: UserAnalyticsExporterSettings): String

  def indexStatements(settings: UserAnalyticsExporterSettings): Seq[String] = Seq.empty

  /** Parameterised insert, matching the arity of [[toTuple]]. */
  def insertSql(settings: UserAnalyticsExporterSettings): String

  /**
   * Pruning applied automatically before the row is built.
   *
   * The default keeps the event whole, which is the right choice only for events that are already
   * small. Anything carrying headers, bodies, certificate chains or a signal trail should drop them
   * here rather than in the denormaliser, so the untyped `raw` column stays a payload and not an
   * archive.
   */
  def strip(event: JsValue): JsValue = event

  def toTuple(event: JsValue): VertxTuple

  /** The timestamp column the retention job prunes on. */
  def retentionColumn: String = "ts"

  /** Set false for a table that manages its own lifecycle. */
  def retention: Boolean = true
}

object AnalyticsProjection {

  /**
   * The columns a projection must open with.
   *
   * This is not style advice. `FilterSql.whereClause` — the clause every generic query, dashboard
   * widget and alert condition is built on — emits predicates against these exact names, `api_id`
   * and `group_ids` included. A table missing one of them does not filter badly, it fails: the
   * query references a column that is not there.
   *
   * Declare them and the whole query, bucketing and dashboard machinery works on the table for
   * free. A projection with no notion of, say, an api simply leaves that column null.
   */
  val commonColumns: String =
    """  id              TEXT        PRIMARY KEY,
      |  ts              TIMESTAMPTZ NOT NULL,
      |  env             TEXT,
      |  tenant          TEXT        NOT NULL DEFAULT 'default',
      |  teams           TEXT[]      NOT NULL DEFAULT '{}',
      |  route_id        TEXT,
      |  route_name      TEXT,
      |  api_id          TEXT,
      |  group_ids       TEXT[]      NOT NULL DEFAULT '{}',
      |  apikey_id       TEXT,
      |  user_email      TEXT,
      |  from_ip         TEXT,""".stripMargin

  /** The indexes that make the common filters usable. `prefix` must be unique per table. */
  def commonIndexes(table: String, prefix: String): Seq[String] = Seq(
    s"CREATE INDEX IF NOT EXISTS idx_${prefix}_ts        ON $table (ts DESC);",
    s"CREATE INDEX IF NOT EXISTS idx_${prefix}_route_ts  ON $table (route_id, ts DESC);",
    s"CREATE INDEX IF NOT EXISTS idx_${prefix}_tenant_ts ON $table (tenant, ts DESC);",
    s"CREATE INDEX IF NOT EXISTS idx_${prefix}_api_ts    ON $table (api_id, ts DESC) WHERE api_id IS NOT NULL;",
    s"CREATE INDEX IF NOT EXISTS idx_${prefix}_apikey_ts ON $table (apikey_id, ts DESC) WHERE apikey_id IS NOT NULL;",
    s"CREATE INDEX IF NOT EXISTS idx_${prefix}_groups_gin ON $table USING GIN (group_ids);"
  )

  /**
   * What the exporter has always stored, expressed as projections.
   *
   * They delegate to the existing stripper, denormalisers and schema rather than restating them, so
   * this refactor cannot change what lands in the two tables that already exist.
   */
  val core: Seq[AnalyticsProjection] = Seq(GatewayEventProjection, FiredAlertProjection)

  /**
   * Core projections first, then the extensions', minus any that reuse a core id.
   *
   * The ordering is the safety property: [[routeOf]] takes the first match, so an extension cannot
   * take over a family the platform already owns — deliberately or by writing an `accepts` that is
   * broader than its author realised.
   */
  def resolve(extras: Seq[AnalyticsProjection], core: Seq[AnalyticsProjection] = core): Seq[AnalyticsProjection] =
    core ++ extras.filterNot(p => core.exists(_.id == p.id))

  /** Core projections plus every installed extension's, in routing order. */
  def installed(env: Env): Seq[AnalyticsProjection] = resolve(
    try env.adminExtensions.analyticsProjections()
    catch { case _: Throwable => Seq.empty[AnalyticsProjection] }
  )

  def routeOf(projections: Seq[AnalyticsProjection], event: JsValue): Option[AnalyticsProjection] =
    projections.find(_.accepts(event))

  /**
   * The projection that stores that event, if that projection is not excluded.
   *
   * Exclusion is applied after routing, never before: filtering the list first would hand an
   * excluded core family to the next projection that happens to accept it, and it would be written
   * with a denormaliser that was never meant for it.
   */
  def capturedRouteOf(
      projections: Seq[AnalyticsProjection],
      settings: UserAnalyticsExporterSettings,
      event: JsValue
  ): Option[AnalyticsProjection] =
    routeOf(projections, event).filter(settings.captures)
}

object GatewayEventProjection extends AnalyticsProjection {
  override val id                                                     = "otoroshi.gateway-events"
  override val name                                                   = "Gateway events"
  override val description                                            =
    "Every request proxied by Otoroshi. Feeds the core queries, the default dashboards and most alerts."
  override def accepts(event: JsValue): Boolean                       =
    event.select("@type").asOptString.contains("GatewayEvent")
  override def table(s: UserAnalyticsExporterSettings): String        = AnalyticsSchema.fullTable(s)
  override def createTableSql(s: UserAnalyticsExporterSettings)       = AnalyticsSchema.createTableSql(s)
  override def indexStatements(s: UserAnalyticsExporterSettings)      = AnalyticsSchema.indexStatements(s)
  override def insertSql(s: UserAnalyticsExporterSettings): String    = EventDenormalizer.insertSql(s)
  override def strip(event: JsValue): JsValue                         = EventStripper.stripGatewayEvent(event)
  override def toTuple(event: JsValue): VertxTuple                    =
    EventDenormalizer.toTuple(EventDenormalizer.extractColumns(event))
}

object FiredAlertProjection extends AnalyticsProjection {
  override val id                                                  = "otoroshi.fired-alerts"
  override val name                                                = "Fired user analytics alerts"
  override val description                                         =
    "Alerts raised by user analytics alert rules. Feeds the alert log; alert delivery is not affected."
  override def accepts(event: JsValue): Boolean                    =
    event.select("@type").asOptString.contains("AlertEvent") &&
      event.select("alertSubcategory").asOptString.contains("user-analytics")
  override def table(s: UserAnalyticsExporterSettings): String     = AnalyticsSchema.firedAlertsTable(s)
  override def createTableSql(s: UserAnalyticsExporterSettings)    = AnalyticsSchema.createFiredAlertsTableSql(s)
  override def indexStatements(s: UserAnalyticsExporterSettings)   = AnalyticsSchema.firedAlertsIndexStatements(s)
  override def insertSql(s: UserAnalyticsExporterSettings): String = FiredAlertDenormalizer.insertSql(s)
  override def toTuple(event: JsValue): VertxTuple                 = FiredAlertDenormalizer.toTuple(event)
  // the alert log was never pruned: it is created, written, read and updated, and nothing anywhere
  // deleted from it. one `retentionDays` covers both tables and the UI offers no nuance, so an
  // operator setting 30 days was getting 30 days on one table and forever on the other
  override def retention: Boolean                                  = true
}
