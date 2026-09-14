package otoroshi.next.analytics.exporter

import org.apache.pekko.http.scaladsl.util.FastFuture
import io.vertx.core.json.JsonObject
import io.vertx.pgclient.{PgBuilder, PgConnectOptions, SslMode}
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.{PoolOptions, Tuple => VertxTuple}
import otoroshi.env.Env
import otoroshi.events.ExportResult
import otoroshi.events.DataExporter.DefaultDataExporter
import otoroshi.models.{DataExporterConfig, Exporter}
import otoroshi.security.IdGenerator
import otoroshi.storage.drivers.reactivepg.pgimplicits.*
import otoroshi.utils.syntax.implicits.*
import play.api.Logger
import play.api.libs.json.*

import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class UserAnalyticsExporterSettings(
    uri: Option[String] = None,
    host: String = "localhost",
    port: Int = 5432,
    database: String = "otoroshi",
    user: String = "otoroshi",
    password: String = "otoroshi",
    schema: String = "public",
    tablePrefix: String = UserAnalyticsExporterSettings.DefaultTablePrefix,
    poolSize: Int = 10,
    ssl: Boolean = false,
    retentionDays: Int = 30,
    statementTimeoutMs: Int = 30000,
    rollupEnabled: Boolean = false,
    excludedProjections: Seq[String] = Seq.empty
) extends Exporter {

  override def toJson: JsValue = UserAnalyticsExporterSettings.format.writes(this)

  /**
   * The name every projection derives its table from.
   *
   * It was called `table` because it once named the only table there was, but extensions have been
   * suffixing it (`<table>_cloudapim_security`, ...) ever since projections existed, so it was a
   * prefix in all but name. Kept so already compiled extensions keep linking.
   */
  @deprecated("use tablePrefix", "18.0.0")
  def table: String = tablePrefix

  /** `<schema>.<tablePrefix>_<suffix>`, the table naming every non-gateway projection follows. */
  def prefixedTable(suffix: String): String = s"$schema.${tablePrefix}_$suffix"

  /**
   * Whether events of that projection are stored.
   *
   * An excluded projection still gets its table created and pruned: its queries and widgets keep
   * answering (with nothing new), and capturing it again is a config change, not a migration.
   */
  def captures(projection: AnalyticsProjection): Boolean = !excludedProjections.contains(projection.id)
}

object UserAnalyticsExporterSettings {

  val ActiveMetadataKey: String = "otoroshi:user-analytics:active"

  val DefaultTablePrefix: String = "otoroshi_analytics_events"

