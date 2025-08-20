package otoroshi.next.workflow

import com.auth0.jwt.JWT
import org.joda.time.DateTime
import otoroshi.api.OtoroshiEnvHolder
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

case class PausedWorkflowSession(
    id: String,
    workflowRef: String,
    workflow: JsObject,
    functions: Map[String, JsObject],
    wfr: WorkflowRun,
    from: Seq[Int],
    createdAt: DateTime,
    validUntil: Option[DateTime]) {

  lazy val token = JWT.create()
    .withClaim("wi", workflowRef)
    .withClaim("i", id)
    .withClaim("k", "resume-token")
    .sign(OtoroshiEnvHolder.get().sha512Alg)

  def json: JsValue = PausedWorkflowSession.format.writes(this)

  def resume(data: JsObject, attrs: TypedMap, env: Env): Future[WorkflowResult] = {
    val ext = env.adminExtensions.extension[WorkflowAdminExtension].get
    val wfrHydrated = wfr.hydrate(workflowRef, workflow, attrs, env)
    wfrHydrated.memory.set("resume_data", data)
    val node = Node.from(workflow)
    ext.datastores.pausedWorkflowSession.delete(workflowRef, id)
    if (env.clusterConfig.mode.isWorker) {
      env.clusterAgent.deleteWorkflowSession(this)
    }
    ext.engine.resume(node, wfrHydrated, from, attrs)
  }

  def save(env: Env): Future[Boolean] = {
    val ext = env.adminExtensions.extension[WorkflowAdminExtension].get
    val fu = ext.datastores.pausedWorkflowSession.save(workflowRef, id, this)
    if (env.clusterConfig.mode.isWorker) {
      env.clusterAgent.saveWorkflowSession(this)
    }
    fu
  }
}

object PausedWorkflowSession {
  val format = new Format[PausedWorkflowSession] {
    override def writes(o: PausedWorkflowSession): JsValue = Json.obj(
      "id" -> o.id,
      "workflow_ref" -> o.workflowRef,
      "workflow" -> o.workflow,
      "functions" -> o.functions,
      "wfr" -> WorkflowRun.format.writes(o.wfr),
      "from" -> o.from,
      "created_at" -> o.createdAt.toString,
      "valid_until" -> o.validUntil.map(_.toString.json).getOrElse(JsNull).asValue,
      "access_token" -> o.token
    )
    override def reads(json: JsValue): JsResult[PausedWorkflowSession] = Try {
      PausedWorkflowSession(
        id = json.select("id").as[String],
        workflowRef = json.select("workflow_ref").as[String],
        workflow = json.select("workflow").as[JsObject],
        functions = json.select("functions").asOpt[Map[String, JsObject]].getOrElse(Map.empty),
        wfr =  WorkflowRun.format.reads(json.select("wfr").as[JsObject]).get,
        from = json.select("from").as[Seq[Int]],
        createdAt = DateTime.parse(json.select("created_at").asString),
        validUntil = json.select("valid_until").asOptString.map(v => DateTime.parse(v)),
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(r) => JsSuccess(r)
    }
  }
}
