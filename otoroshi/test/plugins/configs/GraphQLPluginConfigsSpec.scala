package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class GraphQLPluginConfigsSpec extends PluginConfigFormatSpec {

  "GraphQLQueryConfig" should {

    val format = GraphQLQueryConfig.format

    val pluginDefault = GraphQLQueryConfig(url = "https://some.graphql/endpoint")

    val full = GraphQLQueryConfig(
      url = "https://countries.trevorblades.com/graphql",
      headers = Map("Authorization" -> "Bearer ${config.token}"),
      method = "GET",
      timeout = 5000L,
      query = "query { countries { code name } }",
      responsePath = Some("$.data.countries"),
      responseFilter = Some(".[] | .name")
    )

    "post an empty query with a one minute timeout by default" in {
      pluginDefault.headers mustBe empty
      pluginDefault.method mustBe "POST"
      pluginDefault.timeout mustBe 60000L
      pluginDefault.query mustBe "{\n\n}"
      pluginDefault.responsePath mustBe None
      pluginDefault.responseFilter mustBe None
    }

    "write the default config of the plugin" in {
      format.writes(pluginDefault) mustBe Json.obj(
        "url"             -> "https://some.graphql/endpoint",
        "headers"         -> Json.obj(),
        "method"          -> "POST",
        "query"           -> "{\n\n}",
        "timeout"         -> 60000L,
        "response_path"   -> JsNull,
        "response_filter" -> JsNull
      )
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "url"             -> "https://countries.trevorblades.com/graphql",
        "headers"         -> Json.obj("Authorization" -> "Bearer ${config.token}"),
        "method"          -> "GET",
        "query"           -> "query { countries { code name } }",
        "timeout"         -> 5000L,
        "response_path"   -> "$.data.countries",
        "response_filter" -> ".[] | .name"
      )
    }

    "round trip the default config of the plugin" in {
      roundTrip(format, pluginDefault)
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a response path without response filter" in {
      roundTrip(format, pluginDefault.copy(responsePath = Some("$.data")))
    }

    "round trip a response filter without response path" in {
      roundTrip(format, pluginDefault.copy(responseFilter = Some(".data")))
    }

    "round trip a multiline query with variables" in {
      val query =
        """query Country($code: ID!) {
          |  country(code: $code) {
          |    name
          |  }
          |}""".stripMargin
      roundTrip(format, pluginDefault.copy(query = query)).query mustBe query
    }

    "round trip the method as written, without normalizing it" in {
      roundTrip(format, pluginDefault.copy(method = "get")).method mustBe "get"
    }

    "round trip a zero timeout" in {
      roundTrip(format, pluginDefault.copy(timeout = 0L)).timeout mustBe 0L
    }

    "not read an empty object, the url being mandatory" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a json string" in {
      readError(format, JsString("https://some.graphql/endpoint"))
    }

    "not read a json array" in {
      readError(format, Json.arr("https://some.graphql/endpoint"))
    }

    "not read a url that is not a string" in {
      readError(format, Json.obj("url" -> 42))
    }

    "read a url alone with the default for everything else" in {
      readOk(format, Json.obj("url" -> "https://some.graphql/endpoint")) mustBe pluginDefault
    }

    "read a null response path and filter as none" in {
      readOk(
        format,
        Json.obj("url" -> "https://some.graphql/endpoint", "response_path" -> JsNull, "response_filter" -> JsNull)
      ) mustBe pluginDefault
    }

    "ignore a timeout written as a string" in {
      readOk(format, Json.obj("url" -> "https://some.graphql/endpoint", "timeout" -> "5000")).timeout mustBe 60000L
    }

    "ignore a method that is not a string" in {
      readOk(format, Json.obj("url" -> "https://some.graphql/endpoint", "method" -> 1)).method mustBe "POST"
    }

    "ignore the headers as soon as a value is not a string" in {
      readOk(
        format,
        Json.obj("url" -> "https://some.graphql/endpoint", "headers" -> Json.obj("X-Foo" -> "a", "X-Bar" -> 1))
      ).headers mustBe empty
    }

    "ignore a response path that is not a string" in {
      readOk(format, Json.obj("url" -> "https://some.graphql/endpoint", "response_path" -> 1)).responsePath mustBe None
    }

    "not read the camel case keys" in {
      val json = Json.obj(
        "url"            -> "https://some.graphql/endpoint",
        "responsePath"   -> "$.data",
        "responseFilter" -> ".data"
      )
      readOk(format, json) mustBe pluginDefault
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("url" -> "https://some.graphql/endpoint", "foo" -> "bar")) mustBe pluginDefault
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of GraphQLQuery" in {
      defaultConfigReadsBack(new GraphQLQuery(), format) mustBe pluginDefault
    }
  }

  "GraphQLBackendConfig" should {

    val format = GraphQLBackendConfig.format

    val schema = "type Query { hello: String }"

    val full = GraphQLBackendConfig(
      schema = schema,
      permissions = Seq("$.apikey.metadata.admin", "$.user.email"),
      initialData = Some(Json.obj("users" -> Json.arr(Json.obj("name" -> "foo")))),
      maxDepth = 3
    )

    "have no permission, no initial data and a max depth of 15 by default" in {
      GraphQLBackendConfig(schema) mustBe GraphQLBackendConfig(schema, Seq.empty, None, 15)
    }

    "write a config with the defaults" in {
      format.writes(GraphQLBackendConfig(schema)) mustBe Json.obj(
        "schema"       -> schema,
        "permissions"  -> Json.arr(),
        "initial_data" -> JsNull,
        "max_depth"    -> 15
      )
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "schema"       -> schema,
        "permissions"  -> Json.arr("$.apikey.metadata.admin", "$.user.email"),
        "initial_data" -> Json.obj("users" -> Json.arr(Json.obj("name" -> "foo"))),
        "max_depth"    -> 3
      )
    }

    "round trip a config with the defaults" in {
      roundTrip(format, GraphQLBackendConfig(schema))
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip an empty schema" in {
      roundTrip(format, GraphQLBackendConfig("")).schema mustBe ""
    }

    "round trip an empty initial data object" in {
      roundTrip(format, GraphQLBackendConfig(schema, initialData = Some(Json.obj()))).initialData mustBe Some(Json.obj())
    }

    "round trip permissions alone" in {
      roundTrip(format, GraphQLBackendConfig(schema, permissions = Seq("$.user.admin")))
    }

    "round trip a zero max depth" in {
      roundTrip(format, GraphQLBackendConfig(schema, maxDepth = 0)).maxDepth mustBe 0
    }

    "not read an empty object, the schema being mandatory" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a json string" in {
      readError(format, JsString(schema))
    }

    "not read a json array" in {
      readError(format, Json.arr(schema))
    }

    "not read a schema that is not a string" in {
      readError(format, Json.obj("schema" -> Json.obj("type" -> "Query")))
    }

    "read a schema alone with the default for everything else" in {
      readOk(format, Json.obj("schema" -> schema)) mustBe GraphQLBackendConfig(schema)
    }

    "only read an initial data that is an object" in {
      readOk(format, Json.obj("schema" -> schema, "initial_data" -> Json.arr(1, 2))).initialData mustBe None
      readOk(format, Json.obj("schema" -> schema, "initial_data" -> "{}")).initialData mustBe None
    }

    "ignore a max depth written as a string" in {
      readOk(format, Json.obj("schema" -> schema, "max_depth" -> "3")).maxDepth mustBe 15
    }

    "ignore permissions holding something else than strings" in {
      readOk(format, Json.obj("schema" -> schema, "permissions" -> Json.arr("$.a", 1))).permissions mustBe empty
    }

    "not read the camel case keys" in {
      val json = Json.obj(
        "schema"      -> schema,
        "initialData" -> Json.obj("a" -> 1),
        "maxDepth"    -> 3
      )
      readOk(format, json) mustBe GraphQLBackendConfig(schema)
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("schema" -> schema, "foo" -> "bar")) mustBe GraphQLBackendConfig(schema)
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of GraphQLBackend" in {
      val back = defaultConfigReadsBack(new GraphQLBackend(), format)
      back.schema must include("type Query")
      back.permissions mustBe empty
      back.initialData mustBe None
      back.maxDepth mustBe 15
    }
  }

  "GraphQLProxyConfig" should {

    val format = GraphQLProxyConfig.format

    val full = GraphQLProxyConfig(
      endpoint = "https://graphql.oto.tools/api",
      schema = Some("type Query { hello: String }"),
      maxDepth = 10,
      maxComplexity = 1234.5,
      path = "/api/graphql",
      headers = Map("Authorization" -> "Bearer token")
    )

    "proxy the countries api with generous limits by default" in {
      GraphQLProxyConfig.default mustBe GraphQLProxyConfig(
        "https://countries.trevorblades.com/graphql",
        None,
        50,
        50000.0,
        "/graphql",
        Map.empty
      )
    }

    "write the default config" in {
      format.writes(GraphQLProxyConfig.default) mustBe Json.obj(
        "endpoint"       -> "https://countries.trevorblades.com/graphql",
        "schema"         -> JsNull,
        "max_depth"      -> 50,
        "max_complexity" -> 50000,
        "path"           -> "/graphql",
        "headers"        -> Json.obj()
      )
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "endpoint"       -> "https://graphql.oto.tools/api",
        "schema"         -> "type Query { hello: String }",
        "max_depth"      -> 10,
        "max_complexity" -> 1234.5,
        "path"           -> "/api/graphql",
        "headers"        -> Json.obj("Authorization" -> "Bearer token")
      )
    }

    "round trip the default config" in {
      roundTrip(format, GraphQLProxyConfig.default)
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a schema alone" in {
      roundTrip(format, GraphQLProxyConfig.default.copy(schema = Some("type Query { a: Int }")))
    }

    "round trip a fractional max complexity" in {
      roundTrip(format, GraphQLProxyConfig.default.copy(maxComplexity = 0.5)).maxComplexity mustBe 0.5
    }

    "round trip the headers alone" in {
      roundTrip(format, GraphQLProxyConfig.default.copy(headers = Map("X-Foo" -> "bar", "X-Bar" -> "foo")))
    }

    "not read an empty object, the endpoint being mandatory" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a json string" in {
      readError(format, JsString("https://countries.trevorblades.com/graphql"))
    }

    "not read a json array" in {
      readError(format, Json.arr("https://countries.trevorblades.com/graphql"))
    }

    "not read an endpoint that is not a string" in {
      readError(format, Json.obj("endpoint" -> 42))
    }

    "not read the url key of a query config as the endpoint" in {
      readError(format, Json.obj("url" -> "https://countries.trevorblades.com/graphql"))
    }

    "read an endpoint alone with the default for everything else" in {
      readOk(format, Json.obj("endpoint" -> "https://graphql.oto.tools")) mustBe GraphQLProxyConfig.default.copy(
        endpoint = "https://graphql.oto.tools"
      )
    }

    "read a whole max complexity as a double" in {
      readOk(format, Json.obj("endpoint" -> "https://graphql.oto.tools", "max_complexity" -> 100)).maxComplexity mustBe 100.0
    }

    "read a path alone" in {
      readOk(format, Json.obj("endpoint" -> "https://graphql.oto.tools", "path" -> "/gql")).path mustBe "/gql"
    }

    "ignore a max complexity written as a string" in {
      readOk(
        format,
        Json.obj("endpoint" -> "https://graphql.oto.tools", "max_complexity" -> "100")
      ).maxComplexity mustBe 50000.0
    }

    "ignore a max depth that is not a whole number" in {
      readOk(format, Json.obj("endpoint" -> "https://graphql.oto.tools", "max_depth" -> 10.5)).maxDepth mustBe 50
    }

    "ignore a schema that is not a string" in {
      readOk(format, Json.obj("endpoint" -> "https://graphql.oto.tools", "schema" -> Json.obj())).schema mustBe None
    }

    "ignore the headers as soon as a value is not a string" in {
      readOk(
        format,
        Json.obj("endpoint" -> "https://graphql.oto.tools", "headers" -> Json.obj("X-Foo" -> true))
      ).headers mustBe empty
    }

    "not read the camel case keys" in {
      val json = Json.obj(
        "endpoint"      -> "https://graphql.oto.tools",
        "maxDepth"      -> 1,
        "maxComplexity" -> 2
      )
      readOk(format, json) mustBe GraphQLProxyConfig.default.copy(endpoint = "https://graphql.oto.tools")
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("endpoint" -> "https://graphql.oto.tools", "foo" -> "bar")) mustBe GraphQLProxyConfig.default
        .copy(endpoint = "https://graphql.oto.tools")
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of GraphQLProxy" in {
      defaultConfigReadsBack(new GraphQLProxy(), format) mustBe GraphQLProxyConfig.default
    }
  }
}