  val format: Format[UserAnalyticsExporterSettings] = new Format[UserAnalyticsExporterSettings] {
    override def reads(json: JsValue): JsResult[UserAnalyticsExporterSettings] = Try {
      UserAnalyticsExporterSettings(
        uri = json.select("uri").asOptString.filterNot(_.isEmpty),
        host = json.select("host").asOptString.getOrElse("localhost"),
        port = json.select("port").asOptInt.getOrElse(5432),
        database = json.select("database").asOptString.getOrElse("otoroshi"),
        user = json.select("user").asOptString.getOrElse("otoroshi"),
        password = json.select("password").asOptString.getOrElse("otoroshi"),
        schema = json.select("schema").asOptString.getOrElse("public"),
        // configs saved before the rename only carry `table`
        tablePrefix = json
          .select("table_prefix")
          .asOptString
          .filterNot(_.isEmpty)
          .orElse(json.select("table").asOptString.filterNot(_.isEmpty))
          .getOrElse(DefaultTablePrefix),
        poolSize = json.select("pool_size").asOptInt.getOrElse(10),
        ssl = json.select("ssl").asOptBoolean.getOrElse(false),
        retentionDays = json.select("retention_days").asOptInt.getOrElse(30),
        statementTimeoutMs = json.select("statement_timeout_ms").asOptInt.getOrElse(30000),
        rollupEnabled = json.select("rollup_enabled").asOptBoolean.getOrElse(false),
        excludedProjections = json.select("excluded_projections").asOpt[Seq[String]].getOrElse(Seq.empty)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(s) => JsSuccess(s)
    }

    override def writes(o: UserAnalyticsExporterSettings): JsValue = Json.obj(
      "uri"                  -> o.uri.map(JsString.apply).getOrElse(JsNull).asValue,
      "host"                 -> o.host,
      "port"                 -> o.port,
      "database"             -> o.database,
      "user"                 -> o.user,
      "password"             -> o.password,
      "schema"               -> o.schema,
      "table_prefix"         -> o.tablePrefix,
      // still written for nodes that predate the rename: during a rolling upgrade an older worker
      // reading only `table` would otherwise fall back to the default and write to the wrong tables
      "table"                -> o.tablePrefix,
      "pool_size"            -> o.poolSize,
      "ssl"                  -> o.ssl,
      "retention_days"       -> o.retentionDays,
      "statement_timeout_ms" -> o.statementTimeoutMs,
      "rollup_enabled"       -> o.rollupEnabled,
      "excluded_projections" -> JsArray(o.excludedProjections.map(JsString.apply))
    )
  }

  /**
   * Find the data exporter configured as the *active* user-analytics exporter.
   * Selection rule: enabled, type matches `UserAnalyticsExporterSettings`, and
   * carries the `otoroshi:user-analytics:active=true` metadata flag.
   * Only one exporter at a time is expected to carry the flag (see UI promote
   * button). If multiple do, we keep the first one (stable order by id).
   */
  def findActiveAnalyticsExporter(using env: Env, ec: ExecutionContext): Future[Option[DataExporterConfig]] = {
    env.datastores.dataExporterConfigDataStore.findAll().map { all =>
      all
        .filter(_.enabled)
        .filter(_.config.isInstanceOf[UserAnalyticsExporterSettings])
        .filter(_.metadata.get(ActiveMetadataKey).contains("true"))
        .sortBy(_.id)
        .headOption
    }
  }
}

/**
 * Registry of currently running UserAnalyticsExporter instances, keyed by
 * exporter id. The query layer (Phase B) uses this to find the live Pool
 * of the active exporter.
 */
object UserAnalyticsExporterRegistry {

  private val running = new java.util.concurrent.ConcurrentHashMap[String, UserAnalyticsExporter]()

  def register(id: String, exporter: UserAnalyticsExporter): Unit = {
    running.put(id, exporter)
  }

  def deregister(id: String): Unit = {
    running.remove(id)
  }

  def get(id: String): Option[UserAnalyticsExporter] = Option(running.get(id))

  /**
   * Returns the live Pool of the currently active analytics exporter
   * (the one carrying the `otoroshi:user-analytics:active=true` metadata).
   */
  def activeRunningPool(using env: Env, ec: ExecutionContext): Future[Option[Pool]] = {
    UserAnalyticsExporterSettings.findActiveAnalyticsExporter.map { configOpt =>
      configOpt.flatMap(c => Option(running.get(c.id))).flatMap(_.pool)
    }
  }

  /**
   * Returns the active exporter settings AND its live pool, both required to
   * execute a query against the right table/schema.
   */
  def activeRunning(using
      env: Env,
      ec: ExecutionContext
  ): Future[Option[(UserAnalyticsExporterSettings, Pool)]] = {
    UserAnalyticsExporterSettings.findActiveAnalyticsExporter.map { configOpt =>
      for {
        cfg      <- configOpt
        settings <- Some(cfg.config).collect { case s: UserAnalyticsExporterSettings => s }
        runner   <- Option(running.get(cfg.id))
        pool     <- runner.pool
      } yield (settings, pool)
    }
  }
}

object AnalyticsSchema {

  private val logger = Logger("otoroshi-user-analytics-schema")

  def fullTable(settings: UserAnalyticsExporterSettings): String =
    s"${settings.schema}.${settings.tablePrefix}"

