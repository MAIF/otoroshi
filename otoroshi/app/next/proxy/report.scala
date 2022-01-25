package otoroshi.next.proxy

import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.next.utils.JsonHelpers
import play.api.libs.json._

import java.util.concurrent.TimeUnit

case class NgReportPluginSequenceItem(plugin: String, name: String, start: Long, start_ns: Long, stop: Long, stop_ns: Long, in: JsValue, out: JsValue) {
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

case class NgReportPluginSequence(size: Int, kind: String, start: Long, start_ns: Long, stop: Long, stop_ns: Long, plugins: Seq[NgReportPluginSequenceItem]) {
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
  def stopSequence(): NgReportPluginSequence = {
    copy(stop = System.currentTimeMillis(), stop_ns = System.nanoTime())
  }
}

object NgExecutionReport {
  def apply(id: String, reporting: Boolean): NgExecutionReport = new NgExecutionReport(id, DateTime.now(), reporting)
}

sealed trait NgExecutionReportState {
  def name: String
  def json: JsValue = JsString(name)
}

object NgExecutionReportState {
  case object Created    extends NgExecutionReportState { def name: String = "Created"    }
  case object Running    extends NgExecutionReportState { def name: String = "Running"    }
  case object Successful extends NgExecutionReportState { def name: String = "Successful" }
  case object Failed     extends NgExecutionReportState { def name: String = "Failed"     }
}

case class NgExecutionReportStep(task: String, start: Long, stop: Long, duration: Long, duration_ns: Long, ctx: JsValue = JsNull) {
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
  def markDuration()(implicit env: Env): Unit = {
    env.metrics.timerUpdate("ng-report-request-step-" + task, duration, TimeUnit.NANOSECONDS)
  }
}

class NgExecutionReport(val id: String, val creation: DateTime, val reporting: Boolean) {

  // IDEA: move into one big case class with mutable ref if issues are declared ?
  // I know mutability is bad etc but here, i know for sure that concurrency is not an issue
  var currentTask: String = ""
  var lastStart: Long = creation.toDate.getTime
  val start_ns: Long = System.nanoTime()
  var lastStart_ns: Long = start_ns
  var state: NgExecutionReportState = NgExecutionReportState.Created
  var steps: Seq[NgExecutionReportStep] = Seq.empty
  var gduration = -1L
  var gduration_ns = -1L
  var overheadIn = -1L
  var overheadOut = -1L
  var overheadOutStart = creation.toDate.getTime
  var termination = creation
  var ctx: JsValue = JsNull

  def markDurations()(implicit env: Env): Unit = {
    env.metrics.timerUpdate("ng-report-request-duration", gduration_ns, TimeUnit.NANOSECONDS)
    env.metrics.timerUpdate("ng-report-request-overhead", overheadIn + overheadOut, TimeUnit.MILLISECONDS)
    env.metrics.timerUpdate("ng-report-request-overhead-in", overheadIn, TimeUnit.MILLISECONDS)
    env.metrics.timerUpdate("ng-report-request-overhead-out", overheadOut, TimeUnit.MILLISECONDS)
    steps.foreach(_.markDuration())
  }

  def getStep(task: String): Option[NgExecutionReportStep] = {
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

  def markOverheadIn(): NgExecutionReport = {
    if (reporting) {
      overheadIn = System.currentTimeMillis() - creation.getMillis
    }
    this
  }

  def startOverheadOut(): NgExecutionReport = {
    if (reporting) {
      overheadOutStart = System.currentTimeMillis()
    }
    this
  }

  def markOverheadOut(): NgExecutionReport = {
    if (reporting) {
      overheadOut = System.currentTimeMillis() - overheadOutStart
    }
    this
  }

  def setContext(context: JsValue): NgExecutionReport = {
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

  def markFailure(message: String): NgExecutionReport = {
    if (reporting) {
      state = NgExecutionReportState.Failed
      val stop = System.currentTimeMillis()
      val stop_ns = System.nanoTime()
      val duration = stop - lastStart
      val duration_ns = stop_ns - lastStart_ns
      steps = steps :+ NgExecutionReportStep(currentTask, lastStart, stop, duration, duration_ns, ctx) :+ NgExecutionReportStep("request-failure", stop, stop, 0L, 0L, Json.obj("message" -> message))
      lastStart = stop
      lastStart_ns = stop_ns
      gduration = stop - creation.getMillis
      gduration_ns = stop_ns - start_ns
      termination = new DateTime(stop)
    }
    this
  }

  def markFailure(message: String, error: Throwable): NgExecutionReport = {
    if (reporting) {
      state = NgExecutionReportState.Failed
      val stop = System.currentTimeMillis()
      val stop_ns = System.nanoTime()
      val duration = stop - lastStart
      val duration_ns = stop_ns - lastStart_ns
      steps = steps :+ NgExecutionReportStep(currentTask, lastStart, stop, duration, duration_ns, ctx) :+ NgExecutionReportStep("request-failure", stop, stop, 0L, 0L, Json.obj("message" -> message, "error" -> JsonHelpers.errToJson(error)))
      lastStart = stop
      lastStart_ns = stop_ns
      gduration = stop - creation.getMillis
      gduration_ns = stop_ns - start_ns
      termination = new DateTime(stop)
    }
    this
  }

  def markSuccess(): NgExecutionReport = {
    if (reporting) {
      state = NgExecutionReportState.Successful
      val stop = System.currentTimeMillis()
      val stop_ns = System.nanoTime()
      val duration = stop - lastStart
      val duration_ns = stop_ns - lastStart_ns
      steps = steps :+ NgExecutionReportStep(currentTask, lastStart, stop, duration, duration_ns, ctx) :+ NgExecutionReportStep(s"request-success", stop, stop, 0L, 0L)
      lastStart = stop
      lastStart_ns = stop_ns
      gduration = stop - creation.getMillis
      gduration_ns = stop_ns - start_ns
      termination = new DateTime(stop)
    }
    this
  }

  def markDoneAndStart(task: String, previousCtx: Option[JsValue] = None): NgExecutionReport = {
    if (reporting) {
      state = NgExecutionReportState.Running
      val stop = System.currentTimeMillis()
      val stop_ns = System.nanoTime()
      val duration = stop - lastStart
      val duration_ns = stop_ns - lastStart_ns
      steps = steps :+ NgExecutionReportStep(currentTask, lastStart, stop, duration, duration_ns, previousCtx.getOrElse(ctx))
      lastStart = stop
      lastStart_ns = stop_ns
      currentTask = task
      ctx = JsNull
    }
    this
  }

  def start(task: String, context: JsValue = JsNull): NgExecutionReport = {
    if (reporting) {
      state = NgExecutionReportState.Running
      lastStart = System.currentTimeMillis()
      lastStart_ns = System.nanoTime()
      currentTask = task
      ctx = context
    }
    this
  }
}