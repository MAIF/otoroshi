package plugins

import functional.PluginsTestSpecBase
import otoroshi.models.*
import otoroshi.next.workflow.Workflow
import play.api.http.Status
import play.api.libs.json.{JsObject, Json}
import play.api.libs.ws.WSBodyWritables.writeableOf_String
import play.api.libs.ws.WSResponse
import play.api.libs.crypto.CookieSignerProvider
import play.api.mvc.{DefaultSessionCookieBaker, Session}

import scala.concurrent.duration.DurationInt

// the workflow test endpoint runs whatever workflow it is sent, system calls and vault secrets included. it must take
// a backoffice session (it used to run without any) and the rights to write the workflow it runs
class WorkflowTestRightsTests(parent: PluginsTestSpecBase) {
  import parent.*

  def user(id: String, tenant: String, team: String): BackOfficeUser = BackOfficeUser(
    randomId = id,
    name = id,
    email = s"$id@otoroshi.io",
    profile = Json.obj(),
    authConfigId = "basic",
    simpleLogin = true,
    tags = Seq.empty,
    metadata = Map.empty,
    rights = UserRights(Seq(UserRight(TenantAccess(tenant), Seq(TeamAccess(team))))),
    adminEntityValidators = Map()
  )

  val writer = user("workflow-test-writer", "default:rw", "default:rw")
  val reader = user("workflow-test-reader", "default:r", "default:r")
  Seq(writer, reader).foreach(_.save(5.minutes)(using env.otoroshiExecutionContext, env).futureValue)

  val otherTenantWorkflow = Workflow
    .template()
    .copy(id = "workflow-test-other-tenant", location = EntityLocation(TenantId("other-tenant"), Seq(TeamId.all)))
  createOtoroshiWorkflow(otherTenantWorkflow).futureValue

  def payload(workflowId: String, extra: JsObject = Json.obj()): JsObject = Json.obj(
    "input"       -> "{}",
    "workflow_id" -> workflowId,
    "workflow"    -> Json.obj("kind" -> "value", "value" -> "rights-probe"),
    "functions"   -> Json.obj()
  ) ++ extra

  val sessionCookieBaker = new DefaultSessionCookieBaker(
    env.httpConfiguration.session,
    env.httpConfiguration.secret,
    new CookieSignerProvider(env.httpConfiguration.secret).get
  )

  def runTest(user: Option[BackOfficeUser], body: JsObject, origin: Option[String] = None): WSResponse = {
    val session = user.map { u =>
      val cookie = sessionCookieBaker.encodeAsCookie(Session(Map("bousr" -> u.randomId)))
      "Cookie" -> s"${cookie.name}=${cookie.value}"
    }
    ws.url(s"http://127.0.0.1:$port/extensions/workflows/_test")
      .withFollowRedirects(false)
      .withHttpHeaders(
        (Seq("Host" -> env.backOfficeHost, "Content-Type" -> "application/json") ++ session ++ origin.map("Origin" -> _))*
      )
      .post(Json.stringify(body))
      .futureValue
  }

  def assertNotRun(label: String, response: WSResponse, status: Int): Unit = {
    withClue(s"$label: status=${response.status} body=${response.body} ") {
      response.status mustBe status
      response.body.contains("rights-probe") mustBe false
    }
  }

  assertNotRun("no session", runTest(None, payload("workflow-test-new")), Status.SEE_OTHER)
  assertNotRun("read only user", runTest(Some(reader), payload("workflow-test-new")), Status.FORBIDDEN)
  assertNotRun(
    "writer, new workflow in a tenant it cannot write",
    runTest(Some(writer), payload("workflow-test-new", Json.obj("_loc" -> EntityLocation(TenantId("other-tenant")).json))),
    Status.FORBIDDEN
  )
  assertNotRun(
    "writer, stored workflow of a tenant it cannot write",
    runTest(Some(writer), payload(otherTenantWorkflow.id)),
    Status.FORBIDDEN
  )
  assertNotRun(
    "writer, cross origin call",
    runTest(Some(writer), payload("workflow-test-new"), origin = Some("http://evil.oto.tools")),
    Status.EXPECTATION_FAILED
  )

  val allowed = runTest(Some(writer), payload("workflow-test-new"))
  withClue(s"writer, new workflow in its own tenant: status=${allowed.status} body=${allowed.body} ") {
    allowed.status mustBe Status.OK
    (allowed.json \ "returned").as[String] mustBe "rights-probe"
  }

  Seq(writer, reader).foreach(_.delete()(using env.otoroshiExecutionContext, env).futureValue)
  deleteOtoroshiWorkflow(otherTenantWorkflow).futureValue
}