  def createTableSql(settings: UserAnalyticsExporterSettings): String = {
    val t = fullTable(settings)
    s"""
       |CREATE TABLE IF NOT EXISTS $t (
       |  id              TEXT        PRIMARY KEY,
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
       |  identity_type   TEXT,
       |  domain          TEXT,
       |  method          TEXT,
       |  status          SMALLINT,
       |  err             BOOLEAN     NOT NULL DEFAULT false,
       |  duration_ms     INTEGER,
       |  overhead_ms     INTEGER,
       |  backend_ms      INTEGER,
       |  data_in         BIGINT,
       |  data_out        BIGINT,
       |  from_ip         TEXT,
       |  country         TEXT,
       |  user_agent      TEXT,
       |  protocol        TEXT,
       |  raw             JSONB       NOT NULL DEFAULT '{}'::jsonb
       |);
       |""".stripMargin
  }

  def indexStatements(settings: UserAnalyticsExporterSettings): Seq[String] = {
    val t      = fullTable(settings)
    val prefix = settings.tablePrefix
    Seq(
      s"CREATE INDEX IF NOT EXISTS idx_${prefix}_ts        ON $t (ts DESC);",
      s"CREATE INDEX IF NOT EXISTS idx_${prefix}_route_ts  ON $t (route_id, ts DESC);",
      s"CREATE INDEX IF NOT EXISTS idx_${prefix}_api_ts    ON $t (api_id, ts DESC) WHERE api_id IS NOT NULL;",
      s"CREATE INDEX IF NOT EXISTS idx_${prefix}_apikey_ts ON $t (apikey_id, ts DESC) WHERE apikey_id IS NOT NULL;",
      s"CREATE INDEX IF NOT EXISTS idx_${prefix}_tenant_ts ON $t (tenant, ts DESC);",
      s"CREATE INDEX IF NOT EXISTS idx_${prefix}_status_ts ON $t (status, ts DESC);",
      s"CREATE INDEX IF NOT EXISTS idx_${prefix}_err_ts    ON $t (ts DESC) WHERE err = true;",
      s"CREATE INDEX IF NOT EXISTS idx_${prefix}_groups_gin ON $t USING GIN (group_ids);",
      s"CREATE INDEX IF NOT EXISTS idx_${prefix}_teams_gin  ON $t USING GIN (teams);"
    )
  }

  /**
   * Companion table that stores fired alert events ("alert log"). Lives in
   *  the same PG and lifecycle as the events table.
   */
  def firedAlertsTable(settings: UserAnalyticsExporterSettings): String =
    settings.prefixedTable("fired_alerts")

  def createFiredAlertsTableSql(settings: UserAnalyticsExporterSettings): String = {
    val t = firedAlertsTable(settings)
    s"""
       |CREATE TABLE IF NOT EXISTS $t (
       |  id              TEXT        PRIMARY KEY,
       |  ts              TIMESTAMPTZ NOT NULL,
       |  tenant          TEXT        NOT NULL DEFAULT 'default',
       |  alert_id        TEXT        NOT NULL,
       |  alert_name      TEXT,
       |  severity        TEXT,
       |  message         TEXT,
       |  combine_op      TEXT,
       |  window_seconds  INTEGER,
       |  conditions      JSONB,
       |  raw             JSONB       NOT NULL DEFAULT '{}'::jsonb,
       |  seen_at         TIMESTAMPTZ,
       |  seen_by         TEXT
       |);
       |""".stripMargin
  }

  def firedAlertsIndexStatements(settings: UserAnalyticsExporterSettings): Seq[String] = {
    val t      = firedAlertsTable(settings)
    val prefix = s"${settings.tablePrefix}_fa"
    Seq(
      s"CREATE INDEX IF NOT EXISTS idx_${prefix}_alert_ts  ON $t (alert_id, ts DESC);",
      s"CREATE INDEX IF NOT EXISTS idx_${prefix}_tenant_ts ON $t (tenant, ts DESC);",
      s"CREATE INDEX IF NOT EXISTS idx_${prefix}_unseen    ON $t (alert_id, ts DESC) WHERE seen_at IS NULL;",
      // the retention purge filters on `ts` alone, which neither composite above can serve. it
      // matters most on the first run after this table starts being pruned, when it may hold
      // everything ever written to it
      s"CREATE INDEX IF NOT EXISTS idx_${prefix}_ts        ON $t (ts DESC);"
    )
  }

