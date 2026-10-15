package entities

import otoroshi.greenscore.*
import otoroshi.models.EntityLocation
import play.api.libs.json.*

class GreenScoreEntityFormatSpec extends EntityFormatSpec {

  private def bounds(excellent: Int, sufficient: Int, poor: Int): JsObject =
    Json.obj("excellent" -> excellent, "sufficient" -> sufficient, "poor" -> poor)

  private val defaultThresholdsJson = Json.obj(
    "overhead"        -> bounds(5, 10, 15),
    "duration"        -> bounds(5, 10, 15),
    "backendDuration" -> bounds(5, 10, 15),
    "calls"           -> bounds(5, 10, 15),
    "dataIn"          -> bounds(100, 500, 1000),
    "dataOut"         -> bounds(100, 500, 1000),
    "headersOut"      -> bounds(10, 30, 50),
    "headersIn"       -> bounds(10, 30, 50)
  )

  private val customThresholds = Thresholds(
    overhead = TripleBounds(1, 2, 3),
    duration = TripleBounds(4, 5, 6),
    backendDuration = TripleBounds(7, 8, 9),
    calls = TripleBounds(10, 11, 12),
    dataIn = TripleBounds(13, 14, 15),
    dataOut = TripleBounds(16, 17, 18),
    headersOut = TripleBounds(19, 20, 21),
    headersIn = TripleBounds(22, 23, 24)
  )

  private val rulesConfig = RulesRouteConfiguration(
    Seq(
      RuleStateRecord(1000L, Seq(RuleState(RuleId("AR01"), enabled = true), RuleState(RuleId("DE02"), enabled = false))),
      RuleStateRecord(2000L, Seq(RuleState(RuleId("LO01"), enabled = true)))
    )
  )

  private val minimal = GreenScoreEntity(
    location = EntityLocation(),
    id = "green-score-1",
    name = "Group 1",
    description = "",
    tags = Seq.empty,
    metadata = Map.empty,
    routes = Seq.empty
  )

  private val full = GreenScoreEntity(
    location = customLocation,
    id = "green-score-1",
    name = "Group 1",
    description = "the routes of team 1",
    tags = Seq("green", "eu"),
    metadata = Map("owner" -> "ops", "cost-center" -> "42"),
    routes = Seq(RouteRules("route-1", rulesConfig), RouteRules("route-2", RulesRouteConfiguration())),
    thresholds = customThresholds,
    efficiency = Efficiency(Seq("/health", "/metrics"))
  )

  /** the json of a group with the given fields, the rest taken from the minimal group */
  private def minimalJson(fields: (String, Json.JsValueWrapper)*): JsObject =
    written(GreenScoreEntity.format, minimal) ++ Json.obj(fields*)

