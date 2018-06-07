package models

import java.util.concurrent.TimeUnit

import env.Env
import models.SnowMonkeyConfig.logger
import org.joda.time.LocalTime
import play.api.Logger
import play.api.libs.json._

import scala.concurrent.duration.{FiniteDuration, _}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class BadResponse(status: Int, body: String, headers: Map[String, String]) {
  def asJson: JsValue = BadResponse.fmt.writes(this)
}

object BadResponse {
  val fmt: Format[BadResponse] = new Format[BadResponse] {
    override def reads(json: JsValue): JsResult[BadResponse] = Try {
      JsSuccess(
        BadResponse(
          status = (json \ "status").asOpt[Int].getOrElse(500),
          body = (json \ "body").asOpt[String].getOrElse("""{"error":"..."}"""),
          headers = (json \ "headers").asOpt[Map[String, String]].getOrElse(Map.empty[String, String])
        )
      )
    } recover {
      case t => JsError(t.getMessage)
    } get
    override def writes(o: BadResponse): JsValue = Json.obj(
      "status" -> o.status,
      "body" -> o.body,
      "headers" -> o.headers
    )
  }
}

sealed trait FaultConfig {
  def ratio: Double
  def asJson: JsValue
}
case class LargeRequestFaultConfig(ratio: Double, additionalRequestSize: Int) extends FaultConfig {
  def asJson: JsValue = LargeRequestFaultConfig.fmt.writes(this)
}
object LargeRequestFaultConfig {
  val fmt: Format[LargeRequestFaultConfig] = new Format[LargeRequestFaultConfig] {
    override def reads(json: JsValue): JsResult[LargeRequestFaultConfig] = Try {
      JsSuccess(
        LargeRequestFaultConfig(
          ratio = (json \ "ratio").asOpt[Double].getOrElse(0.3),
          additionalRequestSize = (json \ "additionalRequestSize").asOpt[Int].getOrElse(0)
        )
      )
    } recover {
      case t => JsError(t.getMessage)
    } get
    override def writes(o: LargeRequestFaultConfig): JsValue = Json.obj(
      "ratio" -> o.ratio,
      "additionalRequestSize" -> o.additionalRequestSize
    )
  }
}
case class LargeResponseFaultConfig(ratio: Double, additionalResponseSize: Int) extends FaultConfig {
  def asJson: JsValue = LargeResponseFaultConfig.fmt.writes(this)
}
object LargeResponseFaultConfig {
  val fmt: Format[LargeResponseFaultConfig] = new Format[LargeResponseFaultConfig] {
    override def reads(json: JsValue): JsResult[LargeResponseFaultConfig] = Try {
      JsSuccess(
        LargeResponseFaultConfig(
          ratio = (json \ "ratio").asOpt[Double].getOrElse(0.3),
          additionalResponseSize = (json \ "additionalResponseSize").asOpt[Int].getOrElse(0)
        )
      )
    } recover {
      case t => JsError(t.getMessage)
    } get
    override def writes(o: LargeResponseFaultConfig): JsValue = Json.obj(
      "ratio" -> o.ratio,
      "additionalResponseSize" -> o.additionalResponseSize
    )
  }
}
case class LatencyInjectionFaultConfig(ratio: Double, from: FiniteDuration, to: FiniteDuration) extends FaultConfig {
  def asJson: JsValue = LatencyInjectionFaultConfig.fmt.writes(this)
}
object LatencyInjectionFaultConfig {
  val fmt: Format[LatencyInjectionFaultConfig] = new Format[LatencyInjectionFaultConfig] {
    override def reads(json: JsValue): JsResult[LatencyInjectionFaultConfig] = Try {
      JsSuccess(
        LatencyInjectionFaultConfig(
          ratio = (json \ "ratio").asOpt[Double].getOrElse(0.3),
          from = (json \ "from").asOpt(SnowMonkeyConfig.durationFmt).getOrElse(FiniteDuration(0, TimeUnit.MILLISECONDS)),
          to = (json \ "to").asOpt(SnowMonkeyConfig.durationFmt).getOrElse(FiniteDuration(0, TimeUnit.MILLISECONDS))
        )
      )
    } recover {
      case t => JsError(t.getMessage)
    } get
    override def writes(o: LatencyInjectionFaultConfig): JsValue = Json.obj(
      "ratio" -> o.ratio,
      "from" -> SnowMonkeyConfig.durationFmt.writes(o.from),
      "to" -> SnowMonkeyConfig.durationFmt.writes(o.to)
    )
  }
}
case class BadResponsesFaultConfig(ratio: Double, responses: Seq[BadResponse]) extends FaultConfig {
  def asJson: JsValue = BadResponsesFaultConfig.fmt.writes(this)
}
object BadResponsesFaultConfig {
  val fmt: Format[BadResponsesFaultConfig] = new Format[BadResponsesFaultConfig] {
    override def reads(json: JsValue): JsResult[BadResponsesFaultConfig] = Try {
      JsSuccess(
        BadResponsesFaultConfig(
          ratio = (json \ "ratio").asOpt[Double].getOrElse(0.3),
          responses = (json \ "responses").asOpt(Reads.seq(BadResponse.fmt)).getOrElse(Seq.empty)
        )
      )
    } recover {
      case t => JsError(t.getMessage)
    } get
    override def writes(o: BadResponsesFaultConfig): JsValue = Json.obj(
      "ratio" -> o.ratio,
      "responses" -> JsArray(o.responses.map(_.asJson))
    )
  }
}

