package otoroshi.greenscore

import com.typesafe.config.ConfigFactory
import functional.OtoroshiSpec
import org.joda.time.DateTime
import otoroshi.greenscore.EcoMetrics.MAX_GREEN_SCORE_NOTE
import otoroshi.greenscore.{
  GreenScoreEntity,
  GreenScoreExtension,
  RouteRules,
  RouteScoreAtDate,
  RuleState,
  RuleStateRecord,
  RulesManager,
  RulesRouteConfiguration
}
import otoroshi.models.{EntityLocation, RoundRobin}
import otoroshi.next.models.{
  NgBackend,
  NgClientConfig,
  NgDomainAndPath,
  NgFrontend,
  NgPluginInstance,
  NgPlugins,
  NgRoute,
  NgTarget
}
import otoroshi.utils.syntax.implicits.{BetterJsValue, BetterSyntax}
import play.api.Configuration
import play.api.libs.json.{JsArray, JsNull, JsObject, JsValue}
import play.api.libs.ws.WSAuthScheme

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.{DurationInt, FiniteDuration}

class GreenScoreTestSpec(name: String, configurationSpec: => Configuration) extends OtoroshiSpec {

  implicit lazy val mat = otoroshiComponents.materializer
  implicit lazy val env = otoroshiComponents.env

  val initialRoute = NgRoute(
    location = EntityLocation.default,
    id = "basic-sm-test-route",
    name = "basic-sm-test-route",
    description = "basic-sm-test-route",
    tags = Seq(),
    metadata = Map(),
    enabled = true,
    debugFlow = false,
    capture = false,
    exportReporting = false,
    frontend = NgFrontend(
      domains = Seq(NgDomainAndPath("foo.oto.tools")),
      headers = Map(),
      cookies = Map(),
      query = Map(),
      methods = Seq(),
      stripPath = true,
      exact = false
    ),
    backend = NgBackend(
      targets = Seq(
        NgTarget(
          hostname = "127.0.0.1",
          port = 3000,
          id = "monkey-target",
          tls = false
        )
      ),
      root = "/",
      rewrite = false,
      loadBalancing = RoundRobin,
      client = NgClientConfig.default
    ),
    plugins = NgPlugins.empty
  )

  override def getTestConfiguration(configuration: Configuration) =
    Configuration(
      ConfigFactory
        .parseString(s"""
           |{
           |  otoroshi.admin-extensions.configurations.otoroshi_extensions_greenscore.enabled = true
           |}
       """.stripMargin)
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)

  def wait[A](fu: Future[A], duration: Option[FiniteDuration] = Some(10.seconds)): A = Await.result(fu, duration.get)

  def fetch(
      isApiExtension: Boolean = true,
      path: String = "",
      method: String = "get",
      body: Option[JsValue] = JsNull.some
  ) = {
    val request = wsClient
      .url(
        if (isApiExtension)
          s"http://otoroshi-api.oto.tools:$port/apis/green-score.extensions.otoroshi.io/v1/green-scores$path"
        else
          s"http://otoroshi-api.oto.tools:$port/api/extensions/green-score$path"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)

    method match {
      case "post" => wait(request.post(body.get))
      case "put"  => wait(request.put(body.get))
      case _      => wait(request.get())
    }
  }

  private def MAX_SCORE_BY_SECTIONS = RulesManager.rules
    .foldLeft(Seq.empty[(String, Double)]) { case (acc, rule) =>
      acc :+ (rule.section, MAX_GREEN_SCORE_NOTE * (rule.sectionWeight / 100) * (rule.weight / 100))
    }
    .foldLeft(Map.empty[String, Double]) { case (sections, rule) =>
      val value = sections.getOrElse(rule._1, 0.0)
      sections + (rule._1 -> (rule._2 + value))
    }

  def getScore() = fetch(isApiExtension = false).json.as[JsObject]

  val OLD_DATE      = DateTime.now().minusWeeks(2).getMillis
  val LESS_OLD_DATE = DateTime.now().minusWeeks(1).getMillis
  val TODAY_DATE    = DateTime.now().getMillis

