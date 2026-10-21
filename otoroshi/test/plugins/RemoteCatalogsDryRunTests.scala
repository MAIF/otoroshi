package plugins

import functional.PluginsTestSpecBase
import otoroshi.security.IdGenerator
import play.api.libs.json.*

import java.nio.file.{Files, Path}
import scala.concurrent.duration.DurationInt

// a dry run of a remote catalog through the admin api answers what a deploy would do, without writing anything
class RemoteCatalogsDryRunTests(parent: PluginsTestSpecBase) {
  import parent.*

  val root: Path = Files.createTempDirectory("remoteCatalogDryRun")
  val routeId    = s"route_${IdGenerator.uuid}"
  Files.write(
    root.resolve("route.json"),
    Json
      .stringify(
        Json.obj(
          "id"       -> routeId,
          "kind"     -> "Route",
          "name"     -> "dry run route",
          "frontend" -> Json.obj("domains" -> Json.arr("dry-run-catalog.oto.tools")),
          "backend"  -> Json.obj("targets" -> Json.arr(Json.obj("hostname" -> "localhost", "port" -> 8080)))
        )
      )
      .getBytes()
  )

  val catalogId               = s"remote-catalog_${IdGenerator.uuid}"
  val (created, createStatus) = otoroshiApiCall(
    "POST",
    "/apis/catalogs.otoroshi.io/v1/remote-catalogs",
    Some(
      Json.obj(
        "id"               -> catalogId,
        "name"             -> "dry run catalog",
        "description"      -> "dry run catalog",
        "enabled"          -> true,
        "source_kind"      -> "file",
        "source_config"    -> Json.obj("path" -> root.toAbsolutePath.toString),
        "metadata"         -> Json.obj(),
        "tags"             -> Json.arr(),
        "scheduling"       -> Json.obj("enabled" -> false),
        "test_deploy_args" -> Json.obj()
      )
    )
  ).futureValue
  withClue(s"create catalog: $created ") {
    createStatus mustBe 201
  }
  await(2.seconds)

  val (report, reportStatus) = otoroshiApiCall(
    "POST",
    "/api/extensions/remote-catalogs/_test",
    Some(Json.obj("id" -> catalogId, "args" -> Json.obj()))
  ).futureValue
  withClue(s"dry run: status=$reportStatus body=$report ") {
    reportStatus mustBe 200
    (report \ "catalog_id").asOpt[String] mustBe Some(catalogId)
    (report \ "results").as[Seq[JsObject]].map(r => (r \ "created").as[Int]).sum mustBe 1
  }
  withClue("the route of the catalog is not written by a dry run ") {
    otoroshiApiCall("GET", s"/api/routes/$routeId").futureValue._2 mustBe 404
  }

  val (unknown, unknownStatus) = otoroshiApiCall(
    "POST",
    "/api/extensions/remote-catalogs/_test",
    Some(Json.obj("id" -> "remote-catalog_unknown", "args" -> Json.obj()))
  ).futureValue
  withClue(s"dry run of an unknown catalog: status=$unknownStatus body=$unknown ") {
    unknownStatus mustBe 404
  }

  otoroshiApiCall("DELETE", s"/apis/catalogs.otoroshi.io/v1/remote-catalogs/$catalogId").futureValue
  Files
    .walk(root)
    .sorted(java.util.Comparator.reverseOrder())
    .forEach(Files.delete)
}
