package plugins

import functional.{PluginsTestSpecBase, TargetService}
import otoroshi.auth.{GenericOauth2ModuleConfig, SessionCookieValues}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, NgRoute, NgTarget}
import otoroshi.next.plugins.OIDCAuthToken
import otoroshi.next.plugins.api.NgPluginHelper
import play.api.http.Status
import play.api.libs.json.Json

import java.nio.charset.StandardCharsets
import java.util.Base64

// in opaque mode the token is never verified locally, only the authorization server can tell whether it is valid.
// with fetch_user_profile turned off, the payload of the token was decoded and trusted as is, so any forged jwt
// opened a session
class OIDCAuthTokenOpaqueTests(parent: PluginsTestSpecBase) {
  import parent.*

  val backend      = TargetService
    .full(None, "/", "application/json", _ => (200, """{"message":"hello"}""", List.empty))
    .await()
  val rejectingIdp = TargetService
    .full(None, "/userinfo", "application/json", _ => (401, """{"error":"invalid_token"}""", List.empty))
    .await()
  val acceptingIdp = TargetService
    .full(
      None,
      "/userinfo",
      "application/json",
      _ => (200, Json.stringify(Json.obj("sub" -> "john", "name" -> "John", "email" -> "john@oto.tools")), List.empty)
    )
    .await()

  def module(id: String, idpPort: Int): GenericOauth2ModuleConfig = GenericOauth2ModuleConfig(
    id = id,
    name = id,
    desc = id,
    clientSideSessionEnabled = true,
    userInfoUrl = s"http://127.0.0.1:$idpPort/userinfo",
    tags = Seq.empty,
    metadata = Map.empty,
    sessionCookieValues = SessionCookieValues()
  )

  val rejectingModule = module("oidc-opaque-rejecting-module", rejectingIdp.port)
  val acceptingModule = module("oidc-opaque-accepting-module", acceptingIdp.port)
  createAuthModule(rejectingModule).futureValue
  createAuthModule(acceptingModule).futureValue

  def route(moduleId: String): NgRoute = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OIDCAuthToken],
        config = NgPluginInstanceConfig(Json.obj("ref" -> moduleId, "opaque" -> true, "fetch_user_profile" -> false))
      )
    ),
    target = Some(NgTarget(id = "oidc-opaque-backend", hostname = "127.0.0.1", port = backend.port, tls = false))
  ).futureValue

  val rejectingRoute = route(rejectingModule.id)
  val acceptingRoute = route(acceptingModule.id)

  def b64(str: String): String =
    Base64.getUrlEncoder.withoutPadding().encodeToString(str.getBytes(StandardCharsets.UTF_8))

  val forged = s"${b64("""{"alg":"none"}""")}.${b64("""{"sub":"admin","email":"admin@oto.tools"}""")}.forged"

  def call(route: NgRoute) = ws
    .url(s"http://127.0.0.1:$port/")
    .withHttpHeaders(
      "Host"          -> route.frontend.domains.head.domain,
      "Authorization" -> s"Bearer $forged"
    )
    .get()
    .futureValue

  val rejected = call(rejectingRoute)
  withClue(s"token the authorization server rejects: status=${rejected.status} body=${rejected.body} ") {
    rejected.status mustBe Status.UNAUTHORIZED
  }

  val accepted = call(acceptingRoute)
  withClue(s"token the authorization server accepts: status=${accepted.status} body=${accepted.body} ") {
    accepted.status mustBe Status.OK
  }

  deleteOtoroshiRoute(rejectingRoute).futureValue
  deleteOtoroshiRoute(acceptingRoute).futureValue
  deleteAuthModule(rejectingModule).futureValue
  deleteAuthModule(acceptingModule).futureValue
  backend.stop()
  rejectingIdp.stop()
  acceptingIdp.stop()
}