  private val migrationLogger = Logger("otoroshi-user-analytics-schema")

  /**
   * Creates the schema, then every projection's table and indexes.
   *
   * The core projections run first and unguarded — if the gateway events table cannot be created,
   * analytics are broken and that has to surface. An extension's DDL is guarded individually: a
   * plugin with a bad `CREATE TABLE` should lose its own analytics, not the platform's.
   */
  def migrate(
      pool: Pool,
      settings: UserAnalyticsExporterSettings,
      projections: Seq[AnalyticsProjection] = AnalyticsProjection.core
  )(using ec: ExecutionContext): Future[Unit] = {
    val (core, extras) = projections.partition(p => AnalyticsProjection.core.exists(_.id == p.id))
    val createSchema   = pool.query(s"CREATE SCHEMA IF NOT EXISTS ${settings.schema};").executeAsync().map(_ => ())
    val withCore       = core.foldLeft(createSchema) { (acc, projection) =>
      acc.flatMap(_ => runDdl(pool, settings, projection))
    }
    extras.foldLeft(withCore) { (acc, projection) =>
      acc.flatMap { _ =>
        runDdl(pool, settings, projection).recover { case e: Throwable =>
          migrationLogger.error(s"[user-analytics] could not create the table for projection '${projection.id}'", e)
          ()
        }
      }
    }
  }

  private def runDdl(pool: Pool, settings: UserAnalyticsExporterSettings, projection: AnalyticsProjection)(using
      ec: ExecutionContext
  ): Future[Unit] = {
    val created = pool.query(projection.createTableSql(settings)).executeAsync().map(_ => ())
    projection.indexStatements(settings).foldLeft(created) { (acc, ddl) =>
      acc.flatMap(_ => pool.query(ddl).executeAsync().map(_ => ()))
    }
  }
}

object EventDenormalizer {

  case class Row(
      id: String,
      ts: java.time.OffsetDateTime,
      env: String,
      tenant: String,
      teams: Array[String],
      routeId: Option[String],
      routeName: Option[String],
      apiId: Option[String],
      groupIds: Array[String],
      apikeyId: Option[String],
      userEmail: Option[String],
      identityType: Option[String],
      domain: Option[String],
      method: Option[String],
      status: Option[Short],
      err: Boolean,
      durationMs: Option[Int],
      overheadMs: Option[Int],
      backendMs: Option[Int],
      dataIn: Option[Long],
      dataOut: Option[Long],
      fromIp: Option[String],
      country: Option[String],
      userAgent: Option[String],
      protocol: Option[String],
      raw: JsValue
  )

  /**
   * Builds a denormalized row from a stripped GatewayEvent. Field references
   * mirror what `EventStripper` keeps. If you remove a field there, this code
   * will simply fall back to None.
   */
  def extractColumns(stripped: JsValue): Row = {
    val now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)

    val id = stripped.select("@id").asOptString.getOrElse(IdGenerator.uuid)
    val ts = stripped
      .select("@timestamp")
      .asOpt[Long]
      .map(ms => java.time.Instant.ofEpochMilli(ms).atOffset(java.time.ZoneOffset.UTC))
      .orElse(
        stripped.select("@timestamp").asOptString.flatMap(s => Try(java.time.OffsetDateTime.parse(s)).toOption)
      )
      .getOrElse(now)

