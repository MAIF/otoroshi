package entities

import otoroshi.models.EntityLocation
import otoroshi.next.catalogs.*
import otoroshi.script.{JobId, JobInstantiation, JobKind}
import play.api.libs.json.*

import scala.concurrent.duration.*

class RemoteCatalogEntityFormatSpec extends EntityFormatSpec {

  private val defaultSchedulingJson = Json.obj(
    "enabled"         -> false,
    "kind"            -> "ScheduledEvery",
    "instantiation"   -> "OneInstancePerOtoroshiInstance",
    "initial_delay"   -> JsNull,
    "interval"        -> JsNull,
    "cron_expression" -> JsNull,
    "deploy_args"     -> Json.obj()
  )

  private val fullScheduling = RemoteCatalogScheduling(
    enabled = true,
    kind = JobKind.Cron,
    instantiation = JobInstantiation.OneInstancePerOtoroshiCluster,
    initialDelay = Some(10.seconds),
    interval = Some(1.minute),
    cronExpression = Some("0 */5 * * * ?"),
    deployArgs = Json.obj("dry_run" -> false, "env" -> "prod")
  )

  private val minimal = RemoteCatalog(
    location = EntityLocation(),
    id = "remote-catalog-1",
    name = "Catalog 1",
    description = "",
    tags = Seq.empty,
    metadata = Map.empty,
    enabled = true,
    sourceKind = "http",
    sourceConfig = Json.obj(),
    scheduling = RemoteCatalogScheduling.default,
    testDeployArgs = Json.obj()
  )

  private val full = RemoteCatalog(
    location = customLocation,
    id = "remote-catalog-1",
    name = "Catalog 1",
    description = "the catalog of team 1",
    tags = Seq("gitops", "prod"),
    metadata = Map("owner" -> "ops", "repo" -> "infra"),
    enabled = false,
    sourceKind = "github",
    sourceConfig = Json.obj(
      "repo"    -> "https://github.com/acme/infra",
      "branch"  -> "main",
      "path"    -> "/otoroshi",
      "token"   -> "${vault://local/github-token}",
      "headers" -> Json.obj("x-foo" -> "bar"),
      "nested"  -> Json.obj("list" -> Json.arr(1, "two", true, JsNull))
    ),
    scheduling = fullScheduling,
    testDeployArgs = Json.obj("dry_run" -> true)
  )

  /** the json of a catalog with the given fields, the rest taken from the minimal catalog */
  private def minimalJson(fields: (String, Json.JsValueWrapper)*): JsObject =
    written(RemoteCatalog.format, minimal) ++ Json.obj(fields*)

