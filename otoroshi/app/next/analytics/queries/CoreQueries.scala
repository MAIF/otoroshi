package otoroshi.next.analytics.queries

import io.vertx.pgclient.PgPool
import io.vertx.sqlclient.{Row, RowSet, Tuple => VertxTuple}
import otoroshi.env.Env
import otoroshi.next.analytics.exporter.{AnalyticsSchema, UserAnalyticsExporterSettings}
import otoroshi.storage.drivers.reactivepg.pgimplicits._
import play.api.libs.json._

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

/** Shared helpers for core queries (parameter binding, row → JSON, etc.). */
object QueryHelpers {

  def tupleOf(values: Seq[AnyRef]): VertxTuple = VertxTuple.from(values.toArray)

  def runSelect(pool: PgPool, sql: String, values: Seq[AnyRef])(implicit
      ec: ExecutionContext
  ): Future[Seq[Row]] = {
    pool
      .preparedQuery(sql)
      .execute(tupleOf(values))
      .scala
      .map { rs: RowSet[Row] => rs.iterator().asScala.toList }
  }

  def jsTs(odt: java.time.OffsetDateTime): JsValue =
    if (odt == null) JsNull else JsNumber(odt.toInstant.toEpochMilli)

  def safeLong(row: Row, idx: Int): Long = {
    val v = row.getValue(idx)
    if (v == null) 0L
    else
      v match {
        case n: java.lang.Number => n.longValue()
        case _                   => 0L
      }
  }

  def safeDouble(row: Row, idx: Int): Double = {
    val v = row.getValue(idx)
    if (v == null) 0.0
    else
      v match {
        case n: java.lang.Number => n.doubleValue()
        case _                   => 0.0
      }
  }

  def optString(row: Row, idx: Int): Option[String] = {
    val v = row.getValue(idx)
    if (v == null) None else Some(v.toString)
  }
}

/** Aggregates all built-in queries. Extension queries are added by the
 *  registry on top of `all`.
 */
object CoreQueries {
  def all: Seq[AnalyticsQuery] =
    ScalarQueries.all ++ PieQueries.all ++ TopNQueries.all ++ TimeseriesQueries.all ++ HeatmapQueries.all
}

// ============================================================================
// Scalar queries
// ============================================================================

object ScalarQueries {

  def all: Seq[AnalyticsQuery] = Seq(RequestsTotal, ErrorsTotal)

  private def scalar(query: String, label: String, filtersExtra: String = "")(
      filters: Filters,
      settings: UserAnalyticsExporterSettings,
      pool: PgPool
  )(implicit ec: ExecutionContext, env: Env): Future[QueryResult] = {
    val (where, vals) = FilterSql.whereClause(filters)
    val whereWithExtra = if (filtersExtra.isEmpty) where else (if (where.isEmpty) " WHERE " + filtersExtra else where + " AND " + filtersExtra)
    val sql = s"SELECT $query AS value FROM ${AnalyticsSchema.fullTable(settings)}$whereWithExtra"
    QueryHelpers.runSelect(pool, sql, vals).map { rows =>
      val value = rows.headOption.map(r => QueryHelpers.safeLong(r, 0)).getOrElse(0L)
      QueryResult(
        AnalyticsShape.Scalar,
        Json.obj("value" -> value, "label" -> label),
        JsArray(rows.map(r => Json.obj("value" -> QueryHelpers.safeLong(r, 0))))
      )
    }
  }

  object RequestsTotal extends AnalyticsQuery {
    val id              = "requests_total"
    val name            = "Total requests"
    val description     = "Total number of gateway requests over the selected period."
    val shape           = AnalyticsShape.Scalar
    val defaultWidget   = "metric"
    override val supportsCompare = true
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: PgPool)(implicit
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = scalar("COUNT(*)", "Total requests")(f, s, pool)
  }

  object ErrorsTotal extends AnalyticsQuery {
    val id              = "errors_total"
    val name            = "Total errors"
    val description     = "Total number of errored requests over the selected period."
    val shape           = AnalyticsShape.Scalar
    val defaultWidget   = "metric"
    override val supportsCompare = true
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: PgPool)(implicit
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = scalar("COUNT(*)", "Total errors", "err = true")(f, s, pool)
  }
}

// ============================================================================
// Pie queries
// ============================================================================

object PieQueries {

  def all: Seq[AnalyticsQuery] = Seq(ByStatus, ByMethod)