    val envStr   = stripped.select("@env").asOptString.getOrElse("")
    val tenant   = stripped.select("route").select("_loc").select("tenant").asOptString.getOrElse("default")
    val teams    = stripped.select("route").select("_loc").select("teams").asOpt[Seq[String]].getOrElse(Seq.empty).toSeq
    val routeId  = stripped.select("route").select("id").asOptString
    val routeNm  = stripped.select("route").select("name").asOptString
    val apiId    = stripped.select("route").select("api_ref").select("id").asOptString
    val groupIds = stripped.select("route").select("groups").asOpt[Seq[String]].getOrElse(Seq.empty).toSeq

    val identityType          = stripped.select("identity").select("identityType").asOptString
    val identityId            = stripped.select("identity").select("identity").asOptString
    val (apikeyId, userEmail) = identityType match {
      case Some("APIKEY")     => (identityId, None)
      case Some("PRIVATEAPP") => (None, identityId)
      case _                  => (None, None)
    }

    val domain   = stripped.select("to").select("host").asOptString
    val method   = stripped.select("method").asOptString
    val status   = stripped.select("status").asOpt[Int].map(_.toShort)
    val err      = stripped.select("err").asOptBoolean.getOrElse(false)
    val duration = stripped.select("duration").asOpt[Long].map(_.toInt)
    val overhead = stripped.select("overhead").asOpt[Long].map(_.toInt)
    val backend  = stripped.select("backendDuration").asOpt[Long].map(_.toInt)
    val dataIn   = stripped.select("data").select("dataIn").asOpt[Long]
    val dataOut  = stripped.select("data").select("dataOut").asOpt[Long]
    val fromIp   = stripped.select("from").asOptString
    val country  = stripped.select("geolocationInfo").select("country").asOptString
    val userAg   = stripped.select("userAgentInfo").select("ua").asOptString
    val protocol = stripped.select("protocol").asOptString

    Row(
      id = id,
      ts = ts,
      env = envStr,
      tenant = tenant,
      teams = teams.toArray,
      routeId = routeId,
      routeName = routeNm,
      apiId = apiId,
      groupIds = groupIds.toArray,
      apikeyId = apikeyId,
      userEmail = userEmail,
      identityType = identityType,
      domain = domain,
      method = method,
      status = status,
      err = err,
      durationMs = duration,
      overheadMs = overhead,
      backendMs = backend,
      dataIn = dataIn,
      dataOut = dataOut,
      fromIp = fromIp,
      country = country,
      userAgent = userAg,
      protocol = protocol,
      raw = stripped
    )
  }

  /**
   * Converts a denormalized row to a Vert.x tuple ready to bind to the
   *  `insertSql` 26 parameter slots in the right order.
   */
  def toTuple(r: Row): VertxTuple = VertxTuple.from(
    Array[AnyRef](
      r.id,
      r.ts,
      r.env,
      r.tenant,
      r.teams,
      r.routeId.orNull,
      r.routeName.orNull,
      r.apiId.orNull,
      r.groupIds,
      r.apikeyId.orNull,
      r.userEmail.orNull,
      r.identityType.orNull,
      r.domain.orNull,
      r.method.orNull,
      r.status.map(java.lang.Short.valueOf).orNull,
      java.lang.Boolean.valueOf(r.err),
      r.durationMs.map(java.lang.Integer.valueOf).orNull,
      r.overheadMs.map(java.lang.Integer.valueOf).orNull,
      r.backendMs.map(java.lang.Integer.valueOf).orNull,
      r.dataIn.map(java.lang.Long.valueOf).orNull,
      r.dataOut.map(java.lang.Long.valueOf).orNull,
      r.fromIp.orNull,
      r.country.orNull,
      r.userAgent.orNull,
      r.protocol.orNull,
      new JsonObject(Json.stringify(r.raw))
    )
  )

