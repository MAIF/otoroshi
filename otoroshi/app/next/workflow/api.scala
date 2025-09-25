package otoroshi.next.workflow

import io.azam.ulidj.ULID
import org.joda.time.DateTime
import otoroshi.api.OtoroshiEnvHolder
import otoroshi.env.Env
import otoroshi.events.AnalyticEvent
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.concurrent.TrieMap
import scala.concurrent._
import scala.jdk.CollectionConverters._
import scala.util._

// TODO: time budget per node
// TODO: fuel budget per run, with fuel consumption per node
class WorkflowEngine(env: Env) {

  implicit val executorContext: ExecutionContext = env.otoroshiExecutionContext

  def run(
      wfRef: String,
      node: Node,
      input: JsObject,
      attrs: TypedMap,
      functions: Map[String, JsObject] = Map.empty
  ): Future[WorkflowResult] = {
    val wfRun = WorkflowRun(
      ULID.random(),
      attrs,
      env,
      functions,
      workflow_ref = wfRef,
      workflow = node.json
    )
    attrs.get(WorkflowAdminExtension.workflowDebuggerKey).foreach { debugger =>
      debugger.initialize(wfRun)
    }
    if (attrs.contains(WorkflowAdminExtension.liveUpdatesSourceKey)) {
      attrs.get(WorkflowAdminExtension.liveUpdatesSourceKey).foreach { source =>
        wfRun.runlog.addCallback("debug") { item =>
          source.tryEmitNext(Json.obj("kind" -> "progress", "data" -> item.json))
        }
      }
    }
    wfRun.memory.set("workflow_input", input)
    wfRun.memory.set("input", input)
    node
      .internalRun(wfRun, Seq(0), Seq.empty)(env, executorContext)
      .flatMap {
        case Left(err) if err.message == "_____otoroshi_workflow_paused" =>
          WorkflowResult(err.details.get.select("access_token").asOpt[JsValue], None, wfRun).future
        case Left(err) if err.message == "_____otoroshi_workflow_ended" =>
          WorkflowResult(wfRun.memory.get("input"), None, wfRun).future
        case Left(err) if err.message == "_____otoroshi_workflow_jump" =>
          err.details.flatMap(_.select("to").asOptString) match {
            case None => err.details.flatMap(_.select("path").asOpt[Seq[Int]]) match {
              case None => WorkflowResult(None, Some(WorkflowError(s"'to' not specified", None, None)), wfRun).future
              case Some(path) => resume(node, wfRun, path, attrs)
            }
            case Some(to) => {
              node.collectSubNodes(Seq(0)).find(_._1.id == to) match {
                case None => WorkflowResult(None, Some(WorkflowError(s"unable to find node with id '${to}'", None, None)), wfRun).future
                case Some((_, path)) => resume(node, wfRun, path, attrs)
              }
            }
          }
        case Left(err)     => WorkflowResult(None, err.some, wfRun).future
        case Right(result) => WorkflowResult(result.some, None, wfRun).future
      }
      .recover { case t: Throwable =>
        WorkflowResult(
          node.result.flatMap(r => wfRun.memory.get(r)),
          WorkflowError("exception on root run", None, t.some).some,
          wfRun
        )
      }
      .andThen {
        case _ => attrs.get(WorkflowAdminExtension.workflowDebuggerKey).foreach { debugger =>
          debugger.shutdown()
        }
      }
      .andThen { case Success(value) =>
        WorkflowRunEvent(node, input, value, env).toAnalytics()(env)
      }
  }

