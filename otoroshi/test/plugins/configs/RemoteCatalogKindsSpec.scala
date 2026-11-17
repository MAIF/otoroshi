package plugins.configs

import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import otoroshi.api.{Resource, ResourceVersion}
import otoroshi.models.{EntityLocation, TeamId, TenantId}
import otoroshi.next.catalogs.{RemoteCatalog, RemoteCatalogKinds, RemoteCatalogKindsRule}
import play.api.libs.json.*

// the kinds of entities a remote catalog deploys, globally and by location (#2933)
class RemoteCatalogKindsSpec extends AnyWordSpec with Matchers {

  def resource(kind: String, plural: String, group: String): Resource =
    Resource(kind, plural, plural.stripSuffix("s"), group, ResourceVersion("v1", true, false, true), access = null)

  val routes       = resource("Route", "routes", "proxy.otoroshi.io")
  val backends     = resource("Backend", "backends", "proxy.otoroshi.io")
  val wasmPlugins  = resource("WasmPlugin", "wasm-plugins", "plugins.otoroshi.io")
  val globalConfig = resource("GlobalConfig", "global-configs", "config.otoroshi.io")

  def loc(tenant: String, teams: String*): EntityLocation = EntityLocation(TenantId(tenant), teams.map(TeamId.apply))

  def read(json: JsValue): RemoteCatalogKinds = RemoteCatalogKinds.format.reads(json) match {
    case JsSuccess(kinds, _) => kinds
    case JsError(errors)     => fail(s"cannot read $json: $errors")
  }

  "RemoteCatalogKindsRule" should {

    "match a kind by plural name, kind or group, whatever the case" in {
      Seq("routes", "Route", "proxy.otoroshi.io/routes", "proxy.otoroshi.io/Route", "ROUTES").foreach { name =>
        withClue(name) { RemoteCatalogKindsRule(include = Seq(name)).allows(routes) mustBe true }
      }
      RemoteCatalogKindsRule(include = Seq("routes")).allows(backends) mustBe false
    }

    "match patterns of kinds" in {
      RemoteCatalogKindsRule(include = Seq("proxy.otoroshi.io/*")).allows(backends) mustBe true
      RemoteCatalogKindsRule(include = Seq("proxy.otoroshi.io/*")).allows(wasmPlugins) mustBe false
      RemoteCatalogKindsRule(include = Seq("*-plugins")).allows(wasmPlugins) mustBe true
    }

    "allow the included kinds that are not excluded" in {
      val rule = RemoteCatalogKindsRule(include = Seq("*"), exclude = Seq("GlobalConfig"))
      rule.allows(routes) mustBe true
      rule.allows(globalConfig) mustBe false
    }
  }

  "RemoteCatalogKinds" should {

    val kinds = read(
      Json.obj(
        "exclude"   -> Json.arr("global-configs"),
        "locations" -> Json.obj(
          "orga-*"         -> Json.obj("include" -> Json.arr("routes", "backends")),
          "orga-a/sandbox" -> Json.obj("exclude" -> Json.arr("backends"))
        )
      )
    )

    "deploy every kind everywhere by default" in {
      RemoteCatalogKinds.everything.allows(globalConfig, loc("default", "default")) mustBe true
    }

    "apply the global rule to every location" in {
      kinds.allows(routes, loc("default", "default")) mustBe true
      kinds.allows(wasmPlugins, loc("default", "default")) mustBe true
      kinds.allows(globalConfig, loc("default", "default")) mustBe false
      kinds.allowsKind(globalConfig) mustBe false
    }

    "only narrow the global rule with the rules of the locations" in {
      kinds.allows(routes, loc("orga-a", "default")) mustBe true
      kinds.allows(wasmPlugins, loc("orga-a", "default")) mustBe false
      kinds.allows(backends, loc("orga-a", "default")) mustBe true
      // a location matching several patterns gets all their rules
      kinds.allows(backends, loc("orga-a", "sandbox")) mustBe false
      kinds.allows(routes, loc("orga-a", "sandbox")) mustBe true
      // an entity in several teams gets the rules of each of them
      kinds.allows(backends, loc("orga-a", "default", "sandbox")) mustBe false
    }

    "apply every rule to an entity located everywhere" in {
      kinds.allows(wasmPlugins, loc("*", "*")) mustBe false
      kinds.allows(backends, loc("orga-a", "*")) mustBe false
      kinds.allows(routes, loc("*", "*")) mustBe true
    }

    "refuse kinds that cannot be read" in {
      RemoteCatalogKinds.format.reads(Json.obj("include" -> 42)).isError mustBe true
      RemoteCatalogKinds.format.reads(Json.obj("locations" -> Json.arr())).isError mustBe true
      RemoteCatalogKinds.format.reads(Json.obj("locations" -> Json.obj("a" -> "routes"))).isError mustBe true
      RemoteCatalogKinds.format.reads(JsString("routes")).isError mustBe true
    }

    "refuse patterns that are not valid" in {
      RemoteCatalogKinds.format.reads(Json.obj("include" -> Json.arr("routes["))).isError mustBe true
      RemoteCatalogKinds.format.reads(Json.obj("exclude" -> Json.arr("(routes"))).isError mustBe true
      RemoteCatalogKinds.format.reads(Json.obj("locations" -> Json.obj("orga-[" -> Json.obj()))).isError mustBe true
      RemoteCatalogKinds.format.reads(Json.obj("locations" -> Json.obj("orga-a/team-(" -> Json.obj()))).isError mustBe true
      RemoteCatalogKinds.format.reads(Json.obj("locations" -> Json.obj("orga-*/team-*" -> Json.obj()))).isError mustBe false
    }

    "accept a single kind written as a string" in {
      read(Json.obj("include" -> "routes")).allowsKind(routes) mustBe true
      read(Json.obj("include" -> "routes")).allowsKind(backends) mustBe false
    }

    "round trip" in {
      read(kinds.json) mustBe kinds
    }
  }

  "RemoteCatalog" should {

    val base = RemoteCatalog.template().json.as[JsObject]

    "deploy every kind without kinds, like before" in {
      RemoteCatalog.format.reads(base - "kinds").get.kinds mustBe RemoteCatalogKinds.everything
      RemoteCatalog.format.reads(base ++ Json.obj("kinds" -> JsNull)).get.kinds mustBe RemoteCatalogKinds.everything
    }

    "not be read with kinds that cannot be read" in {
      RemoteCatalog.format.reads(base ++ Json.obj("kinds" -> Json.obj("exclude" -> 42))).isError mustBe true
    }

    "write its kinds" in {
      val kinds = read(Json.obj("exclude" -> Json.arr("admins")))
      val json  = base ++ Json.obj("kinds" -> kinds.json)
      RemoteCatalog.format.reads(json).get.kinds mustBe kinds
      (RemoteCatalog.format.reads(json).get.json \ "kinds").as[JsObject] mustBe kinds.json
    }
  }
}
