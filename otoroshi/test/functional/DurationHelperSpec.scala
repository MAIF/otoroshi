package functional

import otoroshi.next.proxy.DurationHelper

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}

// the durations of the debug headers are formatted from every thread handling requests
class DurationHelperSpec extends org.scalatest.wordspec.AnyWordSpec with org.scalatest.matchers.must.Matchers {

  "DurationHelper" should {

    "format nanoseconds as milliseconds and microseconds, rounded up to three decimals" in {
      DurationHelper.formatMillis(1500000L) mustBe "1.5"
      DurationHelper.formatMillis(1234567L) mustBe "1.235"
      DurationHelper.formatMicros(1500L) mustBe "1.5"
    }

    "format the same values the same way from many threads at once" in {
      val values   = (1 to 2000).map(i => i.toLong * 1234567L + i)
      val expected = values.map(v => (v, DurationHelper.formatMillis(v), DurationHelper.formatMicros(v)))
      val runs     = (0 until 8).map { _ =>
        Future {
          (0 until 20).flatMap { _ =>
            expected.filterNot { case (v, millis, micros) =>
              DurationHelper.formatMillis(v) == millis && DurationHelper.formatMicros(v) == micros
            }
          }
        }
      }
      Await.result(Future.sequence(runs), 60.seconds).flatten mustBe empty
    }
  }
}
