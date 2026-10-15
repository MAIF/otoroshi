package plugins.configs

import org.joda.time.{DateTime, LocalTime}
import otoroshi.next.plugins.*
import play.api.libs.json.*

class TimePluginConfigsSpec extends PluginConfigFormatSpec {

  // 2024-01-01 is a monday
  private def at(day: Int, hour: Int, minute: Int = 0): DateTime = new DateTime(2024, 1, day, hour, minute)

  "TimeRestrictedAccessPluginConfigRule" should {

    val format = TimeRestrictedAccessPluginConfigRule.format

    val weekend = TimeRestrictedAccessPluginConfigRule(new LocalTime(10, 30), new LocalTime(18, 45, 15), 6, 7)

    "write the default rule as working hours from monday to friday" in {
      format.writes(TimeRestrictedAccessPluginConfigRule()) mustBe Json.obj(
        "time_start" -> "08:00:00.000",
        "time_end"   -> "20:00:00.000",
        "day_start"  -> 1,
        "day_end"    -> 5
      )
    }

    "round trip the default rule" in {
      roundTrip(format, TimeRestrictedAccessPluginConfigRule())
    }

    "round trip a rule where every field is set" in {
      roundTrip(format, weekend)
    }

    "write the times with their seconds and millis" in {
      written(format, weekend).value("time_end") mustBe JsString("18:45:15.000")
    }

    "round trip times with millis" in {
      roundTrip(format, TimeRestrictedAccessPluginConfigRule(timeStart = new LocalTime(0, 0, 0, 1))).timeStart mustBe
        new LocalTime(0, 0, 0, 1)
    }

    "read times without seconds" in {
      val rule = readOk(format, Json.obj("time_start" -> "09:15", "time_end" -> "17:00"))
      rule.timeStart mustBe new LocalTime(9, 15)
      rule.timeEnd mustBe new LocalTime(17, 0)
    }

    "read the days alone with the times and default nothing else" in {
      readOk(
        format,
        Json.obj("time_start" -> "08:00", "time_end" -> "20:00", "day_start" -> 3, "day_end" -> 4)
      ) mustBe TimeRestrictedAccessPluginConfigRule(dayStart = 3, dayEnd = 4)
    }

    "read missing days as monday to friday" in {
      val rule = readOk(format, Json.obj("time_start" -> "08:00", "time_end" -> "20:00"))
      rule.dayStart mustBe 1
      rule.dayEnd mustBe 5
    }

    "read a missing time as the current time instead of the default hours" in {
      val before = LocalTime.now()
      val rule   = readOk(format, Json.obj())
      val after  = LocalTime.now()
      rule.timeStart.isBefore(before) mustBe false
      rule.timeStart.isAfter(after) mustBe false
      rule.timeEnd.isBefore(before) mustBe false
      rule.timeEnd.isAfter(after) mustBe false
    }

    "refuse a time it cannot parse" in {
      readError(format, Json.obj("time_start" -> "8h", "time_end" -> "20:00"))
    }

    "refuse an out of range time" in {
      readError(format, Json.obj("time_start" -> "25:00", "time_end" -> "20:00"))
    }

    "ignore a day written as a string" in {
      readOk(format, Json.obj("time_start" -> "08:00", "time_end" -> "20:00", "day_start" -> "3")).dayStart mustBe 1
    }

    "keep days out of the week range as is" in {
      roundTrip(format, TimeRestrictedAccessPluginConfigRule(dayStart = 0, dayEnd = 9))
    }

    "accept a day within its day range" in {
      TimeRestrictedAccessPluginConfigRule().dayOk(at(1, 12)) mustBe true
      TimeRestrictedAccessPluginConfigRule().dayOk(at(5, 12)) mustBe true
    }

    "refuse a day out of its day range" in {
      TimeRestrictedAccessPluginConfigRule().dayOk(at(6, 12)) mustBe false
      TimeRestrictedAccessPluginConfigRule().dayOk(at(7, 12)) mustBe false
    }

    "accept a time strictly within its time range" in {
      TimeRestrictedAccessPluginConfigRule().timeOk(at(1, 8, 1)) mustBe true
      TimeRestrictedAccessPluginConfigRule().timeOk(at(1, 19, 59)) mustBe true
    }

    "refuse the bounds of its time range" in {
      TimeRestrictedAccessPluginConfigRule().timeOk(at(1, 8)) mustBe false
      TimeRestrictedAccessPluginConfigRule().timeOk(at(1, 20)) mustBe false
    }

    "never accept a time range crossing midnight" in {
      val night = TimeRestrictedAccessPluginConfigRule(timeStart = new LocalTime(22, 0), timeEnd = new LocalTime(6, 0))
      night.timeOk(at(1, 23)) mustBe false
      night.timeOk(at(2, 3)) mustBe false
    }

    "expose through json what the format writes" in {
      weekend.json mustBe format.writes(weekend)
    }
  }

