package plugins.configs

import otoroshi.models.{
  BadResponse,
  BadResponsesFaultConfig,
  ChaosConfig,
  LargeRequestFaultConfig,
  LargeResponseFaultConfig,
  LatencyInjectionFaultConfig
}
import otoroshi.next.plugins.*
import play.api.libs.json.*

import scala.concurrent.duration.*

class ChaosPluginConfigsSpec extends PluginConfigFormatSpec {

  "NgBadResponse" should {

    val format = NgBadResponse.format

    val response = NgBadResponse(503, """{"error":"unavailable"}""", Map("Content-Type" -> "application/json"))

    "write status, body and headers" in {
      format.writes(response) mustBe Json.obj(
        "status"  -> 503,
        "body"    -> """{"error":"unavailable"}""",
        "headers" -> Json.obj("Content-Type" -> "application/json")
      )
    }

    "round trip a bad response" in {
      roundTrip(format, response)
    }

    "round trip a bad response without headers nor body" in {
      roundTrip(format, NgBadResponse(500, "", Map.empty))
    }

    "read an empty object as a 500 json error" in {
      readOk(format, Json.obj()) mustBe NgBadResponse(500, """{"error":"..."}""", Map.empty)
    }

    "read a status written as a string" in {
      readOk(format, Json.obj("status" -> "418")).status mustBe 418
    }

    "fail to read a status written as a string that is not a number" in {
      readError(format, Json.obj("status" -> "teapot"))
    }

    "ignore the whole headers map as soon as a value is not a string" in {
      readOk(format, Json.obj("headers" -> Json.obj("X-Foo" -> "foo", "X-Count" -> 1))).headers mustBe empty
    }

    "convert to and from the legacy bad response" in {
      response.legacy mustBe BadResponse(503, """{"error":"unavailable"}""", Map("Content-Type" -> "application/json"))
      NgBadResponse.fromLegacy(response.legacy) mustBe response
    }
  }

  "NgLargeRequestFaultConfig" should {

    val format = NgLargeRequestFaultConfig.format

    "write ratio and additionalRequestSize" in {
      format.writes(NgLargeRequestFaultConfig(0.2, 1024)) mustBe Json.obj(
        "ratio"                 -> 0.2,
        "additionalRequestSize" -> 1024
      )
    }

    "round trip a fault" in {
      roundTrip(format, NgLargeRequestFaultConfig(0.2, 1024))
    }

    "round trip the bounds of the ratio" in {
      roundTrip(format, NgLargeRequestFaultConfig(0.0, 0))
      roundTrip(format, NgLargeRequestFaultConfig(1.0, Int.MaxValue))
    }

    "read a whole number ratio" in {
      readOk(format, Json.obj("ratio" -> 1, "additionalRequestSize" -> 10)) mustBe NgLargeRequestFaultConfig(1.0, 10)
    }

    "fail to read a fault without ratio" in {
      readError(format, Json.obj("additionalRequestSize" -> 1024))
    }

    "fail to read a fault without additionalRequestSize" in {
      readError(format, Json.obj("ratio" -> 0.2))
    }

    "fail to read a ratio written as a string" in {
      readError(format, Json.obj("ratio" -> "0.2", "additionalRequestSize" -> 1024))
    }

    "fail to read the snake case size key" in {
      readError(format, Json.obj("ratio" -> 0.2, "additional_request_size" -> 1024))
    }

    "convert to and from the legacy fault" in {
      NgLargeRequestFaultConfig(0.2, 1024).legacy mustBe LargeRequestFaultConfig(0.2, 1024)
      NgLargeRequestFaultConfig.fromLegacy(LargeRequestFaultConfig(0.2, 1024)) mustBe
        NgLargeRequestFaultConfig(0.2, 1024)
    }
  }

