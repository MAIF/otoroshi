package functional

import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import otoroshi.events.impl.{ElasticClusterInfo, ElasticVersion, ElasticWritesAnalytics}
import otoroshi.models.ElasticAnalyticsConfig
import play.api.libs.json.Json

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

    "not add _type for a confirmed opensearch cluster" in {
      val cfg = config("http://os-unit-confirmed:9200")
      ElasticWritesAnalytics.shouldAddLegacyType(
        cfg,
        versionConfirmed = true,
        ElasticVersion.OpenSearch("2.19.2")
      ) mustBe false
    }

    "not add _type when version is explicitly configured as an opensearch one" in {
      val cfg = config("http://os-unit-explicit:9200", version = Some("opensearch-2.19.2"))
      ElasticWritesAnalytics.shouldAddLegacyType(
        cfg,
        versionConfirmed = false,
        ElasticVersion.OpenSearch("opensearch-2.19.2")
      ) mustBe false
    }

    "let an explicitly configured version win over a previously detected one" in {
      val cfg = config("http://os-unit-override:9200", version = Some("7.10.2"))
      ElasticWritesAnalytics.shouldAddLegacyType(
        cfg,
        versionConfirmed = true,
        ElasticVersion.UnderSeven("2.19.2")
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

    "let a new detection fix a previously wrong one" in {
      val cfg = config("http://es-redetect:9200")
      ElasticWritesAnalytics.initialized(cfg, ElasticVersion.UnderSeven("2.19.2"))
      ElasticWritesAnalytics.initialized(cfg, ElasticVersion.OpenSearch("2.19.2"))
      ElasticWritesAnalytics.isInitialized(cfg)._2 mustBe ElasticVersion.OpenSearch("2.19.2")
    }

    "not share a cache entry between two different configured versions" in {
      val cfg = config("http://es-key:9200")
      ElasticWritesAnalytics.initialized(cfg, ElasticVersion.UnderSeven("2.19.2"))
      val fixed = config("http://es-key:9200", version = Some("opensearch-2.19.2"))
      ElasticWritesAnalytics.isInitialized(fixed) mustBe ((false, ElasticVersion.OpenSearch("opensearch-2.19.2")))
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

  "ElasticVersion.parse" should {

    "classify a cluster advertising the opensearch distribution" in {
      ElasticVersion.parse("2.19.2", Some("opensearch")) mustBe ElasticVersion.OpenSearch("2.19.2")
      ElasticVersion.parse("1.3.0", Some("opensearch")) mustBe ElasticVersion.OpenSearch("1.3.0")
      ElasticVersion.parse("3.0.0", Some("opensearch")) mustBe ElasticVersion.OpenSearch("3.0.0")
    }

    "classify a version manually configured with the opensearch distribution" in {
      ElasticVersion.parse("opensearch-2.19.2") mustBe ElasticVersion.OpenSearch("opensearch-2.19.2")
      ElasticVersion.parse("OpenSearch:2.19.2") mustBe ElasticVersion.OpenSearch("OpenSearch:2.19.2")
      ElasticVersion.parse("opensearch") mustBe ElasticVersion.OpenSearch("opensearch")
    }

    "keep classifying elasticsearch versions by thresholds" in {
      ElasticVersion.parse("6.8.1") mustBe ElasticVersion.UnderSeven("6.8.1")
      ElasticVersion.parse("7.10.2", Some("elasticsearch")) mustBe ElasticVersion.AboveSevenEight("7.10.2")
      ElasticVersion.parse("8.15.0") mustBe ElasticVersion.AboveEightFifteen("8.15.0")
    }

    "never handle an opensearch cluster like a pre-7 elasticsearch one" in {
      ElasticVersion.parse("2.19.2", Some("opensearch")).underSeven mustBe false
      ElasticVersion.parse("2.19.2", Some("opensearch")).aboveOrEqualsEight mustBe false
      ElasticVersion.parse("2.19.2", None).underSeven mustBe true
    }
  }

  "ElasticClusterInfo" should {

    "expose a config version carrying the distribution for opensearch" in {
      ElasticClusterInfo("2.19.2", Some("opensearch")).configVersion mustBe "opensearch-2.19.2"
      ElasticClusterInfo("8.15.0", Some("elasticsearch")).configVersion mustBe "8.15.0"
      ElasticClusterInfo("8.15.0", None).configVersion mustBe "8.15.0"
    }

    "not prefix a config version that already carries the distribution" in {
      ElasticClusterInfo("opensearch-2.19.2", Some("opensearch")).configVersion mustBe "opensearch-2.19.2"
    }
  }

  "ElasticClusterInfo.fromRootResponse" should {

    "detect an opensearch cluster from its distribution" in {
      val root = Json.parse("""{
        |  "name": "opensearch-node1",
        |  "cluster_name": "opensearch",
        |  "version": {
        |    "distribution": "opensearch",
        |    "number": "2.19.2",
        |    "build_type": "tar",
        |    "lucene_version": "9.12.1",
        |    "minimum_wire_compatibility_version": "7.10.0",
        |    "minimum_index_compatibility_version": "7.0.0"
        |  },
        |  "tagline": "The OpenSearch Project: https://opensearch.org/"
        |}""".stripMargin)
      val info = ElasticClusterInfo.fromRootResponse(root, None)
      info.version mustBe "2.19.2"
      info.isOpenSearch mustBe true
      info.elasticVersion mustBe ElasticVersion.OpenSearch("2.19.2")
      info.configVersion mustBe "opensearch-2.19.2"
    }

    "fall back on the tagline when the distribution is not advertised" in {
      val root = Json.parse("""{
        |  "version": { "number": "1.3.20" },
        |  "tagline": "The OpenSearch Project: https://opensearch.org/"
        |}""".stripMargin)
      val info = ElasticClusterInfo.fromRootResponse(root, None)
      info.isOpenSearch mustBe true
      info.elasticVersion mustBe ElasticVersion.OpenSearch("1.3.20")
    }

    "detect a regular elasticsearch cluster" in {
      val root = Json.parse("""{
        |  "name": "es-node1",
        |  "cluster_name": "elasticsearch",
        |  "version": {
        |    "number": "8.15.0",
        |    "build_flavor": "default",
        |    "lucene_version": "9.11.1"
        |  },
        |  "tagline": "You Know, for Search"
        |}""".stripMargin)
      val info = ElasticClusterInfo.fromRootResponse(root, None)
      info.isOpenSearch mustBe false
      info.elasticVersion mustBe ElasticVersion.AboveEightFifteen("8.15.0")
      info.configVersion mustBe "8.15.0"
    }

    "use the configured version when the cluster does not advertise one" in {
      val info = ElasticClusterInfo.fromRootResponse(Json.obj(), Some("7.10.2"))
      info.version mustBe "7.10.2"
      info.elasticVersion mustBe ElasticVersion.AboveSevenEight("7.10.2")
    }
  }
}