  "GreenScoreEntity" should {

    val format = GreenScoreEntity.format

    "write a minimal group with the default thresholds and efficiency" in {
      format.writes(minimal) mustBe Json.obj(
        "_loc"        -> defaultLocationJson,
        "id"          -> "green-score-1",
        "name"        -> "Group 1",
        "description" -> "",
        "metadata"    -> Json.obj(),
        "tags"        -> Json.arr(),
        "routes"      -> Json.arr(),
        "thresholds"  -> defaultThresholdsJson,
        "efficiency"  -> Json.obj("paths" -> Json.arr())
      )
    }

    "write the same keys whatever the fields hold" in {
      writtenKeys(format, full) mustBe writtenKeys(format, minimal)
    }

    "write the routes with their id and rules config" in {
      (written(format, full) \ "routes").as[JsArray] mustBe Json.arr(
        Json.obj(
          "routeId"     -> "route-1",
          "rulesConfig" -> Json.obj(
            "states" -> Json.arr(
              Json.obj(
                "date"   -> 1000L,
                "states" -> Json.arr(
                  Json.obj("id" -> "AR01", "enabled" -> true),
                  Json.obj("id" -> "DE02", "enabled" -> false)
                )
              ),
              Json.obj("date" -> 2000L, "states" -> Json.arr(Json.obj("id" -> "LO01", "enabled" -> true)))
            )
          )
        ),
        Json.obj("routeId" -> "route-2", "rulesConfig" -> Json.obj("states" -> Json.arr()))
      )
    }

    "write the excluded paths of the efficiency under the paths key" in {
      (written(format, full) \ "efficiency").as[JsObject] mustBe Json.obj("paths" -> Json.arr("/health", "/metrics"))
    }

    "write the custom thresholds" in {
      (written(format, full) \ "thresholds" \ "headersIn").as[JsObject] mustBe bounds(22, 23, 24)
    }

    "round trip a minimal group" in {
      roundTrip(format, minimal)
    }

    "round trip a group where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a route without rule state record" in {
      roundTrip(format, minimal.copy(routes = Seq(RouteRules("route-1", RulesRouteConfiguration())))).routes mustBe Seq(
        RouteRules("route-1", RulesRouteConfiguration())
      )
    }

    "keep the order of the routes" in {
      val routes = Seq("route-c", "route-a", "route-b").map(id => RouteRules(id, RulesRouteConfiguration()))
      roundTrip(format, minimal.copy(routes = routes)).routes.map(_.routeId) mustBe Seq("route-c", "route-a", "route-b")
    }

    "keep the order of the excluded paths" in {
      roundTrip(format, minimal.copy(efficiency = Efficiency(Seq("/z", "/a")))).efficiency.excludedPaths mustBe Seq("/z", "/a")
    }

    "round trip a custom location" in {
      roundTrip(format, minimal.copy(location = customLocation)).location mustBe customLocation
    }

    "read a group without _loc in the default location" in {
      readOk(format, minimalJson() - "_loc").location mustBe EntityLocation()
    }

    "read the id, name, description and thresholds alone with defaults for the rest" in {
      readOk(
        format,
        Json.obj("id" -> "g", "name" -> "G", "description" -> "d", "thresholds" -> defaultThresholdsJson)
      ) mustBe GreenScoreEntity(EntityLocation(), "g", "G", "d", Seq.empty, Map.empty, Seq.empty)
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a group without id" in {
      readError(format, minimalJson() - "id")
    }

    "not read a group without name" in {
      readError(format, minimalJson() - "name")
    }

    "not read a group without description" in {
      readError(format, minimalJson() - "description")
    }

    "not read an id that is not a string" in {
      readError(format, minimalJson("id" -> 42))
    }

    "not read a group without thresholds, although they have a default" in {
      readError(format, minimalJson() - "thresholds")
    }

    "not read a group whose thresholds miss a metric" in {
      readError(format, minimalJson("thresholds" -> (defaultThresholdsJson - "headersIn")))
    }

    "not read a group whose thresholds hold a bound written as a string" in {
      readError(
        format,
        minimalJson("thresholds" -> (defaultThresholdsJson ++ Json.obj("calls" -> Json.obj("excellent" -> "5", "sufficient" -> 10, "poor" -> 15))))
      )
    }

    "read a group without routes as a group without route" in {
      readOk(format, minimalJson() - "routes").routes mustBe empty
    }

    "read routes that are not an array as no route" in {
      readOk(format, minimalJson("routes" -> Json.obj("routeId" -> "route-1"))).routes mustBe empty
    }

    "not read a group holding a route that is not an object" in {
      readError(format, minimalJson("routes" -> Json.arr("route-1")))
    }

    "not read a route without routeId" in {
      readError(format, minimalJson("routes" -> Json.arr(Json.obj("rulesConfig" -> Json.obj()))))
    }

    "not read a route without rules config" in {
      readError(format, minimalJson("routes" -> Json.arr(Json.obj("routeId" -> "route-1"))))
    }

    "not read a route whose rules config is not an object" in {
      readError(format, minimalJson("routes" -> Json.arr(Json.obj("routeId" -> "route-1", "rulesConfig" -> Json.arr()))))
    }

    "read a route whose rules config is empty as a route without rule state record" in {
      readOk(format, minimalJson("routes" -> Json.arr(Json.obj("routeId" -> "route-1", "rulesConfig" -> Json.obj())))).routes mustBe Seq(
        RouteRules("route-1", RulesRouteConfiguration())
      )
    }

    "read a group without efficiency with the default efficiency" in {
      readOk(format, minimalJson() - "efficiency").efficiency mustBe Efficiency()
    }

    "read an efficiency without paths as the default efficiency" in {
      readOk(format, minimalJson("efficiency" -> Json.obj())).efficiency mustBe Efficiency()
    }

    "read an efficiency whose paths are not strings as the default efficiency" in {
      readOk(format, minimalJson("efficiency" -> Json.obj("paths" -> Json.arr(1, 2)))).efficiency mustBe Efficiency()
    }

    "not read the excluded paths under their field name" in {
      readOk(format, minimalJson("efficiency" -> Json.obj("excludedPaths" -> Json.arr("/health")))).efficiency mustBe Efficiency()
    }

    "read a group without tags nor metadata with empty ones" in {
      val back = readOk(format, minimalJson() - "tags" - "metadata")
      back.tags mustBe empty
      back.metadata mustBe empty
    }

    "ignore metadata holding a non string value" in {
      readOk(format, minimalJson("metadata" -> Json.obj("a" -> "b", "n" -> 1))).metadata mustBe Map.empty
    }

    "ignore tags given as a single string" in {
      readOk(format, minimalJson("tags" -> "green")).tags mustBe empty
    }

    "not read a description that is not a string" in {
      readError(format, minimalJson("description" -> 1))
    }

    "ignore unknown keys" in {
      readOk(format, minimalJson("foo" -> "bar")) mustBe minimal
    }

    "expose its fields through the generic accessors" in {
      hasAccessors(full, "green-score-1", "Group 1", "the routes of team 1", Seq("green", "eu"), Map("owner" -> "ops", "cost-center" -> "42"))
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
    }
  }

