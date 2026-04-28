package functional

import org.scalatest.{MustMatchers, OptionValues, WordSpec}
import otoroshi.next.analytics.alerts.AlertEvaluator
import otoroshi.next.analytics.exporter.{EventDenormalizer, EventStripper}
import otoroshi.next.analytics.models.{AlertCondition, UserAlert, UserDashboard, Widget}
import otoroshi.next.analytics.queries._
import play.api.libs.json._

import java.time.Instant

/**
 * Pure-logic tests for the user-analytics feature: no PG, no Env, no
 * Otoroshi instance required. Covers:
 *   - EventStripper / EventDenormalizer
 *   - Filters parsing & JSON deserialization
 *   - Bucketing rules + SQL fragments
 *   - FilterSql clause + parameter binding (incl. tenant injection)
 *   - AlertEvaluator reducers and operator comparison
 *   - JSON round-trips for entity case classes
 */
class UserAnalyticsSpec extends WordSpec with MustMatchers with OptionValues {

  // ---------------------------------------------------------------------------
  // Test fixtures
  // ---------------------------------------------------------------------------

  private val sampleEvent: JsObject = Json.parse(
    """
      |{
      |  "@id": "event_42",
      |  "@timestamp": 1775637516194,
      |  "@type": "GatewayEvent",
      |  "@env": "prod",
      |  "@service": "MyApp",
      |  "@serviceId": "route_xxx",
      |  "@product": "--",
      |  "method": "GET",
      |  "status": 502,
      |  "url": "https://example.com/path",
      |  "protocol": "HTTP/1.1",
      |  "duration": 121,
      |  "overhead": 20,
      |  "backendDuration": 0,
      |  "err": true,
      |  "from": "1.2.3.4",
      |  "data": { "dataIn": 100, "dataOut": 200 },
      |  "to":   { "uri": "/", "host": "api.example.com", "scheme": "https" },
      |  "target": { "uri": "/", "host": "backend.local", "scheme": "https" },
      |  "headers": [ { "key": "X-A", "value": "1" } ],
      |  "headersOut": [ { "key": "X-B", "value": "2" } ],
      |  "otoroshiHeadersIn":  [ { "key": "X-C", "value": "3" } ],
      |  "otoroshiHeadersOut": [ { "key": "X-D", "value": "4" } ],
      |  "clientCertChain": [],
      |  "identity": {
      |    "identityType": "APIKEY",
      |    "identity": "apikey_42",
      |    "label": "demo apikey"
      |  },
      |  "geolocationInfo": { "country": "FR" },
      |  "userAgentInfo":   { "ua": "curl/8.0" },
      |  "route": {
      |    "id":   "route_xxx",
      |    "name": "MyRoute",
      |    "_loc": { "tenant": "acme", "teams": ["red", "blue"] },
      |    "groups": ["g1"],
      |    "metadata": { "Otoroshi-Api-Ref": "api_42", "extra": "x" },
      |    "tags": ["t1"],
      |    "description": "desc",
      |    "frontend": { "domains": ["example.com"], "exact": false },
      |    "backend":  { "targets": [{ "host": "h", "port": 1 }] },
      |    "plugins":  [{ "plugin": "cp:foo" }]
      |  }
      |}
      |""".stripMargin
  ).as[JsObject]

  // ---------------------------------------------------------------------------
  // EventStripper
  // ---------------------------------------------------------------------------

