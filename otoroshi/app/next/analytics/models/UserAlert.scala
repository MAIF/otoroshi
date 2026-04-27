package otoroshi.next.analytics.models

import otoroshi.env.Env
import otoroshi.models.{EntityLocation, EntityLocationSupport}
import otoroshi.security.IdGenerator
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import scala.util.{Failure, Success, Try}

/**
 * One condition of a user-analytics alert.
 *
 *   - `query`    : id of an analytics query in the catalogue (e.g. `error_rate_ts`)
 *   - `params`   : params for that query (e.g. `top_n`)
 *   - `filters`  : entity filters applied for this condition only
 *                  (route_id, api_id, apikey_id, group_id)
 *   - `reducer`  : how to reduce the timeseries / topN result to a scalar
 *                  (`avg` | `max` | `min` | `sum` | `last`)
 *   - `operator` : comparison vs threshold (`>` | `>=` | `<` | `<=` | `==` | `!=`)
 *   - `threshold`: value compared with the reduced result
 */
case class AlertCondition(
    query: String,
    params: JsObject = Json.obj(),
    filters: JsObject = Json.obj(),
    reducer: String = "avg",
    operator: String = ">",
    threshold: Double = 0.0
) {
  def json: JsValue = AlertCondition.format.writes(this)
}

object AlertCondition {
  val format: Format[AlertCondition] = new Format[AlertCondition] {
    override def reads(json: JsValue): JsResult[AlertCondition] = Try {
      AlertCondition(
        query = (json \ "query").as[String],
        params = (json \ "params").asOpt[JsObject].getOrElse(Json.obj()),
        filters = (json \ "filters").asOpt[JsObject].getOrElse(Json.obj()),
        reducer = (json \ "reducer").asOpt[String].getOrElse("avg"),
        operator = (json \ "operator").asOpt[String].getOrElse(">"),
        threshold = (json \ "threshold").asOpt[Double].getOrElse(0.0)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(s) => JsSuccess(s)
    }
    override def writes(o: AlertCondition): JsValue = Json.obj(
      "query"     -> o.query,
      "params"    -> o.params,
      "filters"   -> o.filters,
      "reducer"   -> o.reducer,
      "operator"  -> o.operator,
      "threshold" -> o.threshold
    )
  }
}

/**
 * A user-analytics alert. Owned by tenant/teams. Periodically evaluated by
 * `AlertEvaluationJob`; when the configured logical expression turns true,
 * a `UserAnalyticsAlertEvent` is emitted, respecting cooldown.
 *
 * Conditions are combined via `combine` (`AND` or `OR`). For richer
 * boolean expressions, phase 2.
 */
case class UserAlert(
    location: EntityLocation,
    id: String,
    name: String,
    description: String,
    tags: Seq[String],
    metadata: Map[String, String],
    enabled: Boolean,
    windowSeconds: Long,
    evaluationIntervalSeconds: Long,
    cooldownSeconds: Long,
    severity: String,
    message: String,
    combine: String,
    conditions: Seq[AlertCondition]
) extends EntityLocationSupport {
  override def internalId: String                 = id
  override def theDescription: String             = description
  override def theMetadata: Map[String, String]   = metadata
  override def theName: String                    = name
  override def theTags: Seq[String]               = tags
  override def json: JsValue                      = UserAlert.format.writes(this)
}

object UserAlert {

  val DEFAULT_WINDOW_S       = 300L
  val DEFAULT_EVAL_S         = 60L
  val DEFAULT_COOLDOWN_S     = 600L
  val DEFAULT_SEVERITY       = "warning"
  val ALLOWED_SEVERITIES     = Set("info", "warning", "critical")
  val ALLOWED_REDUCERS       = Set("avg", "max", "min", "sum", "last")
  val ALLOWED_OPERATORS      = Set(">", ">=", "<", "<=", "==", "!=")
  val ALLOWED_COMBINES       = Set("AND", "OR")

