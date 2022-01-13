package otoroshi.next.proxy

import org.joda.time.DateTime
import otoroshi.next.utils.JsonHelpers
import play.api.libs.json._

case class ReportPluginSequenceItem(plugin: String, name: String, start: Long, start_ns: Long, stop: Long, stop_ns: Long, in: JsValue, out: JsValue) {
  def json: JsValue = Json.obj(
    "plugin" -> plugin,
    "name" -> name,
    "start" -> start,
    "start_fmt" -> new DateTime(start).toString(),
    "stop" -> stop,
    "stop_fmt" -> new DateTime(stop).toString(),
    "duration" -> (stop - start),
    "duration_ns" -> (stop_ns - start_ns),
    "execution_debug" -> Json.obj(
      "in" -> in,
      "out" -> out
    )
  )
}

case class ReportPluginSequence(size: Int, kind: String, start: Long, start_ns: Long, stop: Long, stop_ns: Long, plugins: Seq[ReportPluginSequenceItem]) {
  def json: JsValue = Json.obj(
    "size" -> size,
    "kind" -> kind,
    "start" -> start,
    "start_ns" -> start_ns,
    "start_fmt" -> new DateTime(start).toString(),
    "stop" -> stop,
    "stop_ns" -> stop_ns,
    "stop_fmt" -> new DateTime(stop).toString(),
    "duration" -> (stop - start),
    "duration_ns" -> (stop_ns - start_ns),
    "plugins" -> JsArray(plugins.map(_.json))
  )
  def stopSequence(): ReportPluginSequence = {
    copy(stop = System.currentTimeMillis(), stop_ns = System.nanoTime())
  }
}

object ExecutionReport {
  def apply(id: String, reporting: Boolean): ExecutionReport = new ExecutionReport(id, DateTime.now(), reporting)
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

case class ExecutionReportStep(task: String, start: Long, stop: Long, duration: Long, duration_ns: Long, ctx: JsValue = JsNull) {
  def json: JsValue = Json.obj(
    "task" -> task,
    "start" -> start,
    "start_fmt" -> new DateTime(start).toString(),
    "stop" -> stop,
    "stop_fmt" -> new DateTime(stop).toString(),
    "duration" -> duration,
    "duration_ns" -> duration_ns,
    "ctx" -> ctx
  )
}

class ExecutionReport(val id: String, val creation: DateTime, val reporting: Boolean) {

  // TODO: move into one big case class with mutable ref ?
  // I know mutability is bad etc but here, i know for sure that concurrency is not an issue
  var currentTask: String = ""
  var lastStart: Long = creation.toDate.getTime
  val start_ns: Long = System.nanoTime()
  var lastStart_ns: Long = start_ns
  var state: ExecutionReportState = ExecutionReportState.Created
  var steps: Seq[ExecutionReportStep] = Seq.empty
  var gduration = -1L
  var gduration_ns = -1L
  var overheadIn = -1L
  var overheadOut = -1L
  var overheadOutStart = creation.toDate.getTime
  var termination = creation
  var ctx: JsValue = JsNull

  def getStep(task: String): Option[ExecutionReportStep] = {
    steps.find(_.task == task )
  }

  def json: JsValue = Json.obj(
    "id" -> id,
    "creation" -> creation.toString(),
    "termination" -> termination.toString(),
    "duration" -> gduration,
    "duration_ns" -> gduration_ns,
    "overhead" -> (overheadIn + overheadOut),
    "overhead_in" -> overheadIn,
    "overhead_out" -> overheadOut,
    "state" -> state.json,
    "steps" -> JsArray(steps.map(_.json))
  )

  def markOverheadIn(): ExecutionReport = {
    if (reporting) {
      overheadIn = System.currentTimeMillis() - creation.getMillis
    }
    this
  }

  def startOverheadOut(): ExecutionReport = {
    if (reporting) {
      overheadOutStart = System.currentTimeMillis()
    }
    this
  }

  def markOverheadOut(): ExecutionReport = {
    if (reporting) {
      overheadOut = System.currentTimeMillis() - overheadOutStart
    }
    this
  }

  def setContext(context: JsValue): ExecutionReport = {
    if (reporting) {
      ctx = context
    }
    this
  }

  def getDurationNow(): Long = {
    System.currentTimeMillis() - creation.getMillis
  }

  def getOverheadInNow(): Long = {
    overheadIn
  }

  def getOverheadOutNow(): Long = {
    overheadOut
  }

  def getOverheadNow(): Long = {
    getOverheadInNow() + getOverheadOutNow()
  }

  def markFailure(message: String): ExecutionReport = {
    if (reporting) {
      state = ExecutionReportState.Failed
      val stop = System.currentTimeMillis()
      val stop_ns = System.nanoTime()
      val duration = stop - lastStart
      val duration_ns = stop_ns - lastStart_ns
      steps = steps :+ ExecutionReportStep(currentTask, lastStart, stop, duration, duration_ns, ctx) :+ ExecutionReportStep("request-failure", stop, stop, 0L, 0L, Json.obj("message" -> message))
      lastStart = stop
      lastStart_ns = stop_ns
      gduration = stop - creation.getMillis
      gduration_ns = stop_ns - start_ns
      termination = new DateTime(stop)
    }
    this
  }

  def markFailure(message: String, error: Throwable): ExecutionReport = {
    if (reporting) {
      state = ExecutionReportState.Failed
      val stop = System.currentTimeMillis()
      val stop_ns = System.nanoTime()
      val duration = stop - lastStart
      val duration_ns = stop_ns - lastStart_ns
      steps = steps :+ ExecutionReportStep(currentTask, lastStart, stop, duration, duration_ns, ctx) :+ ExecutionReportStep("request-failure", stop, stop, 0L, 0L, Json.obj("message" -> message, "error" -> JsonHelpers.errToJson(error)))
      lastStart = stop
      lastStart_ns = stop_ns
      gduration = stop - creation.getMillis
      gduration_ns = stop_ns - start_ns
      termination = new DateTime(stop)
    }
    this
  }

  def markSuccess(): ExecutionReport = {
    if (reporting) {
      state = ExecutionReportState.Successful
      val stop = System.currentTimeMillis()
      val stop_ns = System.nanoTime()
      val duration = stop - lastStart
      val duration_ns = stop_ns - lastStart_ns
      steps = steps :+ ExecutionReportStep(currentTask, lastStart, stop, duration, duration_ns, ctx) :+ ExecutionReportStep(s"request-success", stop, stop, 0L, 0L)
      lastStart = stop
      lastStart_ns = stop_ns
      gduration = stop - creation.getMillis
      gduration_ns = stop_ns - start_ns
      termination = new DateTime(stop)
    }
    this
  }

  def markDoneAndStart(task: String, previousCtx: Option[JsValue] = None): ExecutionReport = {
    if (reporting) {
      state = ExecutionReportState.Running
      val stop = System.currentTimeMillis()
      val stop_ns = System.nanoTime()
      val duration = stop - lastStart
      val duration_ns = stop_ns - lastStart_ns
      steps = steps :+ ExecutionReportStep(currentTask, lastStart, stop, duration, duration_ns, previousCtx.getOrElse(ctx))
      lastStart = stop
      lastStart_ns = stop_ns
      currentTask = task
      ctx = JsNull
    }
    this
  }

  def start(task: String, context: JsValue = JsNull): ExecutionReport = {
    if (reporting) {
      state = ExecutionReportState.Running
      lastStart = System.currentTimeMillis()
      lastStart_ns = System.nanoTime()
      currentTask = task
      ctx = context
    }
    this
  }
}