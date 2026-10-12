package plugins

import functional.{PluginsTestSpecBase, TargetService}
import otoroshi.models.EntityLocation
import otoroshi.next.catalogs.{DeployReport, RemoteCatalog, RemoteCatalogAdminExtension}
import otoroshi.next.models.*
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*

import java.nio.file.{Files, Path}
import scala.concurrent.ExecutionContext

// an api written as code by a remote catalog: its source plays the role of the draft, so a deploy of the
// catalog deploys the api, and a change of what is live is recorded in its deployment history
class RemoteCatalogsApisTests(parent: PluginsTestSpecBase) {
  import parent.*

  private given ec: ExecutionContext = env.otoroshiExecutionContext

  private val target = TargetService(None, "/", "application/json", _ => Json.obj("ok" -> true).stringify).await()

  private val apiId   = s"api_${IdGenerator.uuid}"
  private val routeId = s"apiroute_${IdGenerator.uuid}"
  private val domain  = s"$apiId.oto.tools"

  // the api as a source writes it: without the testing block, so the reads give it a new secret each time
  private def source(state: ApiState, path: String): JsObject = {
    given otoroshi.env.Env = env
    val backend            = ApiBackend.empty.copy(
      id = "backend",
      backend = NgBackend.empty.copy(
        targets = Seq(NgTarget(id = "target", hostname = "127.0.0.1", port = target.port, tls = false))
      )
    )
    val flow               = ApiFlows.empty
    Api(
      location = EntityLocation.default,
      id = apiId,
      name = apiId,
      description = "",
      domain = domain,
      contextPath = "",
      version = "1.0.0",
      debugFlow = false,
      capture = false,
      exportReporting = false,
      groups = Seq.empty,
      state = state,
      blueprint = ApiBlueprint.REST,
      testing = ApiTesting(),
      backends = Seq(backend),
      flows = Seq(flow),
      routes = Seq(
        ApiRoute(
          id = routeId,
          frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(path))),
          flowRef = flow.id,
          backend = backend.id
        )
      )
    ).json.as[JsObject] - "testing" ++ Json.obj("kind" -> "apis.otoroshi.io/Api")
  }

  private val tempRoot: Path = Files.createTempDirectory("remoteCatalogApisTest")
  private val apiFile: Path  = tempRoot.resolve("api.json")

  private def writeSource(state: ApiState, path: String): Unit =
    Files.write(apiFile, source(state, path).stringify.getBytes)

  private val catalog   = RemoteCatalog.format
    .reads(
      Json.obj(
        "id"               -> s"remote-catalog_${IdGenerator.uuid}",
        "name"             -> "Apis catalog",
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
  private val createdBy = s"remote_catalog=${catalog.id}"
  private val engine    = env.adminExtensions.extension[RemoteCatalogAdminExtension].get.engine

  private def apiResult(report: DeployReport) =
    report.results
      .find(_.entityKind == "apis.otoroshi.io/Api")
      .getOrElse(fail(s"no result for apis in ${report.json.stringify}"))

  private def dryRun(): DeployReport = engine.dryRun(catalog, Json.obj()).futureValue.toOption.get

  // the proxy state is synced after the deploy: the catalog finds the api it updates there, and the
  // router serves the routes of the api from it
  private def deploy(): DeployReport = {
    val report = engine.deploy(catalog, Json.obj()).futureValue.toOption.get
    env.proxyState.sync().futureValue
    report
  }

  private def storedApi(): Option[Api] = env.datastores.apiDataStore.findById(apiId).futureValue

  private def call(path: String): Int = ws
    .url(s"http://127.0.0.1:$port$path")
    .withHttpHeaders("Host" -> domain)
    .get()
    .futureValue
    .status

  private def deployedPaths(deployment: ApiDeployment): Seq[String] =
    deployment.apiDefinition
      .select("routes")
      .as[Seq[JsObject]]
      .flatMap(_.select("frontend").select("domains").as[Seq[String]])

  try {
    // a published api in the source: the dry run accepts it without writing it
    writeSource(ApiPublished, "/v1")
    val checked = dryRun()
    apiResult(checked).created mustBe 1
    apiResult(checked).errors mustBe empty
    storedApi() mustBe None

    // the deploy publishes it, its routes answer, and the deploy is the first of its history
    val created = deploy()
    apiResult(created).created mustBe 1
    apiResult(created).errors mustBe empty
    val published = storedApi().get
    published.state mustBe ApiPublished
    published.metadata.get("created_by") mustBe Some(createdBy)
    published.deployments.map(_.owner) mustBe Seq(createdBy)
    deployedPaths(published.deployments.head) mustBe Seq("/v1")
    call("/v1") mustBe 200
    call("/v2") mustBe 404

    // an unchanged source changes nothing: no deploy recorded, the routes and the testing secret stay
    val unchanged = deploy()
    apiResult(unchanged).updated mustBe 1
    apiResult(unchanged).errors mustBe empty
    val same      = storedApi().get
    same.deployments mustBe published.deployments
    same.testing mustBe published.testing
    same.routes mustBe published.routes
    call("/v1") mustBe 200

    // a change of the routes: the dry run accepts it without deploying it
    writeSource(ApiPublished, "/v2")
    val checkedChange = dryRun()
    apiResult(checkedChange).updated mustBe 1
    apiResult(checkedChange).errors mustBe empty
    storedApi().get.deployments mustBe published.deployments
    call("/v1") mustBe 200

    // the deploy puts it live, with a new entry in the history, and keeps the ids of the routes
    val changed    = deploy()
    apiResult(changed).updated mustBe 1
    apiResult(changed).errors mustBe empty
    val redeployed = storedApi().get
    redeployed.deployments.size mustBe 2
    redeployed.deployments.tail mustBe published.deployments
    deployedPaths(redeployed.deployments.head) mustBe Seq("/v2")
    redeployed.routes.map(_.id) mustBe Seq(routeId)
    redeployed.testing mustBe published.testing
    call("/v2") mustBe 200
    call("/v1") mustBe 404

    // a published api does not go back to staging, the catalog reports it and the api stays live
    writeSource(ApiStaging, "/v2")
    apiResult(deploy()).errors.mkString must include("invalid_state_transition")
    storedApi().get.state mustBe ApiPublished
    call("/v2") mustBe 200

    // an api removed from the source is removed, and its routes with it
    Files.delete(apiFile)
    apiResult(dryRun()).deleted mustBe 1
    storedApi().isDefined mustBe true
    apiResult(deploy()).deleted mustBe 1
    storedApi() mustBe None
    call("/v2") mustBe 404
  } finally {
    engine.undeploy(catalog).futureValue
    target.stop()
    Files.walk(tempRoot).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
  }
}
