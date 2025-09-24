package otoroshi.next.workflow

import org.apache.pekko.util.ByteString
import org.apache.pekko.NotUsed
import org.apache.pekko.http.scaladsl.util.FastFuture
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}
import io.azam.ulidj.ULID
import otoroshi.actions.{ApiAction, BackOfficeActionContext}
import otoroshi.api.{GenericResourceAccessApiWithState, Resource, ResourceVersion}
import otoroshi.env.Env
import otoroshi.models.{ApiKey, BackOfficeUser, EntityLocation, EntityLocationSupport}
import otoroshi.next.extensions._
import otoroshi.next.models.NgBackend
import otoroshi.next.plugins.{WasmJob, WasmJobsConfig}
import otoroshi.script.{Job, JobInstantiation, JobKind}
import otoroshi.security.IdGenerator
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.TypedMap
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits._
import otoroshi.wasm.WasmConfig
import play.api.Logger
import play.api.http.websocket.{Message, TextMessage}
import play.api.libs.json._
import play.api.libs.typedmap.TypedKey
import play.api.mvc.{AbstractController, ControllerComponents, RequestHeader, Result, Results, WebSocket}
import reactor.core.publisher.{Flux, Sinks}

import java.io.File
import java.nio.file.Files
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class Orphans(nodes: Seq[Node] = Seq.empty, edges: Seq[JsObject] = Seq.empty)

