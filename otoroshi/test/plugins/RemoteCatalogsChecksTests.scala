package plugins

import functional.PluginsTestSpecBase
import otoroshi.models.EntityLocation
import otoroshi.next.catalogs.{DeployReport, RemoteCatalog, RemoteCatalogAdminExtension}
import otoroshi.next.models.*
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*

import java.nio.file.{Files, Path}
import scala.concurrent.ExecutionContext

// a dry run of a remote catalog goes through the write checks of the resources like a deploy does,
// but without the side effects of their write validation: nothing is minted for a subscription.
class RemoteCatalogsChecksTests(parent: PluginsTestSpecBase) {
  import parent.*

  private given ec: ExecutionContext = env.otoroshiExecutionContext

  private val apisPath          = "/apis/apis.otoroshi.io/v1/apis"
  private val subscriptionsPath = "/apis/apis.otoroshi.io/v1/apisubscriptions"

  private val apikeyPlan = ApiPlan(
    Json.obj(
      "id"                             -> "apikey",
      "name"                           -> "apikey",
      "status"                         -> "published",
      "access_mode_configuration_type" -> "apikey",
      "access_mode_configuration"      -> Json.obj()
    )
  )

  private def api(id: String, state: ApiState): Api = Api(
    location = EntityLocation.default,
    id = id,
    name = id,
    description = "",
    domain = s"$id.oto.tools",
    contextPath = "/v1",
    version = "0.0.1",
    debugFlow = false,
    capture = false,
    exportReporting = false,
    groups = Seq.empty,
    state = state,
    blueprint = ApiBlueprint.REST,
    testing = ApiTesting(),
    plans = Seq(apikeyPlan)
  )

  private def resultOf(report: DeployReport, kind: String) =
    report.results.find(_.entityKind == kind).getOrElse(fail(s"no result for $kind in ${report.json.stringify}"))

  private def apikeysOf(subscriptionId: String) =
    env.datastores.apiKeyDataStore.findAll().futureValue.filter(_.apiRef.exists(_.subscription == subscriptionId))

  // the api the subscription of the catalog subscribes to
  private val stagingApi = api(s"api_${IdGenerator.uuid}", ApiStaging)
  otoroshiApiCall("POST", apisPath, Some(stagingApi.json)).futureValue._2 mustBe 201

  // an api cannot be created published, and a subscription to an apikey plan mints an apikey
  private val publishedApiId = s"api_${IdGenerator.uuid}"
  private val subscriptionId = s"api-subscription_${IdGenerator.uuid}"
  private val tempRoot: Path = Files.createTempDirectory("remoteCatalogChecksTest")
  Files.write(
    tempRoot.resolve("api.json"),
    (api(publishedApiId, ApiPublished).json.as[JsObject] ++ Json.obj("kind" -> "apis.otoroshi.io/Api")).stringify.getBytes
  )
  Files.write(
    tempRoot.resolve("subscription.json"),
    (otoroshiApiCall("GET", s"$subscriptionsPath/_template").futureValue._1.as[JsObject] ++ Json.obj(
      "id"                -> subscriptionId,
      "kind"              -> "apis.otoroshi.io/ApiSubscription",
      "api_ref"           -> stagingApi.id,
      "plan_ref"          -> apikeyPlan.id,
      "subscription_kind" -> "apikey"
    )).stringify.getBytes
  )

  private val catalog = RemoteCatalog.format
    .reads(
      Json.obj(
        "id"               -> s"remote-catalog_${IdGenerator.uuid}",
        "name"             -> "Checks catalog",
        "description"      -> "",
        "enabled"          -> true,
        "source_kind"      -> "file",
        "source_config"    -> Json.obj("path" -> tempRoot.toAbsolutePath.toString),
        "metadata"         -> Json.obj(),
        "tags"             -> Json.arr(),
        "scheduling"       -> Json.obj("enabled" -> false),
        "test_deploy_args" -> Json.obj()
      )
    )
    .get
  private val engine  = env.adminExtensions.extension[RemoteCatalogAdminExtension].get.engine

  // the dry run reports the refusal of the published api, and accepts the subscription without minting anything
  private val dryRun = engine.dryRun(catalog, Json.obj()).futureValue.toOption.get
  resultOf(dryRun, "apis.otoroshi.io/Api").created mustBe 0
  resultOf(dryRun, "apis.otoroshi.io/Api").errors.mkString must include("cannot_create_in_state")
  resultOf(dryRun, "apis.otoroshi.io/ApiSubscription").created mustBe 1
  resultOf(dryRun, "apis.otoroshi.io/ApiSubscription").errors mustBe empty
  otoroshiApiCall("GET", s"$subscriptionsPath/$subscriptionId").futureValue._2 mustBe 404
  apikeysOf(subscriptionId) mustBe empty

  // the deploy gives the same results, and does write the subscription with its apikey
  private val deploy = engine.deploy(catalog, Json.obj()).futureValue.toOption.get
  resultOf(deploy, "apis.otoroshi.io/Api").created mustBe 0
  resultOf(deploy, "apis.otoroshi.io/Api").errors.mkString must include("cannot_create_in_state")
  resultOf(deploy, "apis.otoroshi.io/ApiSubscription").created mustBe 1
  resultOf(deploy, "apis.otoroshi.io/ApiSubscription").errors mustBe empty
  otoroshiApiCall("GET", s"$apisPath/$publishedApiId").futureValue._2 mustBe 404
  otoroshiApiCall("GET", s"$subscriptionsPath/$subscriptionId").futureValue._2 mustBe 200
  apikeysOf(subscriptionId) must have size 1

  apikeysOf(subscriptionId).foreach(key => otoroshiApiCall("DELETE", s"/api/apikeys/${key.clientId}").futureValue)
  engine.undeploy(catalog).futureValue
  otoroshiApiCall("DELETE", s"$apisPath/${stagingApi.id}").futureValue
  Files.walk(tempRoot).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
}
