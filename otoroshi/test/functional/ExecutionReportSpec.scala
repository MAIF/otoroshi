package functional

import otoroshi.next.proxy.NgExecutionReport
import play.api.libs.json.{JsNull, JsValue, Json}

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
