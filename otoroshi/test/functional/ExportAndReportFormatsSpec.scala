package functional

import org.joda.time.{DateTime, DateTimeUtils}
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import otoroshi.models.{GlobalConfig, OtoroshiExport}
import otoroshi.utils.syntax.implicits.*

// the date of an export is written on a 24 hours clock, the averages of the anonymous report are not rounded down
class ExportAndReportFormatsSpec extends AnyWordSpec with Matchers {

  "An export" should {
    "write its date on a 24 hours clock" in {
      DateTimeUtils.setCurrentMillisFixed(new DateTime(2026, 10, 16, 15, 4, 5).getMillis)
      try {
        (OtoroshiExport(GlobalConfig(), extensions = Map.empty).json \ "date").as[String] mustBe "2026-10-16 15:04:05"
      } finally {
        DateTimeUtils.setCurrentMillisSystem()
      }
    }
  }

  "An average of the anonymous report" should {
    "not be rounded down" in {
      Seq(1, 2).avgBy(identity) mustBe 1.5
      Seq.empty[Int].avgBy(identity) mustBe 0.0
    }
  }
}
