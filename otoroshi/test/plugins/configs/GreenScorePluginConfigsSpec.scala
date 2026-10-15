package plugins.configs

import otoroshi.greenscore.*
import play.api.libs.json.*

class GreenScorePluginConfigsSpec extends PluginConfigFormatSpec {

  "RulesRouteConfiguration" should {

    val format = RulesRouteConfiguration.format
    val record = RuleStateRecord(
      date = 1700000000000L,
      states = Seq(RuleState(RuleId("AR01"), enabled = true), RuleState(RuleId("DE02"), enabled = false))
    )

    "have no rule state record by default" in {
      RulesRouteConfiguration().states mustBe empty
    }

    "write the default config as an empty states array" in {
      format.writes(RulesRouteConfiguration()) mustBe Json.obj("states" -> Json.arr())
    }

    "write the records with their date and rule states" in {
      format.writes(RulesRouteConfiguration(Seq(record))) mustBe Json.obj(
        "states" -> Json.arr(
          Json.obj(
            "date"   -> 1700000000000L,
            "states" -> Json.arr(
              Json.obj("id" -> "AR01", "enabled" -> true),
              Json.obj("id" -> "DE02", "enabled" -> false)
            )
          )
        )
      )
    }

    "round trip the default config" in {
      roundTrip(format, RulesRouteConfiguration())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, RulesRouteConfiguration(Seq(record)))
    }

    "round trip several records and keep their order" in {
      val records = Seq(
        RuleStateRecord(3L, Seq(RuleState(RuleId("US01")))),
        RuleStateRecord(1L, Seq(RuleState(RuleId("LO01"), enabled = false))),
        RuleStateRecord(2L, Seq.empty)
      )
      roundTrip(format, RulesRouteConfiguration(records)).states.map(_.date) mustBe Seq(3L, 1L, 2L)
    }

    "round trip a record without rule state" in {
      roundTrip(format, RulesRouteConfiguration(Seq(RuleStateRecord(42L, Seq.empty))))
    }

    "round trip a record holding every known rule" in {
      val states = RulesManager.rules.map(r => RuleState(r.id, enabled = r.weight > 10))
      roundTrip(format, RulesRouteConfiguration(Seq(RuleStateRecord(1L, states)))).states.head.states mustBe states
    }

    "round trip duplicated rule ids" in {
      roundTrip(
        format,
        RulesRouteConfiguration(Seq(RuleStateRecord(1L, Seq(RuleState(RuleId("AR01")), RuleState(RuleId("AR01"), false)))))
      )
    }

    "round trip extreme dates" in {
      roundTrip(format, RulesRouteConfiguration(Seq(RuleStateRecord(Long.MaxValue, Seq.empty), RuleStateRecord(-1L, Seq.empty))))
    }

    "read a rule state without enabled flag as enabled" in {
      readOk(
        format,
        Json.obj("states" -> Json.arr(Json.obj("date" -> 1, "states" -> Json.arr(Json.obj("id" -> "AR01")))))
      ) mustBe RulesRouteConfiguration(Seq(RuleStateRecord(1L, Seq(RuleState(RuleId("AR01"), enabled = true)))))
    }

    "read an enabled flag written as a string as enabled" in {
      readOk(
        format,
        Json.obj(
          "states" -> Json.arr(Json.obj("date" -> 1, "states" -> Json.arr(Json.obj("id" -> "AR01", "enabled" -> "false"))))
        )
      ).states.head.states mustBe Seq(RuleState(RuleId("AR01"), enabled = true))
    }

    "read a record whose rule states are not an array as a record without state" in {
      readOk(
        format,
        Json.obj("states" -> Json.arr(Json.obj("date" -> 1, "states" -> Json.obj("id" -> "AR01"))))
      ) mustBe RulesRouteConfiguration(Seq(RuleStateRecord(1L, Seq.empty)))
    }

    "read a record without rule states as a record without state" in {
      readOk(format, Json.obj("states" -> Json.arr(Json.obj("date" -> 1)))) mustBe RulesRouteConfiguration(
        Seq(RuleStateRecord(1L, Seq.empty))
      )
    }

    "drop every rule state of a record when one of them has no id" in {
      readOk(
        format,
        Json.obj(
          "states" -> Json.arr(
            Json.obj("date" -> 1, "states" -> Json.arr(Json.obj("id" -> "AR01"), Json.obj("enabled" -> false)))
          )
        )
      ) mustBe RulesRouteConfiguration(Seq(RuleStateRecord(1L, Seq.empty)))
    }

    "drop every record when one of them has no date" in {
      readOk(
        format,
        Json.obj(
          "states" -> Json.arr(
            Json.obj("date" -> 1, "states" -> Json.arr()),
            Json.obj("states" -> Json.arr(Json.obj("id" -> "AR01")))
          )
        )
      ) mustBe RulesRouteConfiguration()
    }

    "drop every record when a date is written as a string" in {
      readOk(
        format,
        Json.obj("states" -> Json.arr(Json.obj("date" -> "1700000000000", "states" -> Json.arr())))
      ) mustBe RulesRouteConfiguration()
    }

    "ignore states that are not an array" in {
      readOk(format, Json.obj("states" -> Json.obj("date" -> 1, "states" -> Json.arr()))) mustBe RulesRouteConfiguration()
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe RulesRouteConfiguration()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe RulesRouteConfiguration()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("AR01")) mustBe RulesRouteConfiguration()
    }

    "read a json array of records as the default config" in {
      readOk(format, Json.arr(Json.obj("date" -> 1, "states" -> Json.arr()))) mustBe RulesRouteConfiguration()
    }

    "ignore unknown keys" in {
      readOk(
        format,
        Json.obj("states" -> Json.arr(Json.obj("date" -> 1, "states" -> Json.arr(), "foo" -> "bar")), "foo" -> 1)
      ) mustBe RulesRouteConfiguration(Seq(RuleStateRecord(1L, Seq.empty)))
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, RulesRouteConfiguration(Seq(record)))
    }

    "read a defined lookup through readFrom" in {
      val parent = Json.obj("rulesConfig" -> format.writes(RulesRouteConfiguration(Seq(record))))
      RulesRouteConfiguration.readFrom(parent \ "rulesConfig") mustBe RulesRouteConfiguration(Seq(record))
    }

    "read an undefined lookup as the default config through readFrom" in {
      RulesRouteConfiguration.readFrom(Json.obj() \ "rulesConfig") mustBe RulesRouteConfiguration()
    }

    "read a lookup that is not an object as the default config through readFrom" in {
      RulesRouteConfiguration.readFrom(Json.obj("rulesConfig" -> 42) \ "rulesConfig") mustBe RulesRouteConfiguration()
    }
  }
}
