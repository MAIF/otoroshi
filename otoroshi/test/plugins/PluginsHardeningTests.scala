package plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import functional.{PluginsTestSpecBase, TargetService}
import otoroshi.models.{GlobalJwtVerifier, HSAlgoSettings, InHeader, PassThrough, VerificationSettings}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, NgRoute, NgTarget}
import otoroshi.next.plugins.*
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.utils.JsonPathValidator
import play.api.http.Status
import play.api.libs.json.*

import java.nio.charset.StandardCharsets
import java.util.Base64

// access control plugins that let requests through when they should not
class PluginsHardeningTests(parent: PluginsTestSpecBase) {
  import parent.*

  val backend = TargetService
    .full(None, "/", "application/json", _ => (200, """{"ok":true}""", List.empty))
    .await()

  def route(plugins: NgPluginInstance*): NgRoute = createRouteWithExternalTarget(
    plugins,
    target = Some(NgTarget(id = "hardening-backend", hostname = "127.0.0.1", port = backend.port, tls = false))
  ).futureValue

  def call(route: NgRoute, path: String, headers: (String, String)*): Int = ws
    .url(s"http://127.0.0.1:$port$path")
    .withHttpHeaders((Seq("Host" -> route.frontend.domains.head.domain) ++ headers)*)
    .get()
    .futureValue
    .status

  def basic(user: String, password: String): (String, String) =
    "Authorization" -> ("Basic " + Base64.getEncoder.encodeToString(s"$user:$password".getBytes(StandardCharsets.UTF_8)))

  // ContainsNot(Regex(...)) and friends were shadowed by the plain ContainsNot( branch and never denied
  def negativeMatchers(): Unit = {
    val groups = Json.obj("groups" -> Json.arr("admin", "dev"))
    JsonPathValidator("$.groups", JsString("ContainsNot(Regex(adm.*))")).validate(groups) mustBe false
    JsonPathValidator("$.groups", JsString("ContainsNot(Regex(ops.*))")).validate(groups) mustBe true
    JsonPathValidator("$.groups", JsString("ContainsNot(Wildcard(adm*))")).validate(groups) mustBe false
    JsonPathValidator("$.groups", JsString("Contains(Regex(adm.*))")).validate(groups) mustBe true
  }

  // a stored bcrypt hash used to be accepted as the password itself
  def simpleBasicAuthHash(): Unit = {
    val hash = "$2a$10$RtYWagxgvorxpxNIYTi4Be2tU.n8294eHpwle1ad0Tmh7.NiVXOEq" // bcrypt of "password"
    val r    = route(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[SimpleBasicAuth],
        config = NgPluginInstanceConfig(SimpleBasicAuthConfig(users = Map("john" -> hash)).json.as[JsObject])
      )
    )
    withClue("the password ") { call(r, "/", basic("john", "password")) mustBe Status.OK }
    withClue("the stored hash ") { call(r, "/", basic("john", hash)) mustBe Status.UNAUTHORIZED }
    deleteOtoroshiRoute(r).futureValue
  }

  // two instances of the apikey plugin on one route used to share the config of the first one read
  def apikeyInstances(): Unit = {
    val r = route(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[ApikeyCalls],
        include = Seq("/public/.*"),
        config = NgPluginInstanceConfig(NgApikeyCallsConfig(mandatory = false).json.as[JsObject])
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[ApikeyCalls],
        exclude = Seq("/public/.*"),
        config = NgPluginInstanceConfig(NgApikeyCallsConfig().json.as[JsObject])
      )
    )
    withClue("public path without apikey ") { call(r, "/public/x") mustBe Status.OK }
    withClue("private path without apikey ") { call(r, "/private/x") must not be Status.OK }
    deleteOtoroshiRoute(r).futureValue
  }

  // the verdict of a jwt used to be cached under its signature alone
  def jwtVerifierCache(): Unit = {
    val verifier = GlobalJwtVerifier(
      id = "hardening-jwt-verifier",
      name = "hardening-jwt-verifier",
      desc = "hardening-jwt-verifier",
      strict = true,
      source = InHeader(name = "Authorization", remove = "Bearer "),
      algoSettings = HSAlgoSettings(512, "hardening-secret"),
      strategy = PassThrough(verificationSettings = VerificationSettings())
    )
    createOtoroshiVerifier(verifier).futureValue
    val r        = route(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[JwtVerification],
        config = NgPluginInstanceConfig(NgJwtVerificationConfig(verifiers = Seq(verifier.id)).json.as[JsObject])
      )
    )
    val token    = JWT.create().withClaim("sub", "user").sign(Algorithm.HMAC512("hardening-secret"))
    val parts    = token.split("\\.")
    val payload  = Base64.getUrlEncoder.withoutPadding.encodeToString("""{"sub":"admin"}""".getBytes(StandardCharsets.UTF_8))
    val tampered = s"${parts(0)}.$payload.${parts(2)}"
    withClue("the signed token ") { call(r, "/", "Authorization" -> s"Bearer $token") mustBe Status.OK }
    withClue("another payload with the same signature ") {
      call(r, "/", "Authorization" -> s"Bearer $tampered") must not be Status.OK
    }
    deleteOtoroshiRoute(r).futureValue
  }

  // a pem public key used to be accepted as an hmac secret
  def httpSignaturePemAsHmac(): Unit = {
    val pem = "-----BEGIN PUBLIC KEY-----\nMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE\n-----END PUBLIC KEY-----"
    HttpSigKeyResolver
      .publicKey(HttpSigKeyInline(pem, None, None), HttpSigAlgorithms.Hmac256, None)(using env, env.otoroshiExecutionContext)
      .futureValue
      .isLeft mustBe true
    HttpSigKeyResolver
      .publicKey(HttpSigKeyInline("an-hmac-secret", None, None), HttpSigAlgorithms.Hmac256, None)(using
        env,
        env.otoroshiExecutionContext
      )
      .futureValue
      .isRight mustBe true
  }

  def stop(): Unit = backend.stop()
}
