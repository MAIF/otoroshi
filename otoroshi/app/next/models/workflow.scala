package otoroshi.next.models

import io.azam.ulidj.ULID
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import scala.collection.concurrent.TrieMap
import scala.concurrent._

case class Env()

class WorkflowEngine(env: Env) {

  implicit val executorContext = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(4))

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

class WorkflowLog() {
  def json: JsValue = Json.arr()
}

case class WorkflowRun(id: String) {
  val memory = new WorkflowMemory()
  val runlog = new WorkflowLog()
  def log(message: String, node: Node, error: Option[WorkflowError] = None): Unit = {
    println(s"[LOG] ${id} - ${message}")
     // TODO: implement it
  }
  def json: JsValue = Json.obj(
    "id" -> id,
    "memory" -> memory.json,
    "log" -> runlog.json,
  )
}

trait WorkflowFunction {
  def call(args: JsObject): Future[Either[WorkflowError, JsValue]]
}

object WorkflowFunction {
  val functions = {
    val m = new TrieMap[String, WorkflowFunction]()
    m.put("core.log", new PrintFunction())
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
  def returned: Option[String] = json.select("returned").asOptString
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
          returned.flatMap(name => wfr.memory.get(name)).map(v => Right(v)).getOrElse(Right(res)) // TODO: el like
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
  def from(json: JsObject): Node = {
    json.select("kind").asOpt[String].getOrElse("--").toLowerCase() match {
      case "workflow" => WorkflowNode(json)
      case "call" => CallNode(json)
      case _ => NoopNode(json)
    }
  }
}

case class NoopNode(json: JsObject) extends Node {
  override def run(wfr: WorkflowRun)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    println("noop, doing nothing")
    JsNull.rightf
  }
}

case class WorkflowNode(json: JsObject) extends Node {

  lazy val steps: Seq[Node] = json.select("steps").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map(o => Node.from(o))

  def next(nodes: Seq[Node], wfr: WorkflowRun)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    if (nodes.nonEmpty) {
      val head = nodes.head
      head.internalRun(wfr).flatMap {
        case Left(err) => Left(err).vfuture
        case Right(_) => next(nodes.tail, wfr)
      }.recover {
        case t: Throwable => WorkflowError(s"caught exception on task '${id}' at step: '${head.id}'", None, Some(t)).left
      }
    } else {
      JsNull.rightf
    }
  }

  override def run(wfr: WorkflowRun)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    next(steps, wfr)
  }
}

case class CallNode(json: JsObject) extends Node {

  lazy val functionName: String = json.select("function").asString
  lazy val args: JsObject = json.select("args").asObject

  override def run(wfr: WorkflowRun)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    WorkflowFunction.get(functionName) match {
      case None => WorkflowError(s"function '${functionName}' not supported in task '${id}'", None, None).leftf
      case Some(function) => function.call(args)
    }
  }
}

class PrintFunction() extends WorkflowFunction {

  override def call(args: JsObject): Future[Either[WorkflowError, JsValue]] = {
    val message = args.select("message").asString
    println(s"print: ${message}")
    message.json.rightf
  }
}

object WorkflowTest {
  def main(args: Array[String]): Unit = {
    implicit val executorContext = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(4))
    val env = Env()
    val engine = new WorkflowEngine(env)
    val workflow = Json.obj(
      "id" -> "main",
      "kind" -> "workflow",
      "steps" -> Json.arr(
        Json.obj("id" -> "call_1", "kind" -> "call", "function" -> "core.log", "args" -> Json.obj("message" -> "step 1"), "result" -> "call_1"),
        Json.obj("id" -> "call_2", "kind" -> "call", "function" -> "core.log", "args" -> Json.obj("message" -> "step 2"), "result" -> "call_2"),
        Json.obj("id" -> "call_3", "kind" -> "call", "function" -> "core.log", "args" -> Json.obj("message" -> "step 3"), "result" -> "call_3"),
      ),
      "returned" -> "call_3"
    )
    val node = Node.from(workflow)
    Files.writeString(new File("./workflow_test.json").toPath, workflow.prettify)
    engine.run(node, Json.obj("foo" -> "bar")).map { res =>
      println(s"result: ${res.json.prettify}")
    }
  }
}
