package plugins.configs

import otoroshi.next.workflow.*
import play.api.libs.json.*

class WorkflowPluginConfigsSpec extends PluginConfigFormatSpec {

  "WorkflowBackendConfig" should {

    val format = WorkflowBackendConfig.format
    val full   = WorkflowBackendConfig(Json.obj("ref" -> "workflow_1", "async" -> true))

    "be an empty object by default" in {
      WorkflowBackendConfig().json mustBe Json.obj()
    }

    "write the default config as an empty object" in {
      format.writes(WorkflowBackendConfig()) mustBe Json.obj()
    }

    "write the json it holds as is" in {
      format.writes(full) mustBe Json.obj("ref" -> "workflow_1", "async" -> true)
    }

    "round trip the default config" in {
      roundTrip(format, WorkflowBackendConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a config holding a ref alone" in {
      roundTrip(format, WorkflowBackendConfig(Json.obj("ref" -> "workflow_2"))).ref mustBe "workflow_2"
    }

    "keep unknown keys through a round trip" in {
      val config = WorkflowBackendConfig(Json.obj("ref" -> "wf", "foo" -> Json.obj("bar" -> Json.arr(1, 2))))
      roundTrip(format, config).json mustBe config.json
    }

    "keep camel case or legacy keys as they are" in {
      val config = WorkflowBackendConfig(Json.obj("workflowRef" -> "wf"))
      format.writes(readOk(format, config.json)) mustBe Json.obj("workflowRef" -> "wf")
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe WorkflowBackendConfig()
    }

    "read a json null as a config holding null" in {
      readOk(format, JsNull) mustBe WorkflowBackendConfig(JsNull)
    }

    "read a json string as a config holding that string" in {
      readOk(format, JsString("workflow_1")).json mustBe JsString("workflow_1")
    }

    "read a json array as a config holding that array" in {
      readOk(format, Json.arr("workflow_1")).json mustBe Json.arr("workflow_1")
    }

    "read the ref of the workflow" in {
      full.ref mustBe "workflow_1"
    }

    "fail to read a ref when there is none" in {
      a[JsResultException] must be thrownBy WorkflowBackendConfig().ref
    }

    "fail to read a ref that is not a string" in {
      a[JsResultException] must be thrownBy WorkflowBackendConfig(Json.obj("ref" -> 42)).ref
    }

    "fail to read a ref out of a config that is not an object" in {
      a[JsResultException] must be thrownBy WorkflowBackendConfig(JsString("workflow_1")).ref
    }

    "be synchronous by default" in {
      WorkflowBackendConfig().async mustBe false
      WorkflowBackendConfig(Json.obj("ref" -> "wf")).async mustBe false
    }

    "read the async flag" in {
      full.async mustBe true
      WorkflowBackendConfig(Json.obj("async" -> false)).async mustBe false
    }

    "ignore an async flag written as a string" in {
      WorkflowBackendConfig(Json.obj("async" -> "true")).async mustBe false
    }

    "be synchronous when the config is not an object" in {
      WorkflowBackendConfig(JsNull).async mustBe false
      WorkflowBackendConfig(Json.arr(true)).async mustBe false
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "list ref and async in its config flow and schema" in {
      WorkflowBackendConfig.configFlow mustBe Seq("ref", "async")
      WorkflowBackendConfig.configSchema.get.keys must contain allElementsOf WorkflowBackendConfig.configFlow
    }

    "list ref alone in its config flow without async" in {
      WorkflowBackendConfig.configFlowNoAsync mustBe Seq("ref")
      WorkflowBackendConfig.configSchema.get.keys must contain("ref")
    }

    "give the async flag to the backend plugins only" in {
      new WorkflowBackend().configFlow mustBe WorkflowBackendConfig.configFlow
      new WorkflowResumeBackend().configFlow mustBe WorkflowBackendConfig.configFlow
      new WorkflowRequestTransformer().configFlow mustBe WorkflowBackendConfig.configFlowNoAsync
      new WorkflowResponseTransformer().configFlow mustBe WorkflowBackendConfig.configFlowNoAsync
      new WorkflowAccessValidator().configFlow mustBe WorkflowBackendConfig.configFlowNoAsync
    }

    "be the default config of WorkflowBackend" in {
      defaultConfigReadsBack(new WorkflowBackend(), format) mustBe WorkflowBackendConfig()
    }

    "be the default config of WorkflowRequestTransformer" in {
      defaultConfigReadsBack(new WorkflowRequestTransformer(), format) mustBe WorkflowBackendConfig()
    }

    "be the default config of WorkflowResponseTransformer" in {
      defaultConfigReadsBack(new WorkflowResponseTransformer(), format) mustBe WorkflowBackendConfig()
    }

    "be the default config of WorkflowAccessValidator" in {
      defaultConfigReadsBack(new WorkflowAccessValidator(), format) mustBe WorkflowBackendConfig()
    }

    "be the default config of WorkflowResumeBackend" in {
      defaultConfigReadsBack(new WorkflowResumeBackend(), format) mustBe WorkflowBackendConfig()
    }
  }
}
