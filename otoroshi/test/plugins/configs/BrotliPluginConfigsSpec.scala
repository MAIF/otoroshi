package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class BrotliPluginConfigsSpec extends PluginConfigFormatSpec {

  "NgBrotliConfig" should {

    val format = NgBrotliConfig.format

    val full = NgBrotliConfig(
      whiteList = Seq("image/svg+xml", "application/xml"),
      blackList = Seq("application/octet-stream"),
      bufferSize = 4096,
      chunkedThreshold = 1024,
      compressionLevel = 11
    )

    "compress text, javascript and json with a medium level by default" in {
      val default = NgBrotliConfig()
      default.whiteList mustBe Seq("text/*", "application/javascript", "application/json")
      default.blackList mustBe empty
      default.bufferSize mustBe 8192
      default.chunkedThreshold mustBe 102400
      default.compressionLevel mustBe 5
    }

    "write the default config" in {
      format.writes(NgBrotliConfig()) mustBe Json.obj(
        "allowed_list"      -> Json.arr("text/*", "application/javascript", "application/json"),
        "blocked_list"      -> Json.arr(),
        "buffer_size"       -> 8192,
        "chunked_threshold" -> 102400,
        "compression_level" -> 5
      )
    }

    "write the white and black lists under allowed_list and blocked_list" in {
      val json = written(format, full)
      json.value("allowed_list") mustBe Json.arr("image/svg+xml", "application/xml")
      json.value("blocked_list") mustBe Json.arr("application/octet-stream")
    }

    "round trip the default config" in {
      roundTrip(format, NgBrotliConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip empty allowed and blocked lists" in {
      roundTrip(format, NgBrotliConfig(whiteList = Seq.empty, blackList = Seq.empty))
    }

    "round trip a blocked list without allowed list" in {
      roundTrip(format, NgBrotliConfig(whiteList = Seq.empty, blackList = Seq("image/*", "video/*")))
    }

    "round trip the catch all mask" in {
      roundTrip(format, NgBrotliConfig(whiteList = Seq("*")))
    }

    "keep the order of the allowed list" in {
      roundTrip(format, NgBrotliConfig(whiteList = Seq("text/html", "application/json", "text/css"))).whiteList mustBe Seq(
        "text/html",
        "application/json",
        "text/css"
      )
    }

    "round trip a compression level out of the brotli range as is" in {
      roundTrip(format, NgBrotliConfig(compressionLevel = 42)).compressionLevel mustBe 42
      roundTrip(format, NgBrotliConfig(compressionLevel = -1)).compressionLevel mustBe -1
    }

    "round trip a zero buffer size as is" in {
      roundTrip(format, NgBrotliConfig(bufferSize = 0)).bufferSize mustBe 0
    }

    "read an empty object with an empty allowed list, not the default one" in {
      readOk(format, Json.obj()) mustBe NgBrotliConfig(whiteList = Seq.empty)
    }

    "read a json null like an empty object" in {
      readOk(format, JsNull) mustBe NgBrotliConfig(whiteList = Seq.empty)
    }

    "read a json string like an empty object" in {
      readOk(format, JsString("text/*")) mustBe NgBrotliConfig(whiteList = Seq.empty)
    }

    "read a json array like an empty object" in {
      readOk(format, Json.arr("text/*")) mustBe NgBrotliConfig(whiteList = Seq.empty)
    }

    "read an allowed_list alone" in {
      readOk(format, Json.obj("allowed_list" -> Json.arr("text/plain"))) mustBe NgBrotliConfig(
        whiteList = Seq("text/plain")
      )
    }

    "read a blocked_list alone" in {
      readOk(format, Json.obj("blocked_list" -> Json.arr("image/png"))) mustBe NgBrotliConfig(
        whiteList = Seq.empty,
        blackList = Seq("image/png")
      )
    }

    "read a buffer_size alone" in {
      readOk(format, Json.obj("buffer_size" -> 1024)) mustBe NgBrotliConfig(whiteList = Seq.empty, bufferSize = 1024)
    }

    "read a chunked_threshold alone" in {
      readOk(format, Json.obj("chunked_threshold" -> 2048)) mustBe NgBrotliConfig(
        whiteList = Seq.empty,
        chunkedThreshold = 2048
      )
    }

    "read a compression_level alone" in {
      readOk(format, Json.obj("compression_level" -> 9)) mustBe NgBrotliConfig(
        whiteList = Seq.empty,
        compressionLevel = 9
      )
    }

    "ignore a compression_level written as a string" in {
      readOk(format, Json.obj("compression_level" -> "9")).compressionLevel mustBe 5
    }

    "ignore a buffer_size that is not a whole number" in {
      readOk(format, Json.obj("buffer_size" -> 1024.5)).bufferSize mustBe 8192
    }

    "ignore a chunked_threshold that does not fit an int" in {
      readOk(format, Json.obj("chunked_threshold" -> 3000000000L)).chunkedThreshold mustBe 102400
    }

    "ignore an allowed_list that is a string" in {
      readOk(format, Json.obj("allowed_list" -> "text/*")).whiteList mustBe empty
    }

    "ignore a blocked_list holding something else than strings" in {
      readOk(format, Json.obj("blocked_list" -> Json.arr("image/png", 42))).blackList mustBe empty
    }

    "not read the names of the case class fields" in {
      val json = Json.obj(
        "whiteList"        -> Json.arr("text/plain"),
        "blackList"        -> Json.arr("image/png"),
        "bufferSize"       -> 1,
        "chunkedThreshold" -> 2,
        "compressionLevel" -> 3
      )
      readOk(format, json) mustBe NgBrotliConfig(whiteList = Seq.empty)
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("compression_level" -> 2, "foo" -> "bar")) mustBe NgBrotliConfig(
        whiteList = Seq.empty,
        compressionLevel = 2
      )
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of BrotliResponseCompressor" in {
      defaultConfigReadsBack(new BrotliResponseCompressor(), format) mustBe NgBrotliConfig()
    }
  }
}
