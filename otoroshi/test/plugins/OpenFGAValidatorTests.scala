package plugins

import com.dimafeng.testcontainers.GenericContainer
import functional.PluginsTestSpec
import otoroshi.models.{ApiKey, RouteIdentifier}
import otoroshi.next.models._
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits._
import play.api.http.Status
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.duration._

class OpenFGAValidatorTests(parent: PluginsTestSpec) {

  import parent._

  def setupOpenFGA(): (GenericContainer, OpenFGAValidatorConfig) = {
    System.setProperty("api.version", "1.44")
    val openFgaContainer = GenericContainer(
      dockerImage = "openfga/openfga",
      exposedPorts = Seq(8080),
      env = Map.empty,
      command = Seq("run")
    )
    openFgaContainer.start()
    await(5.seconds)
    val openFgaHost      = openFgaContainer.host
    val openFgaPort      = openFgaContainer.mappedPort(8080)
    val baseUrl          = s"http://${openFgaHost}:${openFgaPort}"
    val storeResp        = ws
      .url(s"${baseUrl}/stores")
      .post(
        Json.obj(
          "name" -> "test_store"
        )
      )
      .futureValue
      .json
    val storeId          = storeResp.select("id").asString
    val model            = Json.parse("""{
                             |    "schema_version": "1.1",
                             |    "type_definitions": [
                             |        {
                             |            "type": "user"
                             |        },
                             |        {
                             |            "type": "document",
                             |            "relations": {
                             |                "reader": {
                             |                    "this": {}
                             |                },
                             |                "writer": {
                             |                    "this": {}
                             |                },
                             |                "owner": {
                             |                    "this": {}
                             |                }
                             |            },
                             |            "metadata": {
                             |                "relations": {
                             |                    "reader": {
                             |                        "directly_related_user_types": [
                             |                            {
                             |                                "type": "user"
                             |                            }
                             |                        ]
                             |                    },
                             |                    "writer": {
                             |                        "directly_related_user_types": [
                             |                            {
                             |                                "type": "user"
                             |                            }
                             |                        ]
                             |                    },
                             |                    "owner": {
                             |                        "directly_related_user_types": [
                             |                            {
                             |                                "type": "user"
                             |                            }
                             |                        ]
                             |                    }
                             |                }
                             |            }
                             |        }
                             |    ]
                             |}""".stripMargin)
    val modelResp        = ws.url(s"${baseUrl}/stores/${storeId}/authorization-models").post(model).futureValue.json
    val modelId          = modelResp.select("authorization_model_id").asString
    val tuple1           = Json.parse(s"""{
        |  "writes": {
        |    "tuple_keys": [
        |      {
        |        "user": "user:mathieu.ancelin@cloud-apim.com",
        |        "relation": "reader",
        |        "object": "document:foo.doc"
        |      }
        |    ]
        |  },
        |  "authorization_model_id": "${modelId}"
        |}""".stripMargin)
    val tuple2           = Json.parse(s"""{
         |  "writes": {
         |    "tuple_keys": [
         |      {
         |        "user": "user:mathieu.ancelin@cloud-apim.com",
         |        "relation": "writer",
         |        "object": "document:bar.doc"
         |      }
         |    ]
         |  },
         |  "authorization_model_id": "${modelId}"
         |}""".stripMargin)

    val respTuple1 = ws.url(s"${baseUrl}/stores/${storeId}/write").post(tuple1).futureValue.json
    val respTuple2 = ws.url(s"${baseUrl}/stores/${storeId}/write").post(tuple2).futureValue.json

    val config = OpenFGAValidatorConfig(
      url = baseUrl,
      storeId = storeId,
      modelId = modelId,
      tupleKey = Json.obj(),
      contextualTuples = Json.arr()
    )
    (openFgaContainer, config)
  }

  def run(): Unit = {
    val (container, openFGAValidatorConfig) = setupOpenFGA()

    val readConfig = openFGAValidatorConfig.copy(tupleKey =
      Json.obj(
        "user"     -> "user:${apikey.metadata.user}",
        "relation" -> "reader",
        "object"   -> "document:${req.pathparams.doc}"
      )
    )

    val writeConfig = openFGAValidatorConfig.copy(tupleKey =
      Json.obj(
        "user"     -> "user:${apikey.metadata.user}",
        "relation" -> "writer",
        "object"   -> "document:${req.pathparams.doc}"
      )
    )

    val route1 = createRouteWithExternalTarget(
      domain = "openfgaread.oto.tools/docs/:doc".some,
      plugins = Seq(
        NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[ApikeyCalls],
          config = NgPluginInstanceConfig(
            NgApikeyCallsConfig().json.as[JsObject]
          )
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OpenFGAValidator],
          config = NgPluginInstanceConfig(
            readConfig.json.as[JsObject]
          )
        )
      )
    ).futureValue

    val route2 = createRouteWithExternalTarget(
      domain = "openfgawrite.oto.tools/docs/:doc".some,
      plugins = Seq(
        NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[ApikeyCalls],
          config = NgPluginInstanceConfig(
            NgApikeyCallsConfig().json.as[JsObject]
          )
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OpenFGAValidator],
          config = NgPluginInstanceConfig(
            writeConfig.json.as[JsObject]
          )
        )
      )
    ).futureValue

    val apikeyJson = createOtoroshiApiKey(
      ApiKey(
        clientId = IdGenerator.token(16),
        clientSecret = IdGenerator.token(64),
        clientName = "test",
        authorizedEntities = Seq(
          RouteIdentifier(route1.id),
          RouteIdentifier(route2.id)
        ),
        metadata = Map(
          "user" -> "mathieu.ancelin@cloud-apim.com"
        )
      )
    ).futureValue
    val apikey     = ApiKey._fmt.reads(apikeyJson._1).get

    val respAllowed = ws
      .url(s"http://127.0.0.1:$port/docs/foo.doc")
      .withHttpHeaders(
        "Host"          -> route1.frontend.domains.head.domain,
        "Authorization" -> s"Bearer ${apikey.toBearer()}"
      )
      .get()
      .futureValue

    respAllowed.status mustBe Status.OK

    val respNotAllowed = ws
      .url(s"http://127.0.0.1:$port/docs/food.doc")
      .withHttpHeaders(
        "Host"          -> route1.frontend.domains.head.domain,
        "Authorization" -> s"Bearer ${apikey.toBearer()}"
      )
      .get()
      .futureValue

    respNotAllowed.status mustBe Status.UNAUTHORIZED

    val respNotAllowed2 = ws
      .url(s"http://127.0.0.1:$port/docs/foo.doc")
      .withHttpHeaders(
        "Host"          -> route2.frontend.domains.head.domain,
        "Authorization" -> s"Bearer ${apikey.toBearer()}"
      )
      .get()
      .futureValue

    respNotAllowed2.status mustBe Status.UNAUTHORIZED

    val respAllowed2 = ws
      .url(s"http://127.0.0.1:$port/docs/bar.doc")
      .withHttpHeaders(
        "Host"          -> route2.frontend.domains.head.domain,
        "Authorization" -> s"Bearer ${apikey.toBearer()}"
      )
      .get()
      .futureValue

    respAllowed2.status mustBe Status.OK

    deleteOtoroshiRoute(route1).futureValue
    deleteOtoroshiRoute(route2).futureValue
    deleteOtoroshiApiKey(apikey).futureValue
    container.stop()
  }
}
