package otoroshi.next.plugins

import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.Results

import scala.concurrent._
import scala.util._

case class TimeRestrictedAccessPluginConfigRule(
  timeStart: DateTime = new DateTime(0, 0, 0, 8, 0, 0),
  timeEnd: DateTime = new DateTime(0, 0, 0, 20, 0, 0),
  dayStart: Int = 1,
  dayEnd: Int = 5,
  denyStatus: Option[Int] = Some(403),
) {

  def json: JsValue = TimeRestrictedAccessPluginConfigRule.format.writes(this)
  
  def dayOk(now: DateTime): Boolean = {
    val day = now.getDayOfWeek
    day >= dayStart && day <= dayEnd
  }
  
  def timeOk(now: DateTime): Boolean = {
    val nowTime = new DateTime(0, 0, 0, now.getHourOfDay, now.getMinuteOfHour, now.getSecondOfMinute)
    nowTime.isAfter(timeStart) && nowTime.isBefore(timeEnd)
  }
}

object TimeRestrictedAccessPluginConfigRule {
  val format = new Format[TimeRestrictedAccessPluginConfigRule] {
    override def reads(json: JsValue): JsResult[TimeRestrictedAccessPluginConfigRule] = Try {
      val timeStart = (json \ "time_start").asOpt[String].map(s => DateTime.parse(s)).getOrElse(DateTime.now())
      val timeEnd = (json \ "time_end").asOpt[String].map(s => DateTime.parse(s)).getOrElse(DateTime.now())
      val dayStart = (json \ "day_start").asOpt[Int].getOrElse(1)
      val dayEnd = (json \ "day_end").asOpt[Int].getOrElse(5)
      val denyStatus = (json \ "deny_status").asOpt[Int]
      TimeRestrictedAccessPluginConfigRule(timeStart, timeEnd, dayStart, dayEnd, denyStatus)
    } match {
      case Success(rule) => JsSuccess(rule)
      case Failure(e) => JsError(e.getMessage)
    }
    override def writes(o: TimeRestrictedAccessPluginConfigRule): JsValue = {
      Json.obj(
        "time_start" -> o.timeStart.toString(),
        "time_end" -> o.timeEnd.toString(),
        "day_start" -> o.dayStart,
        "day_end" -> o.dayEnd,
        "deny_status" -> o.denyStatus
      )
    }
  }
}

case class TimeRestrictedAccessPluginConfig(rules: Seq[TimeRestrictedAccessPluginConfigRule] = Seq.empty) extends NgPluginConfig {
  def json: JsValue = TimeRestrictedAccessPluginConfig.format.writes(this)

}

object TimeRestrictedAccessPluginConfig {
  val format = new Format[TimeRestrictedAccessPluginConfig]() {

    override def reads(json: JsValue): JsResult[TimeRestrictedAccessPluginConfig] = Try {
      val rules = (json \ "rules").asOpt[Seq[JsObject]].map(_.flatMap(o => TimeRestrictedAccessPluginConfigRule.format.reads(o).asOpt)).getOrElse(Seq.empty)
      TimeRestrictedAccessPluginConfig(rules)
    } match {
      case Success(config) => JsSuccess(config)
      case Failure(e) => JsError(e.getMessage)
    }

    override def writes(o: TimeRestrictedAccessPluginConfig): JsValue = {
      Json.obj(
        "rules" -> JsArray(o.rules.map(_.json))
      )
    }
  }
  val configFlow: Seq[String] = Seq(
    "rules",
  )
  val configSchema: Option[JsObject] = Some(
    Json.obj(
      "rules" -> Json.obj(
        "type" -> "array",
        "array" -> true,
        "label" -> s"Rules",
        "format" -> "form",
        "schema" -> Json.obj(
          "time_start" -> Json.obj("type" -> "string", "format" -> "time", "label" -> "Start Time", "placeholder" -> "HH:mm:ss"),
          "time_end" -> Json.obj("type" -> "string", "format" -> "time", "label" -> "End Time", "placeholder" -> "HH:mm:ss"),
          "day_start" -> Json.obj("type" -> "number", "label" -> "Start Day", "help" -> "1=Monday, 7=Sunday"),
          "day_end" -> Json.obj("type" -> "number", "label" -> "End Day", "help" -> "1=Monday, 7=Sunday"),
          "deny_status" -> Json.obj("type" -> "number", "label" -> "Deny Status Code"),
        ),
        "flow" -> Json.arr(
          "time_start",
          "time_end",
          "day_start",
          "day_end",
          "deny_status",
        )
      )
    )
  )
}

class TimeRestrictedAccessPlugin extends NgAccessValidator {

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl, NgPluginCategory.Classic)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Time Restriction"
  override def description: Option[String]                 = "This plugin restrict when a route is accessible".some
  override def defaultConfigObject: Option[NgPluginConfig] = TimeRestrictedAccessPluginConfig().some
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = TimeRestrictedAccessPluginConfig.configFlow
  override def configSchema: Option[JsObject] = TimeRestrictedAccessPluginConfig.configSchema

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config  = ctx.cachedConfig(internalName)(TimeRestrictedAccessPluginConfig.format).getOrElse(TimeRestrictedAccessPluginConfig())
    val exists = config.rules.find { rule =>
      val nowUtc = DateTime.now()
      val dayOk = rule.dayOk(nowUtc)
      val timeOk = rule.timeOk(nowUtc)
      dayOk && timeOk
    }
    if (exists.isDefined) {
      NgAccess.NgAllowed.vfuture
    } else {
      val body = Json.obj(
        "error" -> "forbidden",
        "error_description"  -> "You cannot access this resource right now",
      )
      NgAccess.NgDenied(
        Results.Status(exists.get.denyStatus.getOrElse(403))(body)
      ).vfuture
    }
  }
}