  "RemoteCatalog" should {

    val format = RemoteCatalog.format

    "write a minimal catalog" in {
      format.writes(minimal) mustBe Json.obj(
        "_loc"             -> defaultLocationJson,
        "id"               -> "remote-catalog-1",
        "name"             -> "Catalog 1",
        "description"      -> "",
        "metadata"         -> Json.obj(),
        "tags"             -> Json.arr(),
        "enabled"          -> true,
        "source_kind"      -> "http",
        "source_config"    -> Json.obj(),
        "scheduling"       -> defaultSchedulingJson,
        "test_deploy_args" -> Json.obj()
      )
    }

    "write the same keys whatever the fields hold" in {
      writtenKeys(format, full) mustBe writtenKeys(format, minimal)
    }

    "write the source config and the test deploy args as they are" in {
      val json = written(format, full)
      (json \ "source_config").get mustBe full.sourceConfig
      (json \ "test_deploy_args").get mustBe Json.obj("dry_run" -> true)
    }

    "write the scheduling of a catalog" in {
      (written(format, full) \ "scheduling").get mustBe Json.obj(
        "enabled"         -> true,
        "kind"            -> "Cron",
        "instantiation"   -> "OneInstancePerOtoroshiCluster",
        "initial_delay"   -> 10000,
        "interval"        -> 60000,
        "cron_expression" -> "0 */5 * * * ?",
        "deploy_args"     -> Json.obj("dry_run" -> false, "env" -> "prod")
      )
    }

    "round trip a minimal catalog" in {
      roundTrip(format, minimal)
    }

    "round trip a catalog where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a disabled catalog" in {
      roundTrip(format, minimal.copy(enabled = false)).enabled mustBe false
    }

    "round trip a catalog for each kind of source" in {
      val kinds = Seq("file", "http", "github", "gitlab", "s3", "consulkv", "bitbucket", "bitbucketserver", "gitea", "forgejo", "codeberg", "git")
      kinds.foreach { kind =>
        roundTrip(format, full.copy(sourceKind = kind)).sourceKind mustBe kind
      }
    }

    "round trip a source kind that is not registered" in {
      roundTrip(format, minimal.copy(sourceKind = "ftp")).sourceKind mustBe "ftp"
    }

    "round trip a custom location" in {
      roundTrip(format, minimal.copy(location = customLocation)).location mustBe customLocation
    }

    "read a catalog without _loc in the default location" in {
      readOk(format, minimalJson() - "_loc").location mustBe EntityLocation()
    }

    "read a lone id and name with the defaults for the rest" in {
      readOk(format, Json.obj("id" -> "c", "name" -> "C")) mustBe RemoteCatalog(
        location = EntityLocation(),
        id = "c",
        name = "C",
        description = "--",
        tags = Seq.empty,
        metadata = Map.empty,
        enabled = true,
        sourceKind = "http",
        sourceConfig = Json.obj(),
        scheduling = RemoteCatalogScheduling.default,
        testDeployArgs = Json.obj()
      )
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a catalog without id" in {
      readError(format, minimalJson() - "id")
    }

    "not read a catalog without name" in {
      readError(format, minimalJson() - "name")
    }

    "not read an id that is not a string" in {
      readError(format, minimalJson("id" -> 1))
    }

    "read a catalog without description with a placeholder description" in {
      readOk(format, minimalJson() - "description").description mustBe "--"
    }

    "read a description that is not a string as the placeholder description" in {
      readOk(format, minimalJson("description" -> 1)).description mustBe "--"
    }

    "read a catalog without enabled flag as enabled" in {
      readOk(format, written(format, full) - "enabled").enabled mustBe true
    }

    "read an enabled flag written as a string as enabled" in {
      readOk(format, minimalJson("enabled" -> "false")).enabled mustBe true
    }

    "read a catalog without source kind as an http catalog" in {
      readOk(format, written(format, full) - "source_kind").sourceKind mustBe "http"
    }

    "read a catalog without source config with an empty one" in {
      readOk(format, written(format, full) - "source_config").sourceConfig mustBe Json.obj()
    }

    "read a source config that is not an object as an empty one" in {
      readOk(format, minimalJson("source_config" -> Json.arr("a"))).sourceConfig mustBe Json.obj()
    }

    "read a catalog without test deploy args with empty ones" in {
      readOk(format, written(format, full) - "test_deploy_args").testDeployArgs mustBe Json.obj()
    }

    "read test deploy args that are not an object as empty ones" in {
      readOk(format, minimalJson("test_deploy_args" -> "dry_run")).testDeployArgs mustBe Json.obj()
    }

    "read a catalog without scheduling with the default scheduling" in {
      readOk(format, written(format, full) - "scheduling").scheduling mustBe RemoteCatalogScheduling.default
    }

    "read a scheduling that is not an object as the default scheduling" in {
      readOk(format, minimalJson("scheduling" -> true)).scheduling mustBe RemoteCatalogScheduling.default
    }

    "read an empty scheduling object as the default scheduling" in {
      readOk(format, minimalJson("scheduling" -> Json.obj())).scheduling mustBe RemoteCatalogScheduling.default
    }

    "not read the camel case keys of the fields" in {
      val back = readOk(
        format,
        minimalJson() - "source_kind" ++ Json.obj("sourceKind" -> "github", "testDeployArgs" -> Json.obj("a" -> 1))
      )
      back.sourceKind mustBe "http"
      back.testDeployArgs mustBe Json.obj()
    }

    "read a catalog without tags nor metadata with empty ones" in {
      val back = readOk(format, written(format, full) - "tags" - "metadata")
      back.tags mustBe empty
      back.metadata mustBe empty
    }

    "ignore metadata holding a non string value" in {
      readOk(format, minimalJson("metadata" -> Json.obj("a" -> "b", "n" -> 1))).metadata mustBe Map.empty
    }

    "ignore tags given as a single string" in {
      readOk(format, minimalJson("tags" -> "gitops")).tags mustBe empty
    }

    "keep the order of the tags" in {
      roundTrip(format, minimal.copy(tags = Seq("z", "a", "m"))).tags mustBe Seq("z", "a", "m")
    }

    "ignore unknown keys" in {
      readOk(format, minimalJson("foo" -> "bar")) mustBe minimal
    }

    "expose its fields through the generic accessors" in {
      hasAccessors(full, "remote-catalog-1", "Catalog 1", "the catalog of team 1", Seq("gitops", "prod"), Map("owner" -> "ops", "repo" -> "infra"))
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
    }

    "build an enabled http template in the default location" in {
      val template = RemoteCatalog.template()
      template.id must startWith("remote-catalog_")
      template.location mustBe EntityLocation.default
      template.enabled mustBe true
      template.sourceKind mustBe "http"
      template.scheduling mustBe RemoteCatalogScheduling.default
    }

    "build templates with distinct ids" in {
      RemoteCatalog.template().id must not be RemoteCatalog.template().id
    }

    "round trip its template" in {
      roundTrip(format, RemoteCatalog.template())
    }
  }