  def resume(node: Node, wfr: WorkflowRun, from: Seq[Int], attrs: TypedMap): Future[WorkflowResult] = {
    val wfRun = wfr.copy(attrs = attrs)
    val input = wfr.memory.get("workflow_input").map(_.asObject).getOrElse(Json.obj())
    node
      .internalRun(wfRun, Seq(0), from.tail)(env, executorContext)
      .flatMap {
        case Left(err) if err.message == "_____otoroshi_workflow_paused" =>
          WorkflowResult(err.details.get.select("access_token").asOpt[JsValue], None, wfRun).future
        case Left(err) if err.message == "_____otoroshi_workflow_ended" =>
          WorkflowResult(wfRun.memory.get("input"), None, wfRun).future
        case Left(err) if err.message == "_____otoroshi_workflow_jump" =>
          err.details.flatMap(_.select("to").asOptString) match {
            case None => err.details.flatMap(_.select("path").asOpt[Seq[Int]]) match {
              case None => WorkflowResult(None, Some(WorkflowError(s"'to' not specified", None, None)), wfRun).future
              case Some(path) => resume(node, wfRun, path, attrs)
            }
            case Some(to) => {
              node.collectSubNodes(Seq(0)).find(_._1.id == to) match {
                case None => WorkflowResult(None, Some(WorkflowError(s"unable to find node with id '${to}'", None, None, to.some)), wfRun).future
                case Some((_, path)) => resume(node, wfRun, path, attrs)
              }
            }
          }
        case Left(err)     => WorkflowResult(None, err.some, wfRun).future
        case Right(result) => WorkflowResult(result.some, None, wfRun).future
      }
      .recover { case t: Throwable =>
        WorkflowResult(
          node.result.flatMap(r => wfRun.memory.get(r)),
          WorkflowError("exception on root resume", None, t.some).some,
          wfRun
        )
      }
      .andThen { case Success(value) =>
        WorkflowRunEvent(node, input, value, env).toAnalytics()(env)
      }
  }
}

case class WorkflowResult(returned: Option[JsValue], error: Option[WorkflowError], run: WorkflowRun) {
  def hasError: Boolean  = error.isDefined
  def hasReturn: Boolean = returned.isDefined
  def json: JsValue      = Json.obj(
    "returned" -> returned,
    "error"    -> error.map(_.json).getOrElse(JsNull).asValue,
    "run"      -> run.json
  )
  def lightJson: JsValue = Json.obj(
    "returned" -> returned,
    "error"    -> error.map(_.json).getOrElse(JsNull).asValue,
    "run"      -> run.lightJson
  )
}

case class WorkflowError(message: String, details: Option[JsObject] = None, exception: Option[Throwable] = None, nodeId: Option[String] = None) {
  def json: JsValue = WorkflowError.format.writes(this)
}

object WorkflowError {
  val format = new Format[WorkflowError] {

    override def reads(json: JsValue): JsResult[WorkflowError] = Try {
      WorkflowError(
        nodeId = (json \ "nodeId").asOpt[String],
        message = (json \ "message").as[String],
        details = (json \ "details").asOpt[JsObject],
        exception = None
      )
    } match {
      case Failure(t) => JsError(t.getMessage)
      case Success(r) => JsSuccess(r)
    }

    override def writes(o: WorkflowError): JsValue = Json.obj(
      "nodeId"      -> o.nodeId,
      "message"   -> o.message,
      "details"   -> o.details,
      "exception" -> o.exception.map(_.getMessage.json).getOrElse(JsNull).asValue
    )
  }
}

class WorkflowMemory(memory: TrieMap[String, JsValue] = new TrieMap[String, JsValue]()) {
  def contains(name: String): Boolean         = memory.contains(name)
  def get(name: String): Option[JsValue]      = memory.get(name)
  def set(name: String, value: JsValue): Unit = memory.put(name, value)
  def remove(name: String): Unit              = memory.remove(name)
  def json: JsValue                           = JsObject(memory.toMap)
}

case class WorkflowLogItem(
    timestamp: DateTime,
    message: String,
    node: Node,
    memory: JsValue,
    error: Option[WorkflowError] = None
) {
  def json: JsValue = WorkflowLogItem.format.writes(this)
}

