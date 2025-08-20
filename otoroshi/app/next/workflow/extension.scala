package otoroshi.next.workflow

import akka.stream.scaladsl.Source
import akka.util.ByteString
import otoroshi.api.{GenericResourceAccessApiWithState, Resource, ResourceVersion}
import otoroshi.env.Env
import otoroshi.models.{BackOfficeUser, EntityLocation, EntityLocationSupport}
import otoroshi.next.extensions._
import otoroshi.next.plugins.{WasmJob, WasmJobsConfig}
import otoroshi.script.{Job, JobInstantiation, JobKind}
import otoroshi.security.IdGenerator
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.TypedMap
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits._
import otoroshi.wasm.WasmConfig
import play.api.libs.json._
import play.api.mvc.{RequestHeader, Result, Results}

import java.io.File
import java.nio.file.Files
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

case class Orphans(nodes: Seq[Node] = Seq.empty, edges: Seq[JsObject] = Seq.empty)

object Orphans {
  val format               = new Format[Orphans] {
    override def writes(o: Orphans): JsValue             = Json.obj(
      "nodes"         -> o.nodes.map(_.json),
      "edges"         -> o.edges
    )
    override def reads(json: JsValue): JsResult[Orphans] = Try {
      Orphans(
        nodes = json.select("nodes").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map(o => Node.from(o)),
        edges = (json \ "edges").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

case class Workflow(
    location: EntityLocation,
    id: String,
    name: String,
    description: String,
    tags: Seq[String],
    metadata: Map[String, String],
    config: JsObject,
    job: WorkflowJobConfig,
    functions: Map[String, JsObject],
    testPayload: JsObject
) extends EntityLocationSupport {
  override def internalId: String               = id
  override def json: JsValue                    = Workflow.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata
}

object Workflow {
  def template(): Workflow = Workflow(
    location = EntityLocation.default,
    id = s"workflow_${IdGenerator.uuid}",
    name = "New Workflow",
    description = "New Workflow",
    metadata = Map.empty,
    tags = Seq.empty,
    config = Node.default,
    job = WorkflowJobConfig.default,
    functions = Map.empty,
    testPayload = Json.obj("name" -> "foo")
  )
  val format               = new Format[Workflow] {
    override def writes(o: Workflow): JsValue             = o.location.jsonWithKey ++ Json.obj(
      "id"           -> o.id,
      "name"         -> o.name,
      "description"  -> o.description,
      "metadata"     -> o.metadata,
      "tags"         -> JsArray(o.tags.map(JsString.apply)),
      "config"       -> o.config,
      "test_payload" -> o.testPayload,
      "orphans"      -> Orphans.format.writes(o.orphans)
      "job"          -> o.job.json,
      "functions"    -> o.functions
    )
    override def reads(json: JsValue): JsResult[Workflow] = Try {
      Workflow(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        description = (json \ "description").as[String],
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        config = (json \ "config").asOpt[JsObject].getOrElse(Json.obj()),
        job = (json \ "job").asOpt[JsObject].flatMap(o => WorkflowJobConfig.format.reads(o).asOpt).getOrElse(WorkflowJobConfig.default),
        functions = (json \ "functions").asOpt[Map[String, JsObject]].getOrElse(Map.empty),
        testPayload = (json \ "test_payload").asOpt[JsObject].getOrElse(Json.obj("name" -> "foo"))
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

trait WorkflowConfigDataStore extends BasicStore[Workflow]

class KvWorkflowConfigDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
    extends WorkflowConfigDataStore
    with RedisLikeStore[Workflow] {
  override def fmt: Format[Workflow]                   = Workflow.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): String                 = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:workflows:$id"
  override def extractId(value: Workflow): String      = value.id
}

class WorkflowConfigAdminExtensionDatastores(env: Env, extensionId: AdminExtensionId) {
  val workflowsDatastore: WorkflowConfigDataStore =
    new KvWorkflowConfigDataStore(extensionId, env.datastores.redis, env)
}

class WorkflowConfigAdminExtensionState(env: Env) {

  private val configs = new UnboundedTrieMap[String, Workflow]()

  def workflow(id: String): Option[Workflow] = configs.get(id)
  def allWorkflows(): Seq[Workflow]          = configs.values.toSeq

  private[workflow] def updateWorkflows(values: Seq[Workflow]): Unit = {
    configs.addAll(values.map(v => (v.id, v))).remAll(configs.keySet.toSeq.diff(values.map(_.id)))
  }
}

class WorkflowAdminExtension(val env: Env) extends AdminExtension {

  private[workflow] lazy val datastores = new WorkflowConfigAdminExtensionDatastores(env, id)
  private[workflow] lazy val states     = new WorkflowConfigAdminExtensionState(env)
  private[workflow] val handledJobs = new UnboundedTrieMap[String, Job]()

  val engine = new WorkflowEngine(env)

  override def id: AdminExtensionId = AdminExtensionId("otoroshi.extensions.Workflows")

  override def name: String = "Otoroshi Workflows extension"

  override def description: Option[String] = "Otoroshi Workflows extension".some

  override def enabled: Boolean = true

  override def start(): Unit = {
    WorkflowFunctionsInitializer.initDefaults()
    WorkflowOperatorsInitializer.initDefaults()
    NodesInitializer.initDefaults()
  }

  override def stop(): Unit = ()

  override def syncStates(): Future[Unit] = {
    implicit val ec = env.otoroshiExecutionContext
    implicit val ev = env
    for {
      configs <- datastores.workflowsDatastore.findAllAndFillSecrets()
    } yield {
      states.updateWorkflows(configs)
      startJobsIfNeeded(configs)
      ()
    }
  }

  override def backofficeAuthRoutes(): Seq[AdminExtensionBackofficeAuthRoute] = Seq(
    AdminExtensionBackofficeAuthRoute(
      method = "POST",
      path = "/extensions/workflows/_test",
      wantsBody = true,
      handle = handleWorkflowTest
    )
  )

  override def entities(): Seq[AdminExtensionEntity[EntityLocationSupport]] = {
    Seq(
      AdminExtensionEntity(
        Resource(
          "Workflow",
          "workflows",
          "workflow",
          "plugins.otoroshi.io",
          ResourceVersion("v1", true, false, true),
          GenericResourceAccessApiWithState[Workflow](
            Workflow.format,
            classOf[Workflow],
            id => datastores.workflowsDatastore.key(id),
            c => datastores.workflowsDatastore.extractId(c),
            json => json.select("id").asString,
            () => "id",
            tmpl = (v, p, _ctx) => Workflow.template().json,
            stateAll = () => states.allWorkflows(),
            stateOne = id => states.workflow(id),
            stateUpdate = values => states.updateWorkflows(values)
          )
        )
      )
    )
  }

  def workflows(): Seq[Workflow] = states.allWorkflows()

  def workflow(id: String): Option[Workflow] = states.workflow(id)

  def handleWorkflowTest(
      ctx: AdminExtensionRouterContext[AdminExtensionBackofficeAuthRoute],
      req: RequestHeader,
      user: Option[BackOfficeUser],
      body: Option[Source[ByteString, _]]
  ): Future[Result] = {
    implicit val ec  = env.otoroshiExecutionContext
    implicit val mat = env.otoroshiMaterializer
    implicit val ev  = env
    (body match {
      case None             => Results.Ok(Json.obj("done" -> false, "error" -> "no body")).vfuture
      case Some(bodySource) =>
        bodySource.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
          val payload_raw      = bodyRaw.utf8String
          val secretFillFuture =
            if (payload_raw.contains("${vault://")) env.vaults.fillSecretsAsync("workflow-test", payload_raw)
            else payload_raw.vfuture
          secretFillFuture.flatMap { payload_filled =>
            val payload  = payload_filled.parseJson
            val input    = payload.select("input").asString.parseJson.asObject
            val functions = payload.select("functions").asOpt[Map[String, JsObject]].getOrElse(Map.empty)
            val workflow = payload.select("workflow").asObject
            val node     = Node.from(workflow)
            // Node.flattenTree(node).foreach {
            //   case (path, n) => println(s"${path} - ${n.kind} / ${n.id}")
            // }
            engine.run(node, input, TypedMap.empty, functions).map { res =>
              Results.Ok(res.json)
            }
          }
        }
    }).recover {
      case e: Throwable => {
        Results.Ok(Json.obj("done" -> false, "error" -> e.getMessage))
      }
    }
  }

  def startJobsIfNeeded(workflows: Seq[Workflow]): Unit = {
    val currentIds: Seq[String] = workflows.filter(_.job.enabled).map { workflow =>
      val actualJob = new WorkflowJob(workflow.id, workflow.job)
      val uniqueId: String = actualJob.uniqueId.id
      if (!handledJobs.contains(uniqueId)) {
        handledJobs.put(uniqueId, actualJob)
        env.jobManager.registerJob(actualJob)
      }
      uniqueId
    }
    handledJobs.values.toSeq.foreach { job =>
      val id: String = job.uniqueId.id
      if (!currentIds.contains(id)) {
        handledJobs.remove(id)
        env.jobManager.unregisterJob(job)
      }
    }
  }
}