  "Thresholds" should {

    val format = Thresholds.fmt

    "have default bounds for each metric" in {
      Thresholds() mustBe Thresholds(
        overhead = TripleBounds(5, 10, 15),
        duration = TripleBounds(5, 10, 15),
        backendDuration = TripleBounds(5, 10, 15),
        calls = TripleBounds(5, 10, 15),
        dataIn = TripleBounds(100, 500, 1000),
        dataOut = TripleBounds(100, 500, 1000),
        headersOut = TripleBounds(10, 30, 50),
        headersIn = TripleBounds(10, 30, 50)
      )
    }

    "write the default thresholds" in {
      format.writes(Thresholds()) mustBe defaultThresholdsJson
    }

    "expose through json what the format writes" in {
      customThresholds.json() mustBe format.writes(customThresholds)
    }

    "round trip the default thresholds" in {
      roundTrip(format, Thresholds())
    }

    "round trip thresholds where every metric is set" in {
      roundTrip(format, customThresholds)
    }

    "read each metric from its own key" in {
      val back = readOk(format, format.writes(customThresholds))
      back.backendDuration mustBe TripleBounds(7, 8, 9)
      back.headersOut mustBe TripleBounds(19, 20, 21)
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read thresholds missing one metric" in {
      readError(format, defaultThresholdsJson - "dataOut")
    }

    "not read a metric given as a number" in {
      readError(format, defaultThresholdsJson ++ Json.obj("overhead" -> 5))
    }
  }

  "TripleBounds" should {

    val format = TripleBounds.fmt

    "be zero by default" in {
      TripleBounds() mustBe TripleBounds(0, 0, 0)
    }

    "write its three bounds" in {
      format.writes(TripleBounds(1, 2, 3)) mustBe bounds(1, 2, 3)
    }

    "round trip its bounds" in {
      roundTrip(format, TripleBounds(1, 2, 3))
    }

    "round trip negative bounds" in {
      roundTrip(format, TripleBounds(-1, 0, Int.MaxValue))
    }

    "not read bounds missing the poor one" in {
      readError(format, Json.obj("excellent" -> 1, "sufficient" -> 2))
    }

    "not read an empty object as zero bounds" in {
      readError(format, Json.obj())
    }

    "not read a bound written as a string" in {
      readError(format, Json.obj("excellent" -> "1", "sufficient" -> 2, "poor" -> 3))
    }

    "not read a decimal bound" in {
      readError(format, Json.obj("excellent" -> 1.5, "sufficient" -> 2, "poor" -> 3))
    }

    "add two bounds field by field" in {
      TripleBounds(1, 2, 3) + TripleBounds(10, 20, 30) mustBe TripleBounds(11, 22, 33)
    }

    "count a value up to the excellent threshold as excellent" in {
      val thresholds = TripleBounds(5, 10, 15)
      TripleBounds().incr(5, thresholds) mustBe TripleBounds(1, 0, 0)
    }

    "count a value up to the sufficient threshold as sufficient" in {
      val thresholds = TripleBounds(5, 10, 15)
      TripleBounds().incr(6, thresholds) mustBe TripleBounds(0, 1, 0)
      TripleBounds().incr(10, thresholds) mustBe TripleBounds(0, 1, 0)
    }

    "count a value above the sufficient threshold as poor, even below the poor threshold" in {
      val thresholds = TripleBounds(5, 10, 15)
      TripleBounds().incr(11, thresholds) mustBe TripleBounds(0, 0, 1)
      TripleBounds().incr(100, thresholds) mustBe TripleBounds(0, 0, 1)
    }

    "keep the previous counts when counting a value" in {
      TripleBounds(3, 2, 1).incr(0, TripleBounds(5, 10, 15)) mustBe TripleBounds(4, 2, 1)
    }
  }

