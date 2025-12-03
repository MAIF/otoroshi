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

class WorkflowTransformRequestTests(parent: PluginsTestSpec) {

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
        "method"    -> "POST",
        "url"       -> "https://request.otoroshi.io",
        "body_json" -> Json.obj("Foo" -> "bar")
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

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[WorkflowRequestTransformer],
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

  resp.status mustBe Status.OK
  Json.parse(resp.body).selectAsString("method") mustBe "POST"

  deleteOtoroshiWorkflow(workflow).futureValue
  deleteOtoroshiRoute(route).futureValue
}
