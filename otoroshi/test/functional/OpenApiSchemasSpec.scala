package functional

import com.typesafe.config.ConfigFactory
import play.api.Configuration
import play.api.libs.json.*

// the entity schemas of the openapi spec are built by reflection. when that reflection silently stops seeing the
// fields of case classes, the spec is still generated, only with empty schemas, and nothing else fails. this spec
// generates the document without writing it and asserts that well known entities are really described, and that
// every schema a $ref points to exists.
class OpenApiSchemasSpec extends OtoroshiSpec {

  s"OpenApi entity schemas" should {
    "warm up" in {
      startOtoroshi()
    }
    "describe the fields of well known entities" in {
      val spec    = Json.parse(otoroshi.api.OpenApi.generate(otoroshiComponents.env, None))
      val schemas = (spec \ "components" \ "schemas").as[JsObject]
      Seq("proxy.otoroshi.io.Route", "apim.otoroshi.io.Apikey", "pki.otoroshi.io.Certificate").foreach { kind =>
        val properties = (schemas \ kind \ "properties").asOpt[JsObject].map(_.keys.size).getOrElse(0)
        withClue(s"number of properties described for '$kind'") {
          properties must be > 10
        }
      }
      val route      = (schemas \ "proxy.otoroshi.io.Route" \ "properties").as[JsObject]
      Seq("id", "name", "frontend", "backend", "plugins").foreach { field =>
        withClue(s"field '$field' of the route schema") {
          route.keys must contain(field)
        }
      }
      val config     = (schemas \ "config.otoroshi.io.GlobalConfig" \ "properties").as[JsObject]
      Seq("trustXForwarded", "trustedProxies", "ipFiltering").foreach { field =>
        withClue(s"field '$field' of the global config schema") {
          config.keys must contain(field)
        }
      }
      // a sealed trait written as a string must not be described as an object
      (schemas \ "OutageStrategy" \ "type").asOpt[String] mustBe Some("string")
      // scala collections must only be described by their items, not by their bean properties
      Json.stringify(spec) must not include "traversableAgain"
    }
    "only hold schema objects that every $ref can reach" in {
      val spec    = Json.parse(otoroshi.api.OpenApi.generate(otoroshiComponents.env, None))
      val schemas = (spec \ "components" \ "schemas").as[JsObject]
      val wrapped = schemas.fields.collect { case (name, s) if (s \ "referencedSchemas").isDefined => name }
      withClue("components still wrapped in a resolved schema") {
        wrapped mustBe empty
      }
      withClue("$ref that does not resolve in the whole document") {
        unresolvedRefs(spec) mustBe empty
      }
      Seq("proxy.otoroshi.io", "apis.otoroshi.io", "config.otoroshi.io").foreach { group =>
        val sub = Json.parse(otoroshi.api.OpenApi.generate(otoroshiComponents.env, None, Some(group)))
        withClue(s"$$ref that does not resolve in the document of the '$group' group") {
          unresolvedRefs(sub) mustBe empty
        }
      }
    }
    "shutdown" in {
      stopAll()
    }
  }

  // every $ref of the document that points to nothing. only local pointers going through objects are resolved,
  // which is what the generated documents use
  private def unresolvedRefs(doc: JsValue): Seq[String] = {
    def refs(js: JsValue): Seq[String] = js match {
      case JsObject(fields) =>
        fields.toSeq.flatMap {
          case ("$ref", JsString(ref)) => Seq(ref)
          case (_, value)              => refs(value)
        }
      case JsArray(values)  => values.toSeq.flatMap(refs)
      case _                => Seq.empty
    }
    def resolves(ref: String): Boolean = ref.startsWith("#/") && ref
      .drop(2)
      .split("/")
      .map(_.replace("~1", "/").replace("~0", "~"))
      .foldLeft(Option(doc)) {
        case (Some(obj: JsObject), segment) => obj.value.get(segment)
        case _                              => None
      }
      .isDefined
    refs(doc).distinct.filterNot(resolves)
  }

  override def getTestConfiguration(configuration: Configuration) = {
    Configuration(
      ConfigFactory.parseString(s"""app.env = dev""".stripMargin).resolve()
    ).withFallback(configuration)
  }
}