  "EventStripper" should {
    val stripped = EventStripper.stripGatewayEvent(sampleEvent).as[JsObject]

    "drop bulky top-level fields" in {
      (stripped \ "headers").toOption mustBe None
      (stripped \ "headersOut").toOption mustBe None
      (stripped \ "otoroshiHeadersIn").toOption mustBe None
      (stripped \ "otoroshiHeadersOut").toOption mustBe None
      (stripped \ "clientCertChain").toOption mustBe None
    }
    "drop route.backend and route.plugins" in {
      (stripped \ "route" \ "backend").toOption mustBe None
      (stripped \ "route" \ "plugins").toOption mustBe None
    }
    "keep route core identity, metadata and frontend.domains" in {
      (stripped \ "route" \ "id").as[String] mustBe "route_xxx"
      (stripped \ "route" \ "name").as[String] mustBe "MyRoute"
      (stripped \ "route" \ "metadata" \ "Otoroshi-Api-Ref").as[String] mustBe "api_42"
      (stripped \ "route" \ "frontend" \ "domains").as[Seq[String]] mustBe Seq("example.com")
      (stripped \ "route" \ "_loc" \ "tenant").as[String] mustBe "acme"
      (stripped \ "route" \ "groups").as[Seq[String]] mustBe Seq("g1")
    }
    "keep timings, status, identity, geo and ua" in {
      (stripped \ "duration").as[Int] mustBe 121
      (stripped \ "status").as[Int] mustBe 502
      (stripped \ "err").as[Boolean] mustBe true
      (stripped \ "identity" \ "identity").as[String] mustBe "apikey_42"
      (stripped \ "geolocationInfo" \ "country").as[String] mustBe "FR"
      (stripped \ "userAgentInfo" \ "ua").as[String] mustBe "curl/8.0"
    }
  }

  // ---------------------------------------------------------------------------
  // EventDenormalizer
  // ---------------------------------------------------------------------------

  "EventDenormalizer" should {
    val stripped = EventStripper.stripGatewayEvent(sampleEvent)
    val row      = EventDenormalizer.extractColumns(stripped)

    "extract identifiers" in {
      row.id mustBe "event_42"
      row.routeId.value mustBe "route_xxx"
      row.routeName.value mustBe "MyRoute"
      row.apiId.value mustBe "api_42"
      row.tenant mustBe "acme"
      row.teams.toSeq mustBe Seq("red", "blue")
      row.groupIds.toSeq mustBe Seq("g1")
    }
    "split identity by type" in {
      row.identityType.value mustBe "APIKEY"
      row.apikeyId.value mustBe "apikey_42"
      row.userEmail mustBe None
    }
    "extract status, error, timings, traffic" in {
      row.status.value mustBe 502.toShort
      row.err mustBe true
      row.durationMs.value mustBe 121
      row.overheadMs.value mustBe 20
      row.dataIn.value mustBe 100L
      row.dataOut.value mustBe 200L
      row.country.value mustBe "FR"
      row.userAgent.value mustBe "curl/8.0"
      row.method.value mustBe "GET"
      row.domain.value mustBe "api.example.com"
    }

    "fall back to user_email when identityType=PRIVATEAPP" in {
      val ev    = sampleEvent ++ Json.obj(
        "identity" -> Json.obj(
          "identityType" -> "PRIVATEAPP",
          "identity"     -> "alice@example.com",
          "label"        -> "alice"
        )
      )
      val s     = EventStripper.stripGatewayEvent(ev)
      val srow  = EventDenormalizer.extractColumns(s)
      srow.userEmail.value mustBe "alice@example.com"
      srow.apikeyId mustBe None
    }
  }

  // ---------------------------------------------------------------------------
  // Filters parsing
  // ---------------------------------------------------------------------------

  "Filters.parseTime" should {
    "return Now-ish when input is 'now'" in {
      val n = Filters.parseTime("now", Instant.EPOCH)
      math.abs(n.toEpochMilli - System.currentTimeMillis()) must be < 5000L
    }
    "subtract relative durations" in {
      val before    = Instant.now()
      val nowMinus1 = Filters.parseTime("now-1h", Instant.EPOCH).toEpochMilli
      val expected  = before.minusSeconds(3600).toEpochMilli
      math.abs(nowMinus1 - expected) must be < 5000L
    }
    "support seconds, minutes, hours, days" in {
      val base = System.currentTimeMillis()
      val s30  = Filters.parseTime("now-30s", Instant.EPOCH).toEpochMilli
      val m5   = Filters.parseTime("now-5m", Instant.EPOCH).toEpochMilli
      val h2   = Filters.parseTime("now-2h", Instant.EPOCH).toEpochMilli
      val d7   = Filters.parseTime("now-7d", Instant.EPOCH).toEpochMilli
      math.abs(s30 - (base - 30L * 1000)) must be < 5000L
      math.abs(m5 - (base - 5L * 60 * 1000)) must be < 5000L
      math.abs(h2 - (base - 2L * 3600 * 1000)) must be < 5000L
      math.abs(d7 - (base - 7L * 86400 * 1000)) must be < 5000L
    }
    "parse ISO 8601" in {
      val ts = Filters.parseTime("2026-01-15T10:00:00Z", Instant.EPOCH)
      ts.toEpochMilli must be > 1735000000000L
    }
    "return fallback on garbage input" in {
      val fb = Instant.ofEpochMilli(123456789L)
      Filters.parseTime("nope", fb) mustBe fb
    }
  }