  "NgLargeResponseFaultConfig" should {

    val format = NgLargeResponseFaultConfig.format

    "write ratio and additionalResponseSize" in {
      format.writes(NgLargeResponseFaultConfig(0.3, 2048)) mustBe Json.obj(
        "ratio"                  -> 0.3,
        "additionalResponseSize" -> 2048
      )
    }

    "round trip a fault" in {
      roundTrip(format, NgLargeResponseFaultConfig(0.3, 2048))
    }

    "fail to read a fault without additionalResponseSize" in {
      readError(format, Json.obj("ratio" -> 0.3))
    }

    "fail to read the size of a large request fault" in {
      readError(format, Json.obj("ratio" -> 0.3, "additionalRequestSize" -> 2048))
    }

    "fail to read a size that is not a whole number" in {
      readError(format, Json.obj("ratio" -> 0.3, "additionalResponseSize" -> 20.5))
    }

    "convert to and from the legacy fault" in {
      NgLargeResponseFaultConfig(0.3, 2048).legacy mustBe LargeResponseFaultConfig(0.3, 2048)
      NgLargeResponseFaultConfig.fromLegacy(LargeResponseFaultConfig(0.3, 2048)) mustBe NgLargeResponseFaultConfig(
        0.3,
        2048
      )
    }
  }

  "NgLatencyInjectionFaultConfig" should {

    val format = NgLatencyInjectionFaultConfig.format

    val fault = NgLatencyInjectionFaultConfig(0.5, 100.millis, 2.seconds)

    "write the durations in milliseconds" in {
      format.writes(fault) mustBe Json.obj("ratio" -> 0.5, "from" -> 100, "to" -> 2000)
    }

    "round trip a fault" in {
      roundTrip(format, fault)
    }

    "read the durations back in milliseconds" in {
      val back = roundTrip(format, fault)
      back.to.unit mustBe MILLISECONDS
      back.to.length mustBe 2000L
    }

    "truncate the durations to the millisecond" in {
      readOk(format, format.writes(NgLatencyInjectionFaultConfig(0.5, 1500.micros, 999.micros))) mustBe
        NgLatencyInjectionFaultConfig(0.5, 1.millis, 0.millis)
    }

    "fail to read a fault without from" in {
      readError(format, Json.obj("ratio" -> 0.5, "to" -> 2000))
    }

    "fail to read a duration written as a string" in {
      readError(format, Json.obj("ratio" -> 0.5, "from" -> "100ms", "to" -> 2000))
    }

    "convert to and from the legacy fault" in {
      fault.legacy mustBe LatencyInjectionFaultConfig(0.5, 100.millis, 2.seconds)
      NgLatencyInjectionFaultConfig.fromLegacy(fault.legacy) mustBe fault
    }
  }

  "NgBadResponsesFaultConfig" should {

    val format = NgBadResponsesFaultConfig.format

    val fault = NgBadResponsesFaultConfig(
      0.1,
      Seq(
        NgBadResponse(502, "bad gateway", Map.empty),
        NgBadResponse(503, """{"error":"unavailable"}""", Map("Content-Type" -> "application/json"))
      )
    )

    "write the responses with the bad response format" in {
      format.writes(fault) mustBe Json.obj(
        "ratio"     -> 0.1,
        "responses" -> JsArray(fault.responses.map(NgBadResponse.format.writes))
      )
    }

    "round trip a fault with several responses" in {
      roundTrip(format, fault)
    }

    "keep the order of the responses" in {
      roundTrip(format, fault.copy(responses = fault.responses.reverse)).responses.map(_.status) mustBe Seq(503, 502)
    }

    "round trip a fault without response" in {
      roundTrip(format, NgBadResponsesFaultConfig(0.1, Seq.empty))
    }

    "fail to read a fault without responses" in {
      readError(format, Json.obj("ratio" -> 0.1))
    }

    "fail to read a fault as soon as a response cannot be read" in {
      readError(format, Json.obj("ratio" -> 0.1, "responses" -> Json.arr(Json.obj("status" -> "nope"))))
    }

    "read an empty response object as a 500 json error" in {
      readOk(format, Json.obj("ratio" -> 0.1, "responses" -> Json.arr(Json.obj()))).responses mustBe Seq(
        NgBadResponse(500, """{"error":"..."}""", Map.empty)
      )
    }

    "convert to and from the legacy fault" in {
      fault.legacy mustBe BadResponsesFaultConfig(0.1, fault.responses.map(_.legacy))
      NgBadResponsesFaultConfig.fromLegacy(fault.legacy) mustBe fault
    }
  }

