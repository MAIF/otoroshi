package entities

import io.otoroshi.wasm4s.scaladsl.{WasmSource, WasmSourceKind, WasmVmKillOptions}
import otoroshi.models.{EntityLocation, WasmPlugin}
import otoroshi.next.plugins.api.NgStep
import otoroshi.wasm.{WasmAuthorizations, WasmConfig, WasmDataRights}
import play.api.libs.json.*

import scala.concurrent.duration.*

class WasmPluginEntityFormatSpec extends EntityFormatSpec {

  val allSteps: Seq[NgStep] = Seq(
    NgStep.Router,
    NgStep.Sink,
    NgStep.PreRoute,
    NgStep.ValidateAccess,
    NgStep.TransformRequest,
    NgStep.TransformResponse,
    NgStep.MatchRoute,
    NgStep.HandlesTunnel,
    NgStep.HandlesRequest,
    NgStep.CallBackend,
    NgStep.Job,
    NgStep.DataExporterFilter,
    NgStep.DataExporterTransform
  )

  "NgStep" should {

    "name every step" in {
      allSteps.map(_.name) mustBe Seq(
        "Router",
        "Sink",
        "PreRoute",
        "ValidateAccess",
        "TransformRequest",
        "TransformResponse",
        "MatchRoute",
        "HandlesTunnel",
        "HandlesRequest",
        "CallBackend",
        "Job",
        "DataExporterFilter",
        "DataExporterTransform"
      )
    }

    "read every step back from its name" in {
      allSteps.map(s => NgStep(s.name)) mustBe allSteps.map(Some(_))
    }

    "write a step as the json string of its name" in {
      NgStep.ValidateAccess.json mustBe JsString("ValidateAccess")
    }

    "not read an unknown step name" in {
      NgStep("Foo") mustBe None
      NgStep("") mustBe None
    }

    "read the step names case sensitively" in {
      NgStep("router") mustBe None
      NgStep("ROUTER") mustBe None
    }

    "not list the job and data exporter steps among all the steps" in {
      NgStep.all mustBe allSteps.take(10)
    }
  }

  "WasmSource" should {

    "build its cache key from its kind and path" in {
      WasmSource(WasmSourceKind.Http, "https://wasm.oto.tools/plugin.wasm").cacheKey mustBe "http://https://wasm.oto.tools/plugin.wasm"
    }

    "lower case the kind in its cache key" in {
      WasmSource(WasmSourceKind.Wasmo, "plugin-1.wasm").cacheKey mustBe "wasmo://plugin-1.wasm"
      WasmSource(WasmSourceKind.ClassPath, "wasm/plugin.wasm").cacheKey mustBe "classpath://wasm/plugin.wasm"
    }
  }