  private def groupBy(field: String, label: String, castToText: Boolean = false)(
      filters: Filters,
      settings: UserAnalyticsExporterSettings,
      pool: PgPool
  )(implicit ec: ExecutionContext, env: Env): Future[QueryResult] = {
    val (where, vals) = FilterSql.whereClause(filters)
    val key           = if (castToText) s"$field::text" else field
    val sql =
      s"""SELECT $key AS key, COUNT(*) AS value
         |FROM ${AnalyticsSchema.fullTable(settings)}$where
         |GROUP BY 1 ORDER BY value DESC""".stripMargin
    QueryHelpers.runSelect(pool, sql, vals).map { rows =>
      val items = rows.map { r =>
        val key: String = QueryHelpers.optString(r, 0).getOrElse("(unknown)")
        Json.obj(
          "key"   -> key,
          "value" -> QueryHelpers.safeLong(r, 1)
        )
      }
      QueryResult(
        AnalyticsShape.Pie,
        Json.obj("label" -> label, "items" -> JsArray(items)),
        JsArray(items)
      )
    }
  }

  object ByStatus extends AnalyticsQuery {
    val id            = "requests_by_status"
    val name          = "Requests by status code"
    val description   = "Distribution of requests by HTTP status code."
    val shape         = AnalyticsShape.Pie
    val defaultWidget = "pie"
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: PgPool)(implicit
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = groupBy("status", "Status code", castToText = true)(f, s, pool)
  }

  object ByMethod extends AnalyticsQuery {
    val id            = "requests_by_method"
    val name          = "Requests by method"
    val description   = "Distribution of requests by HTTP method."
    val shape         = AnalyticsShape.Pie
    val defaultWidget = "pie"
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: PgPool)(implicit
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = groupBy("method", "Method")(f, s, pool)
  }
}

// ============================================================================
// TopN queries
// ============================================================================

object TopNQueries {

  def all: Seq[AnalyticsQuery] =
    Seq(ByRoute, ByApi, ByApikey, ByUser, ByDomain, ByCountry, TopErrorRoutes)

  private val TopNParam = QueryParam("top_n", "int", JsNumber(10), "Maximum number of items returned")

  private def topNQuery(
      keyField: String,
      labelField: Option[String],
      whereExtra: String = ""
  )(
      filters: Filters,
      params: JsObject,
      settings: UserAnalyticsExporterSettings,
      pool: PgPool
  )(implicit ec: ExecutionContext, env: Env): Future[QueryResult] = {
    val topN          = (params \ "top_n").asOpt[Int].getOrElse(10).max(1).min(1000)
    val (where, vals) = FilterSql.whereClause(filters)
    val whereFull = (where, whereExtra) match {
      case ("", "")      => ""
      case (w, "")       => w
      case ("", e)       => " WHERE " + e
      case (w, e)        => w + " AND " + e
    }
    val select = labelField match {
      case Some(lbl) => s"$keyField AS key, MAX($lbl) AS label, COUNT(*) AS value"
      case None      => s"$keyField AS key, COUNT(*) AS value"
    }
    val sql = s"""SELECT $select
                 |FROM ${AnalyticsSchema.fullTable(settings)}$whereFull
                 |GROUP BY $keyField
                 |ORDER BY value DESC
                 |LIMIT $topN""".stripMargin
    QueryHelpers.runSelect(pool, sql, vals).map { rows =>
      val items = rows.map { r =>
        val key: String   = QueryHelpers.optString(r, 0).getOrElse("(unknown)")
        val label: String = labelField.flatMap(_ => QueryHelpers.optString(r, 1)).getOrElse(key)
        val value         = QueryHelpers.safeLong(r, if (labelField.isDefined) 2 else 1)
        Json.obj("key" -> key, "label" -> label, "value" -> value)
      }
      QueryResult(AnalyticsShape.TopN, Json.obj("items" -> JsArray(items)), JsArray(items))
    }
  }

  abstract class TopN(field: String, labelField: Option[String], extra: String = "") extends AnalyticsQuery {
    val shape         = AnalyticsShape.TopN
    val defaultWidget = "bar"
    override val params: Seq[QueryParam] = Seq(TopNParam)
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: PgPool)(implicit
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = topNQuery(field, labelField, extra)(f, p, s, pool)
  }

  object ByRoute extends TopN("route_id", Some("route_name"), "route_id IS NOT NULL") {
    val id          = "requests_by_route"
    val name        = "Top routes"
    val description = "Top N routes by request count."
  }

