package otoroshi.next.analytics.queries

import io.vertx.pgclient.PgPool
import otoroshi.env.Env
import otoroshi.next.analytics.exporter.{
  UserAnalyticsExporterRegistry,
  UserAnalyticsExporterSettings
}
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._

import java.time.{Instant, ZoneOffset}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

// ============================================================================
// Shapes
// ============================================================================

sealed trait AnalyticsShape { def name: String }
object AnalyticsShape {
  case object Timeseries extends AnalyticsShape { val name = "timeseries" }
  case object TopN       extends AnalyticsShape { val name = "topN"       }
  case object Pie        extends AnalyticsShape { val name = "pie"        }
  case object Scalar     extends AnalyticsShape { val name = "scalar"     }
  case object Metric     extends AnalyticsShape { val name = "metric"     }
  case object Table      extends AnalyticsShape { val name = "table"      }
  case object Heatmap    extends AnalyticsShape { val name = "heatmap"    }
}

// ============================================================================
// Filters
// ============================================================================

case class Filters(
    from: Instant,
    to: Instant,
    routeId: Option[String] = None,
    apiId: Option[String] = None,
    apikeyId: Option[String] = None,
    groupId: Option[String] = None,
    err: Option[Boolean] = None,
    tenant: Option[String] = None
)

object Filters {

  /** Parse "now", "now-1h", "now-30m", "now-7d", or absolute ISO. */
  def parseTime(s: String, fallback: Instant): Instant = {
    val trimmed = s.trim
    if (trimmed == "now") Instant.now()
    else if (trimmed.startsWith("now-")) {
      val rest    = trimmed.drop(4)
      val numStr  = rest.takeWhile(_.isDigit)
      val unit    = rest.dropWhile(_.isDigit)
      Try(numStr.toLong).toOption match {
        case None      => fallback
        case Some(num) =>
          val seconds = unit match {
            case "s" => num
            case "m" => num * 60L
            case "h" => num * 3600L
            case "d" => num * 86400L
            case _   => 0L
          }
          Instant.now().minusSeconds(seconds)
      }
    } else {
      Try(Instant.parse(trimmed)).getOrElse(fallback)
    }
  }

  def fromJson(json: JsValue): Filters = {
    val nowMinusHour = Instant.now().minusSeconds(3600)
    val from = (json \ "from").asOpt[String].map(parseTime(_, nowMinusHour)).getOrElse(nowMinusHour)
    val to   = (json \ "to").asOpt[String].map(parseTime(_, Instant.now())).getOrElse(Instant.now())
    Filters(
      from = from,
      to = to,
      routeId = (json \ "route_id").asOpt[String].filterNot(_.isEmpty),
      apiId = (json \ "api_id").asOpt[String].filterNot(_.isEmpty),
      apikeyId = (json \ "apikey_id").asOpt[String].filterNot(_.isEmpty),
      groupId = (json \ "group_id").asOpt[String].filterNot(_.isEmpty),
      err = (json \ "err").asOpt[Boolean]
    )
  }
}

// ============================================================================
// Bucket
// ============================================================================

case class Bucket(name: String, seconds: Long) {

  /** Floor-based truncation expression. Aligns to N-second epoch boundaries. */
  def truncSql(tsExpr: String = "ts"): String =
    s"to_timestamp(floor(extract(epoch from $tsExpr) / $seconds) * $seconds)"

  /** PostgreSQL interval literal for generate_series step. */
  def intervalLiteral: String = s"INTERVAL '$seconds seconds'"

  /** Build a series SQL fragment producing one TIMESTAMPTZ row per bucket
   *  between the `fromExpr` and `toExpr` parameters (themselves SQL expressions
   *  evaluating to TIMESTAMPTZ values, e.g. `$1::timestamptz`).
   */
  def seriesSql(fromExpr: String, toExpr: String): String =
    s"SELECT generate_series(${truncSql(fromExpr)}, ${truncSql(toExpr)}, $intervalLiteral) AS bucket"
}

object Bucket {
  val OneMinute  = Bucket("1m", 60L)
  val FiveMinute = Bucket("5m", 300L)
  val OneHour    = Bucket("1h", 3600L)
  val SixHour    = Bucket("6h", 21600L)
  val OneDay     = Bucket("1d", 86400L)

  def parse(s: String): Option[Bucket] = s match {
    case "1m" => Some(OneMinute)
    case "5m" => Some(FiveMinute)
    case "1h" => Some(OneHour)
    case "6h" => Some(SixHour)
    case "1d" => Some(OneDay)
    case _    => None
  }
}

object Bucketing {