  "NgChaosConfig" should {

    val format = NgChaosConfig.format

    val largeRequest  = NgLargeRequestFaultConfig(0.2, 1024)
    val largeResponse = NgLargeResponseFaultConfig(0.3, 2048)
    val latency       = NgLatencyInjectionFaultConfig(0.5, 100.millis, 2.seconds)
    val badResponses  = NgBadResponsesFaultConfig(0.1, Seq(NgBadResponse(502, "bad gateway", Map.empty)))

    val full = NgChaosConfig(Some(largeRequest), Some(largeResponse), Some(latency), Some(badResponses))

    "default to no fault at all" in {
      NgChaosConfig() mustBe NgChaosConfig(None, None, None, None)
    }

    "write the default config with a null for each fault" in {
      format.writes(NgChaosConfig()) mustBe Json.obj(
        "large_request_fault"     -> JsNull,
        "large_response_fault"    -> JsNull,
        "latency_injection_fault" -> JsNull,
        "bad_responses_fault"     -> JsNull
      )
    }

    "write each fault with its own format" in {
      format.writes(full) mustBe Json.obj(
        "large_request_fault"     -> NgLargeRequestFaultConfig.format.writes(largeRequest),
        "large_response_fault"    -> NgLargeResponseFaultConfig.format.writes(largeResponse),
        "latency_injection_fault" -> NgLatencyInjectionFaultConfig.format.writes(latency),
        "bad_responses_fault"     -> NgBadResponsesFaultConfig.format.writes(badResponses)
      )
    }

    "round trip the default config" in {
      roundTrip(format, NgChaosConfig())
    }

    "round trip a config where every fault is set" in {
      roundTrip(format, full)
    }

    "round trip a large request fault alone" in {
      roundTrip(format, NgChaosConfig(largeRequestFaultConfig = Some(largeRequest)))
    }

    "round trip a large response fault alone" in {
      roundTrip(format, NgChaosConfig(largeResponseFaultConfig = Some(largeResponse)))
    }

    "round trip a latency injection fault alone" in {
      roundTrip(format, NgChaosConfig(latencyInjectionFaultConfig = Some(latency)))
    }

    "round trip a bad responses fault alone" in {
      roundTrip(format, NgChaosConfig(badResponsesFaultConfig = Some(badResponses)))
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgChaosConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgChaosConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("chaos")) mustBe NgChaosConfig()
    }

    "drop a fault that cannot be read and keep the others" in {
      val json = Json.obj(
        "large_request_fault"     -> Json.obj("ratio" -> 0.2),
        "latency_injection_fault" -> NgLatencyInjectionFaultConfig.format.writes(latency)
      )
      readOk(format, json) mustBe NgChaosConfig(latencyInjectionFaultConfig = Some(latency))
    }

    "drop a fault that is not an object" in {
      readOk(format, Json.obj("bad_responses_fault" -> Json.arr())) mustBe NgChaosConfig()
    }

    "not read the camel case keys of the legacy chaos config" in {
      readOk(format, ChaosConfig._fmt.writes(full.legacy)) mustBe NgChaosConfig()
    }

    "convert to an enabled legacy chaos config" in {
      val legacy = full.legacy
      legacy.enabled mustBe true
      legacy.largeRequestFaultConfig mustBe Some(largeRequest.legacy)
      legacy.badResponsesFaultConfig mustBe Some(badResponses.legacy)
    }

    "convert back from the legacy chaos config" in {
      NgChaosConfig.fromLegacy(full.legacy) mustBe full
      NgChaosConfig.fromLegacy(ChaosConfig()) mustBe NgChaosConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of SnowMonkeyChaos" in {
      defaultConfigReadsBack(new SnowMonkeyChaos(), format) mustBe NgChaosConfig()
    }
  }
}
