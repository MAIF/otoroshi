package otoroshi.next.proxy

import org.joda.time.DateTime
import play.api.libs.json.{JsArray, JsNull, JsObject, JsString, JsValue, Json}


object ExecutionReport {
  def apply(id: String): ExecutionReport = new ExecutionReport(id, DateTime.now())
}

sealed trait ExecutionReportState {
  def name: String
  def json: JsValue = JsString(name)
}

object ExecutionReportState {
  case object Created    extends ExecutionReportState { def name: String = "Created"    }
  case object Running    extends ExecutionReportState { def name: String = "Running"    }
  case object Successful extends ExecutionReportState { def name: String = "Successful" }
  case object Failed     extends ExecutionReportState { def name: String = "Failed"     }
}

case class ExecutionReportStep(task: String, start: Long, stop: Long, duration: Long, ctx: JsValue = JsNull) {
  def json: JsValue = Json.obj(
    "task" -> task,
    "start" -> start,
    "start_fmt" -> new DateTime(start).toString(),
    "stop" -> stop,
    "stop_fmt" -> new DateTime(stop).toString(),
    "duration" -> duration,
    "ctx" -> ctx
  )
}

class ExecutionReport(val id: String, val creation: DateTime) {

  // TODO: move into one big case class with mutable ref ?
  // I know mutability is bad etc but here, i know for sure that concurrency is not an issue
  var currentTask: String = ""
  var lastStart: Long = creation.toDate.getTime
  var state: ExecutionReportState = ExecutionReportState.Created
  var steps: Seq[ExecutionReportStep] = Seq.empty
  var gduration = -1L
  var overheadIn = -1L
  var overheadOut = -1L
  var overheadOutStart = creation.toDate.getTime
  var termination = creation
  var ctx: JsValue = JsNull

  def getStep(task: String): Option[ExecutionReportStep] = {
    steps.find(_.task == task )
  }

  def errToJson(error: Throwable): JsValue = {
    Json.obj(
      "message" -> error.getMessage,
      "cause" -> Option(error.getCause).map(errToJson).getOrElse(JsNull).as[JsValue],
      "stack" -> JsArray(error.getStackTrace.toSeq.map(el => Json.obj(
        "class_loader_name" -> el.getClassLoaderName,
        "module_name" -> el.getModuleName,
        "module_version" -> el.getModuleVersion,
        "declaring_class" -> el.getClassName,
        "method_name" -> el.getMethodName,
        "file_name" -> el.getFileName,
      )))
    )
  }

  def json: JsValue = Json.obj(
    "id" -> id,
    "creation" -> creation.toString(),
    "termination" -> termination.toString(),
    "duration" -> gduration,
    "overhead_in" -> overheadIn,
    "overhead_out" -> overheadOut,
    "state" -> state.json,
    "steps" -> JsArray(steps.map(_.json))
  )

  def markOverheadIn(): ExecutionReport = {
    overheadIn = System.currentTimeMillis() - creation.getMillis
    this
  }

  def startOverheadOut(): ExecutionReport = {
    overheadOutStart = System.currentTimeMillis()
    this
  }

  def markOverheadOut(): ExecutionReport = {
    overheadOut = System.currentTimeMillis() - overheadOutStart
    this
  }

  def setContext(context: JsValue): ExecutionReport = {
    ctx = context
    this
  }

  def markFailure(message: String): ExecutionReport = {
    state = ExecutionReportState.Failed
    val stop = System.currentTimeMillis()
    val duration = stop - lastStart
    steps = steps :+ ExecutionReportStep(currentTask, lastStart, stop, duration, ctx) :+ ExecutionReportStep("request-failure", stop, stop, 0L, Json.obj("message" -> message))
    lastStart = stop
    gduration = stop - creation.getMillis
    termination = new DateTime(stop)
    this
  }

  def markFailure(message: String, error: Throwable): ExecutionReport = {
    state = ExecutionReportState.Failed
    val stop = System.currentTimeMillis()
    val duration = stop - lastStart
    steps = steps :+ ExecutionReportStep(currentTask, lastStart, stop, duration, ctx) :+ ExecutionReportStep("request-failure", stop, stop, 0L, Json.obj("message" -> message, "error" -> errToJson(error)))
    lastStart = stop
    gduration = stop - creation.getMillis
    termination = new DateTime(stop)
    this
  }

  def markSuccess(): ExecutionReport = {
    state = ExecutionReportState.Successful
    val stop = System.currentTimeMillis()
    val duration = stop - lastStart
    steps = steps :+ ExecutionReportStep(currentTask, lastStart, stop, duration, ctx) :+ ExecutionReportStep(s"request-success", stop, stop, 0L)
    lastStart = stop
    gduration = stop - creation.getMillis
    termination = new DateTime(stop)
    this
  }

  def markDoneAndStart(task: String, previousCtx: Option[JsValue] = None): ExecutionReport = {
    state = ExecutionReportState.Running
    val stop = System.currentTimeMillis()
    val duration = stop - lastStart
    steps = steps :+ ExecutionReportStep(currentTask, lastStart, stop, duration, previousCtx.getOrElse(ctx))
    lastStart = stop
    currentTask = task
    ctx = JsNull
    this
  }

  def start(task: String, context: JsValue = JsNull): ExecutionReport = {
    state = ExecutionReportState.Running
    lastStart = System.currentTimeMillis()
    currentTask = task
    ctx = context
    this
  }
}