case class ChaosConfig(
  enabled: Boolean = false,
  largeRequestFaultConfig:     Option[LargeRequestFaultConfig] = None,
  largeResponseFaultConfig:    Option[LargeResponseFaultConfig] = None,
  latencyInjectionFaultConfig: Option[LatencyInjectionFaultConfig] = None,
  badResponsesFaultConfig:     Option[BadResponsesFaultConfig] = None,
) {
  def asJson: JsValue = ChaosConfig._fmt.writes(this)
}

object ChaosConfig {
  val _fmt: Format[ChaosConfig] = new Format[ChaosConfig] {
    override def reads(json: JsValue): JsResult[ChaosConfig] = {
      Try {
        ChaosConfig(
          enabled = (json \ "enabled ").asOpt[Boolean].getOrElse(false),
          largeRequestFaultConfig = (json \ "largeRequestFaultConfig ").asOpt[LargeRequestFaultConfig](LargeRequestFaultConfig.fmt),
          largeResponseFaultConfig = (json \ "largeResponseFaultConfig ").asOpt[LargeResponseFaultConfig](LargeResponseFaultConfig.fmt),
          latencyInjectionFaultConfig = (json \ "latencyInjectionFaultConfig ").asOpt[LatencyInjectionFaultConfig](LatencyInjectionFaultConfig.fmt),
          badResponsesFaultConfig = (json \ "badResponsesFaultConfig ").asOpt[BadResponsesFaultConfig](BadResponsesFaultConfig.fmt)
        )
      } map {
        case sd => JsSuccess(sd)
      } recover {
        case t =>
          logger.error("Error while reading SnowMonkeyConfig", t)
          JsError(t.getMessage)
      } get
    }
    override def writes(o: ChaosConfig): JsValue = Json.obj(
      "enabled" -> o.enabled,
      "largeRequestFaultConfig" -> o.largeRequestFaultConfig.map(_.asJson).getOrElse(JsNull).as[JsValue],
      "largeResponseFaultConfig" -> o.largeResponseFaultConfig.map(_.asJson).getOrElse(JsNull).as[JsValue],
      "latencyInjectionFaultConfig" -> o.latencyInjectionFaultConfig.map(_.asJson).getOrElse(JsNull).as[JsValue],
      "badResponsesFaultConfig" -> o.badResponsesFaultConfig.map(_.asJson).getOrElse(JsNull).as[JsValue],
    )
  }
}

sealed trait OutageStrategy
case object OneServicePerGroup extends OutageStrategy
case object AllServicesPerGroup extends OutageStrategy

case class SnowMonkeyConfig(
  enabled: Boolean = false,
  outageStrategy: OutageStrategy = OneServicePerGroup,
  includeFrontends: Boolean = false,
  timesPerDay: Int = 1,
  startTime: LocalTime = LocalTime.parse("09:00:00"),
  stopTime: LocalTime = LocalTime.parse("23:59:59"),
  outageDurationFrom: FiniteDuration = FiniteDuration(10, TimeUnit.MINUTES),
  outageDurationTo: FiniteDuration = FiniteDuration(60, TimeUnit.MINUTES),
  targetGroups: Seq[String] = Seq.empty,
  chaosConfig: ChaosConfig = ChaosConfig(
    enabled = true,
    largeRequestFaultConfig = None,
    largeResponseFaultConfig = None,
    latencyInjectionFaultConfig = Some(LatencyInjectionFaultConfig(0.5, 500.millis, 5000.millis)),
    badResponsesFaultConfig = Some(BadResponsesFaultConfig(0.5, Seq(
      BadResponse(
        502,
        """{"error":"Nihonzaru everywhere ..."}""",
        headers = Map("Content-Type" -> "application/json")
      )
    )))
  )
) {
  def asJson: JsValue = SnowMonkeyConfig._fmt.writes(this)
}

object SnowMonkeyConfig {

  lazy val logger = Logger("otoroshi-snowmonkey-config")