  /** Picks an appropriate bucket size based on the time range:
   *   - ≤ 1h   → 1m
   *   - ≤ 24h  → 1h
   *   - ≤ 7d   → 6h
   *   - >  7d  → 1d
   */
  def autoBucket(from: Instant, to: Instant): Bucket = {
    val seconds = math.max(1L, java.time.Duration.between(from, to).getSeconds)
    if (seconds <= 3600L) Bucket.OneMinute
    else if (seconds <= 86400L) Bucket.OneHour
    else if (seconds <= 604800L) Bucket.SixHour
    else Bucket.OneDay
  }
}

// ============================================================================
// FilterSql — produces parameterized WHERE clause + value list
// ============================================================================

object FilterSql {

  /**
   * Builds a parameterized WHERE clause.
   * Returns:
   *   - sql fragment starting with " WHERE " (or empty string if no clauses)
   *   - ordered values to bind starting at parameter index `startIndex`
   *
   * Time filters (`ts >= ?` and `ts < ?`) are always present.
   */
  def whereClause(filters: Filters, startIndex: Int = 1): (String, Seq[AnyRef]) = {
    val frags  = scala.collection.mutable.ListBuffer[String]()
    val values = scala.collection.mutable.ListBuffer[AnyRef]()

    frags += "ts >= ?"
    values += java.time.OffsetDateTime.ofInstant(filters.from, ZoneOffset.UTC)

    frags += "ts < ?"
    values += java.time.OffsetDateTime.ofInstant(filters.to, ZoneOffset.UTC)

    filters.tenant.foreach { v =>
      frags += "tenant = ?"; values += v
    }
    filters.routeId.foreach { v =>
      frags += "route_id = ?"; values += v
    }
    filters.apiId.foreach { v =>
      frags += "api_id = ?"; values += v
    }
    filters.apikeyId.foreach { v =>
      frags += "apikey_id = ?"; values += v
    }
    filters.groupId.foreach { v =>
      frags += "? = ANY(group_ids)"; values += v
    }
    filters.err.foreach { v =>
      frags += "err = ?"; values += java.lang.Boolean.valueOf(v)
    }

    var idx = startIndex
    val numbered = frags.toList.map { f =>
      val out = f.replace("?", s"$$$idx")
      idx += 1
      out
    }
    val whereStr = if (numbered.isEmpty) "" else " WHERE " + numbered.mkString(" AND ")
    (whereStr, values.toList)
  }
}

// ============================================================================
// QueryParam, QueryResult, AnalyticsQuery trait
// ============================================================================

case class QueryParam(
    name: String,
    kind: String,
    default: JsValue = JsNull,
    description: String = ""
) {
  def toJson: JsObject = Json.obj(
    "name"        -> name,
    "kind"        -> kind,
    "default"     -> default,
    "description" -> description
  )
}

case class QueryResult(
    shape: AnalyticsShape,
    data: JsValue,
    raw: JsArray = JsArray(),
    meta: JsObject = Json.obj()
) {
  def toJson: JsObject = Json.obj(
    "shape" -> shape.name,
    "data"  -> data,
    "raw"   -> raw,
    "meta"  -> meta
  )
}

trait AnalyticsQuery {
  def id: String
  def name: String
  def description: String
  def shape: AnalyticsShape
  def defaultWidget: String
  def params: Seq[QueryParam]      = Seq.empty
  def supportsCompare: Boolean     = false

  def execute(
      filters: Filters,
      params: JsObject,
      bucket: Bucket,
      settings: UserAnalyticsExporterSettings,
      pool: PgPool
  )(implicit ec: ExecutionContext, env: Env): Future[QueryResult]

  def toCatalogJson: JsObject = Json.obj(
    "id"               -> id,
    "name"             -> name,
    "description"      -> description,
    "shape"            -> shape.name,
    "default_widget"   -> defaultWidget,
    "params"           -> JsArray(params.map(_.toJson)),
    "supports_compare" -> supportsCompare
  )
}

// ============================================================================
// Cache
// ============================================================================

class QueryCache(maxEntries: Int = 1000, ttl: FiniteDuration = 30.seconds) {

  private case class Entry(value: QueryResult, expiresAt: Long)

  private val cache: java.util.Map[String, Entry] = java.util.Collections.synchronizedMap(
    new java.util.LinkedHashMap[String, Entry](maxEntries + 1, 0.75f, true) {
      override def removeEldestEntry(eldest: java.util.Map.Entry[String, Entry]): Boolean =
        size() > maxEntries
    }
  )

  def get(key: String): Option[QueryResult] = {
    Option(cache.get(key)).flatMap { e =>
      if (e.expiresAt < System.currentTimeMillis()) {
        cache.remove(key)
        None
      } else Some(e.value)
    }
  }

  def put(key: String, value: QueryResult): Unit = {
    cache.put(key, Entry(value, System.currentTimeMillis() + ttl.toMillis))
  }