  /**
   * SQL `INSERT ... ON CONFLICT DO NOTHING` matching the 26-column layout
   *  produced by `extractColumns`/`toTuple`.
   */
  def insertSql(s: UserAnalyticsExporterSettings): String = {
    val t = AnalyticsSchema.fullTable(s)
    s"""INSERT INTO $t (
       |  id, ts, env, tenant, teams, route_id, route_name, api_id, group_ids,
       |  apikey_id, user_email, identity_type, domain, method, status, err,
       |  duration_ms, overhead_ms, backend_ms, data_in, data_out, from_ip,
       |  country, user_agent, protocol, raw
       |) VALUES (
       |  $$1, $$2, $$3, $$4, $$5, $$6, $$7, $$8, $$9,
       |  $$10, $$11, $$12, $$13, $$14, $$15, $$16,
       |  $$17, $$18, $$19, $$20, $$21, $$22,
       |  $$23, $$24, $$25, $$26::jsonb
       |) ON CONFLICT (id) DO NOTHING;""".stripMargin
  }
}

object FiredAlertDenormalizer {

  def toTuple(event: JsValue): VertxTuple = {
    val now            = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
    val id             = event.select("@id").asOptString.getOrElse(IdGenerator.uuid)
    val ts             = event
      .select("@timestamp")
      .asOpt[Long]
      .map(ms => java.time.Instant.ofEpochMilli(ms).atOffset(java.time.ZoneOffset.UTC))
      .getOrElse(now)
    val tenant         = event.select("tenant").asOptString.getOrElse("default")
    val alertId        = event.select("alertId").asOptString.getOrElse("")
    val alertName      = event.select("alertName").asOptString
    val severity       = event.select("severity").asOptString
    val message        = event.select("message").asOptString
    val combineOp      = event.select("combine").asOptString
    val windowSec      = event.select("windowSeconds").asOpt[Int]
    val conditionsJson = Json.stringify(event.select("conditions").asOpt[JsArray].getOrElse(JsArray()))
    VertxTuple.from(
      Array[AnyRef](
        id,
        ts,
        tenant,
        alertId,
        alertName.orNull,
        severity.orNull,
        message.orNull,
        combineOp.orNull,
        windowSec.map(java.lang.Integer.valueOf).orNull,
        // conditions is a JSON array; pass the string and let `::jsonb` cast handle it
        conditionsJson,
        // raw is a JSON object — same idea
        Json.stringify(event)
      )
    )
  }

  def insertSql(s: UserAnalyticsExporterSettings): String = {
    val t = AnalyticsSchema.firedAlertsTable(s)
    s"""INSERT INTO $t (
       |  id, ts, tenant, alert_id, alert_name, severity, message,
       |  combine_op, window_seconds, conditions, raw
       |) VALUES (
       |  $$1, $$2, $$3, $$4, $$5, $$6, $$7,
       |  $$8, $$9, $$10::jsonb, $$11::jsonb
       |) ON CONFLICT (id) DO NOTHING;""".stripMargin
  }
}

