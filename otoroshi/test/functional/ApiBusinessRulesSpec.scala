package functional

import next.models._
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import otoroshi.models.EntityLocation
import otoroshi.next.models.{NgFrontend, NgPlugins}

class ApiBusinessRulesSpec extends AnyWordSpec with Matchers {

  private def baseApi(state: ApiState, name: String = "test-api"): Api = Api(
    location = EntityLocation.default,
    id = "api_test",
    name = name,
    description = "",
    domain = "api.oto.tools",
    contextPath = "/v1",
    version = "0.0.1",
    debugFlow = false,
    capture = false,
    exportReporting = false,
    groups = Seq.empty,
    state = state,
    blueprint = ApiBlueprint.REST,
    testing = ApiTesting()
  )

  "Api.isValidStateTransition" should {

    // staging
    "allow staging -> staging (no-op)" in {
      Api.isValidStateTransition(ApiStaging, ApiStaging, viaDeploy = false) mustBe true
    }
    "allow staging -> published only via deploy" in {
      Api.isValidStateTransition(ApiStaging, ApiPublished, viaDeploy = true) mustBe true
      Api.isValidStateTransition(ApiStaging, ApiPublished, viaDeploy = false) mustBe false
    }
    "deny staging -> deprecated" in {
      Api.isValidStateTransition(ApiStaging, ApiDeprecated, viaDeploy = false) mustBe false
      Api.isValidStateTransition(ApiStaging, ApiDeprecated, viaDeploy = true) mustBe false
    }
    "deny staging -> removed" in {
      Api.isValidStateTransition(ApiStaging, ApiRemoved, viaDeploy = false) mustBe false
      Api.isValidStateTransition(ApiStaging, ApiRemoved, viaDeploy = true) mustBe false
    }

    // published
    "allow published -> published (no-op)" in {
      Api.isValidStateTransition(ApiPublished, ApiPublished, viaDeploy = false) mustBe true
    }
    "allow published -> deprecated" in {
      Api.isValidStateTransition(ApiPublished, ApiDeprecated, viaDeploy = false) mustBe true
    }
    "allow published -> removed" in {
      Api.isValidStateTransition(ApiPublished, ApiRemoved, viaDeploy = false) mustBe true
    }
    "deny published -> staging" in {
      Api.isValidStateTransition(ApiPublished, ApiStaging, viaDeploy = false) mustBe false
      Api.isValidStateTransition(ApiPublished, ApiStaging, viaDeploy = true) mustBe false
    }

    // deprecated
    "allow deprecated -> published (republish, direct)" in {
      Api.isValidStateTransition(ApiDeprecated, ApiPublished, viaDeploy = false) mustBe true
    }
    "allow deprecated -> deprecated (no-op)" in {
      Api.isValidStateTransition(ApiDeprecated, ApiDeprecated, viaDeploy = false) mustBe true
    }
    "allow deprecated -> removed" in {
      Api.isValidStateTransition(ApiDeprecated, ApiRemoved, viaDeploy = false) mustBe true
    }
    "deny deprecated -> staging" in {
      Api.isValidStateTransition(ApiDeprecated, ApiStaging, viaDeploy = false) mustBe false
    }

    // removed
    "allow removed -> staging (reopen)" in {
      Api.isValidStateTransition(ApiRemoved, ApiStaging, viaDeploy = false) mustBe true
    }
    "allow removed -> removed (no-op)" in {
      Api.isValidStateTransition(ApiRemoved, ApiRemoved, viaDeploy = false) mustBe true
    }
    "deny removed -> published" in {
      Api.isValidStateTransition(ApiRemoved, ApiPublished, viaDeploy = false) mustBe false
      Api.isValidStateTransition(ApiRemoved, ApiPublished, viaDeploy = true) mustBe false
    }
    "deny removed -> deprecated" in {
      Api.isValidStateTransition(ApiRemoved, ApiDeprecated, viaDeploy = false) mustBe false
    }
  }

  "Api.transitionError" should {
    "produce structured error JSON" in {
      val err = Api.transitionError(ApiPublished, ApiStaging)
      (err \ "error").as[String] mustBe "invalid_state_transition"
      (err \ "from").as[String] mustBe "published"
      (err \ "to").as[String] mustBe "staging"
      (err \ "error_description").asOpt[String].isDefined mustBe true
    }
  }

  "Api.diffProtectedFields" should {

    "report no diff when nothing changed" in {
      val a = baseApi(ApiPublished)
      Api.diffProtectedFields(a, a) mustBe empty
    }

    "ignore changes on unprotected fields (name, description, domain, etc.)" in {
      val a = baseApi(ApiPublished)
      val b = a.copy(
        name = "renamed",
        description = "new desc",
        domain = "other.oto.tools",
        contextPath = "/v2",
        metadata = Map("k" -> "v"),
        tags = Seq("t"),
        enabled = false,
        version = "1.0.0"
      )
      Api.diffProtectedFields(a, b) mustBe empty
    }

    "flag a routes diff" in {
      val a = baseApi(ApiPublished)
      val r = ApiRoute(
        id = "r1",
        frontend = NgFrontend.empty,
        flowRef = "default_plugin_chain",
        backend = "b1"
      )
      val b = a.copy(routes = Seq(r))
      Api.diffProtectedFields(a, b) must contain("routes")
    }

    "flag a testing diff" in {
      val a = baseApi(ApiPublished).copy(testing = ApiTesting(headerValue = "v1"))
      val b = a.copy(testing = ApiTesting(headerValue = "v2"))
      Api.diffProtectedFields(a, b) must contain("testing")
    }

    "flag multiple protected fields at once" in {
      val a = baseApi(ApiPublished).copy(testing = ApiTesting(headerValue = "v1"))
      val b = a.copy(
        testing = ApiTesting(headerValue = "v2"),
        flows = Seq(ApiFlows("f1", "f1", NgPlugins.empty))
      )
      val d = Api.diffProtectedFields(a, b)
      d must contain("testing")
      d must contain("flows")
    }
  }
}
