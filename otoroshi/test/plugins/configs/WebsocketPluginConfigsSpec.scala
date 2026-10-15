package plugins.configs

import com.networknt.schema.SpecificationVersion
import otoroshi.next.plugins.*
import otoroshi.utils.JsonPathValidator
import play.api.libs.json.*

class WebsocketPluginConfigsSpec extends PluginConfigFormatSpec {

  "RejectStrategy and FrameFormat" should {

    "write the reject strategies as lower case strings" in {
      RejectStrategy.Drop.json mustBe JsString("drop")
      RejectStrategy.Close.json mustBe JsString("close")
    }

    "parse the reject strategies ignoring the case" in {
      RejectStrategy.parse("close") mustBe RejectStrategy.Close
      RejectStrategy.parse("CLOSE") mustBe RejectStrategy.Close
      RejectStrategy.parse("Drop") mustBe RejectStrategy.Drop
    }

    "parse an unknown reject strategy as drop" in {
      RejectStrategy.parse("reject") mustBe RejectStrategy.Drop
      RejectStrategy.parse("") mustBe RejectStrategy.Drop
    }

    "read the reject strategy from the reject_strategy key of a config" in {
      RejectStrategy.read(Json.obj("reject_strategy" -> "close")) mustBe RejectStrategy.Close
      RejectStrategy.read(Json.obj()) mustBe RejectStrategy.Drop
      RejectStrategy.read(Json.obj("reject_strategy" -> true)) mustBe RejectStrategy.Drop
      RejectStrategy.read(JsNull) mustBe RejectStrategy.Drop
    }

    "write the frame formats as lower case strings" in {
      FrameFormat.All.json mustBe JsString("all")
      FrameFormat.Binary.json mustBe JsString("binary")
      FrameFormat.Text.json mustBe JsString("text")
      FrameFormat.Json.json mustBe JsString("json")
    }

    "parse the frame formats ignoring the case" in {
      FrameFormat.parse("BINARY") mustBe FrameFormat.Binary
      FrameFormat.parse("Text") mustBe FrameFormat.Text
      FrameFormat.parse("jSoN") mustBe FrameFormat.Json
      FrameFormat.parse("all") mustBe FrameFormat.All
    }

    "parse an unknown frame format as all" in {
      FrameFormat.parse("xml") mustBe FrameFormat.All
      FrameFormat.parse("") mustBe FrameFormat.All
    }
  }

  "WebsocketTypeValidatorConfig" should {

    val format = WebsocketTypeValidatorConfig.format

    "accept every format and drop by default" in {
      WebsocketTypeValidatorConfig.default mustBe WebsocketTypeValidatorConfig(FrameFormat.All, RejectStrategy.Drop)
      WebsocketTypeValidatorConfig.default mustBe WebsocketTypeValidatorConfig()
    }

    "write the default config" in {
      format.writes(WebsocketTypeValidatorConfig.default) mustBe Json.obj(
        "allowed_format"  -> "all",
        "reject_strategy" -> "drop"
      )
    }

    "round trip the default config" in {
      roundTrip(format, WebsocketTypeValidatorConfig.default)
    }

    "round trip a config where every field is set" in {
      roundTrip(format, WebsocketTypeValidatorConfig(FrameFormat.Binary, RejectStrategy.Close))
    }

    "round trip every frame format" in {
      Seq(FrameFormat.All, FrameFormat.Binary, FrameFormat.Text, FrameFormat.Json).foreach { f =>
        roundTrip(format, WebsocketTypeValidatorConfig(allowedFormat = f)).allowedFormat mustBe f
      }
    }

    "round trip every reject strategy" in {
      Seq(RejectStrategy.Drop, RejectStrategy.Close).foreach { s =>
        roundTrip(format, WebsocketTypeValidatorConfig(rejectStrategy = s)).rejectStrategy mustBe s
      }
    }

    "write the json frame format as json" in {
      written(format, WebsocketTypeValidatorConfig(FrameFormat.Json)) mustBe Json.obj(
        "allowed_format"  -> "json",
        "reject_strategy" -> "drop"
      )
    }

    "read upper case values" in {
      readOk(
        format,
        Json.obj("allowed_format" -> "TEXT", "reject_strategy" -> "CLOSE")
      ) mustBe WebsocketTypeValidatorConfig(FrameFormat.Text, RejectStrategy.Close)
    }

    "read an unknown frame format as all" in {
      readOk(format, Json.obj("allowed_format" -> "xml")).allowedFormat mustBe FrameFormat.All
    }

    "read a frame format that is not a string as all" in {
      readOk(format, Json.obj("allowed_format" -> 1, "reject_strategy" -> "close")) mustBe WebsocketTypeValidatorConfig(
        FrameFormat.All,
        RejectStrategy.Close
      )
    }

    "read an unknown reject strategy as drop" in {
      readOk(format, Json.obj("allowed_format" -> "text", "reject_strategy" -> "ignore")) mustBe WebsocketTypeValidatorConfig(
        FrameFormat.Text,
        RejectStrategy.Drop
      )
    }

    "read a reject strategy that is not a string as drop" in {
      readOk(format, Json.obj("reject_strategy" -> Json.obj("kind" -> "close"))).rejectStrategy mustBe RejectStrategy.Drop
    }

    "read the allowed format alone" in {
      readOk(format, Json.obj("allowed_format" -> "binary")) mustBe WebsocketTypeValidatorConfig(FrameFormat.Binary)
    }

    "read the reject strategy alone" in {
      readOk(format, Json.obj("reject_strategy" -> "close")) mustBe WebsocketTypeValidatorConfig(
        rejectStrategy = RejectStrategy.Close
      )
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe WebsocketTypeValidatorConfig.default
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe WebsocketTypeValidatorConfig.default
    }

    "read a json string as the default config" in {
      readOk(format, JsString("binary")) mustBe WebsocketTypeValidatorConfig.default
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("binary", "close")) mustBe WebsocketTypeValidatorConfig.default
    }

