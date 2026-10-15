package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class PathsPluginConfigsSpec extends PluginConfigFormatSpec {

  "NgPublicPrivatePathsConfig" should {

    val format = NgPublicPrivatePathsConfig.format

    val full = NgPublicPrivatePathsConfig(
      strict = true,
      publicPatterns = Seq("/public/.*", "/health"),
      privatePatterns = Seq("/public/admin/.*")
    )

    "be lenient with no pattern by default" in {
      NgPublicPrivatePathsConfig() mustBe NgPublicPrivatePathsConfig(false, Seq.empty, Seq.empty)
    }

    "write the default config" in {
      format.writes(NgPublicPrivatePathsConfig()) mustBe Json.obj(
        "strict"           -> false,
        "private_patterns" -> Json.arr(),
        "public_patterns"  -> Json.arr()
      )
    }

    "write the public and private patterns under their own key" in {
      format.writes(full) mustBe Json.obj(
        "strict"           -> true,
        "private_patterns" -> Json.arr("/public/admin/.*"),
        "public_patterns"  -> Json.arr("/public/.*", "/health")
      )
    }

    "round trip the default config" in {
      roundTrip(format, NgPublicPrivatePathsConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip the strict mode alone" in {
      roundTrip(format, NgPublicPrivatePathsConfig(strict = true))
    }

    "round trip public patterns alone" in {
      roundTrip(format, NgPublicPrivatePathsConfig(publicPatterns = Seq("/docs/.*")))
    }

    "round trip private patterns alone" in {
      roundTrip(format, NgPublicPrivatePathsConfig(privatePatterns = Seq("/admin/.*")))
    }

    "round trip patterns holding regex characters" in {
      roundTrip(format, NgPublicPrivatePathsConfig(publicPatterns = Seq("^/api/v[0-9]+/(users|groups)\\?.*$")))
    }

    "keep the order of the patterns" in {
      roundTrip(format, NgPublicPrivatePathsConfig(privatePatterns = Seq("/c", "/a", "/b"))).privatePatterns mustBe Seq(
        "/c",
        "/a",
        "/b"
      )
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgPublicPrivatePathsConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgPublicPrivatePathsConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("/public/.*")) mustBe NgPublicPrivatePathsConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("/public/.*")) mustBe NgPublicPrivatePathsConfig()
    }

    "read the public patterns alone" in {
      readOk(format, Json.obj("public_patterns" -> Json.arr("/docs/.*"))) mustBe NgPublicPrivatePathsConfig(
        publicPatterns = Seq("/docs/.*")
      )
    }

    "read the strict mode alone" in {
      readOk(format, Json.obj("strict" -> true)) mustBe NgPublicPrivatePathsConfig(strict = true)
    }

    "ignore a strict mode written as a string" in {
      readOk(format, Json.obj("strict" -> "true")).strict mustBe false
    }

    "ignore patterns written as a string" in {
      readOk(format, Json.obj("private_patterns" -> "/admin/.*")).privatePatterns mustBe empty
    }

    "ignore patterns holding something else than strings" in {
      readOk(
        format,
        Json.obj("public_patterns" -> Json.arr("/docs/.*", 1), "strict" -> true)
      ) mustBe NgPublicPrivatePathsConfig(strict = true)
    }

    "not read the camel case keys" in {
      val json = Json.obj(
        "publicPatterns"  -> Json.arr("/docs/.*"),
        "privatePatterns" -> Json.arr("/admin/.*")
      )
      readOk(format, json) mustBe NgPublicPrivatePathsConfig()
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("strict" -> true, "foo" -> "bar")) mustBe NgPublicPrivatePathsConfig(strict = true)
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of PublicPrivatePaths" in {
      defaultConfigReadsBack(new PublicPrivatePaths(), format) mustBe NgPublicPrivatePathsConfig()
    }
  }
}
