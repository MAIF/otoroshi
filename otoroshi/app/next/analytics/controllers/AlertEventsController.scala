package otoroshi.next.analytics.controllers

import akka.http.scaladsl.util.FastFuture
import io.vertx.sqlclient.{Tuple => VertxTuple}
import otoroshi.actions.{ApiAction, ApiActionContext}
import otoroshi.env.Env
import otoroshi.next.analytics.exporter.{AnalyticsSchema, UserAnalyticsExporterRegistry, UserAnalyticsExporterSettings}
import otoroshi.storage.drivers.reactivepg.pgimplicits._
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Read + acknowledge endpoints for fired alert events.
 *
 *   GET    /api/analytics/alerts/:alertId/events?limit=&offset=&seen=
 *   POST   /api/analytics/alerts/:alertId/events/_seen-all
 *   POST   /api/analytics/alerts/:alertId/events/:eventId/seen
 *   POST   /api/analytics/alerts/:alertId/events/:eventId/unseen
 */
class AlertEventsController(ApiAction: ApiAction, cc: ControllerComponents)(implicit env: Env)
    extends AbstractController(cc) {

  implicit lazy val ec = env.otoroshiExecutionContext

  private val logger = Logger("otoroshi-user-analytics-alert-events-api")

  /** Read a JSONB array column. Vert.x may return either a `JsonArray`,
   *  a `String` (PG bytea/jsonb representation), or a wrapped `Buffer` —
   *  fall back to Play JSON parsing in any case.
   */
  private def readJsonbArray(row: io.vertx.sqlclient.Row, name: String): JsValue = {
    val v = row.getValue(name)
    if (v == null) JsArray()
    else
      v match {
        case s: String                       => scala.util.Try(Json.parse(s)).getOrElse(JsArray())
        case ja: io.vertx.core.json.JsonArray => scala.util.Try(Json.parse(ja.encode())).getOrElse(JsArray())
        case other                            => scala.util.Try(Json.parse(other.toString)).getOrElse(JsArray())
      }
  }

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

  /** GET /api/analytics/alerts/:alertId/events */
  def listEvents(alertId: String): Action[AnyContent] = ApiAction.async { ctx =>
    requireTenantAccess(ctx) { tenant =>
      val limit  = ctx.request.getQueryString("limit").flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(200).max(1).min(2000)
      val offset = ctx.request.getQueryString("offset").flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(0).max(0)
      val seenFilter = ctx.request.getQueryString("seen") match {
        case Some("true")  => Some("AND seen_at IS NOT NULL")
        case Some("false") => Some("AND seen_at IS NULL")
        case _             => None
      }
      UserAnalyticsExporterRegistry.activeRunning.flatMap {
        case None                   =>
          PreconditionFailed(Json.obj("error" -> "no active user-analytics exporter")).future
        case Some((settings, pool)) =>
          val table = AnalyticsSchema.firedAlertsTable(settings)
          val sql   =
            s"""SELECT id, ts, tenant, alert_id, alert_name, severity, message,
               |       combine_op, window_seconds, conditions, raw, seen_at, seen_by
               |  FROM $table
               | WHERE alert_id = $$1 AND tenant = $$2
               |   ${seenFilter.getOrElse("")}
               | ORDER BY ts DESC
               | LIMIT $$3 OFFSET $$4""".stripMargin
          pool
            .preparedQuery(sql)
            .execute(VertxTuple.from(Array[AnyRef](alertId, tenant, java.lang.Integer.valueOf(limit), java.lang.Integer.valueOf(offset))))
            .scala
            .map { rs =>
              val items = rs.iterator().asScala.toList.map { row =>
                Json.obj(
                  "id"             -> row.getString("id"),
                  "ts"             -> Option(row.getOffsetDateTime("ts")).map(_.toInstant.toEpochMilli).map(JsNumber(_)).getOrElse(JsNull).asInstanceOf[JsValue],
                  "tenant"         -> row.getString("tenant"),
                  "alert_id"       -> row.getString("alert_id"),
                  "alert_name"     -> Option(row.getString("alert_name")).map(JsString.apply).getOrElse(JsNull).asInstanceOf[JsValue],
                  "severity"       -> Option(row.getString("severity")).map(JsString.apply).getOrElse(JsNull).asInstanceOf[JsValue],
                  "message"        -> Option(row.getString("message")).map(JsString.apply).getOrElse(JsNull).asInstanceOf[JsValue],
                  "combine"        -> Option(row.getString("combine_op")).map(JsString.apply).getOrElse(JsNull).asInstanceOf[JsValue],
                  "windowSeconds"  -> Option(row.getValue("window_seconds")).map(v => JsNumber(BigDecimal(v.toString))).getOrElse(JsNull).asInstanceOf[JsValue],
                  "conditions"     -> readJsonbArray(row, "conditions"),
                  "seen_at"        -> Option(row.getOffsetDateTime("seen_at")).map(_.toInstant.toEpochMilli).map(JsNumber(_)).getOrElse(JsNull).asInstanceOf[JsValue],
                  "seen_by"        -> Option(row.getString("seen_by")).map(JsString.apply).getOrElse(JsNull).asInstanceOf[JsValue]
                )
              }
              Ok(Json.obj("items" -> JsArray(items), "limit" -> limit, "offset" -> offset))
            }
            .recover { case e: Throwable =>
              logger.error(s"failed to list fired alert events for $alertId", e)
              InternalServerError(Json.obj("error" -> e.getMessage))
            }
      }
    }
  }

  /** POST /api/analytics/alerts/:alertId/events/:eventId/seen */
  def markSeen(alertId: String, eventId: String): Action[AnyContent] = ApiAction.async { ctx =>
    requireTenantAccess(ctx) { tenant =>
      setSeenFlag(alertId, eventId, tenant, seen = true, ctx)
    }
  }

  /** POST /api/analytics/alerts/:alertId/events/:eventId/unseen */
  def markUnseen(alertId: String, eventId: String): Action[AnyContent] = ApiAction.async { ctx =>
    requireTenantAccess(ctx) { tenant =>
      setSeenFlag(alertId, eventId, tenant, seen = false, ctx)
    }
  }

  /** POST /api/analytics/alerts/:alertId/events/_seen-all */
  def markAllSeen(alertId: String): Action[AnyContent] = ApiAction.async { ctx =>
    requireTenantAccess(ctx) { tenant =>
      val by = ctx.backOfficeUser.toOption.flatten.map(_.email).getOrElse("?")
      UserAnalyticsExporterRegistry.activeRunning.flatMap {
        case None                   =>
          PreconditionFailed(Json.obj("error" -> "no active user-analytics exporter")).future
        case Some((settings, pool)) =>
          val table = AnalyticsSchema.firedAlertsTable(settings)
          val now   = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
          val sql   =
            s"""UPDATE $table
               |   SET seen_at = $$1, seen_by = $$2
               | WHERE alert_id = $$3 AND tenant = $$4 AND seen_at IS NULL""".stripMargin
          pool
            .preparedQuery(sql)
            .execute(VertxTuple.from(Array[AnyRef](now, by, alertId, tenant)))
            .scala
            .map(rs => Ok(Json.obj("updated" -> rs.rowCount())))
            .recover { case e: Throwable =>
              logger.error(s"failed to mark-all-seen on $alertId", e)
              InternalServerError(Json.obj("error" -> e.getMessage))
            }
      }
    }
  }

  private def setSeenFlag(
      alertId: String,
      eventId: String,
      tenant: String,
      seen: Boolean,
      ctx: ApiActionContext[_]
  ): Future[play.api.mvc.Result] = {
    val by = ctx.backOfficeUser.toOption.flatten.map(_.email).getOrElse("?")
    UserAnalyticsExporterRegistry.activeRunning.flatMap {
      case None                   =>
        PreconditionFailed(Json.obj("error" -> "no active user-analytics exporter")).future
      case Some((settings, pool)) =>
        val table = AnalyticsSchema.firedAlertsTable(settings)
        val sql   = if (seen) {
          s"""UPDATE $table SET seen_at = $$1, seen_by = $$2
             | WHERE id = $$3 AND alert_id = $$4 AND tenant = $$5""".stripMargin
        } else {
          s"""UPDATE $table SET seen_at = NULL, seen_by = NULL
             | WHERE id = $$1 AND alert_id = $$2 AND tenant = $$3""".stripMargin
        }
        val tuple = if (seen) {
          val now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
          VertxTuple.from(Array[AnyRef](now, by, eventId, alertId, tenant))
        } else {
          VertxTuple.from(Array[AnyRef](eventId, alertId, tenant))
        }
        pool
          .preparedQuery(sql)
          .execute(tuple)
          .scala
          .map { rs =>
            if (rs.rowCount() == 0) NotFound(Json.obj("error" -> "event not found"))
            else Ok(Json.obj("ok" -> true))
          }
          .recover { case e: Throwable =>
            logger.error(s"failed to update seen flag for $eventId", e)
            InternalServerError(Json.obj("error" -> e.getMessage))
          }
    }
  }
}
