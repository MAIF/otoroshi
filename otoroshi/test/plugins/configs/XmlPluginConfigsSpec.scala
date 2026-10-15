package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class XmlPluginConfigsSpec extends PluginConfigFormatSpec {

  "JsonTransformConfig" should {

    val format = JsonTransformConfig.format

    "have no jq filter by default" in {
      JsonTransformConfig().filter mustBe None
    }

    "write the default config with a null filter" in {
      format.writes(JsonTransformConfig()) mustBe Json.obj("filter" -> JsNull)
    }

    "write the jq filter under filter" in {
      format.writes(JsonTransformConfig(Some(".items | map(.id)"))) mustBe Json.obj("filter" -> ".items | map(.id)")
    }

    "round trip the default config" in {
      roundTrip(format, JsonTransformConfig())
    }

    "round trip a config with a filter" in {
      roundTrip(format, JsonTransformConfig(Some("{ name: .user.name, tags: [.tags[] | ascii_downcase] }")))
    }

    "round trip the identity filter" in {
      roundTrip(format, JsonTransformConfig(Some("."))).filter mustBe Some(".")
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe JsonTransformConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe JsonTransformConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString(".foo")) mustBe JsonTransformConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr(".foo")) mustBe JsonTransformConfig()
    }

    "read a null filter as no filter" in {
      readOk(format, Json.obj("filter" -> JsNull)) mustBe JsonTransformConfig()
    }

    "ignore a filter that is not a string" in {
      readOk(format, Json.obj("filter" -> Json.arr(".a", ".b"))) mustBe JsonTransformConfig()
    }

    "keep an empty filter" in {
      readOk(format, Json.obj("filter" -> "")) mustBe JsonTransformConfig(Some(""))
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("filter" -> ".foo", "jq" -> ".bar")) mustBe JsonTransformConfig(Some(".foo"))
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, JsonTransformConfig(Some(".foo")))
      jsonMatchesFormat(format, JsonTransformConfig())
    }

    "be the default config of XmlToJsonRequest" in {
      defaultConfigReadsBack(new XmlToJsonRequest(), format) mustBe JsonTransformConfig()
    }

    "be the default config of JsonToXmlRequest" in {
      defaultConfigReadsBack(new JsonToXmlRequest(), format) mustBe JsonTransformConfig()
    }

    "be the default config of XmlToJsonResponse" in {
      defaultConfigReadsBack(new XmlToJsonResponse(), format) mustBe JsonTransformConfig()
    }

    "be the default config of JsonToXmlResponse" in {
      defaultConfigReadsBack(new JsonToXmlResponse(), format) mustBe JsonTransformConfig()
    }
  }

  "SOAPActionConfig" should {

    val format = SOAPActionConfig.format

    val pluginDefault = SOAPActionConfig(envelope = "<soap envelope />")

    // every field set to something else than its default
    val full = SOAPActionConfig(
      url = Some("https://soap.oto.tools/ws/NumberConversion.wso"),
      envelope = """<soap:Envelope><soap:Body><NumberToWords><ubiNum>${req.query.n}</ubiNum></NumberToWords></soap:Body></soap:Envelope>""",
      action = Some("NumberToWords"),
      preserveQuery = false,
      charset = Some("utf-8"),
      convertRequestBodyToXml = false,
      jqRequestFilter = Some(".body"),
      jqResponseFilter = Some(".Envelope.Body.NumberToWordsResponse.NumberToWordsResult")
    )

    "keep the query and convert json bodies to xml by default" in {
      pluginDefault.url mustBe None
      pluginDefault.action mustBe None
      pluginDefault.preserveQuery mustBe true
      pluginDefault.charset mustBe None
      pluginDefault.convertRequestBodyToXml mustBe true
      pluginDefault.jqRequestFilter mustBe None
      pluginDefault.jqResponseFilter mustBe None
    }

    "write the plugin default config" in {
      format.writes(pluginDefault) mustBe Json.obj(
        "url"                -> JsNull,
        "envelope"           -> "<soap envelope />",
        "action"             -> JsNull,
        "preserve_query"     -> true,
        "charset"            -> JsNull,
        "convert_request_body_to_xml" -> true,
        "jq_request_filter"  -> JsNull,
        "jq_response_filter" -> JsNull
      )
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "url"                -> "https://soap.oto.tools/ws/NumberConversion.wso",
        "envelope"           -> full.envelope,
        "action"             -> "NumberToWords",
        "preserve_query"     -> false,
        "charset"            -> "utf-8",
        "convert_request_body_to_xml" -> false,
        "jq_request_filter"  -> ".body",
        "jq_response_filter" -> ".Envelope.Body.NumberToWordsResponse.NumberToWordsResult"
      )
    }

    "round trip the plugin default config" in {
      roundTrip(format, pluginDefault)
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a config that does not convert the request body to xml" in {
      roundTrip(format, pluginDefault.copy(convertRequestBodyToXml = false)).convertRequestBodyToXml mustBe false
    }

    "read the convert_request_body_to_xml flag of the ui form" in {
      readOk(
        format,
        Json.obj("envelope" -> "<e/>", "convert_request_body_to_xml" -> false)
      ).convertRequestBodyToXml mustBe false
    }

    "convert the request body to xml when the flag is missing" in {
      readOk(format, Json.obj("envelope" -> "<e/>")).convertRequestBodyToXml mustBe true
    }

    "convert the request body to xml when the flag is not a boolean" in {
      readOk(
        format,
        Json.obj("envelope" -> "<e/>", "convert_request_body_to_xml" -> "false")
      ).convertRequestBodyToXml mustBe true
    }

    "round trip a config that does not preserve the query" in {
      roundTrip(format, pluginDefault.copy(preserveQuery = false)).preserveQuery mustBe false
    }

    "round trip a config with an url only" in {
      roundTrip(format, pluginDefault.copy(url = Some("http://127.0.0.1:8080/soap"))).url mustBe Some(
        "http://127.0.0.1:8080/soap"
      )
    }

    "round trip a config with jq filters only" in {
      val back = roundTrip(format, pluginDefault.copy(jqRequestFilter = Some(".a"), jqResponseFilter = Some(".b")))
      back.jqRequestFilter mustBe Some(".a")
      back.jqResponseFilter mustBe Some(".b")
    }

    "round trip a multi line envelope" in {
      roundTrip(format, pluginDefault.copy(envelope = "<a>\n  <b>${req.body}</b>\n</a>"))
    }

    "read the envelope alone and default the other fields" in {
      readOk(format, Json.obj("envelope" -> "<e/>")) mustBe SOAPActionConfig(envelope = "<e/>")
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a json string" in {
      readError(format, JsString("<soap envelope />"))
    }

    "not read a json array" in {
      readError(format, Json.arr("<soap envelope />"))
    }

    "not read a config without envelope" in {
      readError(format, Json.obj("url" -> "https://soap.oto.tools", "action" -> "Foo"))
    }

    "not read a null envelope" in {
      readError(format, Json.obj("envelope" -> JsNull))
    }

    "not read an envelope that is not a string" in {
      readError(format, Json.obj("envelope" -> Json.obj("soap" -> "envelope")))
    }

    "read null optional fields as missing" in {
      readOk(
        format,
        Json.obj(
          "envelope"           -> "<e/>",
          "url"                -> JsNull,
          "action"             -> JsNull,
          "charset"            -> JsNull,
          "jq_request_filter"  -> JsNull,
          "jq_response_filter" -> JsNull
        )
      ) mustBe SOAPActionConfig(envelope = "<e/>")
    }

    "ignore optional fields that are not strings" in {
      readOk(
        format,
        Json.obj("envelope" -> "<e/>", "url" -> 42, "action" -> true, "charset" -> Json.arr("utf-8"))
      ) mustBe SOAPActionConfig(envelope = "<e/>")
    }

    "ignore a preserve_query written as a string" in {
      readOk(format, Json.obj("envelope" -> "<e/>", "preserve_query" -> "false")).preserveQuery mustBe true
    }

    "not read the camel case keys" in {
      readOk(
        format,
        Json.obj("envelope" -> "<e/>", "preserveQuery" -> false, "jqRequestFilter" -> ".a", "jqResponseFilter" -> ".b")
      ) mustBe SOAPActionConfig(envelope = "<e/>")
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
      jsonMatchesFormat(format, pluginDefault)
    }

    "be the default config of SOAPAction" in {
      defaultConfigReadsBack(new SOAPAction(), format) mustBe pluginDefault
    }
  }
}
