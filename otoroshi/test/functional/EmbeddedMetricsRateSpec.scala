package functional

import otoroshi.storage.stores.KvServiceDescriptorDataStore

// the rate of the embedded metrics over the samples of a list: one sample per second and per instance, an amount and
// the time it was written, or one timestamp per call as the calls were written before
class EmbeddedMetricsRateSpec extends org.scalatest.wordspec.AnyWordSpec with org.scalatest.matchers.must.Matchers {

  import KvServiceDescriptorDataStore.ratePerSecond

  "KvServiceDescriptorDataStore.ratePerSecond" should {

    "be zero without samples" in {
      ratePerSecond(Seq.empty) mustBe 0.0
    }

    "count what was written after the oldest sample, over the time the samples span" in {
      // newest first, as lpush leaves them
      ratePerSecond(Seq("30:3000", "20:2000", "10:1000")) mustBe 25.0
    }

    "read the samples written one per call" in {
      ratePerSecond(Seq("3000", "2500", "2000", "1000")) mustBe 1.5
    }

    "take a single sample as the amount of a second" in {
      ratePerSecond(Seq("12:5000")) mustBe 12.0
      ratePerSecond(Seq("5000")) mustBe 1.0
    }
  }
}