  object ByApi extends TopN("api_id", None, "api_id IS NOT NULL") {
    val id          = "requests_by_api"
    val name        = "Top APIs"
    val description = "Top N APIs by request count."
  }

  object ByApikey extends TopN("apikey_id", None, "apikey_id IS NOT NULL") {
    val id          = "requests_by_apikey"
    val name        = "Top API keys"
    val description = "Top N API keys by request count."
  }

  object ByUser extends TopN("user_email", None, "user_email IS NOT NULL") {
    val id          = "requests_by_user"
    val name        = "Top users"
    val description = "Top N users by request count."
  }

  object ByDomain extends TopN("domain", None, "domain IS NOT NULL") {
    val id          = "requests_by_domain"
    val name        = "Top domains"
    val description = "Top N domains by request count."
  }

  object ByCountry extends TopN("country", None, "country IS NOT NULL") {
    val id          = "requests_by_country"
    val name        = "Top countries"
    val description = "Top N countries by request count."
  }

  object TopErrorRoutes extends TopN("route_id", Some("route_name"), "err = true AND route_id IS NOT NULL") {
    val id          = "top_error_routes"
    val name        = "Top error routes"
    val description = "Top N routes by errored request count."
  }
}

// ============================================================================
// Timeseries queries
// ============================================================================

object TimeseriesQueries {

  def all: Seq[AnalyticsQuery] = Seq(
    RequestsPerSecond,
    ErrorRateTs,
    DurationAvgTs,
    OverheadAvgTs,
    DurationPercentilesTs,
    TrafficInOutTs,
    RequestsByStatusClassTs
  )

  /** Wrap an aggregation expression with the canonical CTE that fills empty buckets. */
  def buildSeriesQuery(
      aggSelect: String,
      bucket: Bucket,
      whereClause: String,
      table: String
  ): String = {
    val series  = bucket.seriesSql("$1::timestamptz", "$2::timestamptz")
    val bucketE = bucket.truncSql("ts")
    s"""WITH series AS ($series),
       |     agg AS (
       |       SELECT $bucketE AS bucket, $aggSelect
       |       FROM $table$whereClause
       |       GROUP BY 1
       |     )
       |SELECT s.bucket, agg.*
       |FROM series s LEFT JOIN agg USING (bucket)
       |ORDER BY s.bucket""".stripMargin
  }

  private def runSingleSeries(
      aggExpr: String,
      asDouble: Boolean
  )(
      filters: Filters,
      bucket: Bucket,
      settings: UserAnalyticsExporterSettings,
      pool: PgPool
  )(implicit ec: ExecutionContext, env: Env): Future[QueryResult] = {
    val (where, vals) = FilterSql.whereClause(filters)
    val sql           = buildSeriesQuery(s"$aggExpr AS value", bucket, where, AnalyticsSchema.fullTable(settings))
    QueryHelpers.runSelect(pool, sql, vals).map { rows =>
      val points = rows.map { r =>
        val ts = r.getOffsetDateTime(0)
        val v: JsValue = if (asDouble) {
          val v = QueryHelpers.safeDouble(r, 2)
          JsNumber(BigDecimal(v))
        } else JsNumber(QueryHelpers.safeLong(r, 2))
        Json.obj("ts" -> QueryHelpers.jsTs(ts), "value" -> v)
      }
      QueryResult(
        AnalyticsShape.Timeseries,
        Json.obj("bucket" -> bucket.name, "points" -> JsArray(points)),
        JsArray(points)
      )
    }
  }

