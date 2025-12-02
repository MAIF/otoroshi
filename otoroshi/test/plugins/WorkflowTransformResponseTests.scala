package plugins

import functional.PluginsTestSpec
import otoroshi.models.EntityLocation
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.workflow._
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.BetterJsValueReader
import play.api.http.Status
import play.api.libs.json.{JsObject, Json}

class WorkflowTransformResponseTests(parent: PluginsTestSpec) {

  import parent._

  val workflow = Workflow(
    location = EntityLocation.default,
    id = IdGenerator.uuid,
    name = "workflow",
    description = "workflow",
    tags = Seq.empty,
    metadata = Map.empty,
    config = Json.obj(
      "kind"     -> "workflow",
      "steps"    -> Json.arr(),
      "returned" -> Json.obj(
        "status"    -> 404,
        "headers"   -> Json.obj("foo" -> "bar"),
        "body_json" -> Json.obj("foo" -> "bar")
      ),
      "id"       -> "start"
    ),
    job = WorkflowJobConfig.default,
    functions = Map.empty,
    testPayload = Json.obj(),
    orphans = Orphans(),
    notes = Seq.empty
  )

  createOtoroshiWorkflow(workflow).futureValue

  val route = createRequestOtoroshiIORoute(
    Seq(
      NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[WorkflowResponseTransformer],
        config = NgPluginInstanceConfig(
          WorkflowBackendConfig(
            json = Json.obj("ref" -> workflow.id)
          ).json.as[JsObject]
        )
      )
    )
  )

  val resp = ws
    .url(s"http://127.0.0.1:$port/")
    .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
    .get()
    .futureValue

  resp.status mustBe Status.NOT_FOUND
  getOutHeader(resp, "foo") mustBe Some("bar")
  Json.parse(resp.body) mustBe Json.obj("foo" -> "bar")

  deleteOtoroshiWorkflow(workflow).futureValue
  deleteOtoroshiRoute(route).futureValue
}
