package plugins

import functional.PluginsTestSpec
import otoroshi.next.catalogs.RemoteCatalogDeploySingle
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.OverrideHost
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.libs.json._

import scala.concurrent.duration.DurationInt

import java.nio.file.{Files, Path}

class RemoteCatalogsTests(parent: PluginsTestSpec) {

  import parent._

  def deployWithAdminApi(): Unit = {

    val tempRoot: Path = Files.createTempDirectory("remoteCatalogTest")

    val backendJson = Json.obj(
      "id"      -> "test-catalog-backend-1",
      "kind"    -> "Backend",
      "name"    -> "Test Catalog Backend",
      "backend" -> Json.obj(
        "targets" -> Json.arr(
          Json.obj("hostname" -> "localhost", "port" -> 8080)
        )
      )
    )
    Files.write(tempRoot.resolve("backend.json"), Json.stringify(backendJson).getBytes())

    val routeJson = Json.obj(
      "id"       -> "test-catalog-route-1",
      "kind"     -> "Route",
      "name"     -> "Test Catalog Route",
      "frontend" -> Json.obj(
        "domains" -> Json.arr("test-from-catalog.oto.tools")
      ),
      "backend" -> Json.obj(
        "targets" -> Json.arr(
          Json.obj("hostname" -> "localhost", "port" -> 8080)
        )
      )
    )
    Files.write(tempRoot.resolve("route.json"), Json.stringify(routeJson).getBytes())

    val catalogId  = s"remote-catalog_${IdGenerator.uuid}"
    val catalogJson = Json.obj(
      "id"               -> catalogId,
      "name"             -> "Test File Catalog",
      "description"      -> "Test catalog for functional tests",
      "enabled"          -> true,
      "source_kind"      -> "file",
      "source_config"    -> Json.obj("path" -> tempRoot.toAbsolutePath.toString),
      "metadata"         -> Json.obj(),
      "tags"             -> Json.arr(),
      "scheduling"       -> Json.obj("enabled" -> false),
      "test_deploy_args" -> Json.obj()
    )

    val (_, createStatus) = otoroshiApiCall(
      "POST",
      "/apis/catalogs.otoroshi.io/v1/remote-catalogs",
      Some(catalogJson)
    ).futureValue
    createStatus mustBe 201

    await(2.seconds)

    // Deploy
    val (deployBody, deployStatus) = otoroshiApiCall(
      "POST",
      "/api/extensions/remote-catalogs/_deploy",
      Some(Json.arr(Json.obj("id" -> catalogId, "args" -> Json.obj())))
    ).futureValue
    deployStatus mustBe 200

    val deployResults = deployBody.as[Seq[JsObject]]
    deployResults.nonEmpty mustBe true
    val totalCreated = deployResults.flatMap(r =>
      (r \ "results").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map(rr => (rr \ "created").asOpt[Int].getOrElse(0))
    ).sum
    totalCreated > 0 mustBe true

    await(2.seconds)

    // Verify route exists with metadata
    val (routeBody, routeStatus) = otoroshiApiCall(
      "GET",
      "/api/routes/test-catalog-route-1"
    ).futureValue
    routeStatus mustBe 200
    (routeBody \ "metadata" \ "created_by").asOpt[String] mustBe Some(s"remote_catalog=$catalogId")

    // Verify backend exists with metadata
    val (backendBody, backendStatus) = otoroshiApiCall(
      "GET",
      "/api/backends/test-catalog-backend-1"
    ).futureValue
    backendStatus mustBe 200
    (backendBody \ "metadata" \ "created_by").asOpt[String] mustBe Some(s"remote_catalog=$catalogId")

    // Undeploy
    val (undeployBody, undeployStatus) = otoroshiApiCall(
      "POST",
      "/api/extensions/remote-catalogs/_undeploy",
      Some(Json.arr(Json.obj("id" -> catalogId)))
    ).futureValue
    undeployStatus mustBe 200

    val undeployResults = undeployBody.as[Seq[JsObject]]
    val totalDeleted = undeployResults.flatMap(r =>
      (r \ "results").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map(rr => (rr \ "deleted").asOpt[Int].getOrElse(0))
    ).sum
    totalDeleted > 0 mustBe true

    await(2.seconds)

    // Verify entities are gone
    val (_, routeGoneStatus) = otoroshiApiCall("GET", "/api/routes/test-catalog-route-1").futureValue
    routeGoneStatus mustBe 404

    val (_, backendGoneStatus) = otoroshiApiCall("GET", "/api/backends/test-catalog-backend-1").futureValue
    backendGoneStatus mustBe 404

    // Cleanup
    otoroshiApiCall("DELETE", s"/apis/catalogs.otoroshi.io/v1/remote-catalogs/$catalogId").futureValue

    Files
      .walk(tempRoot)
      .sorted(java.util.Comparator.reverseOrder())
      .forEach(Files.delete)
  }