  "Filters.fromJson" should {
    "deserialize all fields" in {
      val js = Json.obj(
        "from"      -> "now-2h",
        "to"        -> "now",
        "route_id"  -> "route_x",
        "api_id"    -> "api_x",
        "apikey_id" -> "apikey_x",
        "group_id"  -> "group_x",
        "err"       -> true
      )
      val f = Filters.fromJson(js)
      f.routeId.value mustBe "route_x"
      f.apiId.value mustBe "api_x"
      f.apikeyId.value mustBe "apikey_x"
      f.groupId.value mustBe "group_x"
      f.err.value mustBe true
    }
    "default to last hour when from/to absent" in {
      val f = Filters.fromJson(Json.obj())
      val rangeSeconds =
        java.time.Duration.between(f.from, f.to).getSeconds
      rangeSeconds must (be >= 3500L and be <= 3700L)
    }
    "ignore empty string entity ids" in {
      val f = Filters.fromJson(Json.obj("route_id" -> "", "api_id" -> ""))
      f.routeId mustBe None
      f.apiId mustBe None
    }
  }

  // ---------------------------------------------------------------------------
  // Bucketing
  // ---------------------------------------------------------------------------

  "Bucketing.autoBucket" should {
    val now = Instant.now()
    "pick 1m for ranges <= 1h" in {
      Bucketing.autoBucket(now.minusSeconds(1800), now) mustBe Bucket.OneMinute
      Bucketing.autoBucket(now.minusSeconds(3600), now) mustBe Bucket.OneMinute
    }
    "pick 1h for ranges (1h, 24h]" in {
      Bucketing.autoBucket(now.minusSeconds(3601), now) mustBe Bucket.OneHour
      Bucketing.autoBucket(now.minusSeconds(86400), now) mustBe Bucket.OneHour
    }
    "pick 6h for ranges (24h, 7d]" in {
      Bucketing.autoBucket(now.minusSeconds(86401), now) mustBe Bucket.SixHour
      Bucketing.autoBucket(now.minusSeconds(7L * 86400), now) mustBe Bucket.SixHour
    }
    "pick 1d for ranges > 7d" in {
      Bucketing.autoBucket(now.minusSeconds(8L * 86400), now) mustBe Bucket.OneDay
      Bucketing.autoBucket(now.minusSeconds(60L * 86400), now) mustBe Bucket.OneDay
    }
  }

  "Bucket SQL helpers" should {
    "produce a floor-based truncation" in {
      Bucket.OneMinute.truncSql() must include("extract(epoch from ts)")
      Bucket.OneMinute.truncSql() must include("/ 60")
      Bucket.SixHour.truncSql() must include("/ 21600")
    }
    "produce an interval matching the bucket size" in {
      Bucket.OneMinute.intervalLiteral mustBe "INTERVAL '60 seconds'"
      Bucket.OneHour.intervalLiteral mustBe "INTERVAL '3600 seconds'"
    }
    "produce an aligned series CTE" in {
      val sql = Bucket.OneMinute.seriesSql("$1::timestamptz", "$2::timestamptz")
      sql must startWith("SELECT generate_series(")
      sql must include("INTERVAL '60 seconds'")
    }
  }

