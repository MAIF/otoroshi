package functional

import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import otoroshi.events.impl.{ElasticVersion, ElasticWritesAnalytics}
import otoroshi.models.ElasticAnalyticsConfig

class ElasticWritesAnalyticsSpec extends AnyWordSpec with Matchers {

  private def config(uri: String, version: Option[String] = None): ElasticAnalyticsConfig =
    ElasticAnalyticsConfig(
      uris = Seq(uri),
      index = Some("otoroshi-events"),
      `type` = Some("event"),
      version = version
    )

  "ElasticWritesAnalytics.shouldAddLegacyType" should {

    "not add _type when version is the uninitialized fallback and no version is configured" in {
      val cfg = config("http://es-unit-fallback:9200")
      ElasticWritesAnalytics.shouldAddLegacyType(cfg, versionConfirmed = false, ElasticVersion.default) mustBe false
    }

    "not add _type for a confirmed modern cluster" in {
      val cfg = config("http://es-unit-confirmed-modern:9200")
      ElasticWritesAnalytics.shouldAddLegacyType(
        cfg,
        versionConfirmed = true,
        ElasticVersion.AboveEight("8.5.0")
      ) mustBe false
    }

    "not add _type for an unconfirmed modern cluster" in {
      val cfg = config("http://es-unit-unconfirmed-modern:9200")
      ElasticWritesAnalytics.shouldAddLegacyType(
        cfg,
        versionConfirmed = false,
        ElasticVersion.AboveEight("8.5.0")
      ) mustBe false
    }

    "add _type for a confirmed pre-7 cluster" in {
      val cfg = config("http://es-unit-confirmed-legacy:9200")
      ElasticWritesAnalytics.shouldAddLegacyType(
        cfg,
        versionConfirmed = true,
        ElasticVersion.UnderSeven("6.8.0")
      ) mustBe true
    }

    "add _type when version is explicitly configured as pre-7" in {
      val cfg = config("http://es-unit-explicit-legacy:9200", version = Some("6.8.0"))
      ElasticWritesAnalytics.shouldAddLegacyType(
        cfg,
        versionConfirmed = false,
        ElasticVersion.UnderSeven("6.8.0")
      ) mustBe true
    }

    "not add _type when version is explicitly configured as 8.x" in {
      val cfg = config("http://es-unit-explicit-modern:9200", version = Some("8.0.0"))
      ElasticWritesAnalytics.shouldAddLegacyType(
        cfg,
        versionConfirmed = false,
        ElasticVersion.AboveEight("8.0.0")
      ) mustBe false
    }
  }

  "ElasticWritesAnalytics version cache" should {

    "report not-confirmed and fall back to the default version on cache miss" in {
      val cfg                  = config("http://es-cache-miss:9200")
      val (confirmed, version) = ElasticWritesAnalytics.isInitialized(cfg)
      confirmed mustBe false
      version mustBe ElasticVersion.default
    }

    "not cache the unknown fallback sentinel" in {
      val cfg = config("http://es-sentinel:9200")
      ElasticWritesAnalytics.initialized(cfg, ElasticVersion.default)
      ElasticWritesAnalytics.isInitialized(cfg)._1 mustBe false
    }

    "cache a genuine detected version" in {
      val cfg = config("http://es-genuine:9200")
      ElasticWritesAnalytics.initialized(cfg, ElasticVersion.AboveEight("8.5.0"))
      val (confirmed, version) = ElasticWritesAnalytics.isInitialized(cfg)
      confirmed mustBe true
      version mustBe ElasticVersion.AboveEight("8.5.0")
    }
  }

  "ElasticWritesAnalytics.isInitialized" should {

    "classify an explicitly configured version by thresholds" in {
      ElasticWritesAnalytics.isInitialized(config("http://es-v6:9200", Some("6.8.1")))._2 mustBe
        ElasticVersion.UnderSeven("6.8.1")
      ElasticWritesAnalytics.isInitialized(config("http://es-v7:9200", Some("7.1.0")))._2 mustBe
        ElasticVersion.AboveSeven("7.1.0")
      ElasticWritesAnalytics.isInitialized(config("http://es-v78:9200", Some("7.8.0")))._2 mustBe
        ElasticVersion.AboveSevenEight("7.8.0")
      ElasticWritesAnalytics.isInitialized(config("http://es-v8:9200", Some("8.0.0")))._2 mustBe
        ElasticVersion.AboveEight("8.0.0")
      ElasticWritesAnalytics.isInitialized(config("http://es-v89:9200", Some("8.9.0")))._2 mustBe
        ElasticVersion.AboveEightNine("8.9.0")
      ElasticWritesAnalytics.isInitialized(config("http://es-v815:9200", Some("8.15.0")))._2 mustBe
        ElasticVersion.AboveEightFifteen("8.15.0")
    }
  }
}
