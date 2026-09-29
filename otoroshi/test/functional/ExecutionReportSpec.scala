package functional

import otoroshi.next.proxy.{NgExecutionReport, NgReportPluginSequence, NgReportPluginSequenceItem}
import play.api.libs.json.{JsDefined, JsNull, JsValue, Json}

// the execution report of a request: the contexts of its steps are only built when it reports, and the time spent
// building one stays in the step it describes
class ExecutionReportSpec extends org.scalatest.wordspec.AnyWordSpec with org.scalatest.matchers.must.Matchers {

  "An execution report" should {

    "not build the context of a step when it does not report" in {
      val report = NgExecutionReport("report-off", reporting = false)
      var built  = 0
      def context(): JsValue = { built += 1; Json.obj("built" -> true) }
      report.start("start", context())
      report.setContext(context())
      report.markDoneAndStart("next", Some(context()))
      report.markSuccess()
      built mustBe 0
      report.steps mustBe empty
    }

    "keep the context of each step when it reports" in {
      val report = NgExecutionReport("report-on", reporting = true)
      report.start("first", Json.obj("at" -> "start"))
      report.markDoneAndStart("second", Some(Json.obj("at" -> "done")))
      report.setContext(Json.obj("at" -> "set"))
      report.markDoneAndStart("third")
      report.markSuccess()
      report.steps.map(step => (step.task, step.ctx)) mustBe Seq(
        ("first", Json.obj("at" -> "done")),
        ("second", Json.obj("at" -> "set")),
        ("third", JsNull),
        ("request-success", JsNull)
      )
    }

    "keep the plugin sequence of a step typed, and render it as the context of the step" in {
      val report   = NgExecutionReport("report-sequence", reporting = true)
      val sequence = NgReportPluginSequence(
        size = 1,
        kind = "request-transformer-plugins",
        start = 1000L,
        start_ns = 10L,
        stop = 1002L,
        stop_ns = 2000010L,
        plugins = Seq(NgReportPluginSequenceItem("cp:functional.Probe", "Probe", 1000L, 10L, 1001L, 1000010L, JsNull, JsNull))
      )
      report.start("transform-request")
      report.setSequence(sequence)
      report.markSuccess()
      val step     = report.steps.head
      step.sequence mustBe Some(sequence)
      (step.json \ "ctx") mustBe JsDefined(sequence.json)
      (sequence.json \ "plugins" \ 0 \ "duration_ns").as[Long] mustBe 1000000L
    }

    "only build the contexts of the steps for a report that something reads, and keep the sequences anyway" in {
      val report   = NgExecutionReport("report-unread", reporting = true)
      report.keepContexts = false
      var built    = 0
      def context(): JsValue = { built += 1; Json.obj("built" -> true) }
      val sequence = NgReportPluginSequence(1, "access-validator-plugins", 0L, 0L, 0L, 0L, Seq.empty)
      report.start("start", context())
      report.setContext(context())
      report.markDoneAndStart("phase", Some(context()))
      report.setSequence(sequence)
      report.markSuccess()
      built mustBe 0
      report.steps.map(step => (step.task, step.ctx, step.sequence)) mustBe Seq(
        ("start", JsNull, None),
        ("phase", JsNull, Some(sequence)),
        ("request-success", JsNull, None)
      )
    }

    "let the last of a context and a sequence set in a step describe it" in {
      val report   = NgExecutionReport("report-last", reporting = true)
      val sequence = NgReportPluginSequence(1, "pre-route-plugins", 0L, 0L, 0L, 0L, Seq.empty)
      report.start("first")
      report.setSequence(sequence)
      report.setContext(Json.obj("then" -> "context"))
      report.markDoneAndStart("second")
      report.setContext(Json.obj("then" -> "sequence"))
      report.setSequence(sequence)
      report.markDoneAndStart("third")
      report.setSequence(sequence)
      report.markDoneAndStart("fourth", Some(Json.obj("given" -> "on close")))
      report.markSuccess()
      report.steps.take(3).map(step => (step.task, step.ctx, step.sequence)) mustBe Seq(
        ("first", Json.obj("then" -> "context"), None),
        ("second", JsNull, Some(sequence)),
        ("third", Json.obj("given" -> "on close"), None)
      )
    }

    "build the names of the timers of its steps and of its plugins once" in {
      NgExecutionReport.stepTimerName("call-backend") mustBe "ng-report-request-step-call-backend"
      NgExecutionReport.pluginTimerName("transform-request", "cp:functional.Probe") mustBe
      "ng-report-transform-request-cp:functional.Probe"
      (NgExecutionReport.stepTimerName("call-backend") eq NgExecutionReport.stepTimerName("call-backend")) mustBe true
      (NgExecutionReport.pluginTimerName("transform-request", "cp:functional.Probe") eq
      NgExecutionReport.pluginTimerName("transform-request", "cp:functional.Probe")) mustBe true
    }

    "count the time spent building a context in the step it describes" in {
      val report = NgExecutionReport("report-timing", reporting = true)
      report.start("described")
      report.markDoneAndStart("next", Some { Thread.sleep(50); Json.obj("slow" -> true) })
      report.markSuccess()
      val durations = report.steps.map(step => step.task -> step.duration_ns / 1000000L).toMap
      durations("described") must be >= 50L
      durations("next") must be < 50L
    }
  }
}