  def clear(): Unit = cache.clear()

  def size(): Int = cache.size()
}

// ============================================================================
// Registry — aggregates core + extension queries
// ============================================================================

class AnalyticsQueryRegistry(coreQueries: Seq[AnalyticsQuery])(implicit env: Env) {

  def all: Seq[AnalyticsQuery] = {
    val extensionQueries =
      try {
        env.adminExtensions.analyticsQueries()
      } catch {
        case _: Throwable => Seq.empty[AnalyticsQuery]
      }
    coreQueries ++ extensionQueries
  }

  def find(id: String): Option[AnalyticsQuery] = all.find(_.id == id)
}

// ============================================================================
// Executor
// ============================================================================

class QueryExecutor(registry: AnalyticsQueryRegistry, cache: QueryCache) {

  private val logger = Logger("otoroshi-user-analytics-executor")

  def run(
      queryId: String,
      filters: Filters,
      params: JsObject,
      requestedBucket: Option[String],
      compare: Boolean,
      nocache: Boolean
  )(implicit env: Env, ec: ExecutionContext): Future[Either[String, JsObject]] = {
    registry.find(queryId) match {
      case None        =>
        Future.successful(Left(s"unknown analytics query '$queryId'"))
      case Some(query) =>
        UserAnalyticsExporterRegistry.activeRunning.flatMap {
          case None                   =>
            Future.successful(Left("no active user-analytics exporter"))
          case Some((settings, pool)) =>
            val bucket = requestedBucket
              .flatMap(Bucket.parse)
              .getOrElse(Bucketing.autoBucket(filters.from, filters.to))

            val cacheKey = makeKey(queryId, filters, params, bucket)
            val cached   = if (nocache) None else cache.get(cacheKey)

            cached match {
              case Some(r) =>
                val js = r.toJson.deepMerge(
                  Json.obj("meta" -> Json.obj("from_cache" -> true, "bucket" -> bucket.name))
                )
                Future.successful(Right(js))

              case None    =>
                val started = System.currentTimeMillis()
                val mainFu  = query.execute(filters, params, bucket, settings, pool)

                val withCompare: Future[(JsObject, QueryResult)] =
                  if (compare && query.supportsCompare) {
                    val durationSec = math.max(1L, java.time.Duration.between(filters.from, filters.to).getSeconds)
                    val previous    = filters.copy(
                      from = filters.from.minusSeconds(durationSec),
                      to = filters.from
                    )
                    for {
                      main <- mainFu
                      prev <- query.execute(previous, params, bucket, settings, pool)
                    } yield (main.toJson + ("compare" -> prev.toJson), main)
                  } else {
                    mainFu.map(r => (r.toJson, r))
                  }

                withCompare
                  .map { case (js, original) =>
                    val withMeta = js.deepMerge(
                      Json.obj(
                        "meta" -> Json.obj(
                          "execution_ms" -> (System.currentTimeMillis() - started),
                          "from_cache"   -> false,
                          "bucket"       -> bucket.name
                        )
                      )
                    )
                    if (!nocache) cache.put(cacheKey, original)
                    Right(withMeta): Either[String, JsObject]
                  }
                  .recover { case e: Throwable =>
                    logger.error(s"query '$queryId' failed", e)
                    Left(s"query '$queryId' failed: ${e.getMessage}")
                  }
            }
        }
    }
  }

  private def makeKey(queryId: String, f: Filters, params: JsObject, bucket: Bucket): String = {
    val s = s"$queryId|${f.from}|${f.to}|${f.tenant.getOrElse("")}|${f.routeId.getOrElse("")}|${f.apiId.getOrElse("")}|${f.apikeyId.getOrElse("")}|${f.groupId.getOrElse("")}|${f.err.map(_.toString).getOrElse("")}|${bucket.name}|${params.stringify}"
    val md = java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"))
    md.map("%02x".format(_)).mkString
  }
}

// ============================================================================
// Singleton holder for runtime-wide registry/executor/cache
// ============================================================================

object AnalyticsRuntime {

  private val ref = new AtomicReference[Option[(AnalyticsQueryRegistry, QueryExecutor, QueryCache)]](None)

  def init(coreQueries: Seq[AnalyticsQuery])(implicit env: Env): Unit = {
    val cache    = new QueryCache()
    val registry = new AnalyticsQueryRegistry(coreQueries)
    val exec     = new QueryExecutor(registry, cache)
    ref.set(Some((registry, exec, cache)))
  }

  def registry: Option[AnalyticsQueryRegistry] = ref.get().map(_._1)
  def executor: Option[QueryExecutor]          = ref.get().map(_._2)
  def cache: Option[QueryCache]                = ref.get().map(_._3)
}
