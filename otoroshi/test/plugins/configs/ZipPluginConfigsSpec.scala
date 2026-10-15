package plugins.configs

import otoroshi.next.plugins.*
import otoroshi.utils.JsonPathValidator
import play.api.libs.json.*

class ZipPluginConfigsSpec extends PluginConfigFormatSpec {

  "ZipFileBackendConfig" should {

    val format = ZipFileBackendConfig.format

    val manualUrl = "https://github.com/MAIF/otoroshi/releases/download/16.11.2/otoroshi-manual-16.11.2.zip"

    val full = ZipFileBackendConfig(
      url = "file:///tmp/site.zip",
      headers = Map("Authorization" -> "Bearer token"),
      dir = "/var/zips",
      prefix = Some("site/"),
      ttl = 1000L
    )

    "serve the otoroshi manual from ./zips for an hour by default" in {
      ZipFileBackendConfig.default mustBe ZipFileBackendConfig(manualUrl, Map.empty, "./zips", None, 3600000L)
    }

    "write the default config" in {
      format.writes(ZipFileBackendConfig.default) mustBe Json.obj(
        "url"     -> manualUrl,
        "headers" -> Json.obj(),
        "dir"     -> "./zips",
        "prefix"  -> JsNull,
        "ttl"     -> 3600000L
      )
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "url"     -> "file:///tmp/site.zip",
        "headers" -> Json.obj("Authorization" -> "Bearer token"),
        "dir"     -> "/var/zips",
        "prefix"  -> "site/",
        "ttl"     -> 1000L
      )
    }

    "round trip the default config" in {
      roundTrip(format, ZipFileBackendConfig.default)
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a config without prefix" in {
      roundTrip(format, full.copy(prefix = None)).prefix mustBe None
    }

    "round trip a url holding expression language" in {
      roundTrip(format, ZipFileBackendConfig.default.copy(url = "https://zips.oto.tools/${req.headers.x-site}.zip"))
    }

    "round trip a zero ttl" in {
      roundTrip(format, ZipFileBackendConfig.default.copy(ttl = 0L)).ttl mustBe 0L
    }

    "read an empty prefix as no prefix" in {
      readOk(format, Json.obj("url" -> manualUrl, "prefix" -> "")).prefix mustBe None
    }

    "keep a blank prefix made of spaces" in {
      readOk(format, Json.obj("url" -> manualUrl, "prefix" -> "  ")).prefix mustBe Some("  ")
    }

