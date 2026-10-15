package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class ResponsePluginConfigsSpec extends PluginConfigFormatSpec {

  "StaticResponseConfig" should {

    val format = StaticResponseConfig.format

    val full = StaticResponseConfig(
      status = 404,
      headers = Map("Content-Type" -> "application/json", "X-Foo" -> "bar"),
      body = """{"error":"not found"}""",
      applyEl = true
    )

    "default to an empty 200 response without expression language" in {
      val config = StaticResponseConfig()
      config.status mustBe 200
      config.headers mustBe empty
      config.body mustBe ""
      config.applyEl mustBe false
    }

    "write the default config" in {
      format.writes(StaticResponseConfig()) mustBe Json.obj(
        "status"   -> 200,
        "headers"  -> Json.obj(),
        "body"     -> "",
        "apply_el" -> false
      )
    }

    "write every field under its snake case key" in {
      format.writes(full) mustBe Json.obj(
        "status"   -> 404,
        "headers"  -> Json.obj("Content-Type" -> "application/json", "X-Foo" -> "bar"),
        "body"     -> """{"error":"not found"}""",
        "apply_el" -> true
      )
    }

    "round trip the default config" in {
      roundTrip(format, StaticResponseConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a base64 body as is" in {
      roundTrip(format, StaticResponseConfig(body = "Base64(SGVsbG8gd29ybGQ=)")).body mustBe "Base64(SGVsbG8gd29ybGQ=)"
    }

    "round trip expression language in the body and the headers" in {
      roundTrip(
        format,
        StaticResponseConfig(headers = Map("X-Req" -> "${req.id}"), body = "hello ${user.email}", applyEl = true)
      )
    }

    "round trip a body with quotes, unicode and line breaks" in {
      roundTrip(format, StaticResponseConfig(body = "a \"quoted\" été\nbody"))
    }

    "round trip a non standard status" in {
      roundTrip(format, StaticResponseConfig(status = 599)).status mustBe 599
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe StaticResponseConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe StaticResponseConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("static")) mustBe StaticResponseConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr(1, 2)) mustBe StaticResponseConfig()
    }

    "read a status alone and default the other fields" in {
      readOk(format, Json.obj("status" -> 201)) mustBe StaticResponseConfig(status = 201)
    }

    "read apply_el alone and default the other fields" in {
      readOk(format, Json.obj("apply_el" -> true)) mustBe StaticResponseConfig(applyEl = true)
    }

    "ignore a status written as a string" in {
      readOk(format, Json.obj("status" -> "404")) mustBe StaticResponseConfig()
    }

    "ignore a status that is not a whole number" in {
      readOk(format, Json.obj("status" -> 404.5)) mustBe StaticResponseConfig()
    }

    "ignore the whole headers map as soon as a value is not a string" in {
      readOk(format, Json.obj("headers" -> Json.obj("X-Foo" -> "foo", "X-Count" -> 1))) mustBe StaticResponseConfig()
    }

    "ignore a body that is not a string" in {
      readOk(format, Json.obj("body" -> Json.obj("message" -> "hello"))) mustBe StaticResponseConfig()
    }

    "ignore an apply_el written as a string" in {
      readOk(format, Json.obj("apply_el" -> "true")) mustBe StaticResponseConfig()
    }

    "not read the camel case applyEl key" in {
      readOk(format, Json.obj("applyEl" -> true)) mustBe StaticResponseConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of StaticResponse" in {
      defaultConfigReadsBack(new StaticResponse(), format) mustBe StaticResponseConfig()
    }
  }

  "MockResponse" should {

    val format = MockResponse.format

    val full = MockResponse(
      path = "/users/:id",
      method = "POST",
      status = 201,
      headers = Map("Content-Type" -> "application/json"),
      body = """{"id":"${req.pathparams.id}"}"""
    )

    "default to an empty 200 response on GET /" in {
      MockResponse() mustBe MockResponse("/", "GET", 200, Map.empty, "")
    }

    "write the default mock" in {
      format.writes(MockResponse()) mustBe Json.obj(
        "path"    -> "/",
        "method"  -> "GET",
        "status"  -> 200,
        "headers" -> Json.obj(),
        "body"    -> ""
      )
    }

    "write every field" in {
      format.writes(full) mustBe Json.obj(
        "path"    -> "/users/:id",
        "method"  -> "POST",
        "status"  -> 201,
        "headers" -> Json.obj("Content-Type" -> "application/json"),
        "body"    -> """{"id":"${req.pathparams.id}"}"""
      )
    }

    "round trip the default mock" in {
      roundTrip(format, MockResponse())
    }

    "round trip a mock where every field is set" in {
      roundTrip(format, full)
    }

    "keep the case of the method" in {
      roundTrip(format, MockResponse(method = "delete")).method mustBe "delete"
    }

    "read an empty object as the default mock" in {
      readOk(format, Json.obj()) mustBe MockResponse()
    }

    "read a json null as the default mock" in {
      readOk(format, JsNull) mustBe MockResponse()
    }

    "read a json string as the default mock" in {
      readOk(format, JsString("/foo")) mustBe MockResponse()
    }

    "read a method alone and default the other fields" in {
      readOk(format, Json.obj("method" -> "PUT")) mustBe MockResponse(method = "PUT")
    }

    "ignore a status written as a string" in {
      readOk(format, Json.obj("status" -> "201")) mustBe MockResponse()
    }

    "ignore a path that is not a string" in {
      readOk(format, Json.obj("path" -> 42)) mustBe MockResponse()
    }

    "ignore the headers when one of them holds an object" in {
      readOk(format, Json.obj("headers" -> Json.obj("X-Foo" -> Json.obj("a" -> "b")))) mustBe MockResponse()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }
  }

  "MockField" should {

    val format = MockField.format

    "write its fields in snake case" in {
      format.writes(MockField("age", "Number", JsNumber(42))) mustBe Json.obj(
        "field_name" -> "age",
        "field_type" -> "Number",
        "value"      -> 42
      )
    }

    "round trip any json value" in {
      roundTrip(format, MockField("name", "String", JsString("foo")))
      roundTrip(format, MockField("tags", "Array", Json.arr("a", "b")))
      roundTrip(format, MockField("nested", "Object", Json.obj("a" -> Json.obj("b" -> true))))
    }

    "round trip a null value" in {
      roundTrip(format, MockField("nothing", "Null", JsNull)).value mustBe JsNull
    }

    "fail to read a field without field_name" in {
      readError(format, Json.obj("field_type" -> "String", "value" -> "foo"))
    }

    "fail to read a field without value" in {
      readError(format, Json.obj("field_name" -> "name", "field_type" -> "String"))
    }
  }

  "MockResource" should {

    val format = MockResource.format

    val full = MockResource(
      name = "users",
      schema = Seq(MockField("name", "String", JsString("foo")), MockField("age", "Number", JsNumber(42))),
      additionalData = Some(Json.obj("source" -> "mock"))
    )

    "write a missing additional_data as null" in {
      format.writes(MockResource("users")) mustBe Json.obj(
        "name"            -> "users",
        "schema"          -> Json.arr(),
        "additional_data" -> JsNull
      )
    }

    "round trip a resource without schema" in {
      roundTrip(format, MockResource("users"))
    }

    "round trip a resource where every field is set" in {
      roundTrip(format, full)
    }

    "fail to read a resource without name" in {
      readError(format, Json.obj("schema" -> Json.arr()))
    }

    "drop the schema fields that cannot be read" in {
      val json = Json.obj(
        "name"   -> "users",
        "schema" -> Json.arr(
          Json.obj("field_name" -> "name", "field_type" -> "String", "value" -> "foo"),
          Json.obj("field_type" -> "String")
        )
      )
      readOk(format, json) mustBe MockResource("users", Seq(MockField("name", "String", JsString("foo"))))
    }

    "ignore an additional_data that is not an object" in {
      readOk(format, Json.obj("name" -> "users", "additional_data" -> "foo")) mustBe MockResource("users")
    }
  }

  "MockEndpoint" should {

    val format = MockEndpoint.format

    val full = MockEndpoint(
      method = "GET",
      path = "/users",
      status = 200,
      body = Some(Json.obj("ok" -> true)),
      resource = Some("users"),
      resourceList = true,
      headers = Some(Json.obj("X-Foo" -> "bar")),
      length = Some(10)
    )

    "write the missing optional fields as null" in {
      format.writes(MockEndpoint("GET", "/", 204)) mustBe Json.obj(
        "method"        -> "GET",
        "path"          -> "/",
        "status"        -> 204,
        "body"          -> JsNull,
        "resource"      -> JsNull,
        "resource_list" -> false,
        "headers"       -> JsNull,
        "length"        -> JsNull
      )
    }

    "round trip an endpoint without optional fields" in {
      roundTrip(format, MockEndpoint("GET", "/", 204))
    }

    "round trip an endpoint where every field is set" in {
      roundTrip(format, full)
    }

    "fail to read an endpoint without resource_list" in {
      readError(format, Json.obj("method" -> "GET", "path" -> "/", "status" -> 200))
    }

    "fail to read an endpoint without status" in {
      readError(format, Json.obj("method" -> "GET", "path" -> "/", "resource_list" -> false))
    }

    "ignore a length written as a string" in {
      val json = Json.obj("method" -> "GET", "path" -> "/", "status" -> 200, "resource_list" -> false, "length" -> "10")
      readOk(format, json).length mustBe None
    }
  }

  "MockFormData" should {

    val format = MockFormData.format

    val full = MockFormData(
      resources = Seq(MockResource("users", Seq(MockField("name", "String", JsString("foo"))))),
      endpoints = Seq(MockEndpoint("GET", "/users", 200, resource = Some("users"), resourceList = true))
    )

    "write empty arrays when empty" in {
      format.writes(MockFormData()) mustBe Json.obj("resources" -> Json.arr(), "endpoints" -> Json.arr())
    }

    "round trip an empty form data" in {
      roundTrip(format, MockFormData())
    }

    "round trip resources and endpoints" in {
      roundTrip(format, full)
    }

    "read an empty object as an empty form data" in {
      readOk(format, Json.obj()) mustBe MockFormData()
    }

    "drop the endpoints that cannot be read and keep the others" in {
      val json = Json.obj(
        "endpoints" -> Json.arr(
          Json.obj("method" -> "GET", "path" -> "/a", "status" -> 200, "resource_list" -> false),
          Json.obj("method" -> "GET", "path" -> "/b")
        )
      )
      readOk(format, json) mustBe MockFormData(endpoints = Seq(MockEndpoint("GET", "/a", 200)))
    }
  }

  "MockResponsesConfig" should {

    val format = MockResponsesConfig.format

    val responses = Seq(
      MockResponse(path = "/users", body = "[]"),
      MockResponse(path = "/users/:id", method = "DELETE", status = 204)
    )

    "default to no response, pass through and no form data" in {
      val config = MockResponsesConfig()
      config.responses mustBe empty
      config.passThrough mustBe true
      config.formData mustBe None
    }

    "write the responses and pass_through of the default config" in {
      (written(format, MockResponsesConfig()) - "form_data") mustBe Json.obj(
        "responses"    -> Json.arr(),
        "pass_through" -> true
      )
    }

    "write each response with the mock response format" in {
      (format.writes(MockResponsesConfig(responses)) \ "responses").as[JsArray] mustBe JsArray(
        responses.map(MockResponse.format.writes)
      )
    }

    "round trip the default config" in {
      roundTrip(format, MockResponsesConfig())
    }

    "round trip responses with pass through disabled" in {
      roundTrip(format, MockResponsesConfig(responses = responses, passThrough = false))
    }

    "keep the order of the responses" in {
      roundTrip(format, MockResponsesConfig(responses.reverse)).responses.map(_.path) mustBe Seq("/users/:id", "/users")
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe MockResponsesConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe MockResponsesConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("mock")) mustBe MockResponsesConfig()
    }

    "read pass_through alone and default the other fields" in {
      readOk(format, Json.obj("pass_through" -> false)) mustBe MockResponsesConfig(passThrough = false)
    }

    "ignore a pass_through written as a string" in {
      readOk(format, Json.obj("pass_through" -> "false")) mustBe MockResponsesConfig()
    }

    "ignore responses that are not an array" in {
      readOk(format, Json.obj("responses" -> Json.obj("path" -> "/"))) mustBe MockResponsesConfig()
    }

    "read a response that is not an object as a default mock response" in {
      readOk(format, Json.obj("responses" -> Json.arr("foo"))) mustBe MockResponsesConfig(Seq(MockResponse()))
    }

    "read the form data of the mocks designer" in {
      val formData = MockFormData(endpoints = Seq(MockEndpoint("GET", "/users", 200)))
      val json     = Json.obj("form_data" -> MockFormData.format.writes(formData))
      readOk(format, json) mustBe MockResponsesConfig(formData = Some(formData))
    }

    "read a form_data that is not an object as an empty form data" in {
      readOk(format, Json.obj("form_data" -> "foo")).formData mustBe Some(MockFormData())
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, MockResponsesConfig(responses, passThrough = false))
    }

    "be the default config of MockResponses" in {
      defaultConfigReadsBack(new MockResponses(), format) mustBe MockResponsesConfig()
    }
  }

  "ResponseStatusRange" should {

    "write from and to" in {
      ResponseStatusRange(400, 499).json mustBe Json.obj("from" -> 400, "to" -> 499)
    }

    "contain its bounds" in {
      ResponseStatusRange(500, 599).contains(500) mustBe true
      ResponseStatusRange(500, 599).contains(599) mustBe true
    }

    "not contain the statuses around its bounds" in {
      ResponseStatusRange(500, 599).contains(499) mustBe false
      ResponseStatusRange(500, 599).contains(600) mustBe false
    }

    "contain nothing when reversed" in {
      ResponseStatusRange(599, 500).contains(550) mustBe false
    }
  }

  "NgErrorRewriterConfig" should {

    val format = NgErrorRewriterConfig.fmt

    val default = NgErrorRewriterConfig.default

    val full = NgErrorRewriterConfig(
      ranges = Seq(ResponseStatusRange(400, 403), ResponseStatusRange(502, 504)),
      templates = Map("default" -> "oops ${error_id}", "404-text/html" -> "<p>not found</p>"),
      log = false,
      `export` = false,
      maxBodySize = 2048L,
      useOtoroshiErrorTemplate = false,
      preservedHeaders = Seq("X-Request-Id", "Retry-After"),
      additionalHeaders = Map("X-Rewritten" -> "true"),
      applyEl = false
    )

    "rewrite 5xx errors with a default html template by default" in {
      default.ranges mustBe Seq(ResponseStatusRange(500, 599))
      default.templates.keySet mustBe Set("default")
      default.templates("default") must include("${error_id}")
      default.log mustBe true
      default.`export` mustBe true
      default.maxBodySize mustBe 1048576L
      default.useOtoroshiErrorTemplate mustBe true
      default.preservedHeaders mustBe empty
      default.additionalHeaders mustBe empty
      default.applyEl mustBe true
    }

    "write the default config" in {
      format.writes(default) mustBe Json.obj(
        "ranges"                      -> Json.arr(Json.obj("from" -> 500, "to" -> 599)),
        "templates"                   -> Json.obj("default" -> default.templates("default")),
        "log"                         -> true,
        "export"                      -> true,
        "max_body_size"               -> 1048576L,
        "use_otoroshi_error_template" -> true,
        "preserved_headers"           -> Json.arr(),
        "additional_headers"          -> Json.obj(),
        "apply_el"                    -> true
      )
    }

    "round trip the default config" in {
      roundTrip(format, default)
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a config without range nor template" in {
      roundTrip(format, NgErrorRewriterConfig(Seq.empty, Map.empty, log = true, `export` = true))
    }

    "keep the order of the ranges" in {
      roundTrip(format, full.copy(ranges = full.ranges.reverse)).ranges mustBe Seq(
        ResponseStatusRange(502, 504),
        ResponseStatusRange(400, 403)
      )
    }

    "round trip a zero max body size" in {
      roundTrip(format, default.copy(maxBodySize = 0L)).maxBodySize mustBe 0L
    }

    "read an empty object without range, template, log nor export" in {
      readOk(format, Json.obj()) mustBe NgErrorRewriterConfig(Seq.empty, Map.empty, log = false, `export` = false)
    }

    "not read an empty object as the default config" in {
      readOk(format, Json.obj()) must not be default
    }

    "read a json null like an empty object" in {
      readOk(format, JsNull) mustBe NgErrorRewriterConfig(Seq.empty, Map.empty, log = false, `export` = false)
    }

    "read a json string like an empty object" in {
      readOk(format, JsString("rewrite")) mustBe NgErrorRewriterConfig(Seq.empty, Map.empty, false, false)
    }

    "read the ranges alone and default the other fields" in {
      readOk(format, Json.obj("ranges" -> Json.arr(Json.obj("from" -> 404, "to" -> 404)))) mustBe
        NgErrorRewriterConfig(Seq(ResponseStatusRange(404, 404)), Map.empty, log = false, `export` = false)
    }

    "fail to read a range without to" in {
      readError(format, Json.obj("ranges" -> Json.arr(Json.obj("from" -> 500))))
    }

    "fail to read a range whose bounds are strings" in {
      readError(format, Json.obj("ranges" -> Json.arr(Json.obj("from" -> "500", "to" -> "599"))))
    }

    "ignore ranges that are not an array" in {
      readOk(format, Json.obj("ranges" -> Json.obj("from" -> 500, "to" -> 599))).ranges mustBe empty
    }

    "ignore the whole templates map as soon as a template is not a string" in {
      readOk(format, Json.obj("templates" -> Json.obj("default" -> "ok", "404" -> 404))).templates mustBe empty
    }

    "ignore a max_body_size written as a string" in {
      readOk(format, Json.obj("max_body_size" -> "2048")).maxBodySize mustBe 1048576L
    }

    "ignore a log flag written as a string" in {
      readOk(format, Json.obj("log" -> "true")).log mustBe false
    }

    "read use_otoroshi_error_template and apply_el set to false" in {
      val config = readOk(format, Json.obj("use_otoroshi_error_template" -> false, "apply_el" -> false))
      config.useOtoroshiErrorTemplate mustBe false
      config.applyEl mustBe false
    }

    "ignore preserved_headers holding something else than strings" in {
      readOk(format, Json.obj("preserved_headers" -> Json.arr("X-Foo", 1))).preservedHeaders mustBe empty
    }

    "ignore additional_headers holding something else than strings" in {
      readOk(format, Json.obj("additional_headers" -> Json.obj("X-Foo" -> true))).additionalHeaders mustBe empty
    }

    "not read the camel case keys" in {
      val json   = Json.obj("maxBodySize" -> 10, "applyEl" -> false, "preservedHeaders" -> Json.arr("X"))
      val config = readOk(format, json)
      config.maxBodySize mustBe 1048576L
      config.applyEl mustBe true
      config.preservedHeaders mustBe empty
    }

    "match the statuses of any of its ranges" in {
      full.matching(401) mustBe true
      full.matching(503) mustBe true
    }

    "not match the statuses outside its ranges" in {
      full.matching(404) mustBe false
      full.matching(500) mustBe false
    }

    "match nothing without range" in {
      default.copy(ranges = Seq.empty).matching(500) mustBe false
    }

    "parse the default template key as a catch all" in {
      NgErrorRewriterConfig.parseTemplateKey("default") mustBe ((None, None))
    }

    "parse a status template key" in {
      NgErrorRewriterConfig.parseTemplateKey("404") mustBe ((Some(404), None))
    }

    "parse a content type template key" in {
      NgErrorRewriterConfig.parseTemplateKey("text/html") mustBe ((None, Some("text/html")))
    }

    "parse a content type template key for any status" in {
      NgErrorRewriterConfig.parseTemplateKey("default-application/json") mustBe ((None, Some("application/json")))
    }

    "parse a status and content type template key" in {
      NgErrorRewriterConfig.parseTemplateKey("404-text/html") mustBe ((Some(404), Some("text/html")))
    }

    "keep the dashes of the content type after the status" in {
      NgErrorRewriterConfig.parseTemplateKey("503-application/x-www-form-urlencoded") mustBe
        ((Some(503), Some("application/x-www-form-urlencoded")))
    }

    "parse a key with a dash but no status prefix as a content type" in {
      NgErrorRewriterConfig.parseTemplateKey("application/x-ndjson") mustBe ((None, Some("application/x-ndjson")))
      NgErrorRewriterConfig.parseTemplateKey("-text/html") mustBe ((None, Some("-text/html")))
    }

    "parse an empty key as an empty content type" in {
      NgErrorRewriterConfig.parseTemplateKey("") mustBe ((None, Some("")))
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of NgErrorRewriter" in {
      defaultConfigReadsBack(new NgErrorRewriter(), format) mustBe default
    }
  }
}