    "not read the camel case keys" in {
      readOk(
        format,
        Json.obj("allowedFormat" -> "binary", "rejectStrategy" -> "close")
      ) mustBe WebsocketTypeValidatorConfig.default
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("allowed_format" -> "text", "foo" -> "bar")) mustBe WebsocketTypeValidatorConfig(
        FrameFormat.Text
      )
    }

    "rewrite upper case values in lower case" in {
      val read = readOk(format, Json.obj("allowed_format" -> "Binary", "reject_strategy" -> "Close"))
      format.writes(read) mustBe Json.obj("allowed_format" -> "binary", "reject_strategy" -> "close")
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, WebsocketTypeValidatorConfig(FrameFormat.Text, RejectStrategy.Close))
    }

    "be the default config of WebsocketTypeValidator" in {
      defaultConfigReadsBack(new WebsocketTypeValidator(), format) mustBe WebsocketTypeValidatorConfig.default
    }
  }

  "FrameFormatValidatorConfig" should {

    val format    = FrameFormatValidatorConfig.format
    val validator = JsonPathValidator("$.message.kind", JsString("chat"), Some("not a chat message"))

    "validate the message against foo by default" in {
      FrameFormatValidatorConfig.default mustBe FrameFormatValidatorConfig(
        validator = Some(JsonPathValidator("$.message", JsString("foo"), None)),
        rejectStrategy = RejectStrategy.Drop
      )
    }

    "have no validator in the case class defaults" in {
      FrameFormatValidatorConfig().validator mustBe None
      FrameFormatValidatorConfig() must not be FrameFormatValidatorConfig.default
    }

    "write the default config" in {
      format.writes(FrameFormatValidatorConfig.default) mustBe Json.obj(
        "validator"       -> Json.obj(
          "kind"  -> "json-path-validator",
          "path"  -> "$.message",
          "value" -> "foo",
          "error" -> JsNull
        ),
        "reject_strategy" -> "drop"
      )
    }

    "write a missing validator as null" in {
      format.writes(FrameFormatValidatorConfig()) mustBe Json.obj(
        "validator"       -> JsNull,
        "reject_strategy" -> "drop"
      )
    }

    "round trip the default config" in {
      roundTrip(format, FrameFormatValidatorConfig.default)
    }

    "round trip a config without validator" in {
      roundTrip(format, FrameFormatValidatorConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, FrameFormatValidatorConfig(Some(validator), RejectStrategy.Close))
    }

    "round trip a validator expecting a number" in {
      roundTrip(format, FrameFormatValidatorConfig(Some(JsonPathValidator("$.message.count", JsNumber(42)))))
    }

    "round trip a validator expecting an object" in {
      roundTrip(
        format,
        FrameFormatValidatorConfig(Some(JsonPathValidator("$.message", Json.obj("a" -> Json.arr(1, 2)))))
      )
    }

    "round trip a validator expecting a boolean" in {
      roundTrip(format, FrameFormatValidatorConfig(Some(JsonPathValidator("$.message.ok", JsBoolean(true)))))
    }

    "round trip a validator expecting a null value" in {
      roundTrip(format, FrameFormatValidatorConfig(Some(JsonPathValidator("$.message.ok", JsNull))))
    }

    "round trip a validator using an expression" in {
      roundTrip(format, FrameFormatValidatorConfig(Some(JsonPathValidator("$.message.id", JsString("IsDefined()")))))
    }

    "read an empty object as a config without validator" in {
      readOk(format, Json.obj()) mustBe FrameFormatValidatorConfig()
    }

    "read a json null as a config without validator" in {
      readOk(format, JsNull) mustBe FrameFormatValidatorConfig()
    }

    "read a json string as a config without validator" in {
      readOk(format, JsString("$.message")) mustBe FrameFormatValidatorConfig()
    }

    "read a json array as a config without validator" in {
      readOk(format, Json.arr(Json.obj("path" -> "$.message", "value" -> "foo"))) mustBe FrameFormatValidatorConfig()
    }

    "read the reject strategy alone" in {
      readOk(format, Json.obj("reject_strategy" -> "close")) mustBe FrameFormatValidatorConfig(
        rejectStrategy = RejectStrategy.Close
      )
    }

    "read the validator alone" in {
      readOk(
        format,
        Json.obj("validator" -> Json.obj("path" -> "$.message", "value" -> "bar"))
      ) mustBe FrameFormatValidatorConfig(Some(JsonPathValidator("$.message", JsString("bar"))))
    }

    "ignore a null validator" in {
      readOk(format, Json.obj("validator" -> JsNull, "reject_strategy" -> "close")) mustBe FrameFormatValidatorConfig(
        rejectStrategy = RejectStrategy.Close
      )
    }

    "ignore a validator without path" in {
      readOk(format, Json.obj("validator" -> Json.obj("value" -> "foo"))).validator mustBe None
    }

    "ignore a validator whose path is not a string" in {
      readOk(format, Json.obj("validator" -> Json.obj("path" -> 42, "value" -> "foo"))).validator mustBe None
    }

    "ignore a validator without value" in {
      readOk(format, Json.obj("validator" -> Json.obj("path" -> "$.message"))).validator mustBe None
    }

    "ignore a validator that is a string" in {
      readOk(format, Json.obj("validator" -> "$.message")).validator mustBe None
    }

    "read an empty error as no error" in {
      readOk(
        format,
        Json.obj("validator" -> Json.obj("path" -> "$.message", "value" -> "foo", "error" -> ""))
      ).validator.flatMap(_.error) mustBe None
    }

    "read a blank error as no error" in {
      readOk(
        format,
        Json.obj("validator" -> Json.obj("path" -> "$.message", "value" -> "foo", "error" -> "   "))
      ).validator.flatMap(_.error) mustBe None
    }

    "not check the kind of the validator and write the canonical one" in {
      val read = readOk(
        format,
        Json.obj("validator" -> Json.obj("kind" -> "something-else", "path" -> "$.message", "value" -> "foo"))
      )
      read.validator mustBe Some(JsonPathValidator("$.message", JsString("foo")))
      (written(format, read) \ "validator" \ "kind").as[String] mustBe "json-path-validator"
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("reject_strategy" -> "close", "foo" -> "bar")) mustBe FrameFormatValidatorConfig(
        rejectStrategy = RejectStrategy.Close
      )
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, FrameFormatValidatorConfig(Some(validator), RejectStrategy.Close))
    }

    "be the default config of WebsocketContentValidatorIn" in {
      defaultConfigReadsBack(new WebsocketContentValidatorIn(), format) mustBe FrameFormatValidatorConfig.default
    }
  }

  "WebsocketJsonFormatValidatorConfig" should {

    val format  = WebsocketJsonFormatValidatorConfig.format
    val draft07 = SpecificationVersion.DRAFT_7.getDialectId

    "require a name in an object by default, with the 2020-12 draft" in {
      WebsocketJsonFormatValidatorConfig.default mustBe WebsocketJsonFormatValidatorConfig(
        schema = Some("{ \"type\": \"object\", \"required\": [\"name\"] }"),
        specification = "https://json-schema.org/draft/2020-12/schema",
        rejectStrategy = RejectStrategy.Drop
      )
    }

    "have no schema in the case class defaults" in {
      WebsocketJsonFormatValidatorConfig().schema mustBe None
      WebsocketJsonFormatValidatorConfig().specification mustBe WebsocketJsonFormatValidatorConfig.default.specification
    }

    "use a specification known by the schema validator by default" in {
      SpecificationVersion
        .fromDialectId(WebsocketJsonFormatValidatorConfig.default.specification)
        .get() mustBe SpecificationVersion.DRAFT_2020_12
    }

    "write the default config" in {
      format.writes(WebsocketJsonFormatValidatorConfig.default) mustBe Json.obj(
        "schema"          -> "{ \"type\": \"object\", \"required\": [\"name\"] }",
        "specification"   -> "https://json-schema.org/draft/2020-12/schema",
        "reject_strategy" -> "drop"
      )
    }

    "write a missing schema as null" in {
      written(format, WebsocketJsonFormatValidatorConfig()).value("schema") mustBe JsNull
    }

    "round trip the default config" in {
      roundTrip(format, WebsocketJsonFormatValidatorConfig.default)
    }

    "round trip a config without schema" in {
      roundTrip(format, WebsocketJsonFormatValidatorConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(
        format,
        WebsocketJsonFormatValidatorConfig(
          schema = Some("{ \"type\": \"array\", \"items\": { \"type\": \"number\" } }"),
          specification = draft07,
          rejectStrategy = RejectStrategy.Close
        )
      )
    }

    "round trip the dialect of every specification version" in {
      SpecificationVersion.values().foreach { v =>
        roundTrip(format, WebsocketJsonFormatValidatorConfig(specification = v.getDialectId)).specification mustBe v.getDialectId
      }
    }

    "round trip an unknown specification as is" in {
      roundTrip(format, WebsocketJsonFormatValidatorConfig(specification = "draft-42")).specification mustBe "draft-42"
    }

    "round trip an empty schema" in {
      roundTrip(format, WebsocketJsonFormatValidatorConfig(schema = Some(""))).schema mustBe Some("")
    }

    "round trip a schema spanning several lines" in {
      roundTrip(format, WebsocketJsonFormatValidatorConfig(schema = Some("{\n  \"type\": \"string\"\n}")))
    }

    "ignore a schema written as a json object instead of a string" in {
      readOk(format, Json.obj("schema" -> Json.obj("type" -> "object"))).schema mustBe None
    }

    "ignore a specification that is not a string" in {
      readOk(format, Json.obj("specification" -> 7)).specification mustBe WebsocketJsonFormatValidatorConfig().specification
    }

    "read the schema alone" in {
      readOk(format, Json.obj("schema" -> "{}")) mustBe WebsocketJsonFormatValidatorConfig(schema = Some("{}"))
    }

    "read the specification alone" in {
      readOk(format, Json.obj("specification" -> draft07)) mustBe WebsocketJsonFormatValidatorConfig(
        specification = draft07
      )
    }

    "read the reject strategy alone" in {
      readOk(format, Json.obj("reject_strategy" -> "close")) mustBe WebsocketJsonFormatValidatorConfig(
        rejectStrategy = RejectStrategy.Close
      )
    }

    "read an empty object as a config without schema" in {
      readOk(format, Json.obj()) mustBe WebsocketJsonFormatValidatorConfig()
    }

    "read a json null as a config without schema" in {
      readOk(format, JsNull) mustBe WebsocketJsonFormatValidatorConfig()
    }

    "read a json string as a config without schema" in {
      readOk(format, JsString("{}")) mustBe WebsocketJsonFormatValidatorConfig()
    }

    "read a json array as a config without schema" in {
      readOk(format, Json.arr("{}")) mustBe WebsocketJsonFormatValidatorConfig()
    }

    "not read the camel case reject strategy" in {
      readOk(format, Json.obj("rejectStrategy" -> "close")).rejectStrategy mustBe RejectStrategy.Drop
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, WebsocketJsonFormatValidatorConfig.default.copy(rejectStrategy = RejectStrategy.Close))
    }

    "be the default config of WebsocketJsonFormatValidator" in {
      defaultConfigReadsBack(new WebsocketJsonFormatValidator(), format) mustBe WebsocketJsonFormatValidatorConfig.default
    }
  }

  "WebsocketSizeValidatorConfig" should {

    val format = WebsocketSizeValidatorConfig.format

    "allow 4 kilobytes each way and drop by default" in {
      WebsocketSizeValidatorConfig.default mustBe WebsocketSizeValidatorConfig(4096, 4096, RejectStrategy.Drop)
      WebsocketSizeValidatorConfig.default mustBe WebsocketSizeValidatorConfig()
    }

    "write the default config" in {
      format.writes(WebsocketSizeValidatorConfig.default) mustBe Json.obj(
        "client_max_payload"   -> 4096,
        "upstream_max_payload" -> 4096,
        "reject_strategy"      -> "drop"
      )
    }

    "round trip the default config" in {
      roundTrip(format, WebsocketSizeValidatorConfig.default)
    }

    "round trip a config where every field is set" in {
      roundTrip(format, WebsocketSizeValidatorConfig(128, 1024 * 1024, RejectStrategy.Close))
    }

    "keep the client and upstream limits apart" in {
      val back = roundTrip(format, WebsocketSizeValidatorConfig(clientMaxPayload = 1, upstreamMaxPayload = 2))
      back.clientMaxPayload mustBe 1
      back.upstreamMaxPayload mustBe 2
    }

    "round trip zero limits" in {
      roundTrip(format, WebsocketSizeValidatorConfig(0, 0))
    }

    "round trip negative limits as is" in {
      roundTrip(format, WebsocketSizeValidatorConfig(-1, -1))
    }

    "round trip the largest limits" in {
      roundTrip(format, WebsocketSizeValidatorConfig(Int.MaxValue, Int.MaxValue))
    }

    "ignore a limit that does not fit an int" in {
      readOk(format, Json.obj("client_max_payload" -> (Int.MaxValue.toLong + 1L))).clientMaxPayload mustBe 4096
    }

    "ignore a limit written as a string" in {
      readOk(format, Json.obj("upstream_max_payload" -> "1024")).upstreamMaxPayload mustBe 4096
    }

    "ignore a limit that is not a whole number" in {
      readOk(format, Json.obj("client_max_payload" -> 1024.5)).clientMaxPayload mustBe 4096
    }

    "read a whole number written with a decimal part" in {
      readOk(format, Json.obj("client_max_payload" -> JsNumber(BigDecimal("2048.0")))).clientMaxPayload mustBe 2048
    }

    "read the client limit alone" in {
      readOk(format, Json.obj("client_max_payload" -> 10)) mustBe WebsocketSizeValidatorConfig(clientMaxPayload = 10)
    }

    "read the upstream limit alone" in {
      readOk(format, Json.obj("upstream_max_payload" -> 20)) mustBe WebsocketSizeValidatorConfig(upstreamMaxPayload = 20)
    }

    "read the reject strategy alone" in {
      readOk(format, Json.obj("reject_strategy" -> "close")) mustBe WebsocketSizeValidatorConfig(
        rejectStrategy = RejectStrategy.Close
      )
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe WebsocketSizeValidatorConfig.default
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe WebsocketSizeValidatorConfig.default
    }

    "read a json number as the default config" in {
      readOk(format, JsNumber(1024)) mustBe WebsocketSizeValidatorConfig.default
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr(1024, 1024)) mustBe WebsocketSizeValidatorConfig.default
    }

    "not read the camel case keys" in {
      readOk(
        format,
        Json.obj("clientMaxPayload" -> 1, "upstreamMaxPayload" -> 2)
      ) mustBe WebsocketSizeValidatorConfig.default
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, WebsocketSizeValidatorConfig(1, 2, RejectStrategy.Close))
    }

    "be the default config of WebsocketSizeValidator" in {
      defaultConfigReadsBack(new WebsocketSizeValidator(), format) mustBe WebsocketSizeValidatorConfig.default
    }
  }

  "JqWebsocketMessageTransformerConfig" should {

    val format = JqWebsocketMessageTransformerConfig.format

    "use the identity filter both ways by default" in {
      JqWebsocketMessageTransformerConfig() mustBe JqWebsocketMessageTransformerConfig(".", ".")
    }

    "write the default config" in {
      format.writes(JqWebsocketMessageTransformerConfig()) mustBe Json.obj(
        "request_filter"  -> ".",
        "response_filter" -> "."
      )
    }

    "round trip the default config" in {
      roundTrip(format, JqWebsocketMessageTransformerConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, JqWebsocketMessageTransformerConfig(".payload", "{ wrapped: . }"))
    }

    "keep the request and response filters apart" in {
      val back = roundTrip(format, JqWebsocketMessageTransformerConfig(requestFilter = ".in", responseFilter = ".out"))
      back.requestFilter mustBe ".in"
      back.responseFilter mustBe ".out"
    }

    "round trip filters with pipes, quotes and unicode" in {
      roundTrip(
        format,
        JqWebsocketMessageTransformerConfig(
          ".items[] | select(.name == \"été\")",
          "$__loc__ | {\"line\": .line}"
        )
      )
    }

    "round trip empty filters as empty strings" in {
      roundTrip(format, JqWebsocketMessageTransformerConfig("", "")) mustBe JqWebsocketMessageTransformerConfig("", "")
    }

    "read the request filter alone" in {
      readOk(format, Json.obj("request_filter" -> ".a")) mustBe JqWebsocketMessageTransformerConfig(requestFilter = ".a")
    }

    "read the response filter alone" in {
      readOk(format, Json.obj("response_filter" -> ".b")) mustBe JqWebsocketMessageTransformerConfig(responseFilter = ".b")
    }

    "ignore a filter that is not a string" in {
      readOk(format, Json.obj("request_filter" -> Json.arr(".a"), "response_filter" -> 1)) mustBe JqWebsocketMessageTransformerConfig()
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe JqWebsocketMessageTransformerConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe JqWebsocketMessageTransformerConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString(".a")) mustBe JqWebsocketMessageTransformerConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr(".a", ".b")) mustBe JqWebsocketMessageTransformerConfig()
    }

    "not read the camel case keys" in {
      readOk(format, Json.obj("requestFilter" -> ".a", "responseFilter" -> ".b")) mustBe JqWebsocketMessageTransformerConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, JqWebsocketMessageTransformerConfig(".a", ".b"))
    }

    "be the default config of JqWebsocketMessageTransformer" in {
      defaultConfigReadsBack(new JqWebsocketMessageTransformer(), format) mustBe JqWebsocketMessageTransformerConfig()
    }
  }

  "WorkflowWebsocketConfig" should {

    val format = WorkflowWebsocketConfig.format

    "have no workflow by default" in {
      WorkflowWebsocketConfig() mustBe WorkflowWebsocketConfig(None, None)
    }

    "write the default config with null workflows" in {
      format.writes(WorkflowWebsocketConfig()) mustBe Json.obj(
        "incoming_workflow" -> JsNull,
        "outgoing_workflow" -> JsNull
      )
    }

    "write the workflow ids" in {
      format.writes(WorkflowWebsocketConfig(Some("wf_in"), Some("wf_out"))) mustBe Json.obj(
        "incoming_workflow" -> "wf_in",
        "outgoing_workflow" -> "wf_out"
      )
    }

    "round trip the default config" in {
      roundTrip(format, WorkflowWebsocketConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, WorkflowWebsocketConfig(Some("workflow_1"), Some("workflow_2")))
    }

    "round trip an incoming workflow alone" in {
      roundTrip(format, WorkflowWebsocketConfig(incomingWorkflow = Some("workflow_1")))
    }

    "round trip an outgoing workflow alone" in {
      roundTrip(format, WorkflowWebsocketConfig(outgoingWorkflow = Some("workflow_2")))
    }

    "round trip an empty workflow id" in {
      roundTrip(format, WorkflowWebsocketConfig(Some(""), None)).incomingWorkflow mustBe Some("")
    }

    "ignore a workflow id that is not a string" in {
      readOk(format, Json.obj("incoming_workflow" -> 42, "outgoing_workflow" -> Json.obj("id" -> "wf"))) mustBe WorkflowWebsocketConfig()
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe WorkflowWebsocketConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe WorkflowWebsocketConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("workflow_1")) mustBe WorkflowWebsocketConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("workflow_1")) mustBe WorkflowWebsocketConfig()
    }

    "not read the camel case keys" in {
      readOk(format, Json.obj("incomingWorkflow" -> "a", "outgoingWorkflow" -> "b")) mustBe WorkflowWebsocketConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, WorkflowWebsocketConfig(Some("a"), Some("b")))
    }

    "list both workflows in its config flow and schema" in {
      WorkflowWebsocketConfig.configFlowNoAsync mustBe Seq("incoming_workflow", "outgoing_workflow")
      WorkflowWebsocketConfig.configSchema.get.keys must contain allElementsOf WorkflowWebsocketConfig.configFlowNoAsync
    }

    "be the config flow and schema of WorkflowWebsocketTransformer" in {
      val plugin = new WorkflowWebsocketTransformer()
      plugin.configFlow mustBe WorkflowWebsocketConfig.configFlowNoAsync
      plugin.configSchema mustBe WorkflowWebsocketConfig.configSchema
    }

    "be the default config of WorkflowWebsocketTransformer" in {
      defaultConfigReadsBack(new WorkflowWebsocketTransformer(), format) mustBe WorkflowWebsocketConfig()
    }
  }

  "WebsocketMirrorBackendConfig" should {

    val format = WebsocketMirrorBackendConfig.format

    "have no mirror url by default" in {
      WebsocketMirrorBackendConfig().url mustBe None
    }

    "write the default config with a null url" in {
      format.writes(WebsocketMirrorBackendConfig()) mustBe Json.obj("url" -> JsNull)
    }

    "write the url" in {
      format.writes(WebsocketMirrorBackendConfig(Some("ws://mirror.oto.tools:8080/ws"))) mustBe Json.obj(
        "url" -> "ws://mirror.oto.tools:8080/ws"
      )
    }

    "round trip the default config" in {
      roundTrip(format, WebsocketMirrorBackendConfig())
    }

    "round trip a config with a url" in {
      roundTrip(format, WebsocketMirrorBackendConfig(Some("wss://mirror.oto.tools/ws?foo=bar")))
    }

    "round trip a url holding expression language" in {
      roundTrip(format, WebsocketMirrorBackendConfig(Some("ws://${req.host}/mirror")))
    }

    "keep the spaces around a url" in {
      roundTrip(format, WebsocketMirrorBackendConfig(Some(" ws://mirror.oto.tools "))).url mustBe Some(
        " ws://mirror.oto.tools "
      )
    }

    "read an empty url as no url" in {
      readOk(format, Json.obj("url" -> "")) mustBe WebsocketMirrorBackendConfig()
    }

    "read a blank url as no url" in {
      readOk(format, Json.obj("url" -> "   ")) mustBe WebsocketMirrorBackendConfig()
    }

    "ignore a url that is not a string" in {
      readOk(format, Json.obj("url" -> Json.arr("ws://mirror.oto.tools"))) mustBe WebsocketMirrorBackendConfig()
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe WebsocketMirrorBackendConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe WebsocketMirrorBackendConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("ws://mirror.oto.tools")) mustBe WebsocketMirrorBackendConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("ws://mirror.oto.tools")) mustBe WebsocketMirrorBackendConfig()
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("url" -> "ws://a", "target" -> "ws://b")) mustBe WebsocketMirrorBackendConfig(Some("ws://a"))
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, WebsocketMirrorBackendConfig(Some("ws://mirror.oto.tools")))
    }

    "list url in its config flow and schema" in {
      WebsocketMirrorBackendConfig.configFlow mustBe Seq("url")
      WebsocketMirrorBackendConfig.configSchema.keys must contain("url")
    }

    "be the config flow and schema of WebsocketMirrorBackend" in {
      val plugin = new WebsocketMirrorBackend()
      plugin.configFlow mustBe WebsocketMirrorBackendConfig.configFlow
      plugin.configSchema mustBe Some(WebsocketMirrorBackendConfig.configSchema)
    }

    "be the default config of WebsocketMirrorBackend" in {
      defaultConfigReadsBack(new WebsocketMirrorBackend(), format) mustBe WebsocketMirrorBackendConfig()
    }
  }
}
