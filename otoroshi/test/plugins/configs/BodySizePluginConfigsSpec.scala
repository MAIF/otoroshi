package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class BodySizePluginConfigsSpec extends PluginConfigFormatSpec {

  "BodyLengthLimiterConfig" should {

    val format = BodyLengthLimiterConfig.format

    "write the default config with a null max length" in {
      format.writes(BodyLengthLimiterConfig()) mustBe Json.obj(
        "max_length" -> JsNull,
        "fail"       -> false
      )
    }

    "have no max length and not fail by default" in {
      BodyLengthLimiterConfig().maxLength mustBe None
      BodyLengthLimiterConfig().fail mustBe false
    }

    "round trip the default config" in {
      roundTrip(format, BodyLengthLimiterConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, BodyLengthLimiterConfig(maxLength = Some(1024L), fail = true))
    }

    "write a config where every field is set" in {
      format.writes(BodyLengthLimiterConfig(maxLength = Some(1024L), fail = true)) mustBe Json.obj(
        "max_length" -> 1024,
        "fail"       -> true
      )
    }

    "round trip a max length without failing" in {
      roundTrip(format, BodyLengthLimiterConfig(maxLength = Some(4096L))).fail mustBe false
    }

    "round trip a failing config without max length" in {
      roundTrip(format, BodyLengthLimiterConfig(fail = true)).maxLength mustBe None
    }

    "round trip a zero max length" in {
      roundTrip(format, BodyLengthLimiterConfig(maxLength = Some(0L)))
    }

    "round trip the largest max length" in {
      roundTrip(format, BodyLengthLimiterConfig(maxLength = Some(Long.MaxValue)))
    }

    "round trip a negative max length as is" in {
      roundTrip(format, BodyLengthLimiterConfig(maxLength = Some(-1L)))
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe BodyLengthLimiterConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe BodyLengthLimiterConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("1024")) mustBe BodyLengthLimiterConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr(1024)) mustBe BodyLengthLimiterConfig()
    }

    "read the max length alone and default the rest" in {
      readOk(format, Json.obj("max_length" -> 2048)) mustBe BodyLengthLimiterConfig(maxLength = Some(2048L))
    }

    "read fail alone and default the rest" in {
      readOk(format, Json.obj("fail" -> true)) mustBe BodyLengthLimiterConfig(fail = true)
    }

    "read a null max length as no max length" in {
      readOk(format, Json.obj("max_length" -> JsNull)).maxLength mustBe None
    }

    "ignore a max length written as a string" in {
      readOk(format, Json.obj("max_length" -> "2048")).maxLength mustBe None
    }

    "ignore a max length that is not a whole number" in {
      readOk(format, Json.obj("max_length" -> 2048.5)).maxLength mustBe None
    }

    "read a whole max length written with a decimal part" in {
      readOk(format, Json.obj("max_length" -> JsNumber(BigDecimal("2048.0")))).maxLength mustBe Some(2048L)
    }

    "ignore a fail flag written as a string" in {
      readOk(format, Json.obj("fail" -> "true")).fail mustBe false
    }

    "not read the camel case key" in {
      readOk(format, Json.obj("maxLength" -> 2048)) mustBe BodyLengthLimiterConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, BodyLengthLimiterConfig(maxLength = Some(10L), fail = true))
    }

    "write exactly the keys of its config flow" in {
      writtenKeys(format, BodyLengthLimiterConfig(maxLength = Some(10L))) mustBe BodyLengthLimiterConfig.configFlow.toSet
    }

    "list max_length and fail in its config flow and schema" in {
      BodyLengthLimiterConfig.configFlow mustBe Seq("max_length", "fail")
      BodyLengthLimiterConfig.configSchema.get.keys mustBe Set("max_length", "fail")
    }

    "be the default config of RequestBodyLengthLimiter" in {
      defaultConfigReadsBack(new RequestBodyLengthLimiter(), format) mustBe BodyLengthLimiterConfig()
    }

    "be the default config of ResponseBodyLengthLimiter" in {
      defaultConfigReadsBack(new ResponseBodyLengthLimiter(), format) mustBe BodyLengthLimiterConfig()
    }
  }

  "BandwidthThrottlingConfig" should {

    val format = BandwidthThrottlingConfig.format

    val default = BandwidthThrottlingConfig.default

    val full = BandwidthThrottlingConfig(
      windowMillis = "1000",
      groupExpr = "${apikey.clientId}",
      _throttlingQuota = "2048",
      fail = false,
      kind = BandwidthThrottlingConfigKind.PerCluster
    )

    "write the default config with all its keys" in {
      format.writes(default) mustBe Json.obj(
        "window_millis"    -> "60000",
        "throttling_quota" -> "10485760",
        "group_expr"       -> "${route.id}",
        "fail"             -> true,
        "kind"             -> "per_request"
      )
    }

    "throttle 10 megabytes per minute and per route by default" in {
      default.windowMillis mustBe "60000"
      default._throttlingQuota mustBe "10485760"
      default.groupExpr mustBe "${route.id}"
      default.fail mustBe true
      default.kind mustBe BandwidthThrottlingConfigKind.PerRequest
    }

    "round trip the default config" in {
      roundTrip(format, default)
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "window_millis"    -> "1000",
        "throttling_quota" -> "2048",
        "group_expr"       -> "${apikey.clientId}",
        "fail"             -> false,
        "kind"             -> "per_cluster"
      )
    }

    "round trip the per request kind" in {
      roundTrip(format, full.copy(kind = BandwidthThrottlingConfigKind.PerRequest)).kind mustBe
        BandwidthThrottlingConfigKind.PerRequest
    }

    "round trip the per node kind" in {
      roundTrip(format, full.copy(kind = BandwidthThrottlingConfigKind.PerNode)).kind mustBe
        BandwidthThrottlingConfigKind.PerNode
    }

    "round trip the per cluster kind" in {
      roundTrip(format, default.copy(kind = BandwidthThrottlingConfigKind.PerCluster)).kind mustBe
        BandwidthThrottlingConfigKind.PerCluster
    }

    "write each kind with its name" in {
      BandwidthThrottlingConfigKind.PerRequest.json mustBe JsString("per_request")
      BandwidthThrottlingConfigKind.PerNode.json mustBe JsString("per_node")
      BandwidthThrottlingConfigKind.PerCluster.json mustBe JsString("per_cluster")
    }

    "round trip a quota holding expression language" in {
      roundTrip(format, default.copy(_throttlingQuota = "${route.metadata.quota}"))._throttlingQuota mustBe
        "${route.metadata.quota}"
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe default
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe default
    }

    "read a json string as the default config" in {
      readOk(format, JsString("60000")) mustBe default
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr()) mustBe default
    }

    "read the window alone and default the rest" in {
      readOk(format, Json.obj("window_millis" -> "5000")) mustBe default.copy(windowMillis = "5000")
    }

    "read fail alone and default the rest" in {
      readOk(format, Json.obj("fail" -> false)) mustBe default.copy(fail = false)
    }

    "read the kind whatever its case" in {
      readOk(format, Json.obj("kind" -> "PER_NODE")).kind mustBe BandwidthThrottlingConfigKind.PerNode
      readOk(format, Json.obj("kind" -> "Per_Cluster")).kind mustBe BandwidthThrottlingConfigKind.PerCluster
    }

    "refuse an unknown kind" in {
      readError(format, Json.obj("kind" -> "per_tenant"))
    }

    "ignore a kind that is not a string" in {
      readOk(format, Json.obj("kind" -> 1)).kind mustBe BandwidthThrottlingConfigKind.PerRequest
    }

    "read blank strings as the default values" in {
      readOk(
        format,
        Json.obj("window_millis" -> "", "throttling_quota" -> "  ", "group_expr" -> "\t")
      ) mustBe default
    }

    "ignore a window written as a number" in {
      readOk(format, Json.obj("window_millis" -> 5000)).windowMillis mustBe "60000"
    }

    "ignore a quota written as a number" in {
      readOk(format, Json.obj("throttling_quota" -> 2048))._throttlingQuota mustBe "10485760"
    }

    "ignore a fail flag written as a string" in {
      readOk(format, Json.obj("fail" -> "false")).fail mustBe true
    }

    "keep the spaces around a non blank value" in {
      readOk(format, Json.obj("throttling_quota" -> " 2048 "))._throttlingQuota mustBe " 2048 "
    }

    "not read the camel case keys" in {
      readOk(format, Json.obj("windowMillis" -> "5000", "groupExpr" -> "${req.ip}")) mustBe default
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "write exactly the keys of its config flow" in {
      writtenKeys(format, full) mustBe BandwidthThrottlingConfig.configFlow.toSet
    }

    "describe every config flow field in its config schema" in {
      BandwidthThrottlingConfig.configSchema.get.keys mustBe BandwidthThrottlingConfig.configFlow.toSet
    }

    "offer in its schema exactly the kinds the format reads" in {
      val options = (BandwidthThrottlingConfig.configSchema.get \ "kind" \ "props" \ "options").as[Seq[JsObject]]
      options.map(o => (o \ "value").as[String]) mustBe Seq("per_request", "per_node", "per_cluster")
      options
        .map(o => readOk(format, Json.obj("kind" -> (o \ "value").as[String])).kind)
        .toSet mustBe Set(
        BandwidthThrottlingConfigKind.PerRequest,
        BandwidthThrottlingConfigKind.PerNode,
        BandwidthThrottlingConfigKind.PerCluster
      )
    }

    "be the default config of RequestBandwidthThrottling" in {
      defaultConfigReadsBack(new RequestBandwidthThrottling(), format) mustBe default
    }

    "be the default config of ResponseBandwidthThrottling" in {
      defaultConfigReadsBack(new ResponseBandwidthThrottling(), format) mustBe default
    }
  }
}
