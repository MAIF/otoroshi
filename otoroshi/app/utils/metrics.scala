package utils

import com.codahale.metrics.{Meter, MetricRegistry, Timer}
import play.api.libs.json.{JsValue, Json}

object Metrics {

  //val metrics = new MetricRegistry()

}

case class MeterView(count: Long,
                     meanRate: Double,
                     oneMinuteRate: Double,
                     fiveMinuteRate: Double,
                     fifteenMinuteRate: Double) {
  def toJson: JsValue = Json.obj(
    "count"             -> count,
    "meanRate"          -> meanRate,
    "oneMinuteRate"     -> oneMinuteRate,
    "fiveMinuteRate"    -> fiveMinuteRate,
    "fifteenMinuteRate" -> fifteenMinuteRate
  )
}

object MeterView {
  def apply(meter: Meter): MeterView =
    new MeterView(meter.getCount,
                  meter.getMeanRate,
                  meter.getOneMinuteRate,
                  meter.getFiveMinuteRate,
                  meter.getFifteenMinuteRate)
}

case class TimerView(count: Long,
                     meanRate: Double,
                     oneMinuteRate: Double,
                     fiveMinuteRate: Double,
                     fifteenMinuteRate: Double) {
  def toJson: JsValue = Json.obj(
    "count"             -> count,
    "meanRate"          -> meanRate,
    "oneMinuteRate"     -> oneMinuteRate,
    "fiveMinuteRate"    -> fiveMinuteRate,
    "fifteenMinuteRate" -> fifteenMinuteRate
  )
}

object TimerView {
  def apply(meter: Timer): TimerView =
    new TimerView(meter.getCount,
                  meter.getMeanRate,
                  meter.getOneMinuteRate,
                  meter.getFiveMinuteRate,
                  meter.getFifteenMinuteRate)
}