  "Efficiency" should {

    val format = Efficiency.fmt

    "have no excluded path by default" in {
      Efficiency().excludedPaths mustBe empty
    }

    "write the default efficiency" in {
      format.writes(Efficiency()) mustBe Json.obj("paths" -> Json.arr())
    }

    "round trip excluded paths" in {
      roundTrip(format, Efficiency(Seq("/health", "/metrics")))
    }

    "not read an efficiency without paths" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read paths given as a single string" in {
      readError(format, Json.obj("paths" -> "/health"))
    }
  }

  "Rule" should {

    val format = Rule.fmt

    val rule = Rule(RuleId("AR01"), "architecture", Some("a description"), Some("an advice"), 25, 25)

    "write a rule with its section weight in snake case" in {
      format.writes(Seq(rule)) mustBe Json.arr(
        Json.obj(
          "id"             -> "AR01",
          "section"        -> "architecture",
          "description"    -> "a description",
          "advice"         -> "an advice",
          "weight"         -> 25.0,
          "section_weight" -> 25.0
        )
      )
    }

    "write a missing description and advice as null" in {
      val json = format.writes(Seq(Rule(RuleId("X"), "design", None, None, 1, 2))).as[JsArray].value.head
      (json \ "description").get mustBe JsNull
      (json \ "advice").get mustBe JsNull
    }

    "expose through json what the format writes" in {
      format.writes(Seq(rule)) mustBe Json.arr(rule.json())
    }

    "round trip a rule" in {
      roundTrip(format, Seq(rule))
    }

    "round trip a rule without description nor advice" in {
      roundTrip(format, Seq(Rule(RuleId("X"), "design", None, None, 2.5, 40)))
    }

    "round trip the built in rules" in {
      roundTrip(format, RulesManager.rules)
    }

    "read a rule without description nor advice" in {
      readOk(format, Json.arr(Json.obj("id" -> "X", "section" -> "log", "weight" -> 1, "section_weight" -> 10))) mustBe Seq(
        Rule(RuleId("X"), "log", None, None, 1, 10)
      )
    }

    "not read a rule without section weight" in {
      readError(format, Json.arr(Json.obj("id" -> "X", "section" -> "log", "weight" -> 1)))
    }

    "not read the section weight under its camel case name" in {
      readError(format, Json.arr(Json.obj("id" -> "X", "section" -> "log", "weight" -> 1, "sectionWeight" -> 10)))
    }

    "not read a single rule object" in {
      readError(format, rule.json())
    }

    "not read every rule when one of them has no id" in {
      readError(format, Json.arr(rule.json(), Json.obj("section" -> "log", "weight" -> 1, "section_weight" -> 10)))
    }
  }

  "RulesManager" should {

    "hold rules with unique ids" in {
      RulesManager.rules.map(_.id).distinct.size mustBe RulesManager.rules.size
    }

    "give each section the same section weight in every rule" in {
      RulesManager.rules.groupBy(_.section).view.mapValues(_.map(_.sectionWeight).distinct).toMap mustBe Map(
        "architecture" -> Seq(25.0),
        "design"       -> Seq(40.0),
        "usage"        -> Seq(25.0),
        "log"          -> Seq(10.0)
      )
    }

    "share the score between sections whose weights sum to 100" in {
      RulesManager.rules.groupBy(_.section).values.map(_.head.sectionWeight).sum mustBe 100.0
    }

    "give the rules of each section weights summing to 100" in {
      RulesManager.rules.groupBy(_.section).view.mapValues(_.map(_.weight).sum).toMap mustBe Map(
        "architecture" -> 100.0,
        "design"       -> 100.0,
        "usage"        -> 100.0,
        "log"          -> 100.0
      )
    }
  }

  "the global score of green score groups" should {

    "be empty without group" in {
      new EcoMetrics().calculateGlobalScore(Seq.empty) mustBe GroupScore(Seq.empty, Dynamicvalues(), Seq.empty)
    }

    "be empty for a group without route" in {
      val score = new EcoMetrics().calculateGlobalScore(Seq(minimal))
      score.dynamicValuesByRoutes mustBe empty
      score.routeScoreByDate mustBe empty
    }

    "give the dynamic values of each route of a group" in {
      val score = new EcoMetrics().calculateGlobalScore(Seq(full))
      score.dynamicValuesByRoutes.map(v => (v.routeId, v.groupId)) mustBe Seq(
        ("route-1", "green-score-1"),
        ("route-2", "green-score-1")
      )
    }

    "score the routes at the dates of their rule state records" in {
      val score = new EcoMetrics().calculateGlobalScore(Seq(full))
      score.routeScoreByDate.map(_.date).sorted mustBe Seq(1000L, 2000L)
      score.routeScoreByDate.flatMap(_.routes.map(r => (r.groupId, r.routeId))).distinct mustBe Seq(("green-score-1", "route-1"))
    }

    "score a section from the weights of its enabled rules" in {
      val group    = minimal.copy(
        routes = Seq(RouteRules("route-1", RulesRouteConfiguration(Seq(RuleStateRecord(1000L, Seq(RuleState(RuleId("AR01"))))))))
      )
      val sections = new EcoMetrics().calculateGlobalScore(Seq(group)).routeScoreByDate.head.routes.head.scores
      val arch     = sections.find(_.section == "architecture").get
      arch.score.score mustBe 375.0
      arch.letter mustBe "D"
      sections.find(_.section == "log").get.score.score mustBe 0.0
    }
  }
}