object Orphans {
  val format = new Format[Orphans] {
    override def writes(o: Orphans): JsValue             = Json.obj(
      "nodes" -> o.nodes.map(_.json),
      "edges" -> o.edges
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
    testPayload: JsObject,
    orphans: Orphans
) extends EntityLocationSupport {
  override def internalId: String               = id
  override def json: JsValue                    = Workflow.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata
}

object Workflow {
  def template(): Workflow     = Workflow(
    location = EntityLocation.default,
    id = s"workflow_${IdGenerator.uuid}",
    name = "New Workflow",
    description = "New Workflow",
    metadata = Map.empty,
    tags = Seq.empty,
    config = Node.default,
    job = WorkflowJobConfig.default,
    functions = Map.empty,
    testPayload = Json.obj("name" -> "foo"),
    orphans = Orphans()
  )
  val format: Format[Workflow] = new Format[Workflow] {
    override def writes(o: Workflow): JsValue             = o.location.jsonWithKey ++ Json.obj(
      "id"           -> o.id,
      "name"         -> o.name,
      "description"  -> o.description,
      "metadata"     -> o.metadata,
      "tags"         -> JsArray(o.tags.map(JsString.apply)),
      "config"       -> o.config,
      "test_payload" -> o.testPayload,
      "orphans"      -> Orphans.format.writes(o.orphans),
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
        job = (json \ "job")
          .asOpt[JsObject]
          .flatMap(o => WorkflowJobConfig.format.reads(o).asOpt)
          .getOrElse(WorkflowJobConfig.default),
        functions = (json \ "functions").asOpt[Map[String, JsObject]].getOrElse(Map.empty),
        testPayload = (json \ "test_payload").asOpt[JsObject].getOrElse(Json.obj("name" -> "foo")),
        orphans = (json \ "orphans").asOpt[Orphans](Orphans.format.reads).getOrElse(Orphans())
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
  override def redisLike(using env: Env): RedisLike = redisCli
  override def key(id: String): String                 = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:workflows:$id"
  override def extractId(value: Workflow): String      = value.id
}

class KvPausedWorkflowSessionDatastore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env) {

  implicit val ec  = _env.otoroshiExecutionContext
  implicit val mat = _env.otoroshiMaterializer
  implicit val env = _env

  def fromJsonSafe(value: JsValue): JsResult[PausedWorkflowSession] = fmt.reads(value)
  def fmt: Format[PausedWorkflowSession]                            = PausedWorkflowSession.format
  def redisLike(implicit env: Env): RedisLike                       = redisCli
  def keyAll(): String                                              = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:workflow-sessions:*"
  def key(wfId: String, id: String): String                         =
    s"${_env.storageRoot}:extensions:${extensionId.cleanup}:workflow-sessions:$wfId:$id"
  def extractId(value: PausedWorkflowSession): String               = value.id

  def all(): Future[Seq[PausedWorkflowSession]] = {
    redisLike
      .keys(keyAll())
      .flatMap(keys =>
        if (keys.isEmpty) FastFuture.successful(Seq.empty[Option[ByteString]])
        else redisLike.mget(keys: _*)
      )
      .map(seq =>
        seq.filter(_.isDefined).map(_.get).map(v => fromJsonSafe(Json.parse(v.utf8String))).collect {
          case JsSuccess(i, _) => i
        }
      )
  }
  def allForWorkflow(wfId: String): Future[Seq[PausedWorkflowSession]] = {
    redisLike
      .keys(key(wfId, "*"))
      .flatMap(keys =>
        if (keys.isEmpty) FastFuture.successful(Seq.empty[Option[ByteString]])
        else redisLike.mget(keys: _*)
      )
      .map(seq =>
        seq.filter(_.isDefined).map(_.get).map(v => fromJsonSafe(Json.parse(v.utf8String))).collect {
          case JsSuccess(i, _) => i
        }
      )
  }
  def one(wfId: String, id: String): Future[Option[PausedWorkflowSession]] = {
    redisLike.get(key(wfId, id)).map(_.flatMap(v => fromJsonSafe(Json.parse(v.utf8String)).asOpt))
  }
  def delete(wfId: String, id: String): Future[Boolean] = {
    redisLike.del(key(wfId, id)).map(_ > 0)
  }
  def save(wfId: String, id: String, session: PausedWorkflowSession): Future[Boolean] = {
    redisLike.set(
      key(wfId, id),
      session.json.stringify
    )
  }
}

class WorkflowConfigAdminExtensionDatastores(env: Env, extensionId: AdminExtensionId) {
  val workflowsDatastore: WorkflowConfigDataStore             =
    new KvWorkflowConfigDataStore(extensionId, env.datastores.redis, env)
  val pausedWorkflowSession: KvPausedWorkflowSessionDatastore =
    new KvPausedWorkflowSessionDatastore(extensionId, env.datastores.redis, env)
}

class WorkflowConfigAdminExtensionState(env: Env) {

  private val configs = new UnboundedTrieMap[String, Workflow]()

  def workflow(id: String): Option[Workflow] = configs.get(id)
  def allWorkflows(): Seq[Workflow]          = configs.values.toSeq

  private[workflow] def updateWorkflows(values: Seq[Workflow]): Unit = {
    configs.addAll(values.map(v => (v.id, v))).remAll(configs.keySet.toSeq.diff(values.map(_.id)))
  }
}

object WorkflowAdminExtension {
  val liveUpdatesSourceKey = TypedKey[Sinks.Many[JsObject]]("otoroshi.extensions.workflows.LiveUpdatesSourceKey")
  val workflowDebuggerKey = TypedKey[WorkflowDebugger]("otoroshi.extensions.workflows.WorkflowDebuggerKey")
}

class WorkflowAdminExtension(val env: Env) extends AdminExtension {

  private[workflow] lazy val datastores = new WorkflowConfigAdminExtensionDatastores(env, id)
  private[workflow] lazy val states     = new WorkflowConfigAdminExtensionState(env)
  private[workflow] val handledJobs     = new UnboundedTrieMap[String, Job]()

  val engine = new WorkflowEngine(env)

  override def id: AdminExtensionId = AdminExtensionId("otoroshi.extensions.Workflows")

  override def name: String = "Otoroshi Workflows extension"

  override def description: Option[String] = "Otoroshi Workflows extension".some

  override def enabled: Boolean = true

  override def start(): Unit = {
    WorkflowFunctionsInitializer.initDefaults()
    WorkflowOperatorsInitializer.initDefaults()
    NodesInitializer.initDefaults()
    CategoriesInitializer.initDefaults()
  }

  override def stop(): Unit = ()

  override def syncStates(): Future[Unit] = {
    given ec: ExecutionContext = env.otoroshiExecutionContext
    given ev: Env              = env
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

  override def adminApiRoutes(): Seq[AdminExtensionAdminApiRoute] = {
    Seq(
      AdminExtensionAdminApiRoute(
        method = "POST",
        path = "/apis/extensions/otoroshi.extensions.workflows/sessions/:wfId/:id/_resume",
        wantsBody = true,
        handle = (ctx, req, apikey, optBody) => {
          val wfId = ctx.named("wfId").getOrElse("--")
          val id   = ctx.named("id").getOrElse("--")
          optBody match {
            case None       => Results.BadRequest(Json.obj("error" -> "no body")).vfuture
            case Some(body) =>
              body
                .runFold(ByteString(""))(_ ++ _)(env.otoroshiMaterializer)
                .flatMap { bodyRaw =>
                  bodyRaw.utf8String.parseJson match {
                    case data @ JsObject(_) => {
                      datastores.pausedWorkflowSession
                        .one(wfId, id)
                        .flatMap {
                          case Some(session) => {
                            val async = req.getQueryString("async").contains("true")
                            val attrs = TypedMap.empty
                            if (async) {
                              session.resume(data, attrs, env)
                              Results.Ok(Json.obj("ack" -> true)).vfuture
                            } else {
                              session
                                .resume(data, attrs, env)
                                .map { r =>
                                  Results.Ok(r.json)
                                }(env.otoroshiExecutionContext)
                            }
                          }
                          case None          => Results.NotFound(Json.obj("error" -> "resource not found")).vfuture
                        }(env.otoroshiExecutionContext)
                    }
                    case _                  => Results.BadRequest(Json.obj("error" -> "bad data format")).vfuture
                  }
                }(env.otoroshiExecutionContext)
          }
        }
      ),
      AdminExtensionAdminApiRoute(
        method = "GET",
        path = "/apis/extensions/otoroshi.extensions.workflows/sessions/:wfId/:id",
        wantsBody = false,
        handle = (ctx, req, apikey, optBody) => {
          val wfId = ctx.named("wfId").getOrElse("--")
          val id   = ctx.named("id").getOrElse("--")
          datastores.pausedWorkflowSession
            .one(wfId, id)
            .map {
              case Some(session) => Results.Ok(session.json)
              case None          => Results.NotFound(Json.obj("error" -> "resource not found"))
            }(env.otoroshiExecutionContext)
        }
      ),
      AdminExtensionAdminApiRoute(
        method = "DELETE",
        path = "/apis/extensions/otoroshi.extensions.workflows/sessions/:wfId/:id",
        wantsBody = false,
        handle = (ctx, req, apikey, optBody) => {
          val wfId = ctx.named("wfId").getOrElse("--")
          val id   = ctx.named("id").getOrElse("--")
          datastores.pausedWorkflowSession
            .delete(wfId, id)
            .map {
              case true  => Results.Ok(Json.obj("done" -> true))
              case false => Results.NotFound(Json.obj("error" -> "resource not found"))
            }(env.otoroshiExecutionContext)
        }
      ),
      AdminExtensionAdminApiRoute(
        method = "GET",
        path = "/apis/extensions/otoroshi.extensions.workflows/sessions/:wfId",
        wantsBody = false,
        handle = (ctx, req, apikey, optBody) => {
          val wfId = ctx.named("wfId").getOrElse("--")
          datastores.pausedWorkflowSession
            .allForWorkflow(wfId)
            .map { sessions =>
              Results.Ok(JsArray(sessions.map(_.json)))
            }(env.otoroshiExecutionContext)
        }
      ),
      AdminExtensionAdminApiRoute(
        method = "POST",
        path = "/apis/extensions/otoroshi.extensions.workflows/sessions",
        wantsBody = true,
        handle = (ctx, req, apikey, optBody) => {
          optBody match {
            case None       => Results.BadRequest(Json.obj("error" -> "no body")).vfuture
            case Some(body) =>
              body
                .runFold(ByteString(""))(_ ++ _)(env.otoroshiMaterializer)
                .flatMap { bodyRaw =>
                  val json = bodyRaw.utf8String.parseJson
                  PausedWorkflowSession.format.reads(json) match {
                    case JsError(errors)       => Results.BadRequest(Json.obj("error" -> errors.mkString("\n"))).vfuture
                    case JsSuccess(session, _) => {
                      val wfId = session.workflowRef
                      val id   = session.id
                      datastores.pausedWorkflowSession
                        .one(wfId, id)
                        .flatMap {
                          case Some(s) => Results.Conflict(Json.obj("error" -> "resource already exists")).vfuture
                          case None    => {
                            datastores.pausedWorkflowSession
                              .save(wfId, id, session)
                              .map { _ =>
                                Results.Ok(session.json)
                              }(env.otoroshiExecutionContext)
                          }
                        }(env.otoroshiExecutionContext)
                    }
                  }
                }(env.otoroshiExecutionContext)
          }
        }
      ),
      AdminExtensionAdminApiRoute(
        method = "GET",
        path = "/apis/extensions/otoroshi.extensions.workflows/sessions",
        wantsBody = false,
        handle = (ctx, req, apikey, optBody) => {
          datastores.pausedWorkflowSession
            .all()
            .map { sessions =>
              Results.Ok(JsArray(sessions.map(_.json)))
            }(env.otoroshiExecutionContext)
        }
      )
    )
  }

  override def entities(): Seq[AdminExtensionEntity[EntityLocationSupport]] = {
    Seq(
      AdminExtensionEntity(
        Resource(
          "Workflow",
          "workflows",
          "workflow",
          "plugins.otoroshi.io",
          ResourceVersion("v1", served = true, deprecated = false, storage = true),
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

  def handleWorkflowDebug(): Flow[Message, Message, NotUsed] = {
    implicit val ec = env.otoroshiExecutionContext
    val hotSource: Sinks.Many[JsObject] = Sinks.many().unicast().onBackpressureBuffer[JsObject]()
    val hotFlux: Flux[JsObject] = hotSource.asFlux()
    val debugger = new WorkflowDebugger()

    def start(body: JsObject): Unit = {
      val payload_raw = body.stringify
      val secretFillFuture =
        if (payload_raw.contains("${vault://")) env.vaults.fillSecretsAsync("workflow-test", payload_raw)
        else payload_raw.vfuture
      secretFillFuture.flatMap { payload_filled =>
        val payload     = payload_filled.parseJson
        val input       = payload.select("input").asString.parseJson.asObject
        val functions   = payload.select("functions").asOpt[Map[String, JsObject]].getOrElse(Map.empty)
        val workflow_id = payload.select("workflow_id").asString
        val workflow    = payload.select("workflow").asObject
        val stepByStep   = payload.select("step_by_step").asOptBoolean.getOrElse(false)
        val node        = Node.from(workflow)
        val attrs = TypedMap.empty
        attrs.put(WorkflowAdminExtension.workflowDebuggerKey -> debugger)
        attrs.put(WorkflowAdminExtension.liveUpdatesSourceKey -> hotSource)
        if (stepByStep) {
          debugger.pause()
        }
        engine.run(workflow_id, node, input, attrs, functions).map { res =>
          hotSource.tryEmitNext(Json.obj("kind" -> "result", "data" -> res.json))
          hotSource.tryEmitComplete()
        }
      }
    }

    Flow.fromSinkAndSource[Message, Message](
      Sink.foreach[Message] {
        case tm: TextMessage => {
          val json = tm.data.parseJson
          val kind = json.select("kind").asOptString.getOrElse("noop")
          val data = json.select("data").asOpt[JsObject].getOrElse(Json.obj())

          kind match {
            case "start" =>
              start(data)
            case "next" =>
              // TODO: if new memory in data, update memory
              debugger.next()
            case "resume" =>
              // TODO: if new memory in data, update memory
              debugger.resume()
            case "stop" => debugger.shutdown()
            case _ => println(s"unknown message: '${kind}'")
          }
        }
        case m => println(s"unknown ws message: '${m.getClass.getName}'")
      },
      Source.fromPublisher[Message](hotFlux.map(o => TextMessage(o.stringify)))
    )
  }

  def handleWorkflowTest(
      ctx: AdminExtensionRouterContext[AdminExtensionBackofficeAuthRoute],
      req: RequestHeader,
      user: Option[BackOfficeUser],
      body: Option[Source[ByteString, ?]]
  ): Future[Result] = {
    given ec: ExecutionContext = env.otoroshiExecutionContext
    given mat: Materializer    = env.otoroshiMaterializer
    given ev: Env              = env
    (body match {
      case None             => Results.Ok(Json.obj("done" -> false, "error" -> "no body")).vfuture
      case Some(bodySource) =>
        bodySource.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
          val payload_raw      = bodyRaw.utf8String
          val secretFillFuture =
            if (payload_raw.contains("${vault://")) env.vaults.fillSecretsAsync("workflow-test", payload_raw)
            else payload_raw.vfuture
          secretFillFuture.flatMap { payload_filled =>
            val payload     = payload_filled.parseJson
            val input       = payload.select("input").asString.parseJson.asObject
            val functions   = payload.select("functions").asOpt[Map[String, JsObject]].getOrElse(Map.empty)
            val workflow_id = payload.select("workflow_id").asString
            val workflow    = payload.select("workflow").asObject
            val live        = req.getQueryString("live").contains("true")
            val node        = Node.from(workflow)
            if (live) {
              val hotSource: Sinks.Many[JsObject] = Sinks.many().unicast().onBackpressureBuffer[JsObject]()
              val hotFlux: Flux[JsObject] = hotSource.asFlux()
              //val debugger = new WorkflowDebugger()
              val attrs = TypedMap.empty
              //attrs.put(WorkflowAdminExtension.workflowDebuggerKey -> debugger)
              attrs.put(WorkflowAdminExtension.liveUpdatesSourceKey -> hotSource)
              engine.run(workflow_id, node, input, attrs, functions).map { res =>
                hotSource.tryEmitNext(Json.obj("kind" -> "result", "data" -> res.json))
                hotSource.tryEmitComplete()
              }
              val source = Source.fromPublisher(hotFlux)
              Results.Ok.chunked(source.map(obj => s"data: ${obj.stringify}\n\n")).as("text/event-stream").future
            } else {
              engine.run(workflow_id, node, input, TypedMap.empty, functions).map { res =>
                Results.Ok(res.json)
              }
            }
          }
        }
    }).recover { case e: Throwable =>
      Results.Ok(Json.obj("done" -> false, "error" -> e.getMessage))
    }
  }

  def startJobsIfNeeded(workflows: Seq[Workflow]): Unit = {
    val currentIds: Seq[String] = workflows.filter(_.job.enabled).map { workflow =>
      val actualJob        = new WorkflowJob(workflow.id, workflow.job)
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

class WorkflowsController(ApiAction: ApiAction, cc: ControllerComponents)(implicit env: Env) extends AbstractController(cc) {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  def handleWorkflowDebug() = WebSocket.acceptOrResult[Message, Message] { request =>
    request.session.get("bousr") match {
      case None => Results.Unauthorized(Json.obj("error" -> "unauthorized")).leftf
      case Some(id) => {
        env.datastores.backOfficeUserDataStore.findById(id).flatMap {
          case None       => Results.Unauthorized(Json.obj("error" -> "unauthorized")).leftf
          case Some(user) => {
            env.adminExtensions.extension[WorkflowAdminExtension] match {
              case None => Results.NotFound(Json.obj("error" -> "extension not found")).leftf
              case Some(ext) => {
                ext.handleWorkflowDebug().rightf
              }
            }
          }
        }
      }
    }
  }
}
