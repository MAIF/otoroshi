package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class React2ShellPluginConfigsSpec extends PluginConfigFormatSpec {

  "React2SShellDetectorConfig" should {

    val format = React2SShellDetectorConfig.format

    "only detect, with a 10 megabytes body limit, by default" in {
      React2SShellDetectorConfig() mustBe React2SShellDetectorConfig(block = false, maxBodySize = 10L * 1024L * 1024L)
      React2SShellDetectorConfig.defaultMaxBodySize mustBe 10485760L
    }

    "write the default config" in {
      format.writes(React2SShellDetectorConfig()) mustBe Json.obj("block" -> false, "max_body_size" -> 10485760)
    }

    "write a blocking config with its body limit" in {
      format.writes(React2SShellDetectorConfig(block = true, maxBodySize = 1024)) mustBe Json.obj(
        "block"         -> true,
        "max_body_size" -> 1024
      )
    }

    "round trip the default config" in {
      roundTrip(format, React2SShellDetectorConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, React2SShellDetectorConfig(block = true, maxBodySize = 64L * 1024L))
    }

    "round trip a blocking config alone" in {
      roundTrip(format, React2SShellDetectorConfig(block = true)).block mustBe true
    }

    "round trip a zero body limit" in {
      roundTrip(format, React2SShellDetectorConfig(maxBodySize = 0L)).maxBodySize mustBe 0L
    }

    "round trip the largest body limit" in {
      roundTrip(format, React2SShellDetectorConfig(maxBodySize = Long.MaxValue))
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe React2SShellDetectorConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe React2SShellDetectorConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("block")) mustBe React2SShellDetectorConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr(Json.obj("block" -> true))) mustBe React2SShellDetectorConfig()
    }

    "read a lone block flag and default the body limit" in {
      readOk(format, Json.obj("block" -> true)) mustBe React2SShellDetectorConfig(block = true)
    }

    "read a lone body limit and keep detecting only" in {
      readOk(format, Json.obj("max_body_size" -> 2048)) mustBe React2SShellDetectorConfig(maxBodySize = 2048)
    }

    "ignore a block flag written as a string" in {
      readOk(format, Json.obj("block" -> "true")).block mustBe false
    }

    "ignore a body limit written as a string" in {
      readOk(format, Json.obj("max_body_size" -> "2048")).maxBodySize mustBe React2SShellDetectorConfig.defaultMaxBodySize
    }

    "ignore a body limit that is not a whole number" in {
      readOk(format, Json.obj("max_body_size" -> 2048.5)).maxBodySize mustBe React2SShellDetectorConfig.defaultMaxBodySize
    }

    "not read the camel case body limit" in {
      readOk(format, Json.obj("maxBodySize" -> 2048)) mustBe React2SShellDetectorConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, React2SShellDetectorConfig(block = true, maxBodySize = 1L))
    }

    "list its config flow fields in its config schema" in {
      React2SShellDetectorConfig.configFlow mustBe Seq("block", "max_body_size")
      React2SShellDetectorConfig.configFlow.foreach { field =>
        React2SShellDetectorConfig.configSchema.keys must contain(field)
      }
    }

    "use the flow keys the format writes" in {
      React2SShellDetectorConfig.configFlow.toSet mustBe writtenKeys(format, React2SShellDetectorConfig())
    }

    "advertise the default body limit in its config schema" in {
      (React2SShellDetectorConfig.configSchema \ "max_body_size" \ "default").as[Long] mustBe React2SShellDetectorConfig.defaultMaxBodySize
    }

    "give its config flow and schema to React2SShellDetector" in {
      val plugin = new React2SShellDetector()
      plugin.configFlow mustBe React2SShellDetectorConfig.configFlow
      plugin.configSchema mustBe Some(React2SShellDetectorConfig.configSchema)
    }

    "be the default config of React2SShellDetector" in {
      defaultConfigReadsBack(new React2SShellDetector(), format) mustBe React2SShellDetectorConfig()
    }
  }
}
