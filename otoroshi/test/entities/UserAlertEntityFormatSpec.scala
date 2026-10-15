package entities

import otoroshi.models.EntityLocation
import otoroshi.next.analytics.models.{AlertCondition, UserAlert}
import play.api.libs.json.*

class UserAlertEntityFormatSpec extends EntityFormatSpec {

  val minimalCondition: AlertCondition = AlertCondition(query = "error_rate_ts")

  val fullCondition: AlertCondition = AlertCondition(
    query = "top_routes",
    params = Json.obj("top_n" -> 5),
    filters = Json.obj("route_id" -> "r1", "apikey_id" -> Json.arr("k1", "k2")),
    reducer = "max",
    operator = "<=",
    threshold = -12.75
  )

  "AlertCondition" should {

    val format = AlertCondition.format

    "average the result and compare it as greater than zero by default" in {
      minimalCondition.params mustBe Json.obj()
      minimalCondition.filters mustBe Json.obj()
      minimalCondition.reducer mustBe "avg"
      minimalCondition.operator mustBe ">"
      minimalCondition.threshold mustBe 0.0
    }

    "write a minimal condition" in {
      format.writes(minimalCondition) mustBe Json.obj(
        "query"     -> "error_rate_ts",
        "params"    -> Json.obj(),
        "filters"   -> Json.obj(),
        "reducer"   -> "avg",
        "operator"  -> ">",
        "threshold" -> 0.0
      )
    }

    "write a condition where every field is set" in {
      format.writes(fullCondition) mustBe Json.obj(
        "query"     -> "top_routes",
        "params"    -> Json.obj("top_n" -> 5),
        "filters"   -> Json.obj("route_id" -> "r1", "apikey_id" -> Json.arr("k1", "k2")),
        "reducer"   -> "max",
        "operator"  -> "<=",
        "threshold" -> -12.75
      )
    }

    "round trip a minimal condition" in {
      roundTrip(format, minimalCondition)
    }

    "round trip a condition where every field is set" in {
      roundTrip(format, fullCondition)
    }

    "round trip every allowed reducer" in {
      UserAlert.ALLOWED_REDUCERS.foreach { reducer =>
        roundTrip(format, minimalCondition.copy(reducer = reducer)).reducer mustBe reducer
      }
    }

    "round trip every allowed operator" in {
      UserAlert.ALLOWED_OPERATORS.foreach { operator =>
        roundTrip(format, minimalCondition.copy(operator = operator)).operator mustBe operator
      }
    }

    "round trip a small decimal threshold" in {
      roundTrip(format, minimalCondition.copy(threshold = 0.05)).threshold mustBe 0.05
    }

    "read a lone query with the default reducer, operator and threshold" in {
      readOk(format, Json.obj("query" -> "error_rate_ts")) mustBe minimalCondition
    }

    "not read a condition without query" in {
      readError(format, written(format, fullCondition) - "query")
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a query that is not a string" in {
      readError(format, written(format, fullCondition) ++ Json.obj("query" -> 1))
    }

    "read an integer threshold as a double" in {
      readOk(format, written(format, fullCondition) ++ Json.obj("threshold" -> 5)).threshold mustBe 5.0
    }

    "ignore a threshold written as a string" in {
      readOk(format, written(format, fullCondition) ++ Json.obj("threshold" -> "5")).threshold mustBe 0.0
    }

    "keep a reducer it does not allow" in {
      roundTrip(format, fullCondition.copy(reducer = "median")).reducer mustBe "median"
    }

    "keep an operator it does not allow" in {
      roundTrip(format, fullCondition.copy(operator = "=~")).operator mustBe "=~"
    }

    "ignore a reducer that is not a string" in {
      readOk(format, written(format, fullCondition) ++ Json.obj("reducer" -> 1)).reducer mustBe "avg"
    }

    "ignore an operator that is not a string" in {
      readOk(format, written(format, fullCondition) ++ Json.obj("operator" -> true)).operator mustBe ">"
    }

    "ignore params that are not an object" in {
      readOk(format, written(format, fullCondition) ++ Json.obj("params" -> Json.arr(5))).params mustBe Json.obj()
    }

    "ignore filters that are not an object" in {
      readOk(format, written(format, fullCondition) ++ Json.obj("filters" -> "r1")).filters mustBe Json.obj()
    }

    "expose through json what the format writes" in {
      fullCondition.json mustBe format.writes(fullCondition)
    }
  }

