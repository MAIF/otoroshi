package otoroshi.next.workflow

import io.azam.ulidj.ULID
import org.joda.time.DateTime
import otoroshi.next.plugins.BodyHelper
import otoroshi.next.plugins.api.NgPluginHttpRequest
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import java.io.File
import java.nio.file.Files
import java.util.concurrent.{ConcurrentLinkedQueue, Executors}
import scala.collection.concurrent.TrieMap
import scala.concurrent._
import scala.concurrent.duration.DurationLong
import scala.jdk.CollectionConverters.collectionAsScalaIterableConverter

case class Env() {
  val otoroshiExecutionContext = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(4))
}

class WorkflowEngine(env: Env) {

  implicit val executorContext = env.otoroshiExecutionContext

  def run(node: Node, input: JsObject): Future[WorkflowResult] = {
    val wfRun = WorkflowRun(ULID.random())
    wfRun.memory.set("input", input)
    node.internalRun(wfRun)(env, executorContext).map {
      case Left(err) => WorkflowResult(None, err.some, wfRun)
      case Right(result) => WorkflowResult(result.some, None, wfRun)
    }.recover {
      case t: Throwable => WorkflowResult(node.result.flatMap(r => wfRun.memory.get(r)), WorkflowError("exception on root run", None, t.some).some, wfRun)
    }
  }
}

case class WorkflowResult(returned: Option[JsValue], error: Option[WorkflowError], run: WorkflowRun) {
  def hasError: Boolean = error.isDefined
  def hasReturn: Boolean = returned.isDefined
  def json: JsValue = Json.obj(
    "returned" -> returned,
    "error" -> error.map(_.json).getOrElse(JsNull).asValue,
    "run" -> run.json
  )
}

case class WorkflowError(message: String, details: Option[JsObject], exception: Option[Throwable]) {
  def json: JsValue = Json.obj(
    "message" -> message,
    "details" -> details,
    "exception" -> exception.map(_.getMessage.json).getOrElse(JsNull).asValue,
  )
}

class WorkflowMemory(memory: TrieMap[String, JsValue] = new TrieMap[String, JsValue]()) {
  def get(name: String): Option[JsValue] = memory.get(name)
  def set(name: String, value: JsValue): Unit = memory.put(name, value)
  def remove(name: String): Unit = memory.remove(name)
  def json: JsValue = JsObject(memory.toMap)
}

case class WorkflowLogItem(timestamp: DateTime, message: String, node: Node, memory: WorkflowMemory, error: Option[WorkflowError] = None) {
  def json: JsValue = Json.obj(
    "timestamp" -> timestamp.toDate.getTime,
    "message" -> message,
    "node" -> node.json,
    "memory" -> memory.json,
    "error" -> error.map(_.json).getOrElse(JsNull).asValue,
  )
}
class WorkflowLog {
  private val queue = new ConcurrentLinkedQueue[WorkflowLogItem]()
  def json: JsValue = JsArray(queue.asScala.map(_.json).toSeq)
  def log(item: WorkflowLogItem): Unit = queue.offer(item)
}

case class WorkflowRun(id: String) {
  val memory = new WorkflowMemory()
  val runlog = new WorkflowLog()
  def log(message: String, node: Node, error: Option[WorkflowError] = None): Unit = {
    // println(s"[LOG] ${id} - ${message}")
    runlog.log(WorkflowLogItem(DateTime.now(), message, node, memory, error))
  }
  def json: JsValue = Json.obj(
    "id" -> id,
    "memory" -> memory.json,
    // "log" -> runlog.json,
  )
}

trait WorkflowFunction {
  def call(args: JsObject): Future[Either[WorkflowError, JsValue]]
}

object WorkflowFunction {
  val functions = {
    val m = new TrieMap[String, WorkflowFunction]()
    // TODO: try to find something more automatic
    m.put("core.log", new PrintFunction())
    // http call
    // store get
    // store set
    m
  }
  def registerFunction(name: String, function: WorkflowFunction): Unit = {
    println(s"registering WorkflowFunction: '${name}'")
    functions.put(name, function)
  }
  def get(name: String): Option[WorkflowFunction] = functions.get(name)
}

