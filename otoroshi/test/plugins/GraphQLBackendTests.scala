package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.{BetterJsValue, BetterJsValueReader}
import play.api.http.Status
import play.api.libs.json._

class GraphQLBackendTests(parent: PluginsTestSpec) {

  import parent._

  def jsonDirective() = {
    val id    = IdGenerator.uuid
    val route = createRequestOtoroshiIORoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[GraphQLBackend],
          config = NgPluginInstanceConfig(
            GraphQLBackendConfig(
              schema = """
                  |type Query {
                  |  users: [User] @json(data: "[{\"firstname\":\"Foo\",\"name\":\"Bar\"},{\"firstname\":\"Bar\",\"name\":\"Foo\"}]")
                  |}
                  |
                  |type User {
                  |  name: String!
                  |  firstname: String!
                  |}
                  |""".stripMargin
            ).json.as[JsObject]
          )
        )
      ),
      domain = s"$id.oto.tools",
      id
    )

    {
      val resp = ws
        .url(s"http://127.0.0.1:$port/")
        .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
        .post(Json.obj())
        .futureValue

      resp.status mustBe Status.BAD_REQUEST
      Json.parse(resp.body).selectAsString("error") mustBe "query field missing"
    }

    {
      val resp = ws
        .url(s"http://127.0.0.1:$port/")
        .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
        .post(
          Json.obj(
            "query" ->
            """query {
                | users {
                |    name
                |    firstname
                | }
                |}""".stripMargin
          )
        )
        .futureValue

      resp.status mustBe Status.OK
      Json.parse(resp.body).selectAsOptObject("data").isDefined mustBe true
      Json.parse(resp.body).selectAsObject("data").selectAsArray("users").value.length mustBe 2
    }

    deleteOtoroshiRoute(route).futureValue
  }

  def mockDirective() = {
    val id    = IdGenerator.uuid
    val route = createRequestOtoroshiIORoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[GraphQLBackend],
          config = NgPluginInstanceConfig(
            GraphQLBackendConfig(
              schema = """
                  |type Query {
                  |  users: [User] @mock(url: "/users", headers: "")
                  |  user(id: String): User @mock(url: "/users/${params.id}")
                  |}
                  |
                  |type User {
                  |  id: String!
                  |  firstname: String!
                  |}
                  |""".stripMargin
            ).json.as[JsObject]
          )
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[MockResponses],
          config = NgPluginInstanceConfig(
            MockResponsesConfig(
              responses = Seq(
                MockResponse(
                  path = "/users/:id",
                  method = "GET",
                  status = 200,
                  headers = Map.empty,
                  body = Json
                    .obj(
                      "firstname" -> "Vivian",
                      "id"        -> "${req.pathparams.id}"
                    )
                    .stringify
                ),
                MockResponse(
                  headers = Map.empty,
                  path = "/users",
                  method = "GET",
                  body = Json
                    .arr(
                      Json.obj("firstname" -> "foo", "id" -> 0),
                      Json.obj("firstname" -> "baz", "id" -> 1)
                    )
                    .stringify
                )
              )
            ).json.as[JsObject]
          )
        )
      ),
      domain = s"$id.oto.tools",
      id
    )

    {
      val resp = ws
        .url(s"http://127.0.0.1:$port/users")
        .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
        .post(
          Json.obj(
            "query" ->
            """query {
                | users {
                |    id
                |    firstname
                | }
                |}""".stripMargin
          )
        )
        .futureValue

      resp.status mustBe Status.OK
      Json.parse(resp.body).selectAsOptObject("data").isDefined mustBe true
      Json.parse(resp.body).selectAsObject("data").selectAsArray("users").value.length mustBe 2
      Json.stringify(Json.parse(resp.body).selectAsObject("data").selectAsArray("users")).contains("foo") mustBe true
      Json.stringify(Json.parse(resp.body).selectAsObject("data").selectAsArray("users")).contains("baz") mustBe true
    }

    {
      val resp = ws
        .url(s"http://127.0.0.1:$port/")
        .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
        .post(
          Json.obj(
            "query" ->
            """query {
                | user(id: "2") {
                |    id
                |    firstname
                | }
                |}""".stripMargin
          )
        )
        .futureValue

      resp.status mustBe Status.OK
      Json.parse(resp.body).selectAsObject("data").selectAsOptObject("user").isDefined mustBe true
    }

    deleteOtoroshiRoute(route).futureValue
  }

  def permissions() = {
    val id    = IdGenerator.uuid
    val route = createRequestOtoroshiIORoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[GraphQLBackend],
          config = NgPluginInstanceConfig(
            GraphQLBackendConfig(
              permissions = Seq("$.raw_request.headers.role"),
              schema = """
                  |type Query {
                  |  users: [User] @json(data: "[{\"firstname\":\"Foo\",\"name\":\"Bar\"},{\"firstname\":\"Bar\",\"name\":\"Foo\"}]") @permission(value: "ONLY_THIS_ROLE")
                  |}
                  |
                  |type User {
                  |  name: String!
                  |  firstname: String!
                  |}
                  |""".stripMargin
            ).json.as[JsObject]
          )
        )
      ),
      domain = s"$id.oto.tools",
      id
    )

    {
      val resp = ws
        .url(s"http://127.0.0.1:$port/users")
        .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
        .post(
          Json.obj(
            "query" ->
            """query {
                | users {
                |    name
                |    firstname
                | }
                |}""".stripMargin
          )
        )
        .futureValue

      resp.status mustBe Status.OK
      resp.body.contains("You're not authorized") mustBe true
    }

    {
      val resp = ws
        .url(s"http://127.0.0.1:$port/users")
        .withHttpHeaders("Host" -> route.frontend.domains.head.domain, "role" -> "ONLY_THIS_ROLE")
        .post(
          Json.obj(
            "query" ->
            """query {
                | users {
                |    name
                |    firstname
                | }
                |}""".stripMargin
          )
        )
        .futureValue

      resp.status mustBe Status.OK
      resp.body.contains("You're not authorized") mustBe false
    }

    deleteOtoroshiRoute(route).futureValue
  }
}
