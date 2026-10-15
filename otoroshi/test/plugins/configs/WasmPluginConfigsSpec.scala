package plugins.configs

import io.otoroshi.wasm4s.scaladsl.{WasmSource, WasmSourceKind, WasmVmKillOptions, WasmVmLifetime}
import otoroshi.wasm.{WasmAuthorizations, WasmConfig, WasmDataRights}
import play.api.libs.json.*

import scala.concurrent.duration.*

class WasmPluginConfigsSpec extends PluginConfigFormatSpec {

  "WasmConfig" should {

    val format = WasmConfig.format

    val noRights = Json.obj("read" -> false, "write" -> false)

    val customAuthorizations = WasmAuthorizations(
      httpAccess = true,
      globalDataStoreAccess = WasmDataRights(read = true, write = true),
      pluginDataStoreAccess = WasmDataRights(read = true, write = false),
      globalMapAccess = WasmDataRights(read = false, write = true),
      pluginMapAccess = WasmDataRights(read = true, write = true),
      proxyStateAccess = true,
      configurationAccess = true,
      proxyHttpCallTimeout = 1500
    )

    val customKillOptions = WasmVmKillOptions(
      immortal = true,
      maxCalls = 1000,
      maxMemoryUsage = 0.75,
      maxAvgCallDuration = 250.millis,
      maxUnusedDuration = 2.minutes
    )

    val custom = WasmConfig(
      source = WasmSource(WasmSourceKind.Http, "https://wasm.oto.tools/plugin.wasm", Json.obj("method" -> "POST")),
      memoryPages = 20,
      functionName = Some("handle"),
      config = Map("key" -> "value", "other" -> "thing"),
      allowedHosts = Seq("api.oto.tools", "*.maif.fr"),
      allowedPaths = Map("/tmp" -> "/sandbox"),
      wasi = true,
      opa = true,
      httpWasm = true,
      instances = 4,
      killOptions = customKillOptions,
      authorizations = customAuthorizations,
      isJS = true
    )

    "write the default config" in {
      format.writes(WasmConfig()) mustBe Json.obj(
        "source"         -> Json.obj("kind" -> "Unknown", "path" -> "", "opts" -> Json.obj()),
        "memoryPages"    -> 50,
        "functionName"   -> JsNull,
        "config"         -> Json.obj(),
        "allowedHosts"   -> Json.arr(),
        "allowedPaths"   -> Json.obj(),
        "wasi"           -> false,
        "opa"            -> false,
        "httpWasm"       -> false,
        "authorizations" -> Json.obj(
          "httpAccess"            -> false,
          "proxyHttpCallTimeout"  -> 5000,
          "globalDataStoreAccess" -> noRights,
          "pluginDataStoreAccess" -> noRights,
          "globalMapAccess"       -> noRights,
          "pluginMapAccess"       -> noRights,
          "proxyStateAccess"      -> false,
          "configurationAccess"   -> false
        ),
        "instances"      -> 1,
        "killOptions"    -> Json.obj(
          "immortal"              -> false,
          "max_calls"             -> Int.MaxValue,
          "max_memory_usage"      -> 0.0,
          "max_avg_call_duration" -> 0,
          "max_unused_duration"   -> 300000
        ),
        "isJS"           -> false
      )
    }

    "round trip the default config" in {
      roundTrip(format, WasmConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, custom)
    }

    "round trip every source kind" in {
      Seq(
        WasmSourceKind.Unknown,
        WasmSourceKind.Base64,
        WasmSourceKind.Http,
        WasmSourceKind.Wasmo,
        WasmSourceKind.Local,
        WasmSourceKind.File,
        WasmSourceKind.ClassPath
      ).foreach { kind =>
        roundTrip(format, WasmConfig(source = WasmSource(kind, "some-path"))).source.kind mustBe kind
      }
    }

    "write the source kind by its name" in {
      (written(format, WasmConfig(source = WasmSource(WasmSourceKind.Wasmo, "plugin"))) \ "source" \ "kind")
        .as[String] mustBe "Wasmo"
      (written(format, WasmConfig(source = WasmSource(WasmSourceKind.ClassPath, "p"))) \ "source" \ "kind")
        .as[String] mustBe "ClassPath"
    }

    "read the source kind whatever its case" in {
      readOk(format, Json.obj("source" -> Json.obj("kind" -> "HTTP", "path" -> "p"))).source.kind mustBe WasmSourceKind.Http
      readOk(format, Json.obj("source" -> Json.obj("kind" -> "base64", "path" -> "p"))).source.kind mustBe WasmSourceKind.Base64
    }

    "read the wasmmanager source kind as wasmo" in {
      readOk(format, Json.obj("source" -> Json.obj("kind" -> "WasmManager", "path" -> "p"))).source.kind mustBe WasmSourceKind.Wasmo
    }

    "read an unknown or missing source kind as unknown" in {
      readOk(format, Json.obj("source" -> Json.obj("kind" -> "ftp", "path" -> "p"))).source.kind mustBe WasmSourceKind.Unknown
      readOk(format, Json.obj("source" -> Json.obj("path" -> "p"))).source mustBe WasmSource(WasmSourceKind.Unknown, "p")
    }

    "fail to read a source without path" in {
      readError(format, Json.obj("source" -> Json.obj("kind" -> "Http")))
    }

    "round trip the source options" in {
      val opts = Json.obj("headers" -> Json.obj("Authorization" -> "Bearer x"), "timeout" -> 5000, "followRedirect" -> false)
      roundTrip(format, WasmConfig(source = WasmSource(WasmSourceKind.Http, "https://a/b.wasm", opts))).source.opts mustBe opts
    }

    "read a source without options with empty options" in {
      readOk(format, Json.obj("source" -> Json.obj("kind" -> "File", "path" -> "/p.wasm"))).source.opts mustBe Json.obj()
    }

    "read a legacy compiler_source as a wasmo source" in {
      readOk(format, Json.obj("compiler_source" -> "my-plugin")).source mustBe WasmSource(WasmSourceKind.Wasmo, "my-plugin")
    }

    "read a legacy raw_source by its scheme" in {
      def sourceOf(raw: String): WasmSource = readOk(format, Json.obj("raw_source" -> raw)).source
      sourceOf("http://wasm.oto.tools/p.wasm") mustBe WasmSource(WasmSourceKind.Http, "http://wasm.oto.tools/p.wasm")
      sourceOf("https://wasm.oto.tools/p.wasm") mustBe WasmSource(WasmSourceKind.Http, "https://wasm.oto.tools/p.wasm")
      sourceOf("file:///opt/p.wasm") mustBe WasmSource(WasmSourceKind.File, "/opt/p.wasm")
      sourceOf("base64://AGFzbQ==") mustBe WasmSource(WasmSourceKind.Base64, "AGFzbQ==")
      sourceOf("entity://wasm-plugin-1") mustBe WasmSource(WasmSourceKind.Local, "wasm-plugin-1")
      sourceOf("local://wasm-plugin-2") mustBe WasmSource(WasmSourceKind.Local, "wasm-plugin-2")
    }

    "read a legacy raw_source without scheme as base64" in {
      readOk(format, Json.obj("raw_source" -> "AGFzbQ==")).source mustBe WasmSource(WasmSourceKind.Base64, "AGFzbQ==")
    }

    "prefer the source object over the legacy keys" in {
      readOk(
        format,
        Json.obj(
          "source"          -> Json.obj("kind" -> "Local", "path" -> "wasm-plugin-1"),
          "compiler_source" -> "my-plugin",
          "raw_source"      -> "https://a/b.wasm"
        )
      ).source mustBe WasmSource(WasmSourceKind.Local, "wasm-plugin-1")
    }

    "prefer compiler_source over raw_source" in {
      readOk(format, Json.obj("compiler_source" -> "my-plugin", "raw_source" -> "https://a/b.wasm")).source mustBe WasmSource(
        WasmSourceKind.Wasmo,
        "my-plugin"
      )
    }

    "fall back to the legacy keys when the source is not an object" in {
      readOk(format, Json.obj("source" -> "https://a/b.wasm", "raw_source" -> "https://c/d.wasm")).source mustBe WasmSource(
        WasmSourceKind.Http,
        "https://c/d.wasm"
      )
    }

    "rewrite the legacy source keys as a source object" in {
      val json = written(format, readOk(format, Json.obj("compiler_source" -> "my-plugin")))
      json.keys must not contain "compiler_source"
      (json \ "source").get mustBe Json.obj("kind" -> "Wasmo", "path" -> "my-plugin", "opts" -> Json.obj())
    }

    // the reads default to 100 memory pages, where the case class has 50
    "read an empty object as the default config with 100 memory pages" in {
      readOk(format, Json.obj()) mustBe WasmConfig(memoryPages = 100)
    }

    "read a json null as the default config with 100 memory pages" in {
      readOk(format, JsNull) mustBe WasmConfig(memoryPages = 100)
    }

    "read a json string as the default config with 100 memory pages" in {
      readOk(format, JsString("https://a/b.wasm")) mustBe WasmConfig(memoryPages = 100)
    }

    "read a json array as the default config with 100 memory pages" in {
      readOk(format, Json.arr(Json.obj("memoryPages" -> 10))) mustBe WasmConfig(memoryPages = 100)
    }

    "read lone fields and default the rest" in {
      readOk(format, Json.obj("memoryPages" -> 50, "wasi" -> true)) mustBe WasmConfig(wasi = true)
      readOk(format, Json.obj("memoryPages" -> 50, "instances" -> 8)) mustBe WasmConfig(instances = 8)
    }

    "round trip a config without function name" in {
      roundTrip(format, custom.copy(functionName = None)).functionName mustBe None
    }

    "read an empty function name as no function name" in {
      readOk(format, Json.obj("functionName" -> "")).functionName mustBe None
    }

    "keep a blank function name" in {
      readOk(format, Json.obj("functionName" -> " ")).functionName mustBe Some(" ")
    }

    "ignore numbers written as strings" in {
      val back = readOk(format, Json.obj("memoryPages" -> "20", "instances" -> "4"))
      back.memoryPages mustBe 100
      back.instances mustBe 1
    }

    "ignore flags written as strings" in {
      readOk(format, Json.obj("memoryPages" -> 50, "wasi" -> "true", "opa" -> "true", "isJS" -> "true")) mustBe WasmConfig()
    }

    "ignore a config map holding something else than strings" in {
      readOk(format, Json.obj("config" -> Json.obj("a" -> "b", "n" -> 1))).config mustBe Map.empty
    }

    "ignore allowed hosts and paths of the wrong type" in {
      val back = readOk(format, Json.obj("allowedHosts" -> "api.oto.tools", "allowedPaths" -> Json.arr("/tmp")))
      back.allowedHosts mustBe empty
      back.allowedPaths mustBe empty
    }

    "not read the snake case keys" in {
      readOk(
        format,
        Json.obj("memory_pages" -> 10, "function_name" -> "f", "allowed_hosts" -> Json.arr("h"), "http_wasm" -> true)
      ) mustBe WasmConfig(memoryPages = 100)
    }

    "round trip the authorizations alone" in {
      roundTrip(format, WasmConfig(authorizations = customAuthorizations)).authorizations mustBe customAuthorizations
    }

    "read the legacy accesses key as the authorizations" in {
      val json = Json.obj("accesses" -> Json.obj("httpAccess" -> true, "proxyHttpCallTimeout" -> 100))
      readOk(format, json).authorizations mustBe WasmAuthorizations(httpAccess = true, proxyHttpCallTimeout = 100)
    }

    "prefer the authorizations over the legacy accesses" in {
      val json = Json.obj(
        "authorizations" -> Json.obj("proxyStateAccess" -> true),
        "accesses"       -> Json.obj("httpAccess" -> true)
      )
      readOk(format, json).authorizations mustBe WasmAuthorizations(proxyStateAccess = true)
    }

    "rewrite the legacy accesses as authorizations" in {
      val json = written(format, readOk(format, Json.obj("accesses" -> Json.obj("httpAccess" -> true))))
      json.keys must not contain "accesses"
      (json \ "authorizations" \ "httpAccess").as[Boolean] mustBe true
    }

    "read partial authorizations and data rights" in {
      readOk(
        format,
        Json.obj("authorizations" -> Json.obj("pluginMapAccess" -> Json.obj("read" -> true)))
      ).authorizations mustBe WasmAuthorizations(pluginMapAccess = WasmDataRights(read = true))
    }

    "ignore data rights of the wrong type" in {
      readOk(
        format,
        Json.obj("authorizations" -> Json.obj("globalDataStoreAccess" -> Json.obj("read" -> "true", "write" -> 1)))
      ).authorizations mustBe WasmAuthorizations()
    }

    "ignore an authorizations value that is not an object" in {
      readOk(format, Json.obj("authorizations" -> "all")).authorizations mustBe WasmAuthorizations()
    }

    "write the kill options under snake case keys with durations in milliseconds" in {
      (written(format, custom) \ "killOptions").get mustBe Json.obj(
        "immortal"              -> true,
        "max_calls"             -> 1000,
        "max_memory_usage"      -> 0.75,
        "max_avg_call_duration" -> 250,
        "max_unused_duration"   -> 120000
      )
    }

    "round trip the kill options alone" in {
      roundTrip(format, WasmConfig(killOptions = customKillOptions)).killOptions mustBe customKillOptions
    }

    "read partial kill options" in {
      readOk(format, Json.obj("killOptions" -> Json.obj("max_calls" -> 3))).killOptions mustBe WasmVmKillOptions(maxCalls = 3)
    }

    "ignore kill options that are not an object" in {
      readOk(format, Json.obj("killOptions" -> "immortal")).killOptions mustBe WasmVmKillOptions.default
    }

    "not read the camel case kill options keys" in {
      readOk(format, Json.obj("killOptions" -> Json.obj("maxCalls" -> 3))).killOptions mustBe WasmVmKillOptions.default
    }

    "ignore the legacy lifetime keys and always live forever" in {
      readOk(format, Json.obj("lifetime" -> "Request", "preserve" -> true)).lifetime mustBe WasmVmLifetime.Forever
      writtenKeys(format, custom) must not contain "lifetime"
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, custom)
    }
  }
}
