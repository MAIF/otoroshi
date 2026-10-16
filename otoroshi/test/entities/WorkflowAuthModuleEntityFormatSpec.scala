package entities

import otoroshi.auth.*
import otoroshi.models.*
import otoroshi.next.models.NgTlsConfig
import otoroshi.utils.JsonPathValidator
import play.api.libs.json.*

import scala.concurrent.duration.*

class WorkflowAuthModuleEntityFormatSpec extends EntityFormatSpec {

  "WorkflowAuthModuleConfig" should {

    val format = WorkflowAuthModuleConfig.format

    val minimal = WorkflowAuthModuleConfig(
      location = EntityLocation(),
      id = "auth_mod_workflow",
      name = "Workflow",
      description = "a workflow module",
      tags = Seq.empty,
      metadata = Map.empty,
      sessionMaxAge = 86400,
      clientSideSessionEnabled = true,
      sessionCookieValues = SessionCookieValues(),
      workflowRef = None
    )

    val full = WorkflowAuthModuleConfig(
      location = customLocation,
      id = "auth_mod_workflow",
      name = "Workflow",
      description = "a workflow module",
      tags = Seq("workflow"),
      metadata = Map("owner" -> "dev"),
      sessionMaxAge = 300,
      clientSideSessionEnabled = false,
      sessionCookieValues = SessionCookieValues(httpOnly = true, secure = false),
      userValidators = Seq(JsonPathValidator("$.groups", JsString("Contains(admins)"))),
      remoteValidators = Seq(RemoteUserValidatorSettings("https://validator.oto.tools", Map.empty, 1.second, NgTlsConfig())),
      workflowRef = Some("workflow-1"),
      allowedUsers = Seq("john@oto.tools"),
      deniedUsers = Seq("Contains(external)")
    )

    "write a minimal module" in {
      format.writes(minimal) mustBe Json.obj(
        "_loc"                     -> defaultLocationJson,
        "type"                     -> "workflow",
        "id"                       -> "auth_mod_workflow",
        "name"                     -> "Workflow",
        "desc"                     -> "a workflow module",
        "tags"                     -> Json.arr(),
        "metadata"                 -> Json.obj(),
        "sessionMaxAge"            -> 86400,
        "clientSideSessionEnabled" -> true,
        "sessionCookieValues"      -> Json.obj("httpOnly" -> true, "secure" -> true),
        "userValidators"           -> Json.arr(),
        "remoteValidators"         -> Json.arr(),
        "allowedUsers"             -> Json.arr(),
        "deniedUsers"              -> Json.arr(),
        "workflowRef"              -> JsNull
      )
    }

    "write its workflow ref" in {
      (written(format, full) \ "workflowRef").as[String] mustBe "workflow-1"
    }

    "round trip a minimal module" in {
      roundTrip(format, minimal)
    }

    "round trip a module where every field is set" in {
      roundTrip(format, full)
    }

    "round trip its custom location" in {
      roundTrip(format, minimal.copy(location = customLocation)).location mustBe customLocation
    }

    "read the default location without _loc" in {
      readOk(format, written(format, full) - "_loc").location mustBe EntityLocation()
    }

    "read the defaults of a module with only an id and a name" in {
      readOk(format, Json.obj("id" -> "auth_mod_workflow", "name" -> "Workflow")) mustBe minimal.copy(description = "--", clientSideSessionEnabled = false)
    }

    "not read a module without id" in {
      readError(format, Json.obj("name" -> "Workflow"))
    }

    "not read a module without name" in {
      readError(format, Json.obj("id" -> "auth_mod_workflow"))
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "prefer the description key over the desc key" in {
      readOk(format, Json.obj("id" -> "a", "name" -> "b", "description" -> "d1", "desc" -> "d2")).description mustBe "d1"
    }

    "read a blank workflow ref as missing" in {
      readOk(format, Json.obj("id" -> "a", "name" -> "b", "workflowRef" -> "")).workflowRef mustBe None
    }

    "not read a wasm plugin ref as a workflow ref" in {
      readOk(format, Json.obj("id" -> "a", "name" -> "b", "wasmRef" -> "wasm-1")).workflowRef mustBe None
    }

    "ignore metadata holding a non string value" in {
      readOk(format, Json.obj("id" -> "a", "name" -> "b", "metadata" -> Json.obj("a" -> 1))).metadata mustBe Map.empty
    }

    "drop the remote validators that cannot be read" in {
      val json = Json.obj("id" -> "a", "name" -> "b", "remoteValidators" -> Json.arr(Json.obj("url" -> "http://a"), Json.obj()))
      readOk(format, json).remoteValidators.map(_.url) mustBe Seq("http://a")
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
    }

    "expose its fields through the generic accessors" in {
      hasAccessors(full, "auth_mod_workflow", "Workflow", "a workflow module", Seq("workflow"), Map("owner" -> "dev"))
    }

    "describe its type" in {
      full.`type` mustBe "workflow"
      full.humanName mustBe "Workflow auth. module provider"
      full.desc mustBe "a workflow module"
      full.form mustBe None
    }

    "name its cookie after its id" in {
      full.cookieSuffix(null) mustBe "workflow-auth-auth_mod_workflow"
    }

    "change its location only with withLocation" in {
      minimal.withLocation(customLocation) mustBe minimal.copy(location = customLocation)
    }

    "have a default config without client side session nor workflow" in {
      val default = WorkflowAuthModuleConfig.defaultConfig
      default.id must startWith("auth_mod_")
      default.clientSideSessionEnabled mustBe false
      default.workflowRef mustBe None
      roundTrip(format, default)
    }

    "read a missing client side session as off, like a new module" in {
      readOk(format, Json.obj("id" -> "a", "name" -> "b")).clientSideSessionEnabled mustBe false
    }
  }
}
