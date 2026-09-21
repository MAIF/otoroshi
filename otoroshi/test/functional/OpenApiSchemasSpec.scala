package functional

import com.typesafe.config.ConfigFactory
import play.api.Configuration
import play.api.libs.json.*

// the entity schemas of the openapi spec are built by reflection. when that reflection silently stops seeing the
// fields of case classes, the spec is still generated, only with empty schemas, and nothing else fails. this spec
// generates the document without writing it and asserts that well known entities are really described.
class OpenApiSchemasSpec extends OtoroshiSpec {

  s"OpenApi entity schemas" should {
    "warm up" in {
      startOtoroshi()
    }
    "describe the fields of well known entities" in {
      val spec    = Json.parse(otoroshi.api.OpenApi.generate(otoroshiComponents.env, None))
      val schemas = (spec \ "components" \ "schemas").as[JsObject]
      Seq("proxy.otoroshi.io.Route", "apim.otoroshi.io.Apikey", "pki.otoroshi.io.Certificate").foreach { kind =>
        val properties = (schemas \ kind \ "schema" \ "properties").asOpt[JsObject].map(_.keys.size).getOrElse(0)
        withClue(s"number of properties described for '$kind'") {
          properties must be > 10
        }
      }
      val route      = (schemas \ "proxy.otoroshi.io.Route" \ "schema" \ "properties").as[JsObject]
      Seq("id", "name", "frontend", "backend", "plugins").foreach { field =>
        withClue(s"field '$field' of the route schema") {
          route.keys must contain(field)
        }
      }
      // scala collections must only be described by their items, not by their bean properties
      Json.stringify(spec) must not include "traversableAgain"
    }
    "shutdown" in {
      stopAll()
    }
  }

  override def getTestConfiguration(configuration: Configuration) = {
    Configuration(
      ConfigFactory.parseString(s"""app.env = dev""".stripMargin).resolve()
    ).withFallback(configuration)
  }
}