  "RemoteCatalogScheduling" should {

    val format = RemoteCatalogScheduling.format

    "be disabled, scheduled every and per otoroshi instance by default" in {
      RemoteCatalogScheduling.default mustBe RemoteCatalogScheduling(
        enabled = false,
        kind = JobKind.ScheduledEvery,
        instantiation = JobInstantiation.OneInstancePerOtoroshiInstance,
        initialDelay = None,
        interval = None,
        cronExpression = None,
        deployArgs = Json.obj()
      )
    }

    "write the default scheduling with nulls for the missing durations and cron" in {
      format.writes(RemoteCatalogScheduling.default) mustBe defaultSchedulingJson
    }

    "expose through json what the format writes" in {
      fullScheduling.json mustBe format.writes(fullScheduling)
    }

    "round trip the default scheduling" in {
      roundTrip(format, RemoteCatalogScheduling.default)
    }

    "round trip a scheduling where every field is set" in {
      roundTrip(format, fullScheduling)
    }

    "round trip each job kind" in {
      Seq(JobKind.ScheduledOnce, JobKind.ScheduledEvery, JobKind.Cron, JobKind.Autonomous).foreach { kind =>
        roundTrip(format, fullScheduling.copy(kind = kind)).kind mustBe kind
      }
    }

    "round trip each instantiation" in {
      Seq(
        JobInstantiation.OneInstancePerOtoroshiInstance,
        JobInstantiation.OneInstancePerOtoroshiWorkerInstance,
        JobInstantiation.OneInstancePerOtoroshiLeaderInstance,
        JobInstantiation.OneInstancePerOtoroshiCluster
      ).foreach { instantiation =>
        roundTrip(format, fullScheduling.copy(instantiation = instantiation)).instantiation mustBe instantiation
      }
    }

    "write the durations in milliseconds" in {
      val json = format.writes(RemoteCatalogScheduling(initialDelay = Some(2.seconds), interval = Some(3.hours)))
      (json \ "initial_delay").as[Long] mustBe 2000L
      (json \ "interval").as[Long] mustBe 10800000L
    }

    "read the durations as milliseconds" in {
      val back = readOk(format, Json.obj("initial_delay" -> 1500, "interval" -> 60000))
      back.initialDelay mustBe Some(1500.millis)
      back.interval mustBe Some(1.minute)
    }

    "round trip an interval without initial delay" in {
      roundTrip(format, RemoteCatalogScheduling(enabled = true, interval = Some(30.seconds))).initialDelay mustBe None
    }

    "drop the sub millisecond part of the durations" in {
      val back = readOk(format, format.writes(RemoteCatalogScheduling(interval = Some(1500.micros))))
      back.interval mustBe Some(1.milli)
    }

    "read null durations and cron expression as missing" in {
      val back = readOk(format, Json.obj("initial_delay" -> JsNull, "interval" -> JsNull, "cron_expression" -> JsNull))
      back.initialDelay mustBe None
      back.interval mustBe None
      back.cronExpression mustBe None
    }

    "read durations written as strings as missing" in {
      readOk(format, Json.obj("initial_delay" -> "1000", "interval" -> "10s")) mustBe RemoteCatalogScheduling.default
    }

    "read the kind and the instantiation whatever their case" in {
      val back = readOk(format, Json.obj("kind" -> "CRON", "instantiation" -> "oneinstanceperotoroshicluster"))
      back.kind mustBe JobKind.Cron
      back.instantiation mustBe JobInstantiation.OneInstancePerOtoroshiCluster
    }

    "read an unknown kind as scheduled every" in {
      readOk(format, Json.obj("kind" -> "hourly")).kind mustBe JobKind.ScheduledEvery
    }

    "read an unknown instantiation as one instance per otoroshi instance" in {
      readOk(format, Json.obj("instantiation" -> "everywhere")).instantiation mustBe JobInstantiation.OneInstancePerOtoroshiInstance
    }

    "read a kind that is not a string as scheduled every" in {
      readOk(format, Json.obj("kind" -> 1)).kind mustBe JobKind.ScheduledEvery
    }

    "read an enabled flag written as a string as disabled" in {
      readOk(format, Json.obj("enabled" -> "true")).enabled mustBe false
    }

    "read deploy args that are not an object as empty ones" in {
      readOk(format, Json.obj("deploy_args" -> Json.arr(1))).deployArgs mustBe Json.obj()
    }

    "read an empty object as the default scheduling" in {
      readOk(format, Json.obj()) mustBe RemoteCatalogScheduling.default
    }

    "read a json null as the default scheduling" in {
      readOk(format, JsNull) mustBe RemoteCatalogScheduling.default
    }

    "not read the camel case keys of the fields" in {
      readOk(
        format,
        Json.obj("initialDelay" -> 1000, "cronExpression" -> "* * * * * ?", "deployArgs" -> Json.obj("a" -> 1))
      ) mustBe RemoteCatalogScheduling.default
    }
  }

  "RemoteCatalogJob" should {

    "be identified by the catalog it deploys" in {
      new RemoteCatalogJob("remote-catalog-1", fullScheduling).uniqueId mustBe JobId(
        "io.otoroshi.next.catalogs.RemoteCatalogJob#remote-catalog-1"
      )
    }

    "run with the kind of the catalog scheduling" in {
      new RemoteCatalogJob("remote-catalog-1", fullScheduling).kind mustBe JobKind.Cron
      new RemoteCatalogJob("remote-catalog-1", RemoteCatalogScheduling.default).kind mustBe JobKind.ScheduledEvery
    }

    "have no default config" in {
      new RemoteCatalogJob("remote-catalog-1", fullScheduling).defaultConfig mustBe None
    }
  }
}