    "not read an empty object, the url being mandatory" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a json string" in {
      readError(format, JsString(manualUrl))
    }

    "not read a json array" in {
      readError(format, Json.arr(manualUrl))
    }

    "not read a url that is not a string" in {
      readError(format, Json.obj("url" -> 42))
    }

    "read a url alone with the default for everything else" in {
      readOk(format, Json.obj("url" -> "file:///tmp/a.zip")) mustBe ZipFileBackendConfig.default.copy(
        url = "file:///tmp/a.zip"
      )
    }

    "fall back to the default dir when it is not a string" in {
      readOk(format, Json.obj("url" -> manualUrl, "dir" -> 42)).dir mustBe "./zips"
    }

    "fall back to the default ttl when it is a string" in {
      readOk(format, Json.obj("url" -> manualUrl, "ttl" -> "1000")).ttl mustBe 3600000L
    }

    "fall back to the default ttl when it is not a whole number" in {
      readOk(format, Json.obj("url" -> manualUrl, "ttl" -> 1000.5)).ttl mustBe 3600000L
    }

    "ignore the headers as soon as a value is not a string" in {
      readOk(format, Json.obj("url" -> manualUrl, "headers" -> Json.obj("X-Foo" -> 1))).headers mustBe empty
    }

    "ignore a prefix that is not a string" in {
      readOk(format, Json.obj("url" -> manualUrl, "prefix" -> true)).prefix mustBe None
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("url" -> manualUrl, "foo" -> "bar")) mustBe ZipFileBackendConfig.default
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of ZipFileBackend" in {
      defaultConfigReadsBack(new ZipFileBackend(), format) mustBe ZipFileBackendConfig.default
    }
  }

  "ZipBombBackendConfig" should {

    val format = ZipBombBackendConfig.format

    val predicate      = JsonPathValidator("$.apikey.metadata.foo", JsString("Contains(bar)"))
    val otherPredicate = JsonPathValidator("$.request.method", JsString("POST"), Some("not a post"))

    val full = ZipBombBackendConfig(
      predicates = Seq(predicate, otherPredicate),
      or = true,
      size = "1G",
      status = Some(418),
      contentType = Some("application/zip")
    )

    "send a 10G bomb on every request by default" in {
      ZipBombBackendConfig() mustBe ZipBombBackendConfig(Seq.empty, or = false, size = "10G", None, None)
    }

    "write the default config" in {
      format.writes(ZipBombBackendConfig()) mustBe Json.obj(
        "predicates"   -> Json.arr(),
        "or"           -> false,
        "size"         -> "10G",
        "status"       -> JsNull,
        "content_type" -> JsNull
      )
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "predicates"   -> Json.arr(
          Json.obj(
            "kind"  -> "json-path-validator",
            "path"  -> "$.apikey.metadata.foo",
            "value" -> "Contains(bar)",
            "error" -> JsNull
          ),
          Json.obj(
            "kind"  -> "json-path-validator",
            "path"  -> "$.request.method",
            "value" -> "POST",
            "error" -> "not a post"
          )
        ),
        "or"           -> true,
        "size"         -> "1G",
        "status"       -> 418,
        "content_type" -> "application/zip"
      )
    }

    "round trip the default config" in {
      roundTrip(format, ZipBombBackendConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip every size offered by the form" in {
      Seq("10M", "100M", "1G", "5G", "10G").foreach { size =>
        roundTrip(format, ZipBombBackendConfig(size = size)).size mustBe size
      }
    }

    "round trip a size the form does not offer as is" in {
      roundTrip(format, ZipBombBackendConfig(size = "42T")).size mustBe "42T"
    }

    "round trip a status alone" in {
      roundTrip(format, ZipBombBackendConfig(status = Some(200))).status mustBe Some(200)
    }

    "round trip a content type alone" in {
      roundTrip(format, ZipBombBackendConfig(contentType = Some("text/plain"))).contentType mustBe Some("text/plain")
    }

    "round trip predicates whose values are not strings" in {
      val predicates = Seq(
        JsonPathValidator("$.count", JsNumber(42)),
        JsonPathValidator("$.enabled", JsBoolean(true)),
        JsonPathValidator("$.user", Json.obj("name" -> "foo"))
      )
      roundTrip(format, ZipBombBackendConfig(predicates = predicates)).predicates mustBe predicates
    }

    "keep the order of the predicates" in {
      roundTrip(format, ZipBombBackendConfig(predicates = Seq(otherPredicate, predicate))).predicates mustBe Seq(
        otherPredicate,
        predicate
      )
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe ZipBombBackendConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe ZipBombBackendConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("10G")) mustBe ZipBombBackendConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("10G")) mustBe ZipBombBackendConfig()
    }

    "read the or operator alone" in {
      readOk(format, Json.obj("or" -> true)) mustBe ZipBombBackendConfig(or = true)
    }

    "read a size alone" in {
      readOk(format, Json.obj("size" -> "10M")) mustBe ZipBombBackendConfig(size = "10M")
    }

    "read a null status and content type as none" in {
      readOk(format, Json.obj("status" -> JsNull, "content_type" -> JsNull)) mustBe ZipBombBackendConfig()
    }

    "read a predicate without kind" in {
      readOk(
        format,
        Json.obj("predicates" -> Json.arr(Json.obj("path" -> "$.apikey.metadata.foo", "value" -> "Contains(bar)")))
      ).predicates mustBe Seq(predicate)
    }

    "drop the predicates that cannot be read and keep the others" in {
      val json = Json.obj(
        "predicates" -> Json.arr(
          Json.obj("path" -> "$.apikey.metadata.foo", "value" -> "Contains(bar)"),
          Json.obj("value" -> "no path"),
          Json.obj("path" -> "$.no.value"),
          "not an object"
        )
      )
      readOk(format, json).predicates mustBe Seq(predicate)
    }

    "read a blank predicate error as no error" in {
      readOk(
        format,
        Json.obj("predicates" -> Json.arr(Json.obj("path" -> "$.a", "value" -> "b", "error" -> " ")))
      ).predicates mustBe Seq(JsonPathValidator("$.a", JsString("b"), None))
    }

    "ignore predicates that are not an array" in {
      readOk(format, Json.obj("predicates" -> Json.obj("path" -> "$.a", "value" -> "b"))).predicates mustBe empty
    }

    "ignore an or operator written as a string" in {
      readOk(format, Json.obj("or" -> "true")).or mustBe false
    }

    "ignore a status written as a string" in {
      readOk(format, Json.obj("status" -> "418")).status mustBe None
    }

    "ignore a size that is not a string" in {
      readOk(format, Json.obj("size" -> 10)).size mustBe "10G"
    }

    "ignore a content type that is not a string" in {
      readOk(format, Json.obj("content_type" -> 42)).contentType mustBe None
    }

    "not read the camel case content type" in {
      readOk(format, Json.obj("contentType" -> "text/plain")).contentType mustBe None
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("or" -> true, "foo" -> "bar")) mustBe ZipBombBackendConfig(or = true)
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "list every field in its config flow and schema" in {
      ZipBombBackendConfig.configFlow mustBe Seq("or", "size", "status", "content_type", "predicates")
      ZipBombBackendConfig.configFlow.foreach { field =>
        ZipBombBackendConfig.configSchema.get.keys must contain(field)
      }
    }

    "only offer sizes the plugin knows in its schema" in {
      val options = (ZipBombBackendConfig.configSchema.get \ "size" \ "props" \ "options").as[Seq[JsObject]]
      options.map(o => (o \ "value").as[String]) mustBe Seq("10M", "100M", "1G", "5G", "10G")
    }

    "be the config flow and schema of ZipBombBackend" in {
      val plugin = new ZipBombBackend()
      plugin.configFlow mustBe ZipBombBackendConfig.configFlow
      plugin.configSchema mustBe ZipBombBackendConfig.configSchema
    }

    "be the default config of ZipBombBackend" in {
      defaultConfigReadsBack(new ZipBombBackend(), format) mustBe ZipBombBackendConfig()
    }
  }
}