  val format: Format[UserAlert] = new Format[UserAlert] {
    override def reads(json: JsValue): JsResult[UserAlert] = Try {
      UserAlert(
        location = EntityLocation.readFromKey(json),
        id = (json \ "id").asOpt[String].getOrElse(IdGenerator.namedId("alert", IdGenerator.uuid)),
        name = (json \ "name").asOpt[String].getOrElse(""),
        description = (json \ "description").asOpt[String].getOrElse(""),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty),
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        enabled = (json \ "enabled").asOpt[Boolean].getOrElse(true),
        windowSeconds = (json \ "windowSeconds").asOpt[Long].getOrElse(DEFAULT_WINDOW_S),
        evaluationIntervalSeconds =
          (json \ "evaluationIntervalSeconds").asOpt[Long].getOrElse(DEFAULT_EVAL_S),
        cooldownSeconds = (json \ "cooldownSeconds").asOpt[Long].getOrElse(DEFAULT_COOLDOWN_S),
        severity = (json \ "severity").asOpt[String].getOrElse(DEFAULT_SEVERITY),
        message = (json \ "message").asOpt[String].getOrElse(""),
        combine = (json \ "combine").asOpt[String].getOrElse("AND"),
        conditions = (json \ "conditions")
          .asOpt[Seq[JsValue]]
          .getOrElse(Seq.empty)
          .flatMap(j => AlertCondition.format.reads(j).asOpt)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(s) => JsSuccess(s)
    }
    override def writes(o: UserAlert): JsValue = o.location.jsonWithKey ++ Json.obj(
      "id"                        -> o.id,
      "name"                      -> o.name,
      "description"               -> o.description,
      "tags"                      -> JsArray(o.tags.map(JsString.apply)),
      "metadata"                  -> Json.toJson(o.metadata),
      "enabled"                   -> o.enabled,
      "windowSeconds"             -> o.windowSeconds,
      "evaluationIntervalSeconds" -> o.evaluationIntervalSeconds,
      "cooldownSeconds"           -> o.cooldownSeconds,
      "severity"                  -> o.severity,
      "message"                   -> o.message,
      "combine"                   -> o.combine,
      "conditions"                -> JsArray(o.conditions.map(_.json))
    )
  }

  def defaultUserAlertTemplate(implicit env: Env): UserAlert = UserAlert(
    location = EntityLocation.default,
    id = IdGenerator.namedId("user-alert", env),
    name = "New user alert",
    description = "",
    tags = Seq.empty,
    metadata = Map.empty,
    enabled = true,
    windowSeconds = DEFAULT_WINDOW_S,
    evaluationIntervalSeconds = DEFAULT_EVAL_S,
    cooldownSeconds = DEFAULT_COOLDOWN_S,
    severity = DEFAULT_SEVERITY,
    message = "Alert triggered",
    combine = "AND",
    conditions = Seq(
      AlertCondition(
        query = "error_rate_ts",
        reducer = "max",
        operator = ">",
        threshold = 0.05
      )
    )
  )
}

trait UserAlertDataStore extends BasicStore[UserAlert] {
  def template(env: Env): UserAlert = {
    implicit val e = env
    env.datastores.globalConfigDataStore
      .latest()(env.otoroshiExecutionContext, env)
      .templates
      .userAlertTemplate
      .map { template =>
        UserAlert.format.reads(UserAlert.defaultUserAlertTemplate.json.asObject.deepMerge(template)).get
      }
      .getOrElse {
        UserAlert.defaultUserAlertTemplate
      }
  }
}

class KvUserAlertDataStore(redisCli: RedisLike, _env: Env)
    extends UserAlertDataStore
    with RedisLikeStore[UserAlert] {
  override def fmt: Format[UserAlert]                  = UserAlert.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): String                 = s"${_env.storageRoot}:user-alerts:$id"
  override def extractId(value: UserAlert): String     = value.id
}
