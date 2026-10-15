package entities

import otoroshi.auth.*
import otoroshi.models.*
import otoroshi.next.models.NgTlsConfig
import otoroshi.utils.JsonPathValidator
import play.api.libs.json.*

import scala.concurrent.duration.*

class WasmAuthModuleEntityFormatSpec extends EntityFormatSpec {

  "WasmAuthModuleConfig" should {

    val format = WasmAuthModuleConfig.format

    val minimal = WasmAuthModuleConfig(
      location = EntityLocation(),
      id = "auth_mod_wasm",
      name = "Wasm",
      description = "a wasm module",
      tags = Seq.empty,
      metadata = Map.empty,
      sessionMaxAge = 86400,
      clientSideSessionEnabled = true,
      sessionCookieValues = SessionCookieValues(),
      wasmRef = None
    )

    val full = WasmAuthModuleConfig(
      location = customLocation,
      id = "auth_mod_wasm",
      name = "Wasm",
      description = "a wasm module",
      tags = Seq("wasm"),
      metadata = Map("owner" -> "dev"),
      sessionMaxAge = 60,
      clientSideSessionEnabled = false,
      sessionCookieValues = SessionCookieValues(httpOnly = false, secure = false),
      userValidators = Seq(JsonPathValidator("$.role", JsString("admin"), Some("not an admin"))),
      remoteValidators = Seq(RemoteUserValidatorSettings("https://validator.oto.tools", Map("a" -> "b"), 3.seconds, NgTlsConfig(loose = true))),
      wasmRef = Some("wasm-plugin-1"),
      allowedUsers = Seq("Regex(.*@oto.tools)"),
      deniedUsers = Seq("bob@oto.tools")
    )

    "write a minimal module" in {
      format.writes(minimal) mustBe Json.obj(
        "_loc"                     -> defaultLocationJson,
        "type"                     -> "wasm",
        "id"                       -> "auth_mod_wasm",
        "name"                     -> "Wasm",
        "desc"                     -> "a wasm module",
        "tags"                     -> Json.arr(),
        "metadata"                 -> Json.obj(),
        "sessionMaxAge"            -> 86400,
        "clientSideSessionEnabled" -> true,
        "sessionCookieValues"      -> Json.obj("httpOnly" -> true, "secure" -> true),
        "userValidators"           -> Json.arr(),
        "remoteValidators"         -> Json.arr(),
        "allowedUsers"             -> Json.arr(),
        "deniedUsers"              -> Json.arr(),
        "wasmRef"                  -> JsNull
      )
    }

    "write its description under the desc key only" in {
      val json = written(format, full)
      (json \ "desc").as[String] mustBe "a wasm module"
      json.keys must not contain "description"
    }

    "write its wasm plugin ref" in {
      (written(format, full) \ "wasmRef").as[String] mustBe "wasm-plugin-1"
    }

    "round trip a minimal module" in {
      roundTrip(format, minimal)
    }

    "round trip a module where every field is set" in {
      roundTrip(format, full)
    }

    "keep its validators, allowed and denied users on a round trip" in {
      val back = roundTrip(format, full)
      back.userValidators mustBe full.userValidators
      back.remoteValidators mustBe full.remoteValidators
      back.allowedUsers mustBe Seq("Regex(.*@oto.tools)")
      back.deniedUsers mustBe Seq("bob@oto.tools")
    }

    "round trip its custom location" in {
      roundTrip(format, minimal.copy(location = customLocation)).location mustBe customLocation
    }

    "read the default location without _loc" in {
      readOk(format, written(format, full) - "_loc").location mustBe EntityLocation()
    }

    "read the defaults of a module with only an id and a name" in {
      readOk(format, Json.obj("id" -> "auth_mod_wasm", "name" -> "Wasm")) mustBe minimal.copy(description = "--")
    }

    "not read a module without id" in {
      readError(format, Json.obj("name" -> "Wasm"))
    }

    "not read a module without name" in {
      readError(format, Json.obj("id" -> "auth_mod_wasm"))
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "read the description key" in {
      readOk(format, Json.obj("id" -> "a", "name" -> "b", "description" -> "from description")).description mustBe "from description"
    }

    "prefer the description key over the desc key" in {
      readOk(format, Json.obj("id" -> "a", "name" -> "b", "description" -> "d1", "desc" -> "d2")).description mustBe "d1"
    }

    "read a blank wasm plugin ref as missing" in {
      readOk(format, Json.obj("id" -> "a", "name" -> "b", "wasmRef" -> "  ")).wasmRef mustBe None
    }

    "ignore a wasm plugin ref that is not a string" in {
      readOk(format, Json.obj("id" -> "a", "name" -> "b", "wasmRef" -> Json.obj("id" -> "x"))).wasmRef mustBe None
    }

    "fall back to the defaults for values of the wrong type" in {
      val back = readOk(format, Json.obj("id" -> "a", "name" -> "b", "sessionMaxAge" -> "60", "clientSideSessionEnabled" -> 0))
      back.sessionMaxAge mustBe 86400
      back.clientSideSessionEnabled mustBe true
    }

    "drop the user validators that cannot be read" in {
      val json = Json.obj("id" -> "a", "name" -> "b", "userValidators" -> Json.arr(Json.obj("path" -> "$.a", "value" -> 1), "x"))
      readOk(format, json).userValidators mustBe Seq(JsonPathValidator("$.a", JsNumber(1)))
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
    }

    "expose its fields through the generic accessors" in {
      hasAccessors(full, "auth_mod_wasm", "Wasm", "a wasm module", Seq("wasm"), Map("owner" -> "dev"))
    }

    "expose its description as desc" in {
      full.desc mustBe "a wasm module"
    }

    "describe its type" in {
      full.`type` mustBe "wasm"
      full.humanName mustBe "Wasm auth. module provider"
      full.form mustBe None
    }

    "name its cookie after its id" in {
      full.cookieSuffix(null) mustBe "wasm-auth-auth_mod_wasm"
    }

    "change its location only with withLocation" in {
      minimal.withLocation(customLocation) mustBe minimal.copy(location = customLocation)
    }

    "have a default config without client side session nor wasm plugin" in {
      val default = WasmAuthModuleConfig.defaultConfig
      default.id must startWith("auth_mod_")
      default.clientSideSessionEnabled mustBe false
      default.wasmRef mustBe None
      roundTrip(format, default)
    }
  }
}