  s"Green score" should {
    "warm up" in {
      startOtoroshi()
      getOtoroshiServices().futureValue // WARM UP
      getOtoroshiRoutes().futureValue

      wait(createOtoroshiRoute(initialRoute))
    }

    "initiale state" in {
      val result = getScore()

      result.select("groups").as[JsArray].value mustBe empty
      result.select("scores").as[JsObject].select("dynamic_values_by_routes").as[JsArray].value mustBe empty
      result.select("scores").as[JsObject].select("score_by_route").as[JsArray].value mustBe empty
    }

    // create an empty group without routes called TEMPLATE_WITHOUT_CHANGE_OR_DATES
    "create group" in {
      val result = fetch(path = "/_template")

      val template = GreenScoreEntity.format.reads(result.json).get
      val created  =
        fetch(method = "post", body = template.copy(id = "TEMPLATE_WITHOUT_CHANGE_OR_DATES").json.some).status
      created mustBe 201
    }

    "get score for the first group" in {
      val result = getScore()

      result.select("groups").as[JsArray].value.length mustBe 1
      result.select("scores").as[JsObject].select("dynamic_values_by_routes").as[JsArray].value mustBe empty
      result.select("scores").as[JsObject].select("score_by_route").as[JsArray].value mustBe empty
    }

    // create an group with the apdmin api route called LESS_OLD_GROUP at LESS_OLD_DATE
    "create group with rules" in {
      val routes   = env.proxyState.allRoutes()
      val template = GreenScoreEntity.format.reads(fetch(path = "/_template").json).get

      val created = fetch(
        method = "post",
        body = template
          .copy(
            id = "LESS_OLD_GROUP",
            routes = Seq(
              RouteRules(
                routeId = routes.head.id,
                rulesConfig = RulesRouteConfiguration(states =
                  Seq(
                    RuleStateRecord(
                      date = LESS_OLD_DATE,
                      states = RulesManager.rules.filter(_.section == "log").map(rule => RuleState(rule.id))
                    )
                  )
                )
              )
            )
          )
          .json
          .some
      ).status

      created mustBe 201
    }

    "second group score equals to 600" in {
      val result = getScore()

      result.select("groups").as[JsArray].value.length mustBe 2

      val scoreByRoute                 =
        result.select("scores").as[JsObject].select("score_by_route").as[JsArray].value.map(RouteScoreAtDate.from)
      val groupScore: RouteScoreAtDate = scoreByRoute.head

      groupScore.routes.head.scores.length mustBe 4

      groupScore.routes.head.scores.find(s => s.section == "architecture").get.score.score mustBe 0
      groupScore.routes.head.scores.find(s => s.section == "design").get.score.score mustBe 0
      groupScore.routes.head.scores.find(s => s.section == "usage").get.score.score mustBe 0
      groupScore.routes.head.scores.find(s => s.section == "log").get.score.score mustBe MAX_SCORE_BY_SECTIONS("log")

      groupScore.routes.head.scores.find(s => s.section == "architecture").get.letter mustBe "E"
      groupScore.routes.head.scores.find(s => s.section == "design").get.letter mustBe "E"
      groupScore.routes.head.scores.find(s => s.section == "usage").get.letter mustBe "E"
      groupScore.routes.head.scores.find(s => s.section == "log").get.letter mustBe "A"
    }

    // update LESS_OLD_GROUP by adding on new record at TODAY_DATE
    "put new record to the group" in {
      val greenScoreEntities = env.adminExtensions.extension[GreenScoreExtension].get.datastores

      val greenScoreEntity = wait(greenScoreEntities.greenscoresDatastore.findById("LESS_OLD_GROUP")).get

      val created = fetch(
        method = "put",
        path = s"/${greenScoreEntity.id}",
        body = greenScoreEntity
          .copy(
            routes = Seq(
              greenScoreEntity.routes.head.copy(
                rulesConfig = RulesRouteConfiguration(states =
                  Seq(
                    greenScoreEntity.routes.head.rulesConfig.states.head,
                    RuleStateRecord(
                      date = TODAY_DATE,
                      states = RulesManager.rules
                        .filter(r => r.section == "usage" || r.section == "architecture" || r.section == "log")
                        .map(rule => RuleState(rule.id, enabled = rule.section != "log"))
                    )
                  )
                )
              )
            )
          )
          .json
          .some
      ).status

      created mustBe 200
    }

    // we have 2 groups, one empty and the second LESS_OLD_GROUP with 1 routes and 2 records TODAY_DATE + LESS_OLD_DATE
    "get new score" in {
      val result = getScore()

      result.select("groups").as[JsArray].value.length mustBe 2

      val scoreByRoute =
        result.select("scores").as[JsObject].select("score_by_route").as[JsArray].value.map(RouteScoreAtDate.from)

      scoreByRoute.length mustBe 2

      val groupScore: RouteScoreAtDate = scoreByRoute.maxBy(_.date)
      groupScore.routes.head.scores.foldLeft(0.0) { case (acc, section) =>
        acc + section.score.score
      } mustBe (MAX_SCORE_BY_SECTIONS("architecture") + MAX_SCORE_BY_SECTIONS("usage"))

      val minScore: RouteScoreAtDate = scoreByRoute.minBy(_.date)
      minScore.routes.head.scores.foldLeft(0.0) { case (acc, section) =>
        acc + section.score.score
      } mustBe MAX_SCORE_BY_SECTIONS("log")
    }

    "create group with two routes added in same time" in {
      val routes = env.proxyState.allRoutes()

      val template = GreenScoreEntity.format.reads(fetch(path = "/_template").json).get

      val routeRules1 = RouteRules(
        routeId = routes.head.id,
        rulesConfig = RulesRouteConfiguration(
          states = Seq(
            RuleStateRecord(
              date = OLD_DATE,
              states = RulesManager.rules.filter(_.section == "usage").map(rule => RuleState(rule.id))
            )
          )
        )
      )

      val routeRules2 = RouteRules(
        routeId = routes(1).id,
        rulesConfig = RulesRouteConfiguration(
          states = Seq(
            RuleStateRecord(
              date = OLD_DATE,
              states = RulesManager.rules.filter(_.section == "design").map(rule => RuleState(rule.id))
            )
          )
        )
      )

      val created = fetch(
        method = "post",
        body = template
          .copy(
            id = "OLD_GROUP",
            routes = Seq(routeRules1, routeRules2)
          )
          .json
          .some
      ).status

      created mustBe 201
    }

    "get score of the new group" in {
      val result = getScore()

      val scoreByRoute =
        result.select("scores").as[JsObject].select("score_by_route").as[JsArray].value.map(RouteScoreAtDate.from)

      val oldScore = scoreByRoute.find(p => p.date == OLD_DATE).get
      oldScore.routes.length mustBe 2

      val lessOldScore = scoreByRoute.find(p => p.date == LESS_OLD_DATE).get
      lessOldScore.routes.length mustBe 3

      val todayScore = scoreByRoute.find(p => p.date == TODAY_DATE).get
      todayScore.routes.length mustBe 3

      oldScore.routes
        .flatMap(_.scores)
        .foldLeft(0.0) { case (acc, section) => acc + section.score.score } mustBe (MAX_SCORE_BY_SECTIONS(
        "usage"
      ) + MAX_SCORE_BY_SECTIONS("design"))

      lessOldScore.routes
        .flatMap(_.scores)
        .foldLeft(0.0) { case (acc, section) => acc + section.score.score } mustBe (MAX_SCORE_BY_SECTIONS(
        "usage"
      ) + MAX_SCORE_BY_SECTIONS("design") + MAX_SCORE_BY_SECTIONS("log"))

      todayScore.routes
        .flatMap(_.scores)
        .foldLeft(0.0) { case (acc, section) => acc + section.score.score } mustBe (MAX_SCORE_BY_SECTIONS(
        "usage"
      ) +                                    // OLD_SCORE
      MAX_SCORE_BY_SECTIONS("design") +      // OLD_SCORE
      MAX_SCORE_BY_SECTIONS("usage") +       // TODAY
      MAX_SCORE_BY_SECTIONS("architecture")) // TODAY
    }

    "shutdown" in {
      stopAll()
    }
  }
}