object WorkflowLogItem {
  val format = new Format[WorkflowLogItem] {
    override def reads(json: JsValue): JsResult[WorkflowLogItem] = Try {
      WorkflowLogItem(
        timestamp = new DateTime(json.select("timestamp").as[Long]),
        message = json.select("message").as[String],
        node = Node.from(json.select("node").as[JsObject]),
        memory = json.select("memory").as[JsObject],
        error = json.select("error").asOpt[JsObject].flatMap(o => WorkflowError.format.reads(o).asOpt)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(r) => JsSuccess(r)
    }
    override def writes(o: WorkflowLogItem): JsValue             = Json.obj(
      "timestamp" -> o.timestamp.toDate.getTime,
      "message"   -> o.message,
      "node"      -> o.node.json,
      "memory"    -> o.memory,
      "error"     -> o.error.map(_.json).getOrElse(JsNull).asValue
    )
  }
}

class WorkflowLog {
  private val callbacks                = new TrieMap[String, (WorkflowLogItem) => Unit]()
  private val queue                    = new ConcurrentLinkedQueue[WorkflowLogItem]()
  def json: JsValue                    = JsArray(queue.asScala.map(_.json).toSeq)
  def log(item: WorkflowLogItem): Unit = {
    queue.offer(item)
    if (callbacks.nonEmpty) {
      callbacks.values.foreach(_(item))
    }
  }
  def addCallback(name: String)(f: WorkflowLogItem => Unit): Unit = callbacks.put(name, f)
  def removeCallback(name: String): Unit = callbacks.remove(name)
}

object WorkflowLog {
  def apply(items: Seq[WorkflowLogItem]): WorkflowLog = {
    val wl = new WorkflowLog()
    wl.queue.addAll(items.asJava)
    wl
  }
}

case class WorkflowRun(
    id: String,
    attrs: TypedMap,
    env: Env,
    functions: Map[String, JsObject] = Map.empty,
    memory: WorkflowMemory = new WorkflowMemory(),
    runlog: WorkflowLog = new WorkflowLog(),
    workflow_ref: String,
    workflow: JsObject
) {
  def log(message: String, node: Node, error: Option[WorkflowError] = None): Unit = {
    runlog.log(WorkflowLogItem(DateTime.now(), message, node, memory.json, error))
  }
  def hydrate(
      workflow_ref: String,
      workflow: JsObject,
      attrs: TypedMap,
      env: Env
  ): WorkflowRun = {
    copy(attrs = attrs, env = env)
  }
  def json: JsValue      = WorkflowRun.format.writes(this)
  def lightJson: JsValue = json.asObject - "log" - "functions"
}

object WorkflowRun {
  val format = new Format[WorkflowRun] {
    override def reads(json: JsValue): JsResult[WorkflowRun] = Try {
      WorkflowRun(
        id = json.select("id").asString,
        functions = json.select("functions").asOpt[Map[String, JsObject]].getOrElse(Map.empty),
        memory = {
          val map  = json.select("memory").as[Map[String, JsValue]]
          val tmap = new TrieMap[String, JsValue]()
          tmap.addAll(map)
          new WorkflowMemory(tmap)
        },
        runlog = WorkflowLog(json.select("log").as[Seq[JsObject]].map(o => WorkflowLogItem.format.reads(o).get)),
        attrs = TypedMap.empty,
        env = OtoroshiEnvHolder.get(),
        workflow_ref = "",
        workflow = Json.obj()
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(r) => JsSuccess(r)
    }

    override def writes(o: WorkflowRun): JsValue = Json.obj(
      "id"        -> o.id,
      "functions" -> o.functions,
      "memory"    -> o.memory.json,
      "log"       -> o.runlog.json
    )
  }
}

trait WorkflowFunction {
  def documentationName: String                                                                             = this.getClass.getName.replace("$", "")
  def documentationDisplayName: String                                                                      = documentationName
  def documentationIcon: String                                                                             = "fas fa-circle"
  def documentationDescription: String                                                                      = "no description"
  def documentationInputSchema: Option[JsObject]                                                            = None
  def documentationFormSchema: Option[JsObject]                                                             = None
  def documentationCategory: Option[String]                                                                 = None
  def documentationOutputSchema: Option[JsObject]                                                           = None
  def documentationExample: Option[JsObject]                                                                = None
  def callWithRun(
      args: JsObject
  )(implicit env: Env, ec: ExecutionContext, wfr: WorkflowRun): Future[Either[WorkflowError, JsValue]]      = call(args)
  def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = Left(
    WorkflowError("not implemented", None, None)
  ).vfuture
}

object WorkflowFunction {
  val functions                                   = new TrieMap[String, WorkflowFunction]()
  def registerFunction(name: String, function: WorkflowFunction): Unit = {
    functions.put(name, function)
  }
  def get(name: String): Option[WorkflowFunction] = functions.get(name)
}

trait NodeLike {
  def id: String
  def description: String
  def kind: String
  def enabled: Boolean
  def subNodes: Seq[NodeLike]
  def collectSubNodes(path: Seq[Int] = Seq.empty): Seq[(NodeLike, Seq[Int])] = {
    subNodes.zipWithIndex.flatMap { case (child, idx) =>
      val currentPath = path :+ idx
      (child, currentPath) +: child.collectSubNodes(currentPath)
    }
  }
}

trait Node extends NodeLike {
  def json: JsObject
  def id: String                                 = json.select("id").asOptString.getOrElse(ULID.random().toLowerCase)
  def description: String                        = json.select("description").asOptString.getOrElse("")
  def kind: String                               = json.select("kind").asString
  def enabled: Boolean                           = json.select("enabled").asOptBoolean.getOrElse(true)
  def breakpoint: Boolean                        = json.select("breakpoint").asOptBoolean.getOrElse(false)
  def result: Option[String]                     = json.select("result").asOptString
  def returned: Option[JsValue]                  = json.select("returned").asOpt[JsValue]
  def run(wfr: WorkflowRun, prefix: Seq[Int], from: Seq[Int])(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[WorkflowError, JsValue]]
  def documentationName: String                  = this.getClass.getSimpleName.replace("$", "").toLowerCase()
  def documentationDisplayName: String           = documentationName
  def documentationIcon: String                  = "fas fa-circle"
  def documentationDescription: String           = "no description"
  def documentationInputSchema: Option[JsObject] = None
  def documentationFormSchema: Option[JsObject]  = None
  def documentationCategory: Option[String]      = None
  def documentationExample: Option[JsObject]     = None
  def subNodes: Seq[NodeLike]
  final def internalRun(
      wfr: WorkflowRun,
      prefix: Seq[Int],
      from: Seq[Int]
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    if (!enabled) {
      // println(s"skipping ${id}")
      JsNull.rightf
    } else {
      wfr.log(s"starting '${id}'", this)

      def go(): Future[Either[WorkflowError, JsValue]] = {
        try {
          run(wfr, prefix, from)
            .map {
              case Left(err) if err.message == "_____otoroshi_workflow_paused" => {
                wfr.log(s"pausing '${id}'", this)
                val res = err.details.get.select("access_token").asValue
                result.foreach(name => wfr.memory.set(name, res))
                Left(err)
              }
              case Left(err) if err.message == "_____otoroshi_workflow_ended" => {
                // println(s"ending at '${id}'")
                wfr.log(s"ending at '${id}'", this)
                Left(err)
              }
              case Left(err) => {
                wfr.log(s"ending with error '${id}'", this, err.some)
                Left(err)
              }
              case Right(res) => {
                wfr.log(s"ending '${id}'", this)
                result.foreach(name => wfr.memory.set(name, res))
                returned match {
                  case Some(res) => {
                    val finalRes = WorkflowOperator.processOperators(res, wfr, env)
                    wfr.memory.set("input", finalRes)
                    Right(finalRes)
                  }
                  case None => {
                    if (id != "start")
                      wfr.memory.set("input", res)
                    Right(res) // TODO: el like
                  }
                }
                // returned
                //   .map {
                //     case obj@JsObject(_) =>
                //       val returnedObject = obj - "position" - "description"
                //       WorkflowOperator.processOperators(returnedObject, wfr, env)
                //     case value => WorkflowOperator.processOperators(value, wfr, env)
                //   }
                //   .map { v =>
                //     wfr.memory.set("input", v)
                //     Right(v)
                //   }
                //   .getOrElse {
                //     if (id != "start")
                //       wfr.memory.set("input", res)
                //     Right(res)
                //   }
              }
            }
            .recover {
              case t: Throwable => {
                val error = WorkflowError(s"caught exception on task: '${id}'", None, Some(t), id.some)
                wfr.log(s"ending with exception '${id}'", this, error.some)
                Left(error)
              }
            }
        } catch {
          case t: Throwable => {
            t.printStackTrace()
            val error = WorkflowError(s"caught sync exception on task1: '${id}'", None, Some(t), id.some)
            wfr.log(s"ending with sync exception '${id}'", this, error.some)
            Left(error).future
          }
        }
      }

      if (breakpoint) {
        wfr.attrs.get(WorkflowAdminExtension.workflowDebuggerKey).foreach(_.pause())
      }
      wfr.attrs.get(WorkflowAdminExtension.workflowDebuggerKey) match {
        case None => go()
        case Some(debugger) => debugger.waitForNextStep().flatMap { _ =>
          if (debugger.isStopped) {
            WorkflowError(
              message = "_____otoroshi_workflow_ended",
              details = None,
              exception = None,
              nodeId = id.some
            ).leftf
          } else {
            go()
          }
        }
      }
    }
  }
}

object Node {
  val default         = Json.obj(
    "id"       -> "main",
    "kind"     -> "workflow",
    "steps"    -> Json.arr(
      Json.obj(
        "id"       -> "hello",
        "kind"     -> "call",
        "function" -> "core.hello",
        "args"     -> Json.obj("name" -> "${workflow_input.name}"),
        "result"   -> "call_res"
      )
    ),
    "returned" -> Json.obj("$mem_ref" -> Json.obj("name" -> "call_res"))
  )
  val baseInputSchema = Json.obj(
    "type"       -> "object",
    "required"   -> Seq("kind"),
    "properties" -> Json.obj(
      "id"          -> Json.obj("type" -> "string", "description" -> "id of the node (optional). for debug purposes only"),
      "description" -> Json.obj(
        "type"        -> "string",
        "description" -> "The description of what this node does in the workflow (optional). for debug purposes only"
      ),
      "kind"        -> Json.obj("type" -> "string", "description" -> "The kind of the node"),
      "enabled"     -> Json.obj("type" -> "boolean", "description" -> "Is the node enabled (optional)"),
      "result"      -> Json.obj(
        "type"        -> "string",
        "description" -> "The name of the memory that will be assigned with the result of this node (optional)"
      ),
      "returned"    -> Json.obj(
        "type"        -> "string",
        "description" -> "Overrides the output of the node with the result of an operator (optional)"
      )
    )
  )
  val nodes           = new TrieMap[String, (JsObject) => Node]()
  val categories      = new TrieMap[String, NodeCategory]()