trait Node {
  def json: JsObject
  def id: String = json.select("id").asString
  def kind: String = json.select("kind").asString
  def result: Option[String] = json.select("result").asOptString
  def returned: Option[JsValue] = json.select("returned").asOpt[JsValue]
  def run(wfr: WorkflowRun)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]]
  final def internalRun(wfr: WorkflowRun)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    wfr.log(s"starting '${id}'", this)
    run(wfr)
      .map {
        case Left(err) => {
          wfr.log(s"ending with error '${id}'", this, err.some)
          Left(err)
        }
        case Right(res) => {
          wfr.log(s"ending '${id}'", this)
          result.foreach(name => wfr.memory.set(name, res))
          returned.map(v => WorkflowOperator.processOperators(v, wfr, env)).map(v => Right(v)).getOrElse(Right(res)) // TODO: el like
        }
      }
      .recover {
        case t: Throwable => {
          val error = WorkflowError(s"caught exception on task: '${id}'", None, Some(t))
          wfr.log(s"ending with exception '${id}'", this, error.some)
          Left(error)
        }
      }
  }
}

object Node {
  val nodes = {
    val n = new TrieMap[String, (JsObject) => Node]()
    n.put("workflow", json =>  WorkflowNode(json))
    n.put("call", json =>  CallNode(json))
    n.put("assign", json =>  AssignNode(json))
    n.put("parallel", json =>  ParallelFlowsNode(json))
    n.put("switch", json =>  SwitchNode(json))
    n
  }
  def registerNode(name: String, f: (JsObject) => Node): Unit = {
    nodes.put(name, f)
  }
  def from(json: JsObject): Node = {
    val kind = json.select("kind").asOpt[String].getOrElse("--").toLowerCase() /*match {
      // TODO: search in a map of possible nodes ?
      case "workflow" => WorkflowNode(json)
      case "call" => CallNode(json)
      case "assign" => AssignNode(json)
      case "parallel" => ParallelFlowsNode(json)
      case "switch" => SwitchNode(json)
      case _ => NoopNode(json)
    }*/
    nodes.get(kind) match {
      case None => NoopNode(json)
      case Some(node) => node(json)
    }
  }
}

trait WorkflowOperator {
  def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue
}

object WorkflowOperator {
  private val operators = {
    val ops = new TrieMap[String, WorkflowOperator]()
    ops.put("$mem_ref", new MemRefOperator())
    ops
  }
  def registerOperator(name: String, operator: WorkflowOperator): Unit = {
    operators.put(name, operator)
  }
  def processOperators(value: JsValue, wfr: WorkflowRun, env: Env): JsValue = value match {
    case JsObject(map) if map.size == 1 && map.head._1.startsWith("$") => {
      operators.get(map.head._1) match {
        case None => value
        case Some(operator) => {
          val opts = WorkflowOperator.processOperators(map.head._2.asObject, wfr, env)
          operator.process(opts, wfr, env)
        }
      }
    }
    case JsArray(arr) => JsArray(arr.map(v => processOperators(v, wfr, env)))
    case JsObject(map) => JsObject(map.mapValues(v => processOperators(v, wfr, env)))
    case JsString(str) if str.startsWith("${") && str.endsWith("}") && !str.contains(".") => {
      val name = str.substring(2).init
      wfr.memory.get(name) match {
        case None => JsNull
        case Some(value) => value
      }
    }
    case JsString(str) if str.startsWith("${") && str.endsWith("}") && str.contains(".") => {
      val parts = str.substring(2).init.split("\\.")
      val name = parts.head
      val path = parts.tail.mkString(".")
      wfr.memory.get(name) match {
        case None => JsNull
        case Some(value) => value.at(path).asValue
      }
    }
    case _ => value
  }
}