  "WasmPlugin" should {

    val format = WasmPlugin.format

    val minimal = WasmPlugin(
      id = "wasm-plugin-1",
      name = "Wasm plugin 1",
      description = "",
      config = WasmConfig()
    )

    val fullConfig = WasmConfig(
      source = WasmSource(
        WasmSourceKind.Http,
        "https://wasm.oto.tools/plugin.wasm",
        Json.obj("method" -> "POST", "headers" -> Json.obj("x-key" -> "secret"), "timeout" -> 3000)
      ),
      memoryPages = 20,
      functionName = Some("execute"),
      config = Map("mode" -> "strict"),
      allowedHosts = Seq("api.oto.tools", "*.foo.bar"),
      allowedPaths = Map("/tmp" -> "/sandbox"),
      wasi = true,
      opa = true,
      httpWasm = true,
      instances = 4,
      killOptions = WasmVmKillOptions(
        immortal = true,
        maxCalls = 1000,
        maxMemoryUsage = 0.75,
        maxAvgCallDuration = 250.millis,
        maxUnusedDuration = 10.minutes
      ),
      authorizations = WasmAuthorizations(
        httpAccess = true,
        globalDataStoreAccess = WasmDataRights(read = true, write = true),
        pluginDataStoreAccess = WasmDataRights(read = true, write = false),
        globalMapAccess = WasmDataRights(read = false, write = true),
        pluginMapAccess = WasmDataRights(read = true, write = true),
        proxyStateAccess = true,
        configurationAccess = true,
        proxyHttpCallTimeout = 1000
      ),
      isJS = true
    )

    val full = WasmPlugin(
      id = "wasm-plugin-2",
      name = "My wasm validator",
      description = "validates the access with wasm",
      steps = allSteps,
      config = fullConfig,
      tags = Seq("wasm", "prod"),
      metadata = Map("owner" -> "ops", "ticket" -> "42"),
      location = customLocation
    )

    "have no step, no tags, no metadata and the default location by default" in {
      minimal.steps mustBe empty
      minimal.tags mustBe empty
      minimal.metadata mustBe empty
      minimal.location mustBe EntityLocation()
    }

    "write a minimal plugin" in {
      format.writes(minimal) mustBe Json.obj(
        "_loc"        -> defaultLocationJson,
        "id"          -> "wasm-plugin-1",
        "name"        -> "Wasm plugin 1",
        "description" -> "",
        "config"      -> WasmConfig().json,
        "steps"       -> Json.arr(),
        "metadata"    -> Json.obj(),
        "tags"        -> Json.arr()
      )
    }

    "write the default config with an unknown source and 50 memory pages" in {
      val config = (written(format, minimal) \ "config").as[JsObject]
      (config \ "source").as[JsObject] mustBe Json.obj("kind" -> "Unknown", "path" -> "", "opts" -> Json.obj())
      (config \ "memoryPages").as[Int] mustBe 50
    }

    "write a plugin where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "_loc"        -> customLocationJson,
        "id"          -> "wasm-plugin-2",
        "name"        -> "My wasm validator",
        "description" -> "validates the access with wasm",
        "config"      -> WasmConfig.format.writes(fullConfig),
        "steps"       -> Json.arr(
          "Router",
          "Sink",
          "PreRoute",
          "ValidateAccess",
          "TransformRequest",
          "TransformResponse",
          "MatchRoute",
          "HandlesTunnel",
          "HandlesRequest",
          "CallBackend",
          "Job",
          "DataExporterFilter",
          "DataExporterTransform"
        ),
        "metadata"    -> Json.obj("owner" -> "ops", "ticket" -> "42"),
        "tags"        -> Json.arr("wasm", "prod")
      )
    }

    "write the config of the plugin as the wasm config itself writes it" in {
      (written(format, full) \ "config").as[JsValue] mustBe fullConfig.json
    }

    "round trip a minimal plugin" in {
      roundTrip(format, minimal)
    }

    "round trip a plugin where every field is set" in {
      roundTrip(format, full)
    }

    "round trip the config of a plugin where every field is set" in {
      roundTrip(format, full).config mustBe fullConfig
    }

    "round trip a plugin without function name" in {
      roundTrip(format, full.copy(config = fullConfig.copy(functionName = None))).config.functionName mustBe None
    }

    "keep the order of the steps" in {
      val steps = Seq(NgStep.TransformResponse, NgStep.Router, NgStep.Job)
      roundTrip(format, minimal.copy(steps = steps)).steps mustBe steps
    }

    "keep the order of the tags" in {
      roundTrip(format, full.copy(tags = Seq("z", "a", "m"))).tags mustBe Seq("z", "a", "m")
    }

    "round trip a name and a description with quotes and unicode" in {
      roundTrip(format, full.copy(name = "l'équipe \"ops\"", description = "multi\nline"))
    }

    "read a plugin with only its mandatory fields" in {
      readOk(format, Json.obj("id" -> "w", "name" -> "W", "description" -> "d")) mustBe WasmPlugin(
        id = "w",
        name = "W",
        description = "d",
        steps = Seq.empty,
        config = WasmConfig(),
        tags = Seq.empty,
        metadata = Map.empty,
        location = EntityLocation()
      )
    }

    "read a plugin without config with the default config and its 50 memory pages" in {
      readOk(format, written(format, full) - "config").config mustBe WasmConfig()
    }

    "read a null config with 100 memory pages" in {
      readOk(format, written(format, full) ++ Json.obj("config" -> JsNull)).config mustBe WasmConfig(memoryPages = 100)
    }

    "read an empty config object with 100 memory pages" in {
      readOk(format, written(format, full) ++ Json.obj("config" -> Json.obj())).config mustBe WasmConfig(memoryPages = 100)
    }

    "read a config that is not an object with 100 memory pages" in {
      readOk(format, written(format, full) ++ Json.obj("config" -> "foo")).config mustBe WasmConfig(memoryPages = 100)
    }

    "drop the whole config when its source has no path" in {
      val config = fullConfig.json.as[JsObject] ++ Json.obj("source" -> Json.obj("kind" -> "Http"))
      readOk(format, written(format, full) ++ Json.obj("config" -> config)).config mustBe WasmConfig()
    }

    "read a legacy raw_source of the config and write it back as a source object" in {
      val config = fullConfig.json.as[JsObject] - "source" ++ Json.obj("raw_source" -> "https://wasm.oto.tools/legacy.wasm")
      val back   = readOk(format, written(format, full) ++ Json.obj("config" -> config))
      back.config.source mustBe WasmSource(WasmSourceKind.Http, "https://wasm.oto.tools/legacy.wasm")
      val json   = (written(format, back) \ "config").as[JsObject]
      json.keys must not contain "raw_source"
      (json \ "source" \ "kind").as[String] mustBe "Http"
    }

    "read the legacy accesses of the config as its authorizations" in {
      val config = fullConfig.json.as[JsObject] - "authorizations" ++ Json.obj("accesses" -> fullConfig.authorizations.json)
      readOk(format, written(format, full) ++ Json.obj("config" -> config)).config.authorizations mustBe fullConfig.authorizations
    }

    "read a plugin without steps with no step" in {
      readOk(format, written(format, full) - "steps").steps mustBe empty
    }

    "skip the unknown step names" in {
      readOk(format, written(format, minimal) ++ Json.obj("steps" -> Json.arr("Router", "Foo", "Job"))).steps mustBe Seq(
        NgStep.Router,
        NgStep.Job
      )
    }

    "skip the step names in the wrong case" in {
      readOk(format, written(format, minimal) ++ Json.obj("steps" -> Json.arr("router", "Sink"))).steps mustBe Seq(NgStep.Sink)
    }

    "ignore steps holding a non string value" in {
      readOk(format, written(format, minimal) ++ Json.obj("steps" -> Json.arr("Router", 1))).steps mustBe empty
    }

    "ignore steps given as a single string" in {
      readOk(format, written(format, minimal) ++ Json.obj("steps" -> "Router")).steps mustBe empty
    }

    "ignore steps given as objects" in {
      readOk(format, written(format, minimal) ++ Json.obj("steps" -> Json.arr(Json.obj("name" -> "Router")))).steps mustBe empty
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a json array" in {
      readError(format, Json.arr(written(format, minimal)))
    }

    "not read a plugin without id" in {
      readError(format, written(format, full) - "id")
    }

    "not read a plugin without name" in {
      readError(format, written(format, full) - "name")
    }

    "not read a plugin without description" in {
      readError(format, written(format, full) - "description")
    }

    "not read the desc key in place of description" in {
      readError(format, written(format, full) - "description" ++ Json.obj("desc" -> "d"))
    }

    "not read an id that is not a string" in {
      readError(format, written(format, full) ++ Json.obj("id" -> 1))
    }

    "not read a null name" in {
      readError(format, written(format, full) ++ Json.obj("name" -> JsNull))
    }

    "read a plugin without tags with no tags" in {
      readOk(format, written(format, full) - "tags").tags mustBe empty
    }

    "read a plugin without metadata with no metadata" in {
      readOk(format, written(format, full) - "metadata").metadata mustBe empty
    }

    "ignore tags given as a single string" in {
      readOk(format, written(format, minimal) ++ Json.obj("tags" -> "prod")).tags mustBe empty
    }

    "ignore metadata holding a non string value" in {
      readOk(format, written(format, minimal) ++ Json.obj("metadata" -> Json.obj("a" -> "b", "n" -> 1))).metadata mustBe empty
    }

    "ignore unknown keys" in {
      readOk(format, written(format, full) ++ Json.obj("enabled" -> false, "foo" -> "bar")) mustBe full
    }

    "round trip a custom location" in {
      roundTrip(format, minimal.copy(location = customLocation)).location mustBe customLocation
    }

    "read a plugin without _loc in the default location" in {
      readOk(format, written(format, full) - "_loc").location mustBe EntityLocation()
    }

    "read a plugin whose _loc is not an object in the default location" in {
      readOk(format, written(format, full) ++ Json.obj("_loc" -> "tenant-1")).location mustBe EntityLocation()
    }

    "expose its fields through the generic accessors" in {
      hasAccessors(
        full,
        "wasm-plugin-2",
        "My wasm validator",
        "validates the access with wasm",
        Seq("wasm", "prod"),
        Map("owner" -> "ops", "ticket" -> "42")
      )
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
    }

    "be built from its json" in {
      WasmPlugin.fromJsons(full.json) mustBe full
    }

    "fail to be built from a json without id" in {
      a[Throwable] must be thrownBy WasmPlugin.fromJsons(written(format, full) - "id")
    }
  }
}
