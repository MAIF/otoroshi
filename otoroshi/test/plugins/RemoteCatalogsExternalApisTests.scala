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

// an api an outside tool manages as code is not taken over by a remote catalog whose source holds an api with its id:
// the catalog reports it as refused, writes nothing, and its undeploy leaves it
class RemoteCatalogsExternalApisTests(parent: PluginsTestSpecBase) {
  import parent.*

  private given ec: ExecutionContext = env.otoroshiExecutionContext

  private val apiId = s"api_${IdGenerator.uuid}"
  private val tool  = "external=my-gitops-tool"

  private def api(name: String, path: String, createdBy: String): Api = {
    given otoroshi.env.Env = env
    val backend            = ApiBackend.empty.copy(id = "backend")
    val flow               = ApiFlows.empty
    Api(
      location = EntityLocation.default,
      id = apiId,
      name = name,
      description = "",
      domain = s"$apiId.oto.tools",
      contextPath = "",
      version = "1.0.0",
      debugFlow = false,
      capture = false,
      exportReporting = false,
      groups = Seq.empty,
      state = ApiPublished,
      blueprint = ApiBlueprint.REST,
      testing = ApiTesting(),
      backends = Seq(backend),
      flows = Seq(flow),
      plans = Seq.empty,
      routes = Seq(
        ApiRoute(
          id = s"apiroute_${IdGenerator.uuid}",
          frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(path))),
          flowRef = flow.id,
          backend = backend.id
        )
      ),
      metadata = Map("created_by" -> createdBy)
    )
  }

  private val tempRoot: Path = Files.createTempDirectory("remoteCatalogExternalApisTest")
  private val catalog        = RemoteCatalog.format
    .reads(
      Json.obj(
        "id"               -> s"remote-catalog_${IdGenerator.uuid}",
        "name"             -> "Catalog over an external api",
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
  private val engine         = env.adminExtensions.extension[RemoteCatalogAdminExtension].get.engine

  private def apiResult(report: DeployReport) =
    report.results
      .find(_.entityKind == "apis.otoroshi.io/Api")
      .getOrElse(fail(s"no result for apis in ${report.json.stringify}"))

  private def storedApi(): Option[Api] = env.datastores.apiDataStore.findById(apiId).futureValue

  private val managed = api("managed by the tool", "/mine", tool)
  env.datastores.apiDataStore.set(managed).futureValue mustBe true
  env.proxyState.sync().futureValue
  // the source of the catalog holds another api under the same id
  Files.write(
    tempRoot.resolve("api.json"),
    (api("taken over", "/theirs", "admin").json.as[JsObject] - "testing" ++ Json.obj("kind" -> "apis.otoroshi.io/Api"))
      .stringify
      .getBytes
  )

  try {
    val refusal = s"Entity $apiId of kind apis.otoroshi.io/Api refused: it is managed by $tool"
    Seq("dry run" -> engine.dryRun(catalog, Json.obj()), "deploy" -> engine.deploy(catalog, Json.obj())).foreach {
      case (what, run) =>
        val result = apiResult(run.futureValue.toOption.get)
        withClue(s"$what: ${result.json.stringify}") {
          result.refused mustBe Seq(refusal)
          result.created mustBe 0
          result.updated mustBe 0
          result.errors mustBe empty
        }
    }
    val kept = storedApi().get
    kept.name mustBe "managed by the tool"
    kept.metadata.get("created_by") mustBe Some(tool)
    kept.routes.flatMap(_.frontend.domains.map(_.path)) mustBe Seq("/mine")

    // the catalog owns nothing of it
    engine.undeploy(catalog).futureValue
    storedApi().map(_.name) mustBe Some("managed by the tool")
  } finally {
    env.datastores.apiDataStore.delete(apiId).futureValue
    Files.walk(tempRoot).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
  }
}
