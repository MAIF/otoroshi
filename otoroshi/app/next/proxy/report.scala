package otoroshi.next.proxy

import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.events.AnalyticEvent
import otoroshi.next.models.NgRoute
import otoroshi.next.utils.JsonHelpers
import otoroshi.security.IdGenerator
import play.api.libs.json._

import java.util.concurrent.TimeUnit
import scala.concurrent.duration._

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
    "duration" -> (stop_ns - start_ns).nanos.toMillis,
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

case class NgExecutionReportStep(task: String, start: Long, stop: Long, duration_ns: Long, ctx: JsValue = JsNull) {
  def json: JsValue = Json.obj(
    "task" -> task,
    "start" -> start,
    "start_fmt" -> new DateTime(start).toString(),
    "stop" -> stop,
    "stop_fmt" -> new DateTime(stop).toString(),
    "duration" -> duration_ns.nano.toMillis,
    "duration_ns" -> duration_ns,
    "ctx" -> ctx
  )
  def duration: Long = duration_ns.nanos.toMillis
  def markDuration()(implicit env: Env): Unit = {
    env.metrics.timerUpdate("ng-report-request-step-" + task, duration_ns, TimeUnit.NANOSECONDS)
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
  // var gduration = -1L
  var gduration_ns = -1L
  var overheadIn_ns = -1L
  var overheadOut_ns = -1L
  var overheadOutStart_ns = start_ns // creation.toDate.getTime
  var termination = creation
  var ctx: JsValue = JsNull

  def markDurations()(implicit env: Env): Unit = {
    env.metrics.timerUpdate("ng-report-request-duration", gduration_ns, TimeUnit.NANOSECONDS)
    env.metrics.timerUpdate("ng-report-request-overhead", overheadIn_ns + overheadOut_ns, TimeUnit.NANOSECONDS)
    env.metrics.timerUpdate("ng-report-request-overhead-in", overheadIn_ns, TimeUnit.NANOSECONDS)
    env.metrics.timerUpdate("ng-report-request-overhead-out", overheadOut_ns, TimeUnit.NANOSECONDS)
    steps.foreach(_.markDuration())
  }

  def getStep(task: String): Option[NgExecutionReportStep] = {
    steps.find(_.task == task )
  }

  def json: JsValue = Json.obj(
    "id" -> id,
    "creation" -> creation.toString(),
    "termination" -> termination.toString(),
    "duration" -> gduration_ns.nano.toMillis,
    "duration_ns" -> gduration_ns,
    "overhead" -> (overheadIn_ns + overheadOut_ns).nanos.toMillis,
    "overhead_ns" -> (overheadIn_ns + overheadOut_ns),
    "overhead_in" -> overheadIn_ns.nanos.toMillis,
    "overhead_in_ns" -> overheadIn_ns,
    "overhead_out" -> overheadOut_ns.nanos.toMillis,
    "overhead_out_ns" -> overheadOut_ns,
    "state" -> state.json,
    "steps" -> JsArray(steps.map(_.json))
  )

  def markOverheadIn(): NgExecutionReport = {
    if (reporting) {
      // overheadIn = System.currentTimeMillis() - creation.getMillis
      overheadIn_ns = System.nanoTime() - start_ns
    }
    this
  }

  def startOverheadOut(): NgExecutionReport = {
    if (reporting) {
      // overheadOutStart = System.currentTimeMillis()
      overheadOutStart_ns = System.nanoTime()
    }
    this
  }

  def markOverheadOut(): NgExecutionReport = {
    if (reporting) {
      // overheadOut = System.currentTimeMillis() - overheadOutStart
      overheadOut_ns = System.nanoTime() - overheadOutStart_ns
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

  def getOverheadInNsNow(): Long = {
    overheadIn_ns
  }

  def getOverheadOutNsNow(): Long = {
    overheadOut_ns
  }

  def getOverheadNsNow(): Long = {
    getOverheadInNsNow() + getOverheadOutNsNow()
  }

  /////////////////////////////////////////////////////
  // compat functions                                //
  /////////////////////////////////////////////////////
  def getOverheadInNow(): Long = overheadIn_ns.nanos.toMillis
  def getOverheadOutNow(): Long = overheadOut_ns.nanos.toMillis
  def getOverheadNow(): Long =(overheadIn_ns + overheadOut_ns).nanos.toMillis
  def gduration: Long = gduration_ns.nanos.toMillis
  def overheadIn: Long = overheadIn_ns.nanos.toMillis
  def overheadOut: Long = overheadOut_ns.nanos.toMillis
  /////////////////////////////////////////////////////

  def markFailure(message: String): NgExecutionReport = {
    if (reporting) {
      state = NgExecutionReportState.Failed
      val stop = System.currentTimeMillis()
      val stop_ns = System.nanoTime()
      val duration_ns = stop_ns - lastStart_ns
      steps = steps :+ NgExecutionReportStep(currentTask, lastStart, stop, duration_ns, ctx) :+ NgExecutionReportStep("request-failure", stop, stop, 0L, Json.obj("message" -> message))
      lastStart = stop
      lastStart_ns = stop_ns
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
      val duration_ns = stop_ns - lastStart_ns
      steps = steps :+ NgExecutionReportStep(currentTask, lastStart, stop, duration_ns, ctx) :+ NgExecutionReportStep("request-failure", stop, stop, 0L, Json.obj("message" -> message, "error" -> JsonHelpers.errToJson(error)))
      lastStart = stop
      lastStart_ns = stop_ns
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
      val duration_ns = stop_ns - lastStart_ns
      steps = steps :+ NgExecutionReportStep(currentTask, lastStart, stop, duration_ns, ctx) :+ NgExecutionReportStep(s"request-success", stop, stop, 0L)
      lastStart = stop
      lastStart_ns = stop_ns
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
      val duration_ns = stop_ns - lastStart_ns
      steps = steps :+ NgExecutionReportStep(currentTask, lastStart, stop, duration_ns, previousCtx.getOrElse(ctx))
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

case class RequestFlowReport(report: NgExecutionReport, route: NgRoute) extends AnalyticEvent {

  override def `@service`: String   = route.name
  override def `@serviceId`: String = route.id
  def `@id`: String = IdGenerator.uuid
  def `@timestamp`: org.joda.time.DateTime = timestamp
  def `@type`: String = "RequestFlowReport"
  override def fromOrigin: Option[String]    = None
  override def fromUserAgent: Option[String] = None

  val timestamp = DateTime.now()

  override def toJson(implicit env: Env): JsValue =
    Json.obj(
      "@id"          -> `@id`,
      "@timestamp"   -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(timestamp),
      "@type"        -> "RequestFlowReport",
      "@product"     -> "otoroshi",
      "@serviceId"   -> `@serviceId`,
      "@service"     -> `@service`,
      "@env"         -> "prod",
      "route"        -> route.json,
      "report"       -> report.json
    )
}