  val durationFmt = new Format[FiniteDuration] {
    override def reads(json: JsValue): JsResult[FiniteDuration] = json.asOpt[Long].map(l => JsSuccess(FiniteDuration(l, TimeUnit.MILLISECONDS))).getOrElse(JsError("Not a valid duration"))
    override def writes(o: FiniteDuration): JsValue = JsNumber(o.toMillis)
  }

  val outageStrategyFmt = new Format[OutageStrategy] {
    override def reads(json: JsValue): JsResult[OutageStrategy] = json.asOpt[String].map {
      case "OneServicePerGroup" => JsSuccess(OneServicePerGroup)
      case "AllServicesPerGroup" => JsSuccess(AllServicesPerGroup)
      case _ => JsSuccess(OneServicePerGroup)
    }.getOrElse(JsSuccess(OneServicePerGroup))
    override def writes(o: OutageStrategy): JsValue = o match {
      case OneServicePerGroup => JsString("OneServicePerGroup")
      case AllServicesPerGroup => JsString("AllServicesPerGroup")
    }
  }

  val _fmt: Format[SnowMonkeyConfig] = new Format[SnowMonkeyConfig] {

    override def writes(o: SnowMonkeyConfig): JsValue = {
      Json.obj(
        "enabled" -> o.enabled,
        "outageStrategy" -> outageStrategyFmt.writes(o.outageStrategy),
        "includeFrontends" -> o.includeFrontends,
        "timesPerDay" -> o.timesPerDay,
        "startTime" -> play.api.libs.json.JodaWrites.DefaultJodaLocalTimeWrites.writes(o.startTime),
        "stopTime" -> play.api.libs.json.JodaWrites.DefaultJodaLocalTimeWrites.writes(o.stopTime),
        "outageDurationFrom" -> durationFmt.writes(o.outageDurationFrom),
        "outageDurationTo" -> durationFmt.writes(o.outageDurationTo),
        "targetGroups" -> JsArray(o.targetGroups.map(JsString.apply)),
        "chaosConfig" -> ChaosConfig._fmt.writes(o.chaosConfig)
      )
    }

    override def reads(json: JsValue): JsResult[SnowMonkeyConfig] = {
      Try {
        SnowMonkeyConfig(
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
          outageStrategy = (json \ "outageStrategy").asOpt[OutageStrategy](outageStrategyFmt).getOrElse(OneServicePerGroup),
          includeFrontends = (json \ "includeFrontends").asOpt[Boolean].getOrElse(false),
          timesPerDay = (json \ "timesPerDay").asOpt[Int].getOrElse(1),
          startTime = (json \ "startTime").asOpt[LocalTime](play.api.libs.json.JodaReads.DefaultJodaLocalTimeReads).getOrElse(LocalTime.parse("09:00:00")),
          stopTime = (json \ "stopTime").asOpt[LocalTime](play.api.libs.json.JodaReads.DefaultJodaLocalTimeReads).getOrElse(LocalTime.parse("23:59:59")),
          outageDurationFrom = (json \ "outageDurationFrom").asOpt[FiniteDuration](durationFmt).getOrElse(FiniteDuration(1, TimeUnit.HOURS)),
          outageDurationTo = (json \ "outageDurationTo").asOpt[FiniteDuration](durationFmt).getOrElse(FiniteDuration(10, TimeUnit.MINUTES)),
          targetGroups = (json \ "targetGroups").asOpt[Seq[String]].getOrElse(Seq.empty),
          chaosConfig = (json \ "chaosConfig").asOpt[ChaosConfig](ChaosConfig._fmt).getOrElse(ChaosConfig(true, None, None, None, None))
        )
      } map {
        case sd => JsSuccess(sd)
      } recover {
        case t =>
          logger.error("Error while reading SnowMonkeyConfig", t)
          JsError(t.getMessage)
      } get
    }
  }

  def toJson(value: SnowMonkeyConfig): JsValue = _fmt.writes(value)
  def fromJsons(value: JsValue): SnowMonkeyConfig =
    try {
      _fmt.reads(value).get
    } catch {
      case e: Throwable => {
        logger.error(s"Try to deserialize ${Json.prettyPrint(value)}")
        throw e
      }
    }
  def fromJsonSafe(value: JsValue): JsResult[SnowMonkeyConfig] = _fmt.reads(value)
}

trait ChaosDataStore {
  def serviceAlreadyOutage(serviceId: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def serviceOutages(serviceId: String)(implicit ec: ExecutionContext, env: Env): Future[Int]
  def groupOutages(serviceId: String)(implicit ec: ExecutionContext, env: Env): Future[Int]
  def registerOutage(descriptor: ServiceDescriptor, conf: SnowMonkeyConfig)(implicit ec: ExecutionContext, env: Env): Future[Unit]
}
