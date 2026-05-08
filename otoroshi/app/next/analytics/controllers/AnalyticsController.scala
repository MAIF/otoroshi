package otoroshi.next.analytics.controllers

import akka.http.scaladsl.util.FastFuture
import io.vertx.pgclient.{PgConnectOptions, PgPool, SslMode}
import io.vertx.sqlclient.PoolOptions
import otoroshi.actions.{ApiAction, ApiActionContext}
import otoroshi.env.Env
import otoroshi.next.analytics.exporter.{
  AnalyticsSchema,
  UserAnalyticsExporterRegistry,
  UserAnalyticsExporterSettings
}
import otoroshi.next.analytics.queries.{AnalyticsRuntime, Filters}
import otoroshi.storage.drivers.reactivepg.pgimplicits._
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

import scala.concurrent.{ExecutionContext, Future}

class AnalyticsController(ApiAction: ApiAction, cc: ControllerComponents)(implicit env: Env)
    extends AbstractController(cc) {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  private val logger = Logger("otoroshi-user-analytics-api")

  // ----- helpers -------------------------------------------------------------

  private def requireSuperAdmin(ctx: ApiActionContext[_])(f: => Future[play.api.mvc.Result]): Future[play.api.mvc.Result] = {
    if (ctx.userIsSuperAdmin) f
    else Forbidden(Json.obj("error" -> "super admin only")).future
  }

  /** Allow access if the caller is a super-admin OR has read access on the
   *  current tenant (resolved from the `Otoroshi-Tenant` header). The current
   *  tenant value is also returned so the caller can inject it into the
   *  query filters.
   */
  private def requireTenantAccess(
      ctx: ApiActionContext[_]
  )(f: String => Future[play.api.mvc.Result]): Future[play.api.mvc.Result] = {
    val tenant = ctx.currentTenant
    val canAccess =
      env.bypassUserRightsCheck ||
        ctx.userIsSuperAdmin ||
        ctx.backOfficeUser.toOption.flatten.exists(_.rights.canReadTenant(tenant))
    if (canAccess) f(tenant.value)
    else Forbidden(Json.obj("error" -> s"no access to tenant '${tenant.value}'")).future
  }

  private def requireLeader(f: => Future[play.api.mvc.Result]): Future[play.api.mvc.Result] = {
    if (env.clusterConfig.mode.isOff || env.clusterConfig.mode.isLeader) f
    else NotFound(Json.obj("error" -> "leader-only endpoint")).future
  }

  // ----- POST /api/analytics/_query -----------------------------------------

  def runQuery: Action[JsValue] = ApiAction.async(parse.json) { ctx =>
    requireTenantAccess(ctx) { tenant =>
      requireLeader {
        val body            = ctx.request.body
        val queryId         = (body \ "query").asOpt[String].getOrElse("")
        val params          = (body \ "params").asOpt[JsObject].getOrElse(Json.obj())
        // Always inject the resolved tenant into the SQL WHERE clause.
        // The `Otoroshi-Tenant` header drives which tenant's data is shown.
        val rawFilters      = Filters
          .fromJson((body \ "filters").asOpt[JsValue].getOrElse(Json.obj()))
          .copy(tenant = Some(tenant))
        val requestedBucket = (body \ "bucket").asOpt[String]
        val compare         = (body \ "compare").asOpt[Boolean].getOrElse(false)
        val nocache         = (body \ "nocache").asOpt[Boolean].getOrElse(false)

        AnalyticsRuntime.executor match {
          case None           =>
            InternalServerError(Json.obj("error" -> "analytics runtime not initialized")).future
          case Some(executor) =>
            executor
              .run(queryId, rawFilters, params, requestedBucket, compare, nocache)
              .map {
                case Left(err)  =>
                  err match {
                    case e if e.contains("no active") =>
                      PreconditionFailed(Json.obj("error" -> e))
                    case e if e.startsWith("unknown") =>
                      NotFound(Json.obj("error" -> e))
                    case e                            =>
                      BadRequest(Json.obj("error" -> e))
                  }
                case Right(res) => Ok(res)
              }
        }
      }
    }
  }

  // ----- GET /api/analytics/_schema ------------------------------------------

  def schema: Action[AnyContent] = ApiAction.async { ctx =>
    requireTenantAccess(ctx) { _ =>
      AnalyticsRuntime.registry match {
        case None      =>
          InternalServerError(Json.obj("error" -> "analytics runtime not initialized")).future
        case Some(reg) =>
          val widgets = JsArray(
            Seq("line", "area", "bar", "pie", "donut", "scalar", "metric", "table", "heatmap").map(JsString.apply)
          )
          val queries = JsArray(reg.all.map(_.toCatalogJson))
          Ok(Json.obj("queries" -> queries, "widget_types" -> widgets)).future
      }
    }
  }

  // ----- POST /api/analytics/_test-connection --------------------------------

  def testConnection: Action[JsValue] = ApiAction.async(parse.json) { ctx =>
    requireSuperAdmin(ctx) {
      val body     = ctx.request.body
      val settings = UserAnalyticsExporterSettings.format.reads(body) match {
        case JsSuccess(s, _) => Right(s)
        case JsError(errs)   => Left(errs.mkString)
      }
      settings match {
        case Left(err) => BadRequest(Json.obj("error" -> err)).future
        case Right(s)  =>
          val opts = s.uri match {
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
          val pool = PgPool.pool(opts, new PoolOptions().setMaxSize(1))
          pool
            .query("SELECT 1")
            .executeAsync()
            .map { _ =>
              pool.close()
              Ok(Json.obj("ok" -> true))
            }
            .recover { case e: Throwable =>
              pool.close()
              BadRequest(Json.obj("ok" -> false, "error" -> e.getMessage))
            }
      }
    }
  }

  // ----- POST /api/analytics/_set-active-exporter/:id ------------------------

  def setActiveExporter(id: String): Action[AnyContent] = ApiAction.async { ctx =>
    requireSuperAdmin(ctx) {
      env.datastores.dataExporterConfigDataStore.findAll().flatMap { all =>
        val analyticsExporters = all.filter(_.config.isInstanceOf[UserAnalyticsExporterSettings])
        analyticsExporters.find(_.id == id) match {
          case None         =>
            NotFound(Json.obj("error" -> s"no user-analytics exporter with id '$id'")).future
          case Some(target) =>
            val key = UserAnalyticsExporterSettings.ActiveMetadataKey
            val toUnflag = analyticsExporters.filter(_.id != id).map(_.metadata).count(_.get(key).contains("true"))
            val updates = analyticsExporters.map { c =>
              if (c.id == id) c.copy(metadata = c.metadata + (key -> "true"))
              else c.copy(metadata = c.metadata - key)
            }
            Future
              .sequence(updates.map(_.save()))
              .flatMap { _ =>
                // Seed default dashboards now that an analytics exporter is active.
                otoroshi.next.analytics.defaults.DefaultDashboards
                  .seedIfMissing()
                  .recover { case _ => Seq.empty[String] }
                  .map { seeded =>
                    Ok(
                      Json.obj(
                        "ok"             -> true,
                        "active"         -> id,
                        "demoted_count"  -> toUnflag,
                        "seeded_default" -> JsArray(seeded.map(JsString.apply))
                      )
                    )
                  }
              }
        }
      }
    }
  }

  // ----- POST /api/analytics/_migrate ----------------------------------------

  def migrateLegacy: Action[JsValue] = ApiAction.async(parse.json) { ctx =>
    if (env.isDev) {
      requireSuperAdmin(ctx) {
        requireLeader {
          val body = ctx.request.body
          val sourceJson = (body \ "source").asOpt[JsValue].getOrElse(Json.obj())
          val batchSize = (body \ "batchSize").asOpt[Int].getOrElse(5000).max(100).min(50000)
          val dryRun = (body \ "dryRun").asOpt[Boolean].getOrElse(false)
          otoroshi.next.analytics.migration.LegacyPgMigrator.parseSourceFromJson(sourceJson) match {
            case Left(err) => BadRequest(Json.obj("error" -> err)).future
            case Right(source) =>
              otoroshi.next.analytics.migration.LegacyPgMigrator
                .migrate(source, batchSize, dryRun)
                .map {
                  case Left(err) =>
                    err match {
                      case e if e.contains("no active") =>
                        PreconditionFailed(Json.obj("error" -> e))
                      case e =>
                        InternalServerError(Json.obj("error" -> e))
                    }
                  case Right(result) => Ok(result.toJson)
                }
                .recover { case e: Throwable =>
                  logger.error("[user-analytics-api] migration failed", e)
                  InternalServerError(Json.obj("error" -> e.getMessage))
                }
          }
        }
      }
    } else {
      Forbidden(Json.obj("error" -> "forbidden")).future
    }
  }
}
