package otoroshi.next.workflow

import io.azam.ulidj.ULID
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.events.AnalyticEvent
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.concurrent.TrieMap
import scala.concurrent._
import scala.jdk.CollectionConverters.collectionAsScalaIterableConverter
import scala.util.Success

class WorkflowEngine(env: Env) {

  implicit val executorContext = env.otoroshiExecutionContext

  def run(node: Node, input: JsObject): Future[WorkflowResult] = {
    val wfRun = WorkflowRun(ULID.random())
    wfRun.memory.set("input", input)
    node
      .internalRun(wfRun)(env, executorContext)
      .map {
        case Left(err)     => WorkflowResult(None, err.some, wfRun)
        case Right(result) => WorkflowResult(result.some, None, wfRun)
      }
      .recover { case t: Throwable =>
        WorkflowResult(
          node.result.flatMap(r => wfRun.memory.get(r)),
          WorkflowError("exception on root run", None, t.some).some,
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

case class WorkflowError(message: String, details: Option[JsObject], exception: Option[Throwable]) {
  def json: JsValue = Json.obj(
    "message"   -> message,
    "details"   -> details,
    "exception" -> exception.map(_.getMessage.json).getOrElse(JsNull).asValue
  )
}

class WorkflowMemory(memory: TrieMap[String, JsValue] = new TrieMap[String, JsValue]()) {
  def get(name: String): Option[JsValue]      = memory.get(name)
  def set(name: String, value: JsValue): Unit = memory.put(name, value)
  def remove(name: String): Unit              = memory.remove(name)
  def json: JsValue                           = JsObject(memory.toMap)
}

case class WorkflowLogItem(
    timestamp: DateTime,
    message: String,
    node: Node,
    memory: WorkflowMemory,
    error: Option[WorkflowError] = None
)                 {
  def json: JsValue = Json.obj(
    "timestamp" -> timestamp.toDate.getTime,
    "message"   -> message,
    "node"      -> node.json,
    "memory"    -> memory.json,
    "error"     -> error.map(_.json).getOrElse(JsNull).asValue
  )
}
class WorkflowLog {
  private val queue                    = new ConcurrentLinkedQueue[WorkflowLogItem]()
  def json: JsValue                    = JsArray(queue.asScala.map(_.json).toSeq)
  def log(item: WorkflowLogItem): Unit = queue.offer(item)
}

case class WorkflowRun(id: String) {
  val memory             = new WorkflowMemory()
  val runlog             = new WorkflowLog()
  def log(message: String, node: Node, error: Option[WorkflowError] = None): Unit = {
    // println(s"[LOG] ${id} - ${message}")
    runlog.log(WorkflowLogItem(DateTime.now(), message, node, memory, error))
  }
  def json: JsValue      = Json.obj(
    "id"     -> id,
    "memory" -> memory.json,
    "log"    -> runlog.json
  )
  def lightJson: JsValue = json.asObject - "log"
}

trait WorkflowFunction {
  def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]]
}

object WorkflowFunction {
  val functions                                   = new TrieMap[String, WorkflowFunction]()
  def registerFunction(name: String, function: WorkflowFunction): Unit = {
    functions.put(name, function)
  }
  def get(name: String): Option[WorkflowFunction] = functions.get(name)
}

trait Node {
  def json: JsObject
  def id: String                = json.select("id").asOptString.getOrElse(ULID.random().toLowerCase)
  def description: String       = json.select("description").asOptString.getOrElse("")
  def kind: String              = json.select("kind").asString
  def enabled: Boolean          = json.select("enabled").asOptBoolean.getOrElse(true)
  def result: Option[String]    = json.select("result").asOptString
  def returned: Option[JsValue] = json.select("returned").asOpt[JsValue]
  def run(wfr: WorkflowRun)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]]
  final def internalRun(
      wfr: WorkflowRun
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    if (!enabled) {
      // println(s"skipping ${id}")
      JsNull.rightf
    } else {
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
            returned
              .map(v => WorkflowOperator.processOperators(v, wfr, env))
              .map(v => Right(v))
              .getOrElse(Right(res)) // TODO: el like
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
}

object Node {
  val default = Json.obj(
    "id"       -> "main",
    "kind"     -> "workflow",
    "steps"    -> Json.arr(
      Json.obj(
        "id"       -> "hello",
        "kind"     -> "call",
        "function" -> "core.hello",
        "args"     -> Json.obj("name" -> "${input.name}"),
        "result"   -> "call_res"
      )
    ),
    "returned" -> Json.obj("$mem_ref" -> Json.obj("name" -> "call_res"))
  )
  val nodes   = new TrieMap[String, (JsObject) => Node]()
  def registerNode(name: String, f: (JsObject) => Node): Unit = {
    nodes.put(name, f)
  }
  def from(json: JsObject): Node = {
    val kind = json.select("kind").asOpt[String].getOrElse("--").toLowerCase()
    nodes.get(kind) match {
      case None       => NoopNode(json)
      case Some(node) => node(json)
    }
  }
}

trait WorkflowOperator {
  def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue
}

object WorkflowOperator {
  private val operators                                                     = new TrieMap[String, WorkflowOperator]()
  def registerOperator(name: String, operator: WorkflowOperator): Unit = {
    operators.put(name, operator)
  }
  def processOperators(value: JsValue, wfr: WorkflowRun, env: Env): JsValue = value match {
    case JsObject(map) if map.size == 1 && map.head._1.startsWith("$")                    => {
      operators.get(map.head._1) match {
        case None           => value
        case Some(operator) => {
          val opts = WorkflowOperator.processOperators(map.head._2.asObject, wfr, env)
          operator.process(opts, wfr, env)
        }
      }
    }
    case JsArray(arr)                                                                     => JsArray(arr.map(v => processOperators(v, wfr, env)))
    case JsObject(map)                                                                    => JsObject(map.mapValues(v => processOperators(v, wfr, env)))
    case JsString("${now}")                                                               => System.currentTimeMillis().json
    case JsString(str) if str.startsWith("${") && str.endsWith("}") && !str.contains(".") => {
      val name = str.substring(2).init
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