class UserAnalyticsExporter(config: DataExporterConfig)(using ec: ExecutionContext, env: Env)
    extends DefaultDataExporter(config)(using ec, env) {

  private val poolRef = new AtomicReference[Pool](null)

  def pool: Option[Pool] = Option(poolRef.get())

  private def buildConnectOptions(s: UserAnalyticsExporterSettings): PgConnectOptions = {
    s.uri match {
      case Some(uri) => PgConnectOptions.fromUri(uri)
      case None      =>
        new PgConnectOptions()
          .setHost(s.host)
          .setPort(s.port)
          .setDatabase(s.database)
          .setUser(s.user)
          .setPassword(s.password)
          .applyOnIf(s.ssl)(_.setSslMode(SslMode.REQUIRE))
    }
  }

  /**
   * Core projections first, so an extension can never take over a family the platform already owns.
   */
  private def projections: Seq[AnalyticsProjection] = AnalyticsProjection.installed(env)

  override def accept(event: JsValue): Boolean =
    super.accept(event) && exporter[UserAnalyticsExporterSettings].exists { s =>
      AnalyticsProjection.capturedRouteOf(projections, s, event).isDefined
    }

  override def start(): Future[Unit] = {
    exporter[UserAnalyticsExporterSettings] match {
      case None    => FastFuture.successful(())
      case Some(s) =>
        val newPool = PgBuilder.pool().connectingTo(buildConnectOptions(s)).`with`(new PoolOptions().setMaxSize(s.poolSize)).build()
        poolRef.set(newPool)
        UserAnalyticsExporterRegistry.register(config.id, this)
        if (env.clusterConfig.mode.isOff || env.clusterConfig.mode.isLeader) {
          AnalyticsSchema
            .migrate(newPool, s, projections)
            .flatMap { _ =>
              val isActive =
                config.metadata.get(UserAnalyticsExporterSettings.ActiveMetadataKey).contains("true")
              if (isActive) {
                otoroshi.next.analytics.defaults.DefaultDashboards
                  .seedIfMissing()(using env, ec)
                  .map(_ => ())
                  .recover { case e: Throwable =>
                    logger.error(s"[user-analytics-exporter] error while seeding default dashboards", e)
                  }
              } else FastFuture.successful(())
            }
            .recover { case e: Throwable =>
              logger.error(s"[user-analytics-exporter] error while migrating schema for ${AnalyticsSchema.fullTable(s)}", e)
            }
        } else {
          FastFuture.successful(())
        }
    }
  }

  override def stop(): Future[Unit] = {
    UserAnalyticsExporterRegistry.deregister(config.id)
    Option(poolRef.getAndSet(null)).foreach(_.close())
    FastFuture.successful(())
  }

  override def send(events: Seq[JsValue]): Future[ExportResult] = Try {
    Option(poolRef.get()) match {
      case None       => FastFuture.successful(ExportResult.ExportResultFailure("user-analytics pool not initialized"))
      case Some(pool) =>
        exporter[UserAnalyticsExporterSettings] match {
          case None    => FastFuture.successful(ExportResult.ExportResultFailure("bad config type"))
          case Some(s) =>
            // route each event to the projection that claims it. an event nobody claims cannot
            // normally get here — `accept` filtered on the same list — but writing it with someone
            // else's denormaliser would corrupt their table, so it is dropped and said out loud
            val known        = projections
            val byProjection = events.groupBy(e => AnalyticsProjection.routeOf(known, e))
            byProjection.get(None).foreach { orphans =>
              logger.warn(s"[user-analytics-exporter] ${orphans.size} event(s) matched no projection, dropped")
            }
            // `accept` already left excluded projections out, but events buffered before the
            // exclusion was saved can still arrive here
            val writes = byProjection.collect {
              case (Some(projection), evs) if evs.nonEmpty && s.captures(projection) =>
                insert(pool, s, projection, evs)
            }.toSeq
            Future
              .sequence(writes)
              .map(_ => ExportResult.ExportResultSuccess: ExportResult)
              .recover { case e: Throwable =>
                logger.error(s"[user-analytics-exporter] send failed", e)
                ExportResult.ExportResultFailure(e.getMessage)
              }
        }
    }
  } match {
    case Failure(e) =>
      e.printStackTrace()
      FastFuture.failed(e)
    case Success(s) => s
  }

  /**
   * The single insert path.
   *
   * `strip` is applied here rather than left to the projection's `toTuple`, so pruning happens for
   * every projection whether or not its author remembered to ask for it. A batch that fails is
   * logged and swallowed, as both hardcoded paths did before: one bad table must not stop the
   * others being written.
   */
  private def insert(
      pool: Pool,
      s: UserAnalyticsExporterSettings,
      projection: AnalyticsProjection,
      events: Seq[JsValue]
  ): Future[Unit] = {
    val rows: java.util.List[VertxTuple] = events.map(e => projection.toTuple(projection.strip(e))).asJava
    pool
      .preparedQuery(projection.insertSql(s))
      .executeBatch(rows)
      .scala
      .map(_ => ())
      .recover { case e: Throwable =>
        logger.error(
          s"[user-analytics-exporter] error inserting ${events.size} event(s) into ${projection.table(s)} for projection '${projection.id}'",
          e
        )
        ()
      }
  }
}