  "TimeRestrictedAccessPluginConfig" should {

    val format = TimeRestrictedAccessPluginConfig.format

    val rule    = TimeRestrictedAccessPluginConfigRule()
    val weekend = TimeRestrictedAccessPluginConfigRule(new LocalTime(10, 0), new LocalTime(16, 0), 6, 7)

    val full = TimeRestrictedAccessPluginConfig(rules = Seq(rule, weekend), timezone = Some("Europe/Paris"))

    "write the default config with no rule and a null timezone" in {
      format.writes(TimeRestrictedAccessPluginConfig()) mustBe Json.obj(
        "rules"    -> Json.arr(),
        "timezone" -> JsNull
      )
    }

    "round trip the default config" in {
      roundTrip(format, TimeRestrictedAccessPluginConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "rules"    -> Json.arr(
          Json.obj("time_start" -> "08:00:00.000", "time_end" -> "20:00:00.000", "day_start" -> 1, "day_end" -> 5),
          Json.obj("time_start" -> "10:00:00.000", "time_end" -> "16:00:00.000", "day_start" -> 6, "day_end" -> 7)
        ),
        "timezone" -> "Europe/Paris"
      )
    }

    "round trip several rules and keep their order" in {
      roundTrip(format, TimeRestrictedAccessPluginConfig(rules = Seq(weekend, rule))).rules mustBe Seq(weekend, rule)
    }

    "round trip a timezone alone" in {
      roundTrip(format, TimeRestrictedAccessPluginConfig(timezone = Some("UTC"))).timezone mustBe Some("UTC")
    }

    "round trip a timezone it does not know as is" in {
      roundTrip(format, TimeRestrictedAccessPluginConfig(timezone = Some("Mars/Olympus"))).timezone mustBe
        Some("Mars/Olympus")
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe TimeRestrictedAccessPluginConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe TimeRestrictedAccessPluginConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("Europe/Paris")) mustBe TimeRestrictedAccessPluginConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr()) mustBe TimeRestrictedAccessPluginConfig()
    }

    "read the rules alone and have no timezone" in {
      readOk(format, Json.obj("rules" -> Json.arr(rule.json))) mustBe TimeRestrictedAccessPluginConfig(rules = Seq(rule))
    }

    "read a blank timezone as no timezone" in {
      readOk(format, Json.obj("timezone" -> "  ")).timezone mustBe None
    }

    "read a null timezone as no timezone" in {
      readOk(format, Json.obj("timezone" -> JsNull)).timezone mustBe None
    }

    "ignore a timezone that is not a string" in {
      readOk(format, Json.obj("timezone" -> 2)).timezone mustBe None
    }

    "drop a rule it cannot parse and keep the others" in {
      val json = Json.obj("rules" -> Json.arr(Json.obj("time_start" -> "nope"), weekend.json))
      readOk(format, json).rules mustBe Seq(weekend)
    }

    "ignore all the rules as soon as one of them is not an object" in {
      readOk(format, Json.obj("rules" -> Json.arr(rule.json, "08:00-20:00"))).rules mustBe empty
    }

    "ignore rules that are not an array" in {
      readOk(format, Json.obj("rules" -> rule.json)).rules mustBe empty
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "write exactly the keys of its config flow" in {
      writtenKeys(format, full) mustBe TimeRestrictedAccessPluginConfig.configFlow.toSet
    }

    "describe every config flow field in its config schema" in {
      TimeRestrictedAccessPluginConfig.configSchema.get.keys mustBe TimeRestrictedAccessPluginConfig.configFlow.toSet
    }

    "describe the rule form with the keys the rule format writes" in {
      val rules = (TimeRestrictedAccessPluginConfig.configSchema.get \ "rules").as[JsObject]
      (rules \ "flow").as[Seq[String]].toSet mustBe writtenKeys(TimeRestrictedAccessPluginConfigRule.format, rule)
      (rules \ "schema").as[JsObject].keys mustBe writtenKeys(TimeRestrictedAccessPluginConfigRule.format, rule)
    }

    "be the default config of TimeRestrictedAccessPlugin" in {
      defaultConfigReadsBack(new TimeRestrictedAccessPlugin(), format) mustBe TimeRestrictedAccessPluginConfig()
    }
  }
}