  def deployWithPlugin(): Unit = {

    val tempRoot: Path = Files.createTempDirectory("remoteCatalogPluginTest")

    val backendJson = Json.obj(
      "id"      -> "test-catalog-plugin-backend-1",
      "kind"    -> "Backend",
      "name"    -> "Test Catalog Plugin Backend",
      "backend" -> Json.obj(
        "targets" -> Json.arr(
          Json.obj("hostname" -> "localhost", "port" -> 8080)
        )
      )
    )
    Files.write(tempRoot.resolve("backend.json"), Json.stringify(backendJson).getBytes())

    val catalogId  = s"remote-catalog_${IdGenerator.uuid}"
    val catalogJson = Json.obj(
      "id"               -> catalogId,
      "name"             -> "Test File Catalog Plugin",
      "description"      -> "Test catalog for plugin deploy",
      "enabled"          -> true,
      "source_kind"      -> "file",
      "source_config"    -> Json.obj("path" -> tempRoot.toAbsolutePath.toString),
      "metadata"         -> Json.obj(),
      "tags"             -> Json.arr(),
      "scheduling"       -> Json.obj("enabled" -> false),
      "test_deploy_args" -> Json.obj()
    )

    val (_, createStatus) = otoroshiApiCall(
      "POST",
      "/apis/catalogs.otoroshi.io/v1/remote-catalogs",
      Some(catalogJson)
    ).futureValue
    createStatus mustBe 201

    await(2.seconds)

    // Create a route with the RemoteCatalogDeploySingle plugin
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[RemoteCatalogDeploySingle],
          config = NgPluginInstanceConfig(
            Json.obj("catalog_ref" -> catalogId).as[JsObject]
          )
        )
      ),
      id = IdGenerator.uuid,
      domain = "remote-catalog-trigger.oto.tools".some
    ).futureValue

    await(2.seconds)

    // POST to the route to trigger deploy
    val resp = ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders("Host" -> "remote-catalog-trigger.oto.tools")
      .post(Json.stringify(Json.obj()))
      .futureValue

    resp.status mustBe 200

    val respJson = resp.json
    val pluginCreated = (respJson \ "results").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
      .map(rr => (rr \ "created").asOpt[Int].getOrElse(0)).sum
    pluginCreated > 0 mustBe true

    await(2.seconds)

    // Verify backend entity exists via admin API
    val (backendBody, backendStatus) = otoroshiApiCall(
      "GET",
      "/api/backends/test-catalog-plugin-backend-1"
    ).futureValue
    backendStatus mustBe 200
    (backendBody \ "metadata" \ "created_by").asOpt[String] mustBe Some(s"remote_catalog=$catalogId")

    // Cleanup: undeploy, delete route, delete catalog, delete temp files
    otoroshiApiCall(
      "POST",
      "/api/extensions/remote-catalogs/_undeploy",
      Some(Json.arr(Json.obj("id" -> catalogId)))
    ).futureValue

    await(2.seconds)

    deleteOtoroshiRoute(route).futureValue

    otoroshiApiCall("DELETE", s"/apis/catalogs.otoroshi.io/v1/remote-catalogs/$catalogId").futureValue

    Files
      .walk(tempRoot)
      .sorted(java.util.Comparator.reverseOrder())
      .forEach(Files.delete)
  }
}
