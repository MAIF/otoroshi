package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class CachePluginConfigsSpec extends PluginConfigFormatSpec {

  "NgHttpClientCacheConfig" should {

    val format = NgHttpClientCacheConfig.format

    val default = NgHttpClientCacheConfig.default

    val full = NgHttpClientCacheConfig(
      maxAgeSeconds = 600L,
      methods = Seq("GET", "HEAD"),
      status = Seq(200, 203, 301),
      mimeTypes = Seq("text/css", "application/javascript", "image/*")
    )

    "cache html GET 200 responses for a day by default" in {
      default mustBe NgHttpClientCacheConfig(86400L, Seq("GET"), Seq(200), Seq("text/html"))
    }

    "write the default config" in {
      format.writes(default) mustBe Json.obj(
        "max_age_seconds" -> 86400,
        "methods"         -> Json.arr("GET"),
        "status"          -> Json.arr(200),
        "mime_types"      -> Json.arr("text/html")
      )
    }

    "write the statuses as numbers" in {
      (format.writes(full) \ "status").as[JsArray] mustBe Json.arr(200, 203, 301)
    }

    "round trip the default config" in {
      roundTrip(format, default)
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip empty lists instead of falling back to the defaults" in {
      roundTrip(format, NgHttpClientCacheConfig(0L, Seq.empty, Seq.empty, Seq.empty))
    }

    "round trip a max age larger than an int" in {
      roundTrip(format, default.copy(maxAgeSeconds = 10_000_000_000L)).maxAgeSeconds mustBe 10_000_000_000L
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe default
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe default
    }

    "read a json string as the default config" in {
      readOk(format, JsString("cache")) mustBe default
    }

    "read the max age alone and default the other fields" in {
      readOk(format, Json.obj("max_age_seconds" -> 60)) mustBe default.copy(maxAgeSeconds = 60L)
    }

    "read the mime types alone and default the other fields" in {
      readOk(format, Json.obj("mime_types" -> Json.arr("*"))) mustBe default.copy(mimeTypes = Seq("*"))
    }

    "read statuses written as strings" in {
      readOk(format, Json.obj("status" -> Json.arr("200", "404"))).status mustBe Seq(200, 404)
    }

    "fail to read statuses written as strings that are not numbers" in {
      readError(format, Json.obj("status" -> Json.arr("ok")))
    }

    "fall back to the default statuses when numbers and strings are mixed" in {
      readOk(format, Json.obj("status" -> Json.arr(200, "404"))).status mustBe Seq(200)
    }

    "ignore a max age written as a string" in {
      readOk(format, Json.obj("max_age_seconds" -> "60")).maxAgeSeconds mustBe 86400L
    }

    "ignore methods holding something else than strings" in {
      readOk(format, Json.obj("methods" -> Json.arr("GET", 1))).methods mustBe Seq("GET")
    }

    "ignore mime types written as a single string" in {
      readOk(format, Json.obj("mime_types" -> "text/css")).mimeTypes mustBe Seq("text/html")
    }

    "not read the camel case keys" in {
      readOk(format, Json.obj("maxAgeSeconds" -> 60, "mimeTypes" -> Json.arr("*"))) mustBe default
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of NgHttpClientCache" in {
      defaultConfigReadsBack(new NgHttpClientCache(), format) mustBe default
    }
  }

  "NgResponseCacheFilterConfig" should {

    val format = NgResponseCacheFilterConfig.format

    val full = NgResponseCacheFilterConfig(
      statuses = Seq(200, 404),
      methods = Seq("GET", "HEAD"),
      paths = Seq("/api/.*", "/static/.*"),
      notStatuses = Seq(500),
      notMethods = Seq("POST"),
      notPaths = Seq("/api/private/.*")
    )

    "default to GET 200 responses on every path" in {
      NgResponseCacheFilterConfig() mustBe NgResponseCacheFilterConfig(
        Seq(200),
        Seq("GET"),
        Seq("/.*"),
        Seq.empty,
        Seq.empty,
        Seq.empty
      )
    }

    "write the default filter" in {
      format.writes(NgResponseCacheFilterConfig()) mustBe Json.obj(
        "statuses"    -> Json.arr(200),
        "methods"     -> Json.arr("GET"),
        "paths"       -> Json.arr("/.*"),
        "notStatuses" -> Json.arr(),
        "notMethods"  -> Json.arr(),
        "notPaths"    -> Json.arr()
      )
    }

    "round trip the default filter" in {
      roundTrip(format, NgResponseCacheFilterConfig())
    }

    "round trip a filter where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a filter with empty lists" in {
      roundTrip(format, NgResponseCacheFilterConfig(Seq.empty, Seq.empty, Seq.empty))
    }

    "read an empty object without any path" in {
      readOk(format, Json.obj()) mustBe NgResponseCacheFilterConfig(paths = Seq.empty)
    }

    "read statuses written as strings" in {
      readOk(format, Json.obj("statuses" -> Json.arr("200", "301"))).statuses mustBe Seq(200, 301)
    }

    "fail to read statuses written as strings that are not numbers" in {
      readError(format, Json.obj("statuses" -> Json.arr("ok")))
    }

    "read the legacy nested not statuses written as strings" in {
      readOk(format, Json.obj("not" -> Json.obj("statuses" -> Json.arr("500", "502")))).notStatuses mustBe Seq(
        500,
        502
      )
    }

    "not read the legacy nested not statuses written as numbers" in {
      readOk(format, Json.obj("not" -> Json.obj("statuses" -> Json.arr(500)))).notStatuses mustBe empty
    }

    "read the legacy nested not methods and paths" in {
      val config = readOk(format, Json.obj("not" -> Json.obj("methods" -> Json.arr("POST"), "paths" -> Json.arr("/x"))))
      config.notMethods mustBe Seq("POST")
      config.notPaths mustBe Seq("/x")
    }

    "prefer the flat not keys over the legacy nested ones" in {
      val json = Json.obj(
        "notStatuses" -> Json.arr(500),
        "notMethods"  -> Json.arr("PUT"),
        "notPaths"    -> Json.arr("/a"),
        "not"         -> Json.obj(
          "statuses" -> Json.arr("502"),
          "methods"  -> Json.arr("POST"),
          "paths"    -> Json.arr("/b")
        )
      )
      readOk(format, json) mustBe NgResponseCacheFilterConfig(
        paths = Seq.empty,
        notStatuses = Seq(500),
        notMethods = Seq("PUT"),
        notPaths = Seq("/a")
      )
    }

    "rewrite the legacy nested not keys as flat keys" in {
      val legacy = readOk(format, Json.obj("not" -> Json.obj("methods" -> Json.arr("POST"))))
      val json   = written(format, legacy)
      json.keys must not contain "not"
      (json \ "notMethods").as[Seq[String]] mustBe Seq("POST")
    }

    "ignore methods holding something else than strings" in {
      readOk(format, Json.obj("methods" -> Json.arr("GET", 1))).methods mustBe Seq("GET")
    }
  }

  "NgResponseCacheConfig" should {

    val format = NgResponseCacheConfig.format

    val full = NgResponseCacheConfig(
      ttl = 30000L,
      maxSize = 1024L,
      autoClean = false,
      filter = Some(NgResponseCacheFilterConfig(statuses = Seq(200, 404), notPaths = Seq("/private/.*")))
    )

    "cache for an hour up to 50 megabytes without filter by default" in {
      val config = NgResponseCacheConfig()
      config.ttl mustBe 3600000L
      config.maxSize mustBe 52428800L
      config.autoClean mustBe true
      config.filter mustBe None
    }

    "write the ttl, maxSize and autoClean of the default config" in {
      (written(format, NgResponseCacheConfig()) - "filter") mustBe Json.obj(
        "ttl"       -> 3600000L,
        "maxSize"   -> 52428800L,
        "autoClean" -> true
      )
    }

    "write the filter with the filter format" in {
      (format.writes(full) \ "filter").as[JsValue] mustBe NgResponseCacheFilterConfig.format.writes(full.filter.get)
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgResponseCacheConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgResponseCacheConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr(1)) mustBe NgResponseCacheConfig()
    }

    "read the ttl alone and default the other fields" in {
      readOk(format, Json.obj("ttl" -> 1000)) mustBe NgResponseCacheConfig(ttl = 1000L)
    }

    "read autoClean alone and default the other fields" in {
      readOk(format, Json.obj("autoClean" -> false)) mustBe NgResponseCacheConfig(autoClean = false)
    }

    "read an empty filter object as a filter without path" in {
      readOk(format, Json.obj("filter" -> Json.obj())).filter mustBe Some(
        NgResponseCacheFilterConfig(paths = Seq.empty)
      )
    }

    "drop a filter that cannot be read and keep the other fields" in {
      val json = Json.obj("ttl" -> 1000, "filter" -> Json.obj("statuses" -> Json.arr("ok")))
      readOk(format, json) mustBe NgResponseCacheConfig(ttl = 1000L)
    }

    "ignore a ttl written as a string" in {
      readOk(format, Json.obj("ttl" -> "1000")).ttl mustBe 3600000L
    }

    "ignore a maxSize that is not a whole number" in {
      readOk(format, Json.obj("maxSize" -> 10.5)).maxSize mustBe 52428800L
    }

    "not read the snake case keys" in {
      readOk(format, Json.obj("max_size" -> 10, "auto_clean" -> false)) mustBe NgResponseCacheConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }
  }
}
