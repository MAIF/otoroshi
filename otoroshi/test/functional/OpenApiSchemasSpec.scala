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
    "document every user analytics endpoint" in {
      val spec   = Json.parse(otoroshi.api.OpenApi.generate(otoroshiComponents.env, None))
      val paths  = (spec \ "paths").as[JsObject]
      // the user analytics endpoints are not generic resources, they are described in conf/schemas/additionalPaths.json.
      // the legacy events migration is left out as it only answers in dev mode
      val source = scala.io.Source.fromInputStream(otoroshiComponents.env.environment.resourceAsStream("/routes").get)
      val routes =
        try {
          source
            .getLines()
            .map(_.trim.split("\\s+").toList)
            .collect {
              case method :: path :: _ if path.startsWith("/api/analytics/") && !path.endsWith("/_migrate") =>
                (method.toLowerCase, path.replaceAll(":([A-Za-z]+)", "{$1}"))
            }
            .toList
        } finally {
          source.close()
        }
      routes must not be empty
      routes.foreach { case (method, path) =>
        withClue(s"documentation of $method $path") {
          (paths \ path \ method).isDefined mustBe true
        }
      }
    }
    "leave the third party admin extensions out of the core document" in {
      val env                             = otoroshiComponents.env
      def paths(doc: String): Set[String] = (Json.parse(doc) \ "paths").as[JsObject].keys.toSet

      def collections(resources: Seq[otoroshi.api.Resource]): Seq[String] = resources
        .filter(_.version.served)
        .filterNot(_.version.deprecated)
        .map(res => s"/apis/${res.group}/${res.version.name}/${res.pluralName}")

      val complete   = paths(otoroshi.api.OpenApi.generate(env, None))
      val core       = paths(otoroshi.api.OpenApi.generate(env, None, coreOnly = true))
      val shipped    = collections(env.adminExtensions.coreResources())
      // the third party extensions come from the vendored jars of lib/
      val thirdParty = collections(env.adminExtensions.resources()).filterNot(shipped.contains)
      shipped must not be empty
      thirdParty must not be empty
      shipped.foreach { path =>
        withClue(s"resource of an extension shipped with otoroshi $path") {
          complete must contain(path)
          core must contain(path)
        }
      }
      thirdParty.foreach { path =>
        withClue(s"resource of a third party extension $path") {
          complete must contain(path)
          core must not contain (path)
        }
      }
      collections(env.allResources.coreResources).foreach { path =>
        withClue(s"core resource $path") {
          core must contain(path)
        }
      }
      core must contain("/apis/plugins.otoroshi.io/v1/workflows")
      // the endpoints described by hand belong to the core
      core must contain("/api/analytics/_query")
    }
    "describe the requested version even when the document is cached" in {
      val env                          = otoroshiComponents.env
      def version(doc: String): String = (Json.parse(doc) \ "info" \ "version").as[String]
      version(otoroshi.api.OpenApi.generate(env, None)) mustBe env.otoroshiVersion
      version(otoroshi.api.OpenApi.generate(env, Some("1.2.3"))) mustBe "1.2.3"
      version(otoroshi.api.OpenApi.generate(env, Some("1.2.3"), Some("proxy.otoroshi.io"))) mustBe "1.2.3"
      version(otoroshi.api.OpenApi.generate(env, Some("4.5.6"), coreOnly = true)) mustBe "4.5.6"
      version(otoroshi.api.OpenApi.generate(env, None)) mustBe env.otoroshiVersion
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
