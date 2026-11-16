package plugins

import functional.PluginsTestSpecBase
import otoroshi.security.IdGenerator
import play.api.libs.json.*

import java.nio.file.{Files, Path}
import scala.concurrent.duration.DurationInt

// a remote catalog only deploys the kinds of entities it is given, globally and by location (#2933)
class RemoteCatalogsKindsTests(parent: PluginsTestSpecBase) {
  import parent.*

  val root: Path = Files.createTempDirectory("remoteCatalogKinds")
  val suffix     = IdGenerator.uuid.take(8)
  val routeId    = s"kinds-route-$suffix"
  val backendId  = s"kinds-backend-$suffix"
  val otherId    = s"kinds-other-route-$suffix"

  def route(id: String, tenant: String, team: String): JsObject = Json.obj(
    "id"       -> id,
    "kind"     -> "Route",
    "name"     -> id,
    "_loc"     -> Json.obj("tenant" -> tenant, "teams" -> Json.arr(team)),
    "frontend" -> Json.obj("domains" -> Json.arr(s"$id.oto.tools")),
    "backend"  -> Json.obj("targets" -> Json.arr(Json.obj("hostname" -> "localhost", "port" -> 8080)))
  )

  Files.write(root.resolve("route.json"), Json.stringify(route(routeId, "default", "default")).getBytes())
  Files.write(root.resolve("other.json"), Json.stringify(route(otherId, "other-tenant", "other-team")).getBytes())
  Files.write(
    root.resolve("backend.json"),
    Json
      .stringify(
        Json.obj(
          "id"      -> backendId,
          "kind"    -> "Backend",
          "name"    -> backendId,
          "backend" -> Json.obj("targets" -> Json.arr(Json.obj("hostname" -> "localhost", "port" -> 8080)))
        )
      )
      .getBytes()
  )

  val catalogId = s"remote-catalog_${IdGenerator.uuid}"

  def catalog(kinds: JsValue): JsObject = Json.obj(
    "id"               -> catalogId,
    "name"             -> "kinds catalog",
    "description"      -> "kinds catalog",
    "enabled"          -> true,
    "source_kind"      -> "file",
    "source_config"    -> Json.obj("path" -> root.toAbsolutePath.toString),
    "metadata"         -> Json.obj(),
    "tags"             -> Json.arr(),
    "scheduling"       -> Json.obj("enabled" -> false),
    "test_deploy_args" -> Json.obj(),
    "kinds"            -> kinds
  )

  def errorsOf(report: JsValue): Seq[String] =
    (report \ "results").asOpt[Seq[JsObject]].getOrElse(Seq.empty).flatMap(r => (r \ "errors").as[Seq[String]])

  def deploy(): JsValue = {
    val (body, status) = otoroshiApiCall(
      "POST",
      "/api/extensions/remote-catalogs/_deploy",
      Some(Json.arr(Json.obj("id" -> catalogId, "args" -> Json.obj())))
    ).futureValue
    withClue(s"deploy: $body ") {
      status mustBe 200
    }
    body.as[Seq[JsObject]].head
  }

  def exists(path: String): Boolean = otoroshiApiCall("GET", path).futureValue._2 == 200

  // kinds that cannot be read are refused
  withClue("a catalog whose kinds cannot be read ") {
    otoroshiApiCall(
      "POST",
      "/apis/catalogs.otoroshi.io/v1/remote-catalogs",
      Some(catalog(Json.obj("include" -> 42)))
    ).futureValue._2 mustBe 400
  }

  // every kind but the backends, and only the backends in the other organizations
  val firstKinds = Json.obj(
    "include"   -> Json.arr("*"),
    "exclude"   -> Json.arr("Backend"),
    "locations" -> Json.obj("other-*" -> Json.obj("include" -> Json.arr("backends")))
  )
  otoroshiApiCall("POST", "/apis/catalogs.otoroshi.io/v1/remote-catalogs", Some(catalog(firstKinds))).futureValue._2 mustBe 201
  await(2.seconds)

  // a dry run reports the refused entities too
  val (dryRun, dryRunStatus) = otoroshiApiCall(
    "POST",
    "/api/extensions/remote-catalogs/_test",
    Some(Json.obj("id" -> catalogId, "args" -> Json.obj()))
  ).futureValue
  withClue(s"dry run: $dryRun ") {
    dryRunStatus mustBe 200
    errorsOf(dryRun).count(_.contains("refused")) mustBe 2
  }

  val first = deploy()
  withClue(s"first deploy: $first ") {
    errorsOf(first).exists(e => e.contains(backendId) && e.contains("refused")) mustBe true
    errorsOf(first).exists(e => e.contains(otherId) && e.contains("refused")) mustBe true
  }
  await(2.seconds)
  withClue("the allowed route is deployed ") { exists(s"/api/routes/$routeId") mustBe true }
  withClue("the excluded backend is not ") { exists(s"/api/backends/$backendId") mustBe false }
  withClue("the route of a location that only deploys backends is not ") { exists(s"/api/routes/$otherId") mustBe false }

  // the routes are not deployed anymore: the route deployed before stays
  val (_, updateStatus) = otoroshiApiCall(
    "PUT",
    s"/apis/catalogs.otoroshi.io/v1/remote-catalogs/$catalogId",
    Some(catalog(Json.obj("exclude" -> Json.arr("routes", "backends"))))
  ).futureValue
  updateStatus mustBe 200
  await(2.seconds)
  Files.delete(root.resolve("route.json"))
  val second = deploy()
  await(2.seconds)
  withClue(s"a route of a kind the catalog does not deploy anymore is not deleted: $second ") {
    exists(s"/api/routes/$routeId") mustBe true
  }

  // an undeploy still deletes every entity the catalog created
  otoroshiApiCall(
    "POST",
    "/api/extensions/remote-catalogs/_undeploy",
    Some(Json.arr(Json.obj("id" -> catalogId)))
  ).futureValue._2 mustBe 200
  await(2.seconds)
  withClue("the route is deleted by the undeploy ") { exists(s"/api/routes/$routeId") mustBe false }

  otoroshiApiCall("DELETE", s"/apis/catalogs.otoroshi.io/v1/remote-catalogs/$catalogId").futureValue
  Files
    .walk(root)
    .sorted(java.util.Comparator.reverseOrder())
    .forEach(Files.delete)
}