  def registerCategory(name: String, informations: NodeCategory): Unit = {
    categories.put(name, informations)
  }
  def registerNode(name: String, f: (JsObject) => Node): Unit = {
    nodes.put(name, f)
  }
  def fromObject(obj: JsObject): Node = {
    val kind = obj.select("kind").asOpt[String].getOrElse("--").toLowerCase()
    nodes.get(kind) match {
      case None       => NoopNode(obj)
      case Some(node) => node(obj)
    }
  }
  def fromArray(arr: JsArray): Node = {
    WorkflowNode(Json.obj("steps" -> arr))
  }
  def from(json: JsValue): Node = {
    json match {
      case obj @ JsObject(_) => fromObject(obj)
      case arr @ JsArray(_) => fromArray(arr)
      case _ => NoopNode(Json.obj())
    }
  }
  def flattenTree(node: NodeLike, path: String = "0"): List[(String, NodeLike)] = {
    val children = node.subNodes.filter(_.enabled).zipWithIndex.flatMap { case (child, idx) =>
      val childPath = s"$path.$idx"
      flattenTree(child, childPath)
    }
    List(path -> node) ++ children
  }
}

trait WorkflowOperator {
  def documentationName: String                  = this.getClass.getName.replace("$", "")
  def documentationDisplayName: String           = documentationName
  def documentationIcon: String                  = "fas fa-circle"
  def documentationDescription: String           = "no description"
  def documentationInputSchema: Option[JsObject] = None
  def documentationFormSchema: Option[JsObject]  = None
  def documentationCategory: Option[String]      = None
  def documentationExample: Option[JsObject]     = None
  def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue
}

object WorkflowOperator {
  val pattern = """\$\{([^}]+)\}""".r
  val operators                                                             = new TrieMap[String, WorkflowOperator]()
  def registerOperator(name: String, operator: WorkflowOperator): Unit = {
    operators.put(name, operator)
  }
  def processOperators(value: JsValue, wfr: WorkflowRun, env: Env): JsValue = value match {
    case JsObject(map) if map.size == 1 && map.head._1.startsWith("$")                    => {
      operators.get(map.head._1) match {
        case None           => value
        case Some(operator) => {
          val opts = processOperators(map.head._2.asObject, wfr, env)
          operator.process(opts, wfr, env)
        }
      }
    }
    case JsArray(arr)                                                                     => JsArray(arr.map(v => processOperators(v, wfr, env)))
    case JsObject(map)                                                                    => JsObject(map.mapValues(v => processOperators(v, wfr, env)))
    case JsString("${now}")                                                               => System.currentTimeMillis().json
    case JsString("${workflow_id}")                                                       => wfr.workflow_ref.json
    case JsString("${session_id}")                                                        => wfr.id.json
    case JsString("${resume_token}")                                                      => PausedWorkflowSession.computeToken(wfr.workflow_ref, wfr.id, env).json
    case JsString(str) if str.startsWith("${") && str.endsWith("}") && !str.contains(".") => {
      println(str, str.substring(2), str.substring(2).init)
      val name = str.substring(2).init
      println(name, wfr.memory.get(name))
      wfr.memory.get(name) match {
        case None        => JsNull
        case Some(value) => value
      }
    }
    case JsString(str) if str.startsWith("${") && str.endsWith("}") && str.contains(".")  => {
      val parts = str.substring(2).init.split("\\.")
      val name  = parts.head
      val path  = parts.tail.mkString(".")
      wfr.memory.get(name) match {
        case None        => JsNull
        case Some(value) => value.at(path).asOpt[JsValue].getOrElse(JsNull)
      }
    }
    case JsString(str) if str.contains("${now_str}")                                      => JsString(str.replace("${now_str}", DateTime.now().toString))
    case JsString(str) if str.contains("${now}")                                          => JsString(str.replace("${now}", System.currentTimeMillis().toString))
    case JsString(str) if str.contains("${workflow_id}")                                  => JsString(str.replace("${workflow_id}", wfr.workflow_ref))
    case JsString(str) if str.contains("${session_id}")                                   => JsString(str.replace("${session_id}", wfr.id))
    case JsString(str) if str.contains("${resume_token}")                                 =>
      JsString(str.replace("${resume_token}", PausedWorkflowSession.computeToken(wfr.workflow_ref, wfr.id, env)))
    case JsString(str) if str.contains("${") && str.contains("}")                         => {
      val res = pattern.replaceAllIn(str, m => {
        val key = m.group(1)
        if (key.contains(".")) {
          val parts = key.split("\\.")
          val name  = parts.head
          val path  = parts.tail.mkString(".")
          wfr.memory.get(name).map(obj => obj.at(path).asOpt[JsValue].getOrElse(JsNull) match {
            case JsNull => "null"
            case JsNumber(n) => n.toString
            case JsBoolean(n) => n.toString
            case JsString(n) => n
            case j => j.stringify
          }).getOrElse("--")
        } else {
          wfr.memory.get(key).map {
            case JsNull => "null"
            case JsNumber(n) => n.toString
            case JsBoolean(n) => n.toString
            case JsString(n) => n
            case j => j.stringify
          }.getOrElse("--")
        }
      })
      res.json
    }
    case _                                                                                => value
  }
}

case class WorkflowRunEvent(
    node: Node,
    input: JsObject,
    result: WorkflowResult,
    env: Env
) extends AnalyticEvent {

  val `@id`: String                 = env.snowflakeGenerator.nextIdStr()
  val `@timestamp`: DateTime        = DateTime.now()
  val fromOrigin: Option[String]    = None
  val fromUserAgent: Option[String] = None
  val `@type`: String               = "WorkflowRunEvent"
  val `@service`: String            = "Otoroshi"
  val `@serviceId`: String          = ""

  override def toJson(implicit _env: Env): JsValue = {
    Json.obj(
      "@id"        -> `@id`,
      "@timestamp" -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
      "@type"      -> `@type`,
      "@product"   -> _env.eventsName,
      "@serviceId" -> `@serviceId`,
      "@service"   -> `@service`,
      "@env"       -> env.env,
      "input"      -> input,
      "node"       -> node.json,
      "result"     -> result.json
    )
  }
}