  object RequestsPerSecond extends AnalyticsQuery {
    val id            = "requests_per_second"
    val name          = "Requests per second"
    val description   = "Request rate (computed from bucket count / bucket size)."
    val shape         = AnalyticsShape.Timeseries
    val defaultWidget = "line"
    override val supportsCompare = true
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: PgPool)(implicit
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] =
      runSingleSeries(s"COUNT(*)::float / ${b.seconds.toDouble}", asDouble = true)(f, b, s, pool)
  }

  object ErrorRateTs extends AnalyticsQuery {
    val id            = "error_rate_ts"
    val name          = "Error rate"
    val description   = "Fraction of errored requests per bucket."
    val shape         = AnalyticsShape.Timeseries
    val defaultWidget = "line"
    override val supportsCompare = true
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: PgPool)(implicit
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] =
      runSingleSeries("CASE WHEN COUNT(*) > 0 THEN SUM(CASE WHEN err THEN 1 ELSE 0 END)::float / COUNT(*) ELSE 0 END", asDouble = true)(
        f,
        b,
        s,
        pool
      )
  }

  object DurationAvgTs extends AnalyticsQuery {
    val id            = "duration_avg_ts"
    val name          = "Average response time"
    val description   = "Average duration_ms per bucket."
    val shape         = AnalyticsShape.Timeseries
    val defaultWidget = "line"
    override val supportsCompare = true
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: PgPool)(implicit
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] =
      runSingleSeries("COALESCE(AVG(duration_ms), 0)", asDouble = true)(f, b, s, pool)
  }

  object OverheadAvgTs extends AnalyticsQuery {
    val id            = "overhead_avg_ts"
    val name          = "Average overhead"
    val description   = "Average overhead_ms per bucket."
    val shape         = AnalyticsShape.Timeseries
    val defaultWidget = "line"
    override val supportsCompare = true
    def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: PgPool)(implicit
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] =
      runSingleSeries("COALESCE(AVG(overhead_ms), 0)", asDouble = true)(f, b, s, pool)
  }

  object DurationPercentilesTs extends AnalyticsQuery {
    val id            = "duration_percentiles_ts"
    val name          = "Latency percentiles"
    val description   = "p50/p75/p95/p99 of duration_ms per bucket."
    val shape         = AnalyticsShape.Timeseries
    val defaultWidget = "line"
    override val supportsCompare = true
    def execute(f: Filters, p: JsObject, bucket: Bucket, s: UserAnalyticsExporterSettings, pool: PgPool)(implicit
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = {
      val (where, vals) = FilterSql.whereClause(f)
      val agg =
        """COALESCE(percentile_cont(0.5)  WITHIN GROUP (ORDER BY duration_ms), 0) AS p50,
          |COALESCE(percentile_cont(0.75) WITHIN GROUP (ORDER BY duration_ms), 0) AS p75,
          |COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms), 0) AS p95,
          |COALESCE(percentile_cont(0.99) WITHIN GROUP (ORDER BY duration_ms), 0) AS p99""".stripMargin
      val sql = TimeseriesQueries.buildSeriesQuery(agg, bucket, where, AnalyticsSchema.fullTable(s))
      QueryHelpers.runSelect(pool, sql, vals).map { rows =>
        val seriesNames = Seq("p50", "p75", "p95", "p99")
        val pointsByName = seriesNames.zipWithIndex.map { case (nm, i) =>
          val pts = rows.map { r =>
            val ts = r.getOffsetDateTime(0)
            val v  = QueryHelpers.safeDouble(r, 2 + i)
            Json.obj("ts" -> QueryHelpers.jsTs(ts), "value" -> JsNumber(BigDecimal(v)))
          }
          Json.obj("name" -> nm, "points" -> JsArray(pts))
        }
        QueryResult(
          AnalyticsShape.Timeseries,
          Json.obj("bucket" -> bucket.name, "series" -> JsArray(pointsByName)),
          JsArray()
        )
      }
    }
  }

  object TrafficInOutTs extends AnalyticsQuery {
    val id            = "traffic_in_out_ts"
    val name          = "Traffic in / out"
    val description   = "Sum of data_in and data_out per bucket."
    val shape         = AnalyticsShape.Timeseries
    val defaultWidget = "area"
    override val supportsCompare = true
    def execute(f: Filters, p: JsObject, bucket: Bucket, s: UserAnalyticsExporterSettings, pool: PgPool)(implicit
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = {
      val (where, vals) = FilterSql.whereClause(f)
      val agg           = "COALESCE(SUM(data_in), 0) AS data_in, COALESCE(SUM(data_out), 0) AS data_out"
      val sql = TimeseriesQueries.buildSeriesQuery(agg, bucket, where, AnalyticsSchema.fullTable(s))
      QueryHelpers.runSelect(pool, sql, vals).map { rows =>
        val seriesNames = Seq("data_in", "data_out")
        val seriesJs = seriesNames.zipWithIndex.map { case (nm, i) =>
          val pts = rows.map { r =>
            val ts = r.getOffsetDateTime(0)
            val v  = QueryHelpers.safeLong(r, 2 + i)
            Json.obj("ts" -> QueryHelpers.jsTs(ts), "value" -> JsNumber(v))
          }
          Json.obj("name" -> nm, "points" -> JsArray(pts))
        }
        QueryResult(
          AnalyticsShape.Timeseries,
          Json.obj("bucket" -> bucket.name, "series" -> JsArray(seriesJs)),
          JsArray()
        )
      }
    }
  }

  object RequestsByStatusClassTs extends AnalyticsQuery {
    val id            = "requests_by_status_class_ts"
    val name          = "Status classes over time"
    val description   = "2xx / 3xx / 4xx / 5xx counts per bucket."
    val shape         = AnalyticsShape.Timeseries
    val defaultWidget = "area"
    override val supportsCompare = true
    def execute(f: Filters, p: JsObject, bucket: Bucket, s: UserAnalyticsExporterSettings, pool: PgPool)(implicit
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = {
      val (where, vals) = FilterSql.whereClause(f)
      val agg =
        """COUNT(*) FILTER (WHERE status >= 200 AND status < 300) AS s2xx,
          |COUNT(*) FILTER (WHERE status >= 300 AND status < 400) AS s3xx,
          |COUNT(*) FILTER (WHERE status >= 400 AND status < 500) AS s4xx,
          |COUNT(*) FILTER (WHERE status >= 500 AND status < 600) AS s5xx""".stripMargin
      val sql = TimeseriesQueries.buildSeriesQuery(agg, bucket, where, AnalyticsSchema.fullTable(s))
      QueryHelpers.runSelect(pool, sql, vals).map { rows =>
        val seriesNames = Seq("2xx", "3xx", "4xx", "5xx")
        val seriesJs = seriesNames.zipWithIndex.map { case (nm, i) =>
          val pts = rows.map { r =>
            val ts = r.getOffsetDateTime(0)
            val v  = QueryHelpers.safeLong(r, 2 + i)
            Json.obj("ts" -> QueryHelpers.jsTs(ts), "value" -> JsNumber(v))
          }
          Json.obj("name" -> nm, "points" -> JsArray(pts))
        }
        QueryResult(
          AnalyticsShape.Timeseries,
          Json.obj("bucket" -> bucket.name, "series" -> JsArray(seriesJs)),
          JsArray()
        )
      }
    }
  }
}

// ============================================================================
// Heatmap queries
// ============================================================================

object HeatmapQueries {

  def all: Seq[AnalyticsQuery] = Seq(ResponseTimeHeatmap)

  /** Latency buckets used as Y axis. Order matters for display. */
  private val LatencyBuckets = Seq(
    ("<50ms", "duration_ms < 50"),
    ("50-100ms", "duration_ms >= 50 AND duration_ms < 100"),
    ("100-250ms", "duration_ms >= 100 AND duration_ms < 250"),
    ("250-500ms", "duration_ms >= 250 AND duration_ms < 500"),
    ("500ms-1s", "duration_ms >= 500 AND duration_ms < 1000"),
    ("1s-3s", "duration_ms >= 1000 AND duration_ms < 3000"),
    (">=3s", "duration_ms >= 3000")
  )

  object ResponseTimeHeatmap extends AnalyticsQuery {
    val id            = "response_time_heatmap"
    val name          = "Latency × time heatmap"
    val description   = "Heatmap of duration buckets over time."
    val shape         = AnalyticsShape.Heatmap
    val defaultWidget = "heatmap"
    def execute(f: Filters, p: JsObject, bucket: Bucket, s: UserAnalyticsExporterSettings, pool: PgPool)(implicit
        ec: ExecutionContext,
        env: Env
    ): Future[QueryResult] = {
      val (where, vals) = FilterSql.whereClause(f)
      val filterCounts = LatencyBuckets.zipWithIndex
        .map { case ((_, cond), i) => s"COUNT(*) FILTER (WHERE $cond) AS y$i" }
        .mkString(",\n")
      val sql = TimeseriesQueries.buildSeriesQuery(filterCounts, bucket, where, AnalyticsSchema.fullTable(s))
      QueryHelpers.runSelect(pool, sql, vals).map { rows =>
        val xBuckets = rows.map(r => QueryHelpers.jsTs(r.getOffsetDateTime(0)))
        val values: Seq[Seq[JsValue]] = LatencyBuckets.zipWithIndex.map { case (_, i) =>
          rows.map(r => JsNumber(QueryHelpers.safeLong(r, 2 + i)))
        }
        QueryResult(
          AnalyticsShape.Heatmap,
          Json.obj(
            "bucket"   -> bucket.name,
            "xBuckets" -> JsArray(xBuckets),
            "yBuckets" -> JsArray(LatencyBuckets.map { case (lbl, _) => JsString(lbl) }),
            "values"   -> JsArray(values.map(JsArray.apply))
          ),
          JsArray()
        )
      }
    }
  }
}
