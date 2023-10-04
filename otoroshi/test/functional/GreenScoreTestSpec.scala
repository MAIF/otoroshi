package functional

import com.typesafe.config.ConfigFactory
import otoroshi.greenscore.{GreenScoreEntity, Rule, RuleState}
import otoroshi.jobs.updates.Version
import otoroshi.utils.syntax.implicits.BetterJsValue
import play.api.Configuration
import play.api.libs.json
import play.api.libs.json.{JsArray, JsObject, Json}
import play.api.libs.ws.WSAuthScheme

class GreenScoreTestSpec(name: String, configurationSpec: => Configuration) extends OtoroshiSpec {

  implicit lazy val mat = otoroshiComponents.materializer
  implicit lazy val env = otoroshiComponents.env

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

  def fetch(path: String = "") = wsClient
    .url(s"http://otoroshi-api.oto.tools:$port/api/extensions/green-score$path")
    .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)

  def fetchExtension(path: String = "") = wsClient
    .url(s"http://otoroshi-api.oto.tools:$port/api/green-score.extensions.otoroshi.io/v1/green-scores$path")
    .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)

  s"Green score" should {
    "warm up" in {
      startOtoroshi()
      getOtoroshiServices().futureValue // WARM UP
      getOtoroshiRoutes().futureValue
    }

    "initiale state" in {
      fetch()
        .get()
        .map(r => r.json.as[JsObject])
        .map(result => {
          result.select("groups").as[JsArray].value mustBe empty
          result.select("scores").as[JsObject].select("dynamic_values_by_routes").as[JsArray].value mustBe empty
          result.select("scores").as[JsObject].select("score_by_route").as[JsArray].value mustBe empty
        })
    }

    "create group" in {
      for {
        rules <- fetch("/template")
              .get()
              .map(r => RuleState.reads(r.json).get)
        template <- fetchExtension("/_template")
          .get()
          .map(r => GreenScoreEntity.format.reads(r.json).get)
        created <- fetchExtension()
          .post(Json.stringify(template.json))
          .map(r => r.status)
      } yield {
        created mustBe 201
      }
    }

    "shutdown" in {
      stopAll()
    }
  }
}