  // ---------------------------------------------------------------------------
  // FilterSql
  // ---------------------------------------------------------------------------

  "FilterSql.whereClause" should {
    "always include time bounds" in {
      val now           = Instant.now()
      val (where, vals) = FilterSql.whereClause(Filters(now.minusSeconds(60), now))
      where must startWith(" WHERE ")
      where must include("ts >= $1")
      where must include("ts < $2")
      vals.size mustBe 2
    }
    "inject the tenant when set" in {
      val now = Instant.now()
      val f   = Filters(now.minusSeconds(60), now, tenant = Some("acme"))
      val (where, vals) = FilterSql.whereClause(f)
      where must include("tenant = $3")
      vals(2) mustBe "acme"
    }
    "inject group filter via ANY(group_ids)" in {
      val now = Instant.now()
      val f   = Filters(now.minusSeconds(60), now, groupId = Some("g1"))
      val (where, _) = FilterSql.whereClause(f)
      where must include("$3 = ANY(group_ids)")
    }
    "return placeholders in increasing order" in {
      val now = Instant.now()
      val f   = Filters(
        now.minusSeconds(60),
        now,
        routeId = Some("r"),
        apikeyId = Some("k"),
        tenant = Some("t")
      )
      val (where, vals) = FilterSql.whereClause(f)
      where must include("$1")
      where must include("$2")
      where must include("$3")
      where must include("$4")
      where must include("$5")
      vals.size mustBe 5
    }
  }

  // ---------------------------------------------------------------------------
  // AlertEvaluator reducers + operators
  // ---------------------------------------------------------------------------

  "AlertEvaluator.compare" should {
    "honour every supported operator" in {
      AlertEvaluator.compare(10, ">", 5) mustBe true
      AlertEvaluator.compare(5, ">", 5) mustBe false
      AlertEvaluator.compare(5, ">=", 5) mustBe true
      AlertEvaluator.compare(4, "<", 5) mustBe true
      AlertEvaluator.compare(5, "<=", 5) mustBe true
      AlertEvaluator.compare(5, "==", 5) mustBe true
      AlertEvaluator.compare(5, "!=", 6) mustBe true
      AlertEvaluator.compare(5, "wat", 5) mustBe false
    }
  }

  "AlertEvaluator.reduce" should {
    "extract value from a Scalar shape" in {
      val r = QueryResult(AnalyticsShape.Scalar, Json.obj("value" -> 42, "label" -> "Total"))
      AlertEvaluator.reduce(r, "max").value mustBe 42d
    }

    "reduce a Timeseries with single-series points" in {
      val r = QueryResult(
        AnalyticsShape.Timeseries,
        Json.obj(
          "bucket" -> "1m",
          "points" -> Json.arr(
            Json.obj("ts" -> 1L, "value" -> 1d),
            Json.obj("ts" -> 2L, "value" -> 5d),
            Json.obj("ts" -> 3L, "value" -> 4d)
          )
        )
      )
      AlertEvaluator.reduce(r, "max").value mustBe 5d
      AlertEvaluator.reduce(r, "min").value mustBe 1d
      AlertEvaluator.reduce(r, "sum").value mustBe 10d
      AlertEvaluator.reduce(r, "avg").value mustBe (10d / 3d) +- 0.0001
      AlertEvaluator.reduce(r, "last").value mustBe 4d
    }

    "reduce a Timeseries with multi-series (uses first series)" in {
      val r = QueryResult(
        AnalyticsShape.Timeseries,
        Json.obj(
          "bucket" -> "1m",
          "series" -> Json.arr(
            Json.obj(
              "name"   -> "p50",
              "points" -> Json.arr(Json.obj("ts" -> 1L, "value" -> 100d), Json.obj("ts" -> 2L, "value" -> 200d))
            ),
            Json.obj(
              "name"   -> "p95",
              "points" -> Json.arr(Json.obj("ts" -> 1L, "value" -> 999d), Json.obj("ts" -> 2L, "value" -> 999d))
            )
          )
        )
      )
      AlertEvaluator.reduce(r, "max").value mustBe 200d
    }

    "reduce a TopN/Pie shape" in {
      val r = QueryResult(
        AnalyticsShape.TopN,
        Json.obj(
          "items" -> Json.arr(
            Json.obj("key" -> "a", "value" -> 30d),
            Json.obj("key" -> "b", "value" -> 70d)
          )
        )
      )
      AlertEvaluator.reduce(r, "sum").value mustBe 100d
      AlertEvaluator.reduce(r, "max").value mustBe 70d
    }

    "return None on empty data" in {
      val empty = QueryResult(AnalyticsShape.Timeseries, Json.obj("points" -> Json.arr()))
      AlertEvaluator.reduce(empty, "max") mustBe None
    }

    "return None on unsupported shapes" in {
      val r = QueryResult(AnalyticsShape.Heatmap, Json.obj("xBuckets" -> Json.arr()))
      AlertEvaluator.reduce(r, "max") mustBe None
    }
  }

