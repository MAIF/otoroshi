package otoroshi.next.analytics.alerts

import akka.http.scaladsl.util.FastFuture
import otoroshi.env.Env
import otoroshi.next.analytics.exporter.UserAnalyticsExporterRegistry
import otoroshi.next.analytics.models.{AlertCondition, UserAlert}
import otoroshi.next.analytics.queries.{AnalyticsRuntime, AnalyticsShape, Bucketing, Filters, QueryResult}
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * Evaluates an alert (its list of conditions, combined via AND/OR) against
 * the active analytics exporter, applying the configured reducers and
 * comparing to thresholds. Returns whether the alert is currently firing
 * along with the per-condition evaluation snapshots so the emitted event
 * can explain WHY.
 */
object AlertEvaluator {

  private val logger = Logger("otoroshi-user-analytics-alerts")

  def evaluate(alert: UserAlert)(implicit env: Env, ec: ExecutionContext): Future[(Boolean, Seq[AlertConditionEval])] = {
    if (alert.conditions.isEmpty) FastFuture.successful((false, Seq.empty))
    else {
      Future.sequence(alert.conditions.map(c => evaluateCondition(c, alert))).map { evals =>
        val firing = alert.combine.toUpperCase match {
          case "OR"  => evals.exists(_.matched)
          case _     => evals.forall(_.matched) // AND default
        }
        (firing, evals)
      }
    }
  }

  // --------------------------------------------------------------------------
  // Per-condition evaluation
  // --------------------------------------------------------------------------

  def evaluateCondition(cond: AlertCondition, alert: UserAlert)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[AlertConditionEval] = {
    val skeleton = AlertConditionEval(
      query = cond.query,
      reducer = cond.reducer,
      operator = cond.operator,
      threshold = cond.threshold,
      value = None,
      matched = false
    )
    AnalyticsRuntime.registry match {
      case None      =>
        FastFuture.successful(skeleton.copy(error = "analytics runtime not initialized".some))
      case Some(reg) =>
        reg.find(cond.query) match {
          case None    =>
            FastFuture.successful(skeleton.copy(error = s"unknown query '${cond.query}'".some))
          case Some(q) =>
            UserAnalyticsExporterRegistry.activeRunning.flatMap {
              case None                   =>
                FastFuture.successful(skeleton.copy(error = "no active user-analytics exporter".some))
              case Some((settings, pool)) =>
                Try {
                  val filters = buildFilters(cond, alert)
                  val bucket  = Bucketing.autoBucket(filters.from, filters.to)
                  q.execute(filters, cond.params, bucket, settings, pool)
                } match {
                  case Failure(e)   =>
                    FastFuture.successful(skeleton.copy(error = e.getMessage.some))
                  case Success(fut) =>
                    fut
                      .map { result =>
                        val value   = reduce(result, cond.reducer)
                        val matched = value.exists(v => compare(v, cond.operator, cond.threshold))
                        skeleton.copy(value = value, matched = matched)
                      }
                      .recover { case e: Throwable =>
                        logger.error(s"alert '${alert.id}' condition '${cond.query}' failed", e)
                        skeleton.copy(error = e.getMessage.some)
                      }
                }
            }
        }
    }
  }

  // --------------------------------------------------------------------------
  // Filters / reduction / comparison helpers
  // --------------------------------------------------------------------------

  private def buildFilters(cond: AlertCondition, alert: UserAlert): Filters = {
    val now      = Instant.now()
    val from     = now.minusSeconds(math.max(1L, alert.windowSeconds))
    val routeId  = (cond.filters \ "route_id").asOpt[String].filterNot(_.isEmpty)
    val apiId    = (cond.filters \ "api_id").asOpt[String].filterNot(_.isEmpty)
    val apikeyId = (cond.filters \ "apikey_id").asOpt[String].filterNot(_.isEmpty)
    val groupId  = (cond.filters \ "group_id").asOpt[String].filterNot(_.isEmpty)
    val err      = (cond.filters \ "err").asOpt[Boolean]
    Filters(
      from = from,
      to = now,
      routeId = routeId,
      apiId = apiId,
      apikeyId = apikeyId,
      groupId = groupId,
      err = err,
      tenant = Some(alert.location.tenant.value)
    )
  }

  /**
   * Reduce a QueryResult to a single Double. Handles Scalar, Timeseries
   * (single-series and multi-series — uses the first series for multi),
   * TopN and Pie shapes.
   */
  def reduce(result: QueryResult, reducer: String): Option[Double] = {
    result.shape match {
      case AnalyticsShape.Scalar | AnalyticsShape.Metric =>
        (result.data \ "value").asOpt[Double]

      case AnalyticsShape.Timeseries =>
        val singlePoints = (result.data \ "points").asOpt[Seq[JsValue]]
        val firstSeriesPoints =
          (result.data \ "series").asOpt[Seq[JsValue]].flatMap(_.headOption).flatMap { s =>
            (s \ "points").asOpt[Seq[JsValue]]
          }
        val points = singlePoints.orElse(firstSeriesPoints).getOrElse(Seq.empty)
        val values = points.flatMap(p => (p \ "value").asOpt[Double])
        reduceValues(values, reducer)

      case AnalyticsShape.TopN | AnalyticsShape.Pie =>
        val items  = (result.data \ "items").asOpt[Seq[JsValue]].getOrElse(Seq.empty)
        val values = items.flatMap(i => (i \ "value").asOpt[Double])
        reduceValues(values, reducer)

      case _ => None
    }
  }

  private def reduceValues(values: Seq[Double], reducer: String): Option[Double] = {
    if (values.isEmpty) None
    else
      reducer.toLowerCase match {
        case "max"  => Some(values.max)
        case "min"  => Some(values.min)
        case "sum"  => Some(values.sum)
        case "avg"  => Some(values.sum / values.size.toDouble)
        case "last" => Some(values.last)
        case _      => None
      }
  }

  def compare(value: Double, op: String, threshold: Double): Boolean = op match {
    case ">"  => value > threshold
    case ">=" => value >= threshold
    case "<"  => value < threshold
    case "<=" => value <= threshold
    case "==" => value == threshold
    case "!=" => value != threshold
    case _    => false
  }
}