  "UserAlert" should {

    val format = UserAlert.format

    val minimal = UserAlert(
      location = EntityLocation(),
      id = "alert-1",
      name = "Alert 1",
      description = "",
      tags = Seq.empty,
      metadata = Map.empty,
      enabled = true,
      windowSeconds = UserAlert.DEFAULT_WINDOW_S,
      evaluationIntervalSeconds = UserAlert.DEFAULT_EVAL_S,
      cooldownSeconds = UserAlert.DEFAULT_COOLDOWN_S,
      severity = UserAlert.DEFAULT_SEVERITY,
      message = "",
      combine = "AND",
      conditions = Seq.empty
    )

    val full = UserAlert(
      location = customLocation,
      id = "alert-2",
      name = "Errors on prod",
      description = "too many errors on the prod routes",
      tags = Seq("prod", "errors"),
      metadata = Map("owner" -> "ops", "ticket" -> "42"),
      enabled = false,
      windowSeconds = 900L,
      evaluationIntervalSeconds = 30L,
      cooldownSeconds = 3600L,
      severity = "critical",
      message = "error rate above 5% on ${route}",
      combine = "OR",
      conditions = Seq(fullCondition, minimalCondition)
    )

    "use a 5 minutes window, a 1 minute evaluation and a 10 minutes cooldown by default" in {
      UserAlert.DEFAULT_WINDOW_S mustBe 300L
      UserAlert.DEFAULT_EVAL_S mustBe 60L
      UserAlert.DEFAULT_COOLDOWN_S mustBe 600L
    }

    "use the warning severity by default" in {
      UserAlert.DEFAULT_SEVERITY mustBe "warning"
      UserAlert.ALLOWED_SEVERITIES must contain(UserAlert.DEFAULT_SEVERITY)
    }

    "name the allowed severities, reducers, operators and combines" in {
      UserAlert.ALLOWED_SEVERITIES mustBe Set("info", "warning", "critical")
      UserAlert.ALLOWED_REDUCERS mustBe Set("avg", "max", "min", "sum", "last")
      UserAlert.ALLOWED_OPERATORS mustBe Set(">", ">=", "<", "<=", "==", "!=")
      UserAlert.ALLOWED_COMBINES mustBe Set("AND", "OR")
    }

    "write a minimal alert" in {
      format.writes(minimal) mustBe Json.obj(
        "_loc"                      -> defaultLocationJson,
        "id"                        -> "alert-1",
        "name"                      -> "Alert 1",
        "description"               -> "",
        "tags"                      -> Json.arr(),
        "metadata"                  -> Json.obj(),
        "enabled"                   -> true,
        "windowSeconds"             -> 300,
        "evaluationIntervalSeconds" -> 60,
        "cooldownSeconds"           -> 600,
        "severity"                  -> "warning",
        "message"                   -> "",
        "combine"                   -> "AND",
        "conditions"                -> Json.arr()
      )
    }

    "write an alert where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "_loc"                      -> customLocationJson,
        "id"                        -> "alert-2",
        "name"                      -> "Errors on prod",
        "description"               -> "too many errors on the prod routes",
        "tags"                      -> Json.arr("prod", "errors"),
        "metadata"                  -> Json.obj("owner" -> "ops", "ticket" -> "42"),
        "enabled"                   -> false,
        "windowSeconds"             -> 900,
        "evaluationIntervalSeconds" -> 30,
        "cooldownSeconds"           -> 3600,
        "severity"                  -> "critical",
        "message"                   -> "error rate above 5% on ${route}",
        "combine"                   -> "OR",
        "conditions"                -> Json.arr(fullCondition.json, minimalCondition.json)
      )
    }

    "round trip a minimal alert" in {
      roundTrip(format, minimal)
    }

    "round trip an alert where every field is set" in {
      roundTrip(format, full)
    }

    "round trip every allowed severity" in {
      UserAlert.ALLOWED_SEVERITIES.foreach { severity =>
        roundTrip(format, minimal.copy(severity = severity)).severity mustBe severity
      }
    }

    "round trip the largest durations" in {
      val back = roundTrip(
        format,
        minimal.copy(windowSeconds = Long.MaxValue, evaluationIntervalSeconds = Long.MaxValue, cooldownSeconds = Long.MaxValue)
      )
      back.windowSeconds mustBe Long.MaxValue
    }

    "keep zero and negative durations" in {
      val back = roundTrip(format, minimal.copy(windowSeconds = 0L, evaluationIntervalSeconds = -1L, cooldownSeconds = 0L))
      back.windowSeconds mustBe 0L
      back.evaluationIntervalSeconds mustBe -1L
    }

    "keep the order of the conditions" in {
      roundTrip(format, full.copy(conditions = Seq(minimalCondition, fullCondition))).conditions mustBe Seq(
        minimalCondition,
        fullCondition
      )
    }

    "keep the order of the tags" in {
      roundTrip(format, full.copy(tags = Seq("z", "a", "m"))).tags mustBe Seq("z", "a", "m")
    }

    "round trip a name, a description and a message with quotes and unicode" in {
      roundTrip(format, full.copy(name = "l'équipe \"ops\"", description = "multi\nline", message = "été \"chaud\""))
    }

    "read an empty object as a new enabled alert with the default settings" in {
      val back = readOk(format, Json.obj())
      back mustBe minimal.copy(id = back.id, name = "")
    }

    "generate an alert id for an alert without id" in {
      val id = readOk(format, Json.obj("name" -> "A")).id
      id must startWith("alert_")
      id.split("_").length mustBe 3
    }

    "generate a new id at each read of an alert without id" in {
      readOk(format, Json.obj()).id must not be readOk(format, Json.obj()).id
    }

    "generate an id for an empty id" in {
      readOk(format, written(format, full) ++ Json.obj("id" -> "")).id must startWith("alert_")
    }

    "generate an id for an id that is not a string" in {
      readOk(format, written(format, full) ++ Json.obj("id" -> 42)).id must startWith("alert_")
    }

    "read a json null as a new alert" in {
      val back = readOk(format, JsNull)
      back.id must startWith("alert_")
      back.conditions mustBe empty
    }

    "read a json array as a new alert" in {
      readOk(format, Json.arr(written(format, full))).id must startWith("alert_")
    }

    "read an alert without window with the default 5 minutes window" in {
      readOk(format, written(format, full) - "windowSeconds").windowSeconds mustBe 300L
    }

    "read an alert without evaluation interval with the default 1 minute interval" in {
      readOk(format, written(format, full) - "evaluationIntervalSeconds").evaluationIntervalSeconds mustBe 60L
    }

    "read an alert without cooldown with the default 10 minutes cooldown" in {
      readOk(format, written(format, full) - "cooldownSeconds").cooldownSeconds mustBe 600L
    }

    "ignore durations written as strings" in {
      val back = readOk(
        format,
        written(format, full) ++ Json.obj("windowSeconds" -> "900", "evaluationIntervalSeconds" -> "30", "cooldownSeconds" -> "3600")
      )
      back.windowSeconds mustBe 300L
      back.evaluationIntervalSeconds mustBe 60L
      back.cooldownSeconds mustBe 600L
    }

    "ignore a decimal duration" in {
      readOk(format, written(format, full) ++ Json.obj("windowSeconds" -> 1.5)).windowSeconds mustBe 300L
    }

    "not read the durations from snake case keys" in {
      readOk(format, written(format, full) - "windowSeconds" ++ Json.obj("window_seconds" -> 900)).windowSeconds mustBe 300L
    }

    "read an alert without severity as a warning" in {
      readOk(format, written(format, full) - "severity").severity mustBe "warning"
    }

    "keep a severity it does not allow" in {
      roundTrip(format, full.copy(severity = "fatal")).severity mustBe "fatal"
    }

    "ignore a severity that is not a string" in {
      readOk(format, written(format, full) ++ Json.obj("severity" -> 3)).severity mustBe "warning"
    }

    "read an alert without combine as an AND of its conditions" in {
      readOk(format, written(format, full) - "combine").combine mustBe "AND"
    }

    "keep a combine it does not allow, lower case included" in {
      roundTrip(format, full.copy(combine = "or")).combine mustBe "or"
      roundTrip(format, full.copy(combine = "XOR")).combine mustBe "XOR"
    }

    "read an alert without message with an empty message" in {
      readOk(format, written(format, full) - "message").message mustBe ""
    }

    "read an alert without name nor description with empty ones" in {
      val back = readOk(format, written(format, full) - "name" - "description")
      back.name mustBe ""
      back.description mustBe ""
    }

    "read an alert without enabled flag as enabled" in {
      readOk(format, written(format, full) - "enabled").enabled mustBe true
    }

    "ignore an enabled flag that is not a boolean" in {
      readOk(format, written(format, full) ++ Json.obj("enabled" -> "false")).enabled mustBe true
    }

    "read an alert without conditions with no condition" in {
      readOk(format, written(format, full) - "conditions").conditions mustBe empty
    }

    "skip the conditions that cannot be read" in {
      val conditions = Json.arr(fullCondition.json, Json.obj("reducer" -> "max"), "error_rate_ts", minimalCondition.json)
      readOk(format, written(format, full) ++ Json.obj("conditions" -> conditions)).conditions mustBe Seq(
        fullCondition,
        minimalCondition
      )
    }

    "ignore conditions that are not an array" in {
      readOk(format, written(format, full) ++ Json.obj("conditions" -> fullCondition.json)).conditions mustBe empty
    }

    "read an alert without tags with no tags" in {
      readOk(format, written(format, full) - "tags").tags mustBe empty
    }

    "read an alert without metadata with no metadata" in {
      readOk(format, written(format, full) - "metadata").metadata mustBe empty
    }

    "ignore tags given as a single string" in {
      readOk(format, written(format, full) ++ Json.obj("tags" -> "prod")).tags mustBe empty
    }

    "ignore metadata holding a non string value" in {
      readOk(format, written(format, full) ++ Json.obj("metadata" -> Json.obj("a" -> "b", "n" -> 1))).metadata mustBe empty
    }

    "ignore unknown keys" in {
      readOk(format, written(format, full) ++ Json.obj("lastFiredAt" -> 1234, "foo" -> "bar")) mustBe full
    }

    "round trip a custom location" in {
      roundTrip(format, minimal.copy(location = customLocation)).location mustBe customLocation
    }

    "read an alert without _loc in the default location" in {
      readOk(format, written(format, full) - "_loc").location mustBe EntityLocation()
    }

    "read an alert whose _loc is not an object in the default location" in {
      readOk(format, written(format, full) ++ Json.obj("_loc" -> "tenant-1")).location mustBe EntityLocation()
    }

    "expose its fields through the generic accessors" in {
      hasAccessors(
        full,
        "alert-2",
        "Errors on prod",
        "too many errors on the prod routes",
        Seq("prod", "errors"),
        Map("owner" -> "ops", "ticket" -> "42")
      )
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
    }
  }
}
