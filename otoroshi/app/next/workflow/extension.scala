package otoroshi.next.workflow

import akka.stream.scaladsl.Source
import akka.util.ByteString
import otoroshi.api.{GenericResourceAccessApiWithState, Resource, ResourceVersion}
import otoroshi.env.Env
import otoroshi.models.{BackOfficeUser, EntityLocation, EntityLocationSupport}
import otoroshi.next.extensions._
import otoroshi.security.IdGenerator
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.{RequestHeader, Result, Results}

import java.io.File
import java.nio.file.Files
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

case class Workflow(
  location: EntityLocation,
  id: String,
  name: String,
  description: String,
  tags: Seq[String],
  metadata: Map[String, String],
  config: JsObject,
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
  )
  val format                      = new Format[Workflow] {
    override def writes(o: Workflow): JsValue             = o.location.jsonWithKey ++ Json.obj(
      "id"            -> o.id,
      "name"          -> o.name,
      "description"   -> o.description,
      "metadata"      -> o.metadata,
      "tags"          -> JsArray(o.tags.map(JsString.apply)),
      "config"        -> o.config,
    )
    override def reads(json: JsValue): JsResult[Workflow] = Try {
      Workflow(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        description = (json \ "description").as[String],
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        config = (json \ "config").asOpt[JsObject].getOrElse(Json.obj())
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
  override def fmt: Format[Workflow]              = Workflow.format
  override def redisLike(implicit env: Env): RedisLike   = redisCli
  override def key(id: String): String                   = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:workflows:$id"
  override def extractId(value: Workflow): String = value.id
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
      configs <- datastores.workflowsDatastore.findAll()
    } yield {
      states.updateWorkflows(configs)
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
          "workflows",
          "plugins.otoroshi.io",
          ResourceVersion("v1", true, false, true),
          GenericResourceAccessApiWithState[Workflow](
            Workflow.format,
            classOf[Workflow],
            id => datastores.workflowsDatastore.key(id),
            c => datastores.workflowsDatastore.extractId(c),
            json => json.select("id").asString,
            () => "id",
            tmpl = (v, p) => Workflow.template().json,
            stateAll = () => states.allWorkflows(),
            stateOne = id => states.workflow(id),
            stateUpdate = values => states.updateWorkflows(values)
          )
        )
      )
    )
  }

  def handleWorkflowTest(ctx: AdminExtensionRouterContext[AdminExtensionBackofficeAuthRoute], req: RequestHeader, user: Option[BackOfficeUser], body:  Option[Source[ByteString, _]]): Future[Result] = {
    implicit val ec = env.otoroshiExecutionContext
    implicit val mat = env.otoroshiMaterializer
    implicit val ev = env
    (body match {
      case None => Results.Ok(Json.obj("done" -> false, "error" -> "no body")).vfuture
      case Some(bodySource) => bodySource.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
        val payload = bodyRaw.utf8String.parseJson
        val input = payload.select("input").asString.parseJson.asObject
        val workflow = payload.select("workflow").asObject
        val engine = new WorkflowEngine(env)
        val node = Node.from(workflow)
        // Files.writeString(new File("./workflow_test.json").toPath, workflow.prettify)
        engine.run(node, input).map { res =>
          Results.Ok(res.json)
        }
      }
    }).recover {
      case e: Throwable => {
        Results.Ok(Json.obj("done" -> false, "error" -> e.getMessage))
      }
    }
  }
}
