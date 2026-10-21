package plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import functional.PluginsTestSpecBase
import otoroshi.models.*
import otoroshi.security.IdGenerator
import play.api.http.Status
import play.api.libs.crypto.CookieSignerProvider
import play.api.libs.json.*
import play.api.libs.ws.WSBodyWritables.writeableOf_String
import play.api.libs.ws.{WSAuthScheme, WSResponse}
import play.api.mvc.{DefaultSessionCookieBaker, Session}

import java.nio.file.{Files, Path}
import scala.concurrent.duration.DurationInt

// deploying, undeploying or dry running a remote catalog creates or deletes the entities it describes. from the
// backoffice as from the admin api, it takes the rights to write the catalog
class RemoteCatalogsRightsTests(parent: PluginsTestSpecBase) {
  import parent.*

  val notAuthorized = "You're not authorized here !"

  def user(id: String, tenant: String, team: String): BackOfficeUser = BackOfficeUser(
    randomId = id,
    name = id,
    email = s"$id@otoroshi.io",
    profile = Json.obj(),
    authConfigId = "basic",
    simpleLogin = true,
    tags = Seq.empty,
    metadata = Map.empty,
    rights = UserRights(Seq(UserRight(TenantAccess(tenant), Seq(TeamAccess(team))))),
    adminEntityValidators = Map()
  )

  // remote catalogs are for super admins only: a writer of the location of the catalog is not enough
  val admin  = user("remote-catalog-admin", "*:rw", "*:rw")
  val writer = user("remote-catalog-writer", "default:rw", "default:rw")
  val reader = user("remote-catalog-reader", "default:r", "default:r")
  Seq(admin, writer, reader).foreach(_.save(5.minutes)(using env.otoroshiExecutionContext, env).futureValue)

  // an empty file catalog: deploying it creates nothing, only the rights decide whether it runs
  val root: Path    = Files.createTempDirectory("remoteCatalogRights")
  val catalogId     = s"remote-catalog_${IdGenerator.uuid}"
  val (created, createStatus) = otoroshiApiCall(
    "POST",
    "/apis/catalogs.otoroshi.io/v1/remote-catalogs",
    Some(
      Json.obj(
        "id"               -> catalogId,
        "name"             -> "rights catalog",
        "description"      -> "rights catalog",
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

  val sessionCookieBaker = new DefaultSessionCookieBaker(
    env.httpConfiguration.session,
    env.httpConfiguration.secret,
    new CookieSignerProvider(env.httpConfiguration.secret).get
  )

  def backoffice(action: String, user: BackOfficeUser, body: JsValue): WSResponse = {
    val cookie = sessionCookieBaker.encodeAsCookie(Session(Map("bousr" -> user.randomId)))
    ws.url(s"http://127.0.0.1:$port/extensions/remote-catalogs/$action")
      .withFollowRedirects(false)
      .withHttpHeaders(
        "Host"         -> env.backOfficeHost,
        "Content-Type" -> "application/json",
        "Cookie"       -> s"${cookie.name}=${cookie.value}"
      )
      .post(Json.stringify(body))
      .futureValue
  }

  def adminApi(action: String, user: Option[BackOfficeUser], body: JsValue): WSResponse = {
    val userToken = user.map { u =>
      "Otoroshi-BackOffice-User" -> JWT
        .create()
        .withClaim("user", Json.stringify(u.toJson))
        .withExpiresAt(java.time.Instant.now().plusSeconds(30L))
        .sign(Algorithm.HMAC512(env.otoroshiSecret))
    }
    ws.url(s"http://127.0.0.1:$port/api/extensions/remote-catalogs/$action")
      .withHttpHeaders((Seq("Host" -> "otoroshi-api.oto.tools", "Content-Type" -> "application/json") ++ userToken)*)
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .post(Json.stringify(body))
      .futureValue
  }

  val many = Json.arr(Json.obj("id" -> catalogId, "args" -> Json.obj()))
  val one  = Json.obj("id" -> catalogId, "args" -> Json.obj())

  def itemError(label: String, response: WSResponse): Option[String] = {
    withClue(s"$label: status=${response.status} body=${response.body} ") {
      response.status mustBe Status.OK
      (response.json.as[Seq[JsObject]].head \ "error").asOpt[String]
    }
  }

  itemError("reader deploys from the backoffice", backoffice("_deploy", reader, many)) mustBe Some(notAuthorized)
  itemError("reader undeploys from the backoffice", backoffice("_undeploy", reader, many)) mustBe Some(notAuthorized)
  itemError("reader deploys through the admin api", adminApi("_deploy", Some(reader), many)) mustBe Some(notAuthorized)
  itemError("reader undeploys through the admin api", adminApi("_undeploy", Some(reader), many)) mustBe Some(
    notAuthorized
  )

  val readerTest = backoffice("_test", reader, one)
  withClue(s"reader dry runs from the backoffice: status=${readerTest.status} body=${readerTest.body} ") {
    readerTest.status mustBe Status.FORBIDDEN
  }

  val readerApiTest = adminApi("_test", Some(reader), one)
  withClue(s"reader dry runs through the admin api: status=${readerApiTest.status} body=${readerApiTest.body} ") {
    readerApiTest.status mustBe Status.FORBIDDEN
  }

  val writerTest = backoffice("_test", writer, one)
  withClue(s"writer dry runs from the backoffice: status=${writerTest.status} body=${writerTest.body} ") {
    writerTest.status mustBe Status.FORBIDDEN
  }
  val writerApiTest = adminApi("_test", Some(writer), one)
  withClue(s"writer dry runs through the admin api: status=${writerApiTest.status} body=${writerApiTest.body} ") {
    writerApiTest.status mustBe Status.FORBIDDEN
  }
  itemError("writer deploys from the backoffice", backoffice("_deploy", writer, many)) mustBe Some(notAuthorized)
  itemError("writer deploys through the admin api", adminApi("_deploy", Some(writer), many)) mustBe Some(
    notAuthorized
  )
  val writerList = ws
    .url(s"http://127.0.0.1:$port/apis/catalogs.otoroshi.io/v1/remote-catalogs")
    .withHttpHeaders(
      "Host"                     -> "otoroshi-api.oto.tools",
      "Otoroshi-BackOffice-User" -> JWT
        .create()
        .withClaim("user", Json.stringify(writer.toJson))
        .withExpiresAt(java.time.Instant.now().plusSeconds(30L))
        .sign(Algorithm.HMAC512(env.otoroshiSecret))
    )
    .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
    .get()
    .futureValue
  withClue(s"writer lists the catalogs: status=${writerList.status} ") {
    writerList.status mustBe Status.FORBIDDEN
  }

  val adminTest = backoffice("_test", admin, one)
  withClue(s"super admin dry runs from the backoffice: status=${adminTest.status} body=${adminTest.body} ") {
    adminTest.status mustBe Status.OK
  }
  itemError("super admin deploys from the backoffice", backoffice("_deploy", admin, many)) must not be Some(
    notAuthorized
  )
  itemError("admin apikey deploys through the admin api", adminApi("_deploy", None, many)) must not be Some(
    notAuthorized
  )
  val apikeyTest = adminApi("_test", None, one)
  withClue(s"admin apikey dry runs through the admin api: status=${apikeyTest.status} body=${apikeyTest.body} ") {
    apikeyTest.status mustBe Status.OK
  }

  Seq(admin, writer, reader).foreach(_.delete()(using env.otoroshiExecutionContext, env).futureValue)
  otoroshiApiCall("DELETE", s"/apis/catalogs.otoroshi.io/v1/remote-catalogs/$catalogId").futureValue
}
