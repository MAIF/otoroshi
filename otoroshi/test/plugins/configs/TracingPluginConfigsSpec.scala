package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class TracingPluginConfigsSpec extends PluginConfigFormatSpec {

  "W3CTracingConfig" should {

    val format = W3CTracingConfig.format

    val allKinds = Seq(
      W3CTracingConfigKind.Jaeger,
      W3CTracingConfigKind.OtlpGrpc,
      W3CTracingConfigKind.OtlpHttp,
      W3CTracingConfigKind.Zipkin,
      W3CTracingConfigKind.Logger,
      W3CTracingConfigKind.Noop
    )

    val full = W3CTracingConfig(
      kind = W3CTracingConfigKind.Zipkin,
      endpoint = "http://zipkin:9411/api/v2/spans",
      timeout = 5000,
      baggage = Map("tenant" -> "acme", "user" -> "${user.email}")
    )

    def minimal(extra: JsObject = Json.obj()): JsObject =
      Json.obj("endpoint" -> "http://localhost:4318/v1/traces", "timeout" -> 1000) ++ extra

    "export nothing to a local endpoint by default" in {
      val config = W3CTracingConfig()
      config.kind mustBe W3CTracingConfigKind.Noop
      config.endpoint mustBe "http://localhost:3333/spans"
      config.timeout mustBe 30000L
      config.baggage mustBe empty
    }

    "write the default config" in {
      format.writes(W3CTracingConfig()) mustBe Json.obj(
        "kind"     -> "noop",
        "endpoint" -> "http://localhost:3333/spans",
        "timeout"  -> 30000,
        "baggage"  -> Json.obj()
      )
    }

    "write each kind under its name" in {
      allKinds.map(kind => (format.writes(W3CTracingConfig(kind = kind)) \ "kind").as[String]) mustBe Seq(
        "jaeger",
        "otlp-grpc",
        "otlp-http",
        "zipkin",
        "logger",
        "noop"
      )
    }

    "round trip the default config" in {
      roundTrip(format, W3CTracingConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip every kind" in {
      allKinds.foreach(kind => roundTrip(format, W3CTracingConfig(kind = kind)).kind mustBe kind)
    }

    "round trip baggage values holding expression language" in {
      roundTrip(format, W3CTracingConfig(baggage = Map("route" -> "${route.id}", "empty" -> "")))
    }

    "round trip a zero timeout" in {
      roundTrip(format, W3CTracingConfig(timeout = 0)).timeout mustBe 0L
    }

    "fail to read an empty object because the endpoint and the timeout are mandatory" in {
      readError(format, Json.obj())
    }

    "fail to read a json null" in {
      readError(format, JsNull)
    }

    "fail to read a json string" in {
      readError(format, JsString("zipkin"))
    }

    "fail to read a json array" in {
      readError(format, Json.arr("zipkin"))
    }

    "fail to read a config without endpoint" in {
      readError(format, Json.obj("kind" -> "zipkin", "timeout" -> 1000))
    }

    "fail to read a config without timeout" in {
      readError(format, Json.obj("kind" -> "zipkin", "endpoint" -> "http://zipkin:9411"))
    }

    "fail to read a timeout written as a string" in {
      readError(format, Json.obj("endpoint" -> "http://zipkin:9411", "timeout" -> "1000"))
    }

    "fail to read an endpoint that is not a string" in {
      readError(format, Json.obj("endpoint" -> 9411, "timeout" -> 1000))
    }

    "read a missing kind as logger, not as the default noop" in {
      readOk(format, minimal()) mustBe W3CTracingConfig(
        kind = W3CTracingConfigKind.Logger,
        endpoint = "http://localhost:4318/v1/traces",
        timeout = 1000
      )
    }

    "read a kind that is not a string as logger" in {
      readOk(format, minimal(Json.obj("kind" -> 1))).kind mustBe W3CTracingConfigKind.Logger
    }

    "read a kind whatever its case and write it back in lower case" in {
      val back = readOk(format, minimal(Json.obj("kind" -> "OTLP-HTTP")))
      back.kind mustBe W3CTracingConfigKind.OtlpHttp
      (format.writes(back) \ "kind").as[String] mustBe "otlp-http"
    }

    "read an unknown kind as noop" in {
      readOk(format, minimal(Json.obj("kind" -> "datadog"))).kind mustBe W3CTracingConfigKind.Noop
    }

    "not read the class names of the kinds" in {
      readOk(format, minimal(Json.obj("kind" -> "OtlpGrpc"))).kind mustBe W3CTracingConfigKind.Noop
    }

    "ignore a baggage holding a non string value" in {
      readOk(format, minimal(Json.obj("baggage" -> Json.obj("a" -> "b", "n" -> 1)))).baggage mustBe empty
    }

    "ignore a baggage written as an array" in {
      readOk(format, minimal(Json.obj("baggage" -> Json.arr("a=b")))).baggage mustBe empty
    }

    "ignore unknown keys" in {
      readOk(format, minimal(Json.obj("kind" -> "jaeger", "foo" -> "bar"))).kind mustBe W3CTracingConfigKind.Jaeger
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of W3CTracing" in {
      defaultConfigReadsBack(new W3CTracing(), format) mustBe W3CTracingConfig()
    }
  }
}