  // ---------------------------------------------------------------------------
  // JSON round-trips
  // ---------------------------------------------------------------------------

  "AlertCondition JSON" should {
    "round-trip" in {
      val c    = AlertCondition(
        query = "duration_avg_ts",
        params = Json.obj("top_n" -> 5),
        filters = Json.obj("route_id" -> "r1"),
        reducer = "max",
        operator = ">",
        threshold = 500.0
      )
      val js   = AlertCondition.format.writes(c)
      val back = AlertCondition.format.reads(js).asOpt.value
      back mustBe c
    }
  }

  "Widget JSON" should {
    "round-trip" in {
      val w    = Widget(
        id = "w1",
        title = "RPS",
        query = "requests_per_second",
        params = Json.obj(),
        `type` = "line",
        width = 4,
        height = 2,
        options = Json.obj("format" -> "rps")
      )
      val js   = Widget.format.writes(w)
      val back = Widget.format.reads(js).asOpt.value
      back mustBe w
    }
  }

  "UserAlert JSON" should {
    "round-trip with conditions" in {
      val original = """
                       |{
                       |  "_loc": { "tenant": "acme", "teams": ["red"] },
                       |  "id": "alert_x",
                       |  "name": "n",
                       |  "description": "d",
                       |  "tags": [],
                       |  "metadata": {},
                       |  "enabled": true,
                       |  "windowSeconds": 300,
                       |  "evaluationIntervalSeconds": 60,
                       |  "cooldownSeconds": 600,
                       |  "severity": "warning",
                       |  "message": "m",
                       |  "combine": "AND",
                       |  "conditions": [
                       |    { "query": "error_rate_ts", "reducer": "max", "operator": ">", "threshold": 0.05 }
                       |  ]
                       |}
                       |""".stripMargin
      val parsed   = UserAlert.format.reads(Json.parse(original)).asOpt.value
      parsed.id mustBe "alert_x"
      parsed.severity mustBe "warning"
      parsed.conditions.size mustBe 1
      val again    = UserAlert.format.reads(parsed.json).asOpt.value
      again mustBe parsed
    }
  }

  "UserDashboard JSON" should {
    "round-trip with widgets" in {
      val original = """
                       |{
                       |  "_loc": { "tenant": "default", "teams": ["default"] },
                       |  "id": "dashboard_x",
                       |  "name": "N",
                       |  "description": "D",
                       |  "tags": [],
                       |  "metadata": {},
                       |  "enabled": true,
                       |  "widgets": [
                       |    { "id": "w1", "title": "T", "query": "requests_total", "type": "metric",
                       |      "width": 1, "height": 1, "params": {}, "options": {} }
                       |  ]
                       |}
                       |""".stripMargin
      val parsed   = UserDashboard.format.reads(Json.parse(original)).asOpt.value
      parsed.widgets.size mustBe 1
      val again    = UserDashboard.format.reads(parsed.json).asOpt.value
      again mustBe parsed
    }
  }
}
