package functional

import io.vertx.sqlclient.{Tuple => VertxTuple}
import org.scalatest.OptionValues
import otoroshi.next.analytics.exporter.*
import play.api.libs.json.*

/**
 * Pure-logic tests for extensible analytics projections: no PG, no Env.
 *
 * The properties worth pinning are the ones a partial refactor would have broken — that the two
 * families the exporter has always stored still route exactly where they did, and that an extension
 * cannot take one of them over.
 */
class AnalyticsProjectionSpec
    extends org.scalatest.wordspec.AnyWordSpec
    with org.scalatest.matchers.must.Matchers
    with OptionValues {

  private val settings = UserAnalyticsExporterSettings()

  private def gatewayEvent  = Json.obj("@type" -> "GatewayEvent", "@id" -> "1")
  private def analyticsAlert =
    Json.obj("@type" -> "AlertEvent", "alertSubcategory" -> "user-analytics", "@id" -> "2")
  private def otherAlert    = Json.obj("@type" -> "AlertEvent", "alertSubcategory" -> "something-else")
  private def customEvent   = Json.obj("@type" -> "CloudApimSecurityEvent", "@id" -> "3")

  private class FakeProjection(
      override val id: String,
      typ: String,
      stripped: Boolean = false
  ) extends AnalyticsProjection {
    override def accepts(event: JsValue): Boolean                    = (event \ "@type").asOpt[String].contains(typ)
    override def table(s: UserAnalyticsExporterSettings): String     = s"${s.schema}.fake"
    override def createTableSql(s: UserAnalyticsExporterSettings)    = "CREATE TABLE IF NOT EXISTS fake ();"
    override def insertSql(s: UserAnalyticsExporterSettings): String = "INSERT INTO fake VALUES ($1)"
    override def toTuple(event: JsValue): VertxTuple                 = VertxTuple.of(Json.stringify(event))
    override def strip(event: JsValue): JsValue                      =
      if (stripped) event.as[JsObject] - "bulky" else event
  }

  "Core projections" should {

    "route gateway events exactly where they were routed before" in {
      AnalyticsProjection.routeOf(AnalyticsProjection.core, gatewayEvent).value.id mustBe "otoroshi.gateway-events"
    }

    "route user-analytics alerts to the fired alerts table" in {
      AnalyticsProjection.routeOf(AnalyticsProjection.core, analyticsAlert).value.id mustBe "otoroshi.fired-alerts"
    }

    "not claim an alert of another subcategory" in {
      AnalyticsProjection.routeOf(AnalyticsProjection.core, otherAlert) mustBe None
    }

    "not claim an event nobody declared" in {
      AnalyticsProjection.routeOf(AnalyticsProjection.core, customEvent) mustBe None
    }

    "keep writing to the tables they always wrote to" in {
      GatewayEventProjection.table(settings) mustBe AnalyticsSchema.fullTable(settings)
      FiredAlertProjection.table(settings) mustBe AnalyticsSchema.firedAlertsTable(settings)
      GatewayEventProjection.insertSql(settings) mustBe EventDenormalizer.insertSql(settings)
      FiredAlertProjection.insertSql(settings) mustBe FiredAlertDenormalizer.insertSql(settings)
    }

    "strip gateway events and leave alerts alone, as before" in {
      val event = Json.obj("@type" -> "GatewayEvent", "headers" -> Json.arr("a"))
      (GatewayEventProjection.strip(event) \ "headers").asOpt[JsValue] mustBe None
      FiredAlertProjection.strip(analyticsAlert) mustBe analyticsAlert
    }

    "prune both tables, the alert log included" in {
      // it was created, written, read and updated, and never deleted from — one retention setting
      // covers the exporter, so it has to cover both of its tables
      GatewayEventProjection.retention mustBe true
      FiredAlertProjection.retention mustBe true
      FiredAlertProjection.retentionColumn mustBe "ts"
    }

    "index the alert log on the column the purge filters by" in {
      AnalyticsSchema
        .firedAlertsIndexStatements(settings)
        .exists(ddl => ddl.contains("(ts DESC)") && !ddl.contains("alert_id") && !ddl.contains("tenant")) mustBe true
    }
  }

  "Resolution" should {

    "put an extension's projection after the core ones" in {
      val extra    = new FakeProjection("ext.custom", "CloudApimSecurityEvent")
      val resolved = AnalyticsProjection.resolve(Seq(extra))
      resolved.map(_.id) mustBe Seq("otoroshi.gateway-events", "otoroshi.fired-alerts", "ext.custom")
      AnalyticsProjection.routeOf(resolved, customEvent).value.id mustBe "ext.custom"
    }

    "keep routing core families to core, whatever an extension claims" in {
      // an extension whose `accepts` is broader than its author realised must not capture the
      // platform's own events and write them with the wrong denormaliser
      val greedy   = new FakeProjection("ext.greedy", "GatewayEvent")
      val resolved = AnalyticsProjection.resolve(Seq(greedy))
      AnalyticsProjection.routeOf(resolved, gatewayEvent).value.id mustBe "otoroshi.gateway-events"
    }

    "refuse to let an extension reuse a core id" in {
      val impostor = new FakeProjection("otoroshi.gateway-events", "CloudApimSecurityEvent")
      val resolved = AnalyticsProjection.resolve(Seq(impostor))
      resolved.map(_.id) mustBe Seq("otoroshi.gateway-events", "otoroshi.fired-alerts")
      AnalyticsProjection.routeOf(resolved, customEvent) mustBe None
    }

    "resolve to exactly the core list when no extension declares anything" in {
      AnalyticsProjection.resolve(Seq.empty).map(_.id) mustBe AnalyticsProjection.core.map(_.id)
    }
  }

  "A projection" should {

    "have its strip applied by whoever inserts, not by its toTuple" in {
      val projection = new FakeProjection("ext.stripping", "CloudApimSecurityEvent", stripped = true)
      val event      = Json.obj("@type" -> "CloudApimSecurityEvent", "bulky" -> "x", "keep" -> 1)
      val pruned     = projection.strip(event)
      (pruned \ "bulky").asOpt[JsValue] mustBe None
      (pruned \ "keep").as[Int] mustBe 1
    }

    "keep the event whole by default" in {
      val projection = new FakeProjection("ext.plain", "CloudApimSecurityEvent")
      projection.strip(customEvent) mustBe customEvent
    }

    "prune on ts unless it says otherwise" in {
      new FakeProjection("ext.plain", "x").retentionColumn mustBe "ts"
    }
  }

  "Excluding projections" should {

    "capture everything when nothing is excluded" in {
      AnalyticsProjection.capturedRouteOf(AnalyticsProjection.core, settings, gatewayEvent).value.id mustBe
      "otoroshi.gateway-events"
    }

    "drop the events of an excluded projection" in {
      val s = settings.copy(excludedProjections = Seq("otoroshi.fired-alerts"))
      AnalyticsProjection.capturedRouteOf(AnalyticsProjection.core, s, analyticsAlert) mustBe None
      AnalyticsProjection.capturedRouteOf(AnalyticsProjection.core, s, gatewayEvent).value.id mustBe
      "otoroshi.gateway-events"
    }

    "not hand an excluded family over to the next projection that accepts it" in {
      // filtering the list before routing would let a greedy extension write gateway events with
      // its own denormaliser as soon as the core projection is excluded
      val greedy   = new FakeProjection("ext.greedy", "GatewayEvent")
      val resolved = AnalyticsProjection.resolve(Seq(greedy))
      val s        = settings.copy(excludedProjections = Seq("otoroshi.gateway-events"))
      AnalyticsProjection.capturedRouteOf(resolved, s, gatewayEvent) mustBe None
    }

    "exclude an extension's projection like a core one" in {
      val extra    = new FakeProjection("ext.custom", "CloudApimSecurityEvent")
      val resolved = AnalyticsProjection.resolve(Seq(extra))
      val s        = settings.copy(excludedProjections = Seq("ext.custom"))
      AnalyticsProjection.capturedRouteOf(resolved, s, customEvent) mustBe None
      s.captures(GatewayEventProjection) mustBe true
    }

    "give every core projection a name to list it under" in {
      AnalyticsProjection.core.foreach { p =>
        p.name must not be p.id
        p.description must not be empty
      }
    }

    "fall back to the id for a projection that declares no name" in {
      new FakeProjection("ext.plain", "x").name mustBe "ext.plain"
    }
  }

  "Exporter settings" should {

    "read and write the excluded projections" in {
      val json   = Json.obj("excluded_projections" -> Json.arr("otoroshi.fired-alerts", "ext.custom"))
      val parsed = UserAnalyticsExporterSettings.format.reads(json).get
      parsed.excludedProjections mustBe Seq("otoroshi.fired-alerts", "ext.custom")
      (UserAnalyticsExporterSettings.format.writes(parsed) \ "excluded_projections").as[Seq[String]] mustBe
      Seq("otoroshi.fired-alerts", "ext.custom")
    }

    "exclude nothing when the field is absent" in {
      UserAnalyticsExporterSettings.format.reads(Json.obj()).get.excludedProjections mustBe Seq.empty
    }

    "read a config saved before the rename from its `table` field" in {
      val parsed = UserAnalyticsExporterSettings.format.reads(Json.obj("table" -> "my_events")).get
      parsed.tablePrefix mustBe "my_events"
    }

    "prefer `table_prefix` over the legacy `table`" in {
      // the UI edits `table_prefix` and posts back the stale `table` it was given
      val json = Json.obj("table" -> "old_events", "table_prefix" -> "new_events")
      UserAnalyticsExporterSettings.format.reads(json).get.tablePrefix mustBe "new_events"
    }

    "keep the default table names" in {
      val parsed = UserAnalyticsExporterSettings.format.reads(Json.obj()).get
      AnalyticsSchema.fullTable(parsed) mustBe "public.otoroshi_analytics_events"
      AnalyticsSchema.firedAlertsTable(parsed) mustBe "public.otoroshi_analytics_events_fired_alerts"
    }

    "still write `table` for nodes that predate the rename" in {
      val json = UserAnalyticsExporterSettings.format.writes(settings.copy(tablePrefix = "my_events"))
      (json \ "table_prefix").as[String] mustBe "my_events"
      (json \ "table").as[String] mustBe "my_events"
    }

    "derive extension tables from the prefix the way extensions always have" in {
      val s = settings.copy(schema = "analytics", tablePrefix = "my_events")
      // what the llm and waf extensions compute today, through the deprecated accessor
      @annotation.nowarn
      val legacy = s"${s.schema}.${s.table}_cloudapim_security"
      s.prefixedTable("cloudapim_security") mustBe "analytics.my_events_cloudapim_security"
      s.prefixedTable("cloudapim_security") mustBe legacy
    }
  }

  "The shared column contract" should {

    "name the columns the console's filters are written against" in {
      // FilterSql.whereClause emits predicates against every one of these; a table missing one
      // does not filter badly, it fails on a column that is not there
      Seq("id", "ts", "tenant", "teams", "route_id", "route_name", "api_id", "group_ids", "apikey_id",
        "user_email", "from_ip")
        .foreach(col => AnalyticsProjection.commonColumns must include(col))
    }

    "produce indexes namespaced by the given prefix" in {
      val indexes = AnalyticsProjection.commonIndexes("public.my_table", "my_table")
      indexes.size mustBe 6
      indexes.foreach(_ must include("public.my_table"))
      indexes.head must include("idx_my_table_ts")
    }
  }
}
