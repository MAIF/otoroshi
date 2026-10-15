package plugins.configs

import otoroshi.models.RedirectionSettings
import otoroshi.next.plugins.*
import play.api.libs.json.*

class RedirectionPluginConfigsSpec extends PluginConfigFormatSpec {

  "NgRedirectionSettings" should {

    val format = NgRedirectionSettings.format

    "default to a 303 redirection to the otoroshi website" in {
      NgRedirectionSettings().code mustBe 303
      NgRedirectionSettings().to mustBe "https://www.otoroshi.io"
    }

    "write the default config" in {
      format.writes(NgRedirectionSettings()) mustBe Json.obj(
        "code" -> 303,
        "to"   -> "https://www.otoroshi.io"
      )
    }

    "write the code and the target under code and to" in {
      format.writes(NgRedirectionSettings(301, "https://foo.oto.tools/bar")) mustBe Json.obj(
        "code" -> 301,
        "to"   -> "https://foo.oto.tools/bar"
      )
    }

    "round trip the default config" in {
      roundTrip(format, NgRedirectionSettings())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, NgRedirectionSettings(code = 308, to = "https://foo.oto.tools/new/path?q=1"))
    }

    "round trip every valid redirection code" in {
      roundTrip(format, NgRedirectionSettings(code = 301)).code mustBe 301
      roundTrip(format, NgRedirectionSettings(code = 302)).code mustBe 302
      roundTrip(format, NgRedirectionSettings(code = 307)).code mustBe 307
      roundTrip(format, NgRedirectionSettings(code = 308)).code mustBe 308
    }

    "round trip a code that is not a redirection code as is" in {
      roundTrip(format, NgRedirectionSettings(code = 200)).code mustBe 200
    }

    "round trip a target holding expression language" in {
      roundTrip(format, NgRedirectionSettings(to = "https://${req.host}/new${req.path}"))
    }

    "round trip a relative target" in {
      roundTrip(format, NgRedirectionSettings(to = "/login"))
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgRedirectionSettings()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgRedirectionSettings()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("https://foo.oto.tools")) mustBe NgRedirectionSettings()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr(301, "https://foo.oto.tools")) mustBe NgRedirectionSettings()
    }

    "read the code alone and keep the default target" in {
      readOk(format, Json.obj("code" -> 307)) mustBe NgRedirectionSettings(code = 307)
    }

    "read the target alone and keep the default code" in {
      readOk(format, Json.obj("to" -> "https://foo.oto.tools")) mustBe NgRedirectionSettings(
        to = "https://foo.oto.tools"
      )
    }

    "ignore a code written as a string" in {
      readOk(format, Json.obj("code" -> "301")) mustBe NgRedirectionSettings()
    }

    "ignore a code that is not a whole number" in {
      readOk(format, Json.obj("code" -> 301.5)) mustBe NgRedirectionSettings()
    }

    "ignore a code that does not fit an int" in {
      readOk(format, Json.obj("code" -> 3000000000L)) mustBe NgRedirectionSettings()
    }

    "ignore a target that is not a string" in {
      readOk(format, Json.obj("to" -> 42)) mustBe NgRedirectionSettings()
    }

    "replace an empty target by the default target" in {
      readOk(format, Json.obj("to" -> "", "code" -> 302)) mustBe NgRedirectionSettings(code = 302)
    }

    "replace a blank target by the default target" in {
      readOk(format, Json.obj("to" -> "   ")) mustBe NgRedirectionSettings()
    }

    "keep the spaces around a non blank target" in {
      readOk(format, Json.obj("to" -> " /login ")).to mustBe " /login "
    }

    "not read the enabled flag of the legacy settings" in {
      readOk(format, Json.obj("enabled" -> false, "code" -> 301)) mustBe NgRedirectionSettings(code = 301)
    }

    "have a valid code for every redirection code" in {
      NgRedirectionSettings(code = 301).hasValidCode mustBe true
      NgRedirectionSettings(code = 302).hasValidCode mustBe true
      NgRedirectionSettings(code = 303).hasValidCode mustBe true
      NgRedirectionSettings(code = 307).hasValidCode mustBe true
      NgRedirectionSettings(code = 308).hasValidCode mustBe true
    }

    "not have a valid code for other statuses" in {
      NgRedirectionSettings(code = 200).hasValidCode mustBe false
      NgRedirectionSettings(code = 304).hasValidCode mustBe false
      NgRedirectionSettings(code = 404).hasValidCode mustBe false
      NgRedirectionSettings(code = 0).hasValidCode mustBe false
    }

    "build enabled legacy settings" in {
      NgRedirectionSettings(code = 307, to = "https://foo.oto.tools").legacy mustBe RedirectionSettings(
        enabled = true,
        code = 307,
        to = "https://foo.oto.tools"
      )
    }

    "be built from legacy settings, whatever their enabled flag" in {
      NgRedirectionSettings.fromLegacy(
        RedirectionSettings(enabled = false, code = 308, to = "https://foo.oto.tools")
      ) mustBe NgRedirectionSettings(308, "https://foo.oto.tools")
    }

    "go back and forth with the legacy settings" in {
      val config = NgRedirectionSettings(code = 302, to = "/elsewhere")
      NgRedirectionSettings.fromLegacy(config.legacy) mustBe config
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, NgRedirectionSettings(301, "https://foo.oto.tools"))
    }

    "be the default config of Redirection" in {
      defaultConfigReadsBack(new Redirection(), format) mustBe NgRedirectionSettings()
    }
  }
}
