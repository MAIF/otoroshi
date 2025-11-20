package plugins

import akka.http.scaladsl.model.headers.RawHeader
import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.BetterJsValueReader
import play.api.http.Status
import play.api.libs.json._

class GraphQLBackendTests(parent: PluginsTestSpec) {

  import parent._

  private def getRoute(config: GraphQLBackendConfig) = {
    val id = IdGenerator.uuid
    createRequestOtoroshiIORoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[GraphQLBackend],
          config = NgPluginInstanceConfig(config.json.as[JsObject])
        )
      ),
      domain = s"$id.oto.tools",
      id
    )
  }

  def jsonDirective() = {
    val route = getRoute(
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
      )
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

}
