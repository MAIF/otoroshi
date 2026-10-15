package plugins.configs

import otoroshi.next.plugins.*
import otoroshi.utils.gzip.GzipConfig
import play.api.libs.json.*

class GzipPluginConfigsSpec extends PluginConfigFormatSpec {

  "NgGzipConfig" should {

    val format = NgGzipConfig.format

    val full = NgGzipConfig(
      excludedPatterns = Seq("/api/stream/.*", "/download/.*"),
      whiteList = Seq("text/html", "application/xml"),
      blackList = Seq("image/*"),
      bufferSize = 4096,
      chunkedThreshold = 51200,
      compressionLevel = 9
    )

    "compress text, javascript and json by default" in {
      NgGzipConfig() mustBe NgGzipConfig(
        Seq.empty,
        Seq("text/*", "application/javascript", "application/json"),
        Seq.empty,
        8192,
        102400,
        5
      )
    }

    "write the default config" in {
      format.writes(NgGzipConfig()) mustBe Json.obj(
        "excluded_patterns" -> Json.arr(),
        "allowed_list"      -> Json.arr("text/*", "application/javascript", "application/json"),
        "blocked_list"      -> Json.arr(),
        "buffer_size"       -> 8192,
        "chunked_threshold" -> 102400,
        "compression_level" -> 5
      )
    }

    "write the white and black lists as allowed and blocked lists" in {
      val json = written(format, full)
      (json \ "allowed_list").as[Seq[String]] mustBe Seq("text/html", "application/xml")
      (json \ "blocked_list").as[Seq[String]] mustBe Seq("image/*")
      json.keys must contain noneOf ("whiteList", "blackList", "white_list", "black_list")
    }

    "round trip the default config" in {
      roundTrip(format, NgGzipConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip an empty allowed list instead of falling back to the default one" in {
      roundTrip(format, NgGzipConfig(whiteList = Seq.empty)).whiteList mustBe empty
    }

    "round trip the bounds of the compression level" in {
      roundTrip(format, NgGzipConfig(compressionLevel = 0))
      roundTrip(format, NgGzipConfig(compressionLevel = 9))
    }

    "keep the order of the patterns" in {
      roundTrip(format, full.copy(excludedPatterns = full.excludedPatterns.reverse)).excludedPatterns mustBe Seq(
        "/download/.*",
        "/api/stream/.*"
      )
    }

    "read an empty object with an empty allowed list" in {
      readOk(format, Json.obj()) mustBe NgGzipConfig(whiteList = Seq.empty)
    }

    "not read an empty object as the default config" in {
      readOk(format, Json.obj()) must not be NgGzipConfig()
    }

    "read a json null like an empty object" in {
      readOk(format, JsNull) mustBe NgGzipConfig(whiteList = Seq.empty)
    }

    "read a json string like an empty object" in {
      readOk(format, JsString("gzip")) mustBe NgGzipConfig(whiteList = Seq.empty)
    }

    "read the compression level alone and default the numbers" in {
      readOk(format, Json.obj("compression_level" -> 1)) mustBe NgGzipConfig(
        whiteList = Seq.empty,
        compressionLevel = 1
      )
    }

    "read the allowed list alone" in {
      readOk(format, Json.obj("allowed_list" -> Json.arr("text/plain"))) mustBe NgGzipConfig(
        whiteList = Seq("text/plain")
      )
    }

    "ignore a buffer size written as a string" in {
      readOk(format, Json.obj("buffer_size" -> "4096")).bufferSize mustBe 8192
    }

    "ignore a chunked threshold that does not fit an int" in {
      readOk(format, Json.obj("chunked_threshold" -> 10_000_000_000L)).chunkedThreshold mustBe 102400
    }

    "ignore a blocked list holding something else than strings" in {
      readOk(format, Json.obj("blocked_list" -> Json.arr("image/*", 1))).blackList mustBe empty
    }

    "ignore excluded patterns written as a single string" in {
      readOk(format, Json.obj("excluded_patterns" -> "/api/.*")).excludedPatterns mustBe empty
    }

    "not read the names of the case class fields" in {
      val json = Json.obj(
        "whiteList"        -> Json.arr("text/html"),
        "blackList"        -> Json.arr("image/*"),
        "excludedPatterns" -> Json.arr("/x"),
        "bufferSize"       -> 1
      )
      readOk(format, json) mustBe NgGzipConfig(whiteList = Seq.empty)
    }

    "convert to an enabled legacy config" in {
      full.legacy mustBe GzipConfig(
        enabled = true,
        excludedPatterns = full.excludedPatterns,
        whiteList = full.whiteList,
        blackList = full.blackList,
        bufferSize = 4096,
        chunkedThreshold = 51200,
        compressionLevel = 9
      )
    }

    "convert back from the legacy config" in {
      NgGzipConfig.fromLegacy(full.legacy) mustBe full
    }

    "convert from a disabled legacy config as if it was enabled" in {
      NgGzipConfig.fromLegacy(GzipConfig(enabled = false)) mustBe NgGzipConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of GzipResponseCompressor" in {
      defaultConfigReadsBack(new GzipResponseCompressor(), format) mustBe NgGzipConfig()
    }
  }
}
