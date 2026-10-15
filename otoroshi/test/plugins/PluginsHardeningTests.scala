package plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import functional.{PluginsTestSpecBase, TargetService}
import org.apache.pekko.http.scaladsl.model.HttpRequest
import otoroshi.models.{
  ApiKey,
  GlobalJwtVerifier,
  HSAlgoSettings,
  InHeader,
  PassThrough,
  RouteIdentifier,
  VerificationSettings
}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, NgRoute, NgTarget}
import otoroshi.next.plugins.*
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.security.IdGenerator
import otoroshi.utils.JsonPathValidator
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.http.Status
import play.api.libs.json.*

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.DurationInt

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

  // the apikey plugin validates the apikey for each candidate route of a request: the verdict for one route used to
  // be reused for the next one. both routes serve the same path, the frontend header of the first one makes it the
  // first candidate
  def apikeyRoutingVerdict(): Unit = {
    val domain   = s"apikey-routing-${IdGenerator.uuid}.oto.tools"
    val target   = Some(NgTarget(id = "hardening-backend", hostname = "127.0.0.1", port = backend.port, tls = false))
    val backendB = TargetService.full(None, "/", "application/json", _ => (200, """{"route":"b"}""", List.empty)).await()
    val routeA = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[ApikeyCalls],
          config = NgPluginInstanceConfig(
            NgApikeyCallsConfig(routing = NgApikeyMatcher(enabled = true, oneTagIn = Seq("route-a"))).json.as[JsObject]
          )
        )
      ),
      domain = Some(domain),
      target = target
    ).futureValue
      .applyOn(r => r.copy(frontend = r.frontend.copy(headers = Map("X-Hardening-Route" -> "any"))))
    updateOtoroshiRoute(routeA).futureValue
    val routeB = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[ApikeyCalls],
          config = NgPluginInstanceConfig(NgApikeyCallsConfig().json.as[JsObject])
        )
      ),
      domain = Some(domain),
      target = Some(NgTarget(id = "hardening-backend-b", hostname = "127.0.0.1", port = backendB.port, tls = false))
    ).futureValue
    val onA    = ApiKey(
      clientId = "hardening-on-a",
      clientSecret = "hardening-on-a-secret",
      clientName = "hardening-on-a",
      authorizedEntities = Seq(RouteIdentifier(routeA.id))
    )
    val onB    = ApiKey(
      clientId = "hardening-on-b",
      clientSecret = "hardening-on-b-secret",
      clientName = "hardening-on-b",
      authorizedEntities = Seq(RouteIdentifier(routeB.id))
    )
    // through /api/apikeys: the group endpoint of createOtoroshiApiKey would authorize them on the default group too
    otoroshiApiCall("POST", "/api/apikeys", Some(onA.toJson)).futureValue._2 mustBe Status.CREATED
    otoroshiApiCall("POST", "/api/apikeys", Some(onB.toJson)).futureValue._2 mustBe Status.CREATED
    await(1.second)
    def callWith(apikey: ApiKey): (Int, String) = ws
      .url(s"http://127.0.0.1:$port/api/x")
      .withHttpHeaders(
        "Host"                   -> domain,
        "X-Hardening-Route"      -> "any",
        "Otoroshi-Client-Id"     -> apikey.clientId,
        "Otoroshi-Client-Secret" -> apikey.clientSecret
      )
      .get()
      .futureValue
      .applyOn(resp => (resp.status, resp.body))
    val (statusB, bodyB) = callWith(onB)
    withClue(s"an apikey of the second route: $bodyB ") { statusB mustBe Status.OK }
    val (statusA, bodyA) = callWith(onA)
    withClue(s"an apikey of the first route only, that fails its routing: $bodyA ") { statusA must not be Status.OK }
    otoroshiApiCall("DELETE", s"/api/apikeys/${onA.clientId}").futureValue
    otoroshiApiCall("DELETE", s"/api/apikeys/${onB.clientId}").futureValue
    deleteOtoroshiRoute(routeA).futureValue
    deleteOtoroshiRoute(routeB).futureValue
    backendB.stop()
  }

  // the apikey plugin wipes the credentials from the backend request where it recorded them: it recorded the wrong
  // place for the otoroshi bearer and for a jwt in the otoroshi token header, and missed the custom secret header
  def apikeyWipe(): Unit = {
    val received = new AtomicReference[HttpRequest]()
    val target   = TargetService
      .full(
        None,
        "/",
        "application/json",
        req => {
          received.set(req)
          (200, "{}", List.empty)
        }
      )
      .await()
    val config   = NgApikeyCallsConfig(extractors =
      NgApikeyExtractors(customHeaders =
        NgApikeyExtractorCustomHeaders(
          clientIdHeaderName = Some("X-Hardening-Id"),
          clientSecretHeaderName = Some("X-Hardening-Secret")
        )
      )
    )
    val r        = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[ApikeyCalls],
          config = NgPluginInstanceConfig(config.json.as[JsObject])
        )
      ),
      target = Some(NgTarget(id = "hardening-wipe-backend", hostname = "127.0.0.1", port = target.port, tls = false))
    ).futureValue
    val apikey   = ApiKey(
      clientId = "hardening-wipe",
      clientSecret = "hardening-wipe-secret",
      clientName = "hardening-wipe",
      authorizedEntities = Seq(RouteIdentifier(r.id))
    )
    createOtoroshiApiKey(apikey).futureValue
    await(1.second)
    def forwarded(headers: (String, String)*): String = {
      val status = ws
        .url(s"http://127.0.0.1:$port/")
        .withHttpHeaders((Seq("Host" -> r.frontend.domains.head.domain) ++ headers)*)
        .get()
        .futureValue
        .status
      status mustBe Status.OK
      received.get().headers.map(h => s"${h.name()}: ${h.value()}").mkString("\n")
    }
    val bearer   = apikey.toBearer()
    val jwt      = JWT
      .create()
      .withClaim("clientId", apikey.clientId)
      .withIssuedAt(java.time.Instant.now())
      .withExpiresAt(java.time.Instant.now().plusSeconds(60))
      .sign(Algorithm.HMAC256(apikey.clientSecret))
    withClue("custom secret header ") {
      forwarded("X-Hardening-Id" -> apikey.clientId, "X-Hardening-Secret" -> apikey.clientSecret) must not include apikey.clientSecret
    }
    withClue("otoroshi bearer in the otoroshi token header ") {
      forwarded(env.Headers.OtoroshiBearer -> bearer) must not include bearer
    }
    withClue("otoroshi bearer in a cookie ") { forwarded("Cookie" -> s"bearer=$bearer") must not include bearer }
    withClue("jwt in the otoroshi token header ") { forwarded(env.Headers.OtoroshiBearer -> jwt) must not include jwt }
    deleteOtoroshiApiKey(apikey).futureValue
    deleteOtoroshiRoute(r).futureValue
    target.stop()
  }

  // a not match rule of the allowed users plugin used to let in every user it did not keep out, whatever the other
  // rules, a match rule alone nobody, and an email domain matched any domain ending the same way
  def allowedUsers(): Unit = {
    val verifier                                                = GlobalJwtVerifier(
      id = "hardening-users-verifier",
      name = "hardening-users-verifier",
      desc = "hardening-users-verifier",
      strict = true,
      source = InHeader(name = "Authorization", remove = "Bearer "),
      algoSettings = HSAlgoSettings(512, "hardening-users-secret"),
      strategy = PassThrough(verificationSettings = VerificationSettings())
    )
    createOtoroshiVerifier(verifier).futureValue
    def usersRoute(config: NgHasAllowedUsersValidatorConfig): NgRoute = route(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[NgJwtUserExtractor],
        config = NgPluginInstanceConfig(NgJwtUserExtractorConfig(verifier.id).json.as[JsObject])
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[NgHasAllowedUsersValidator],
        config = NgPluginInstanceConfig(config.json.as[JsObject])
      )
    )
    def user(name: String, email: String, claims: String*): (String, String) = {
      val token = claims
        .foldLeft(JWT.create().withClaim("name", name).withClaim("email", email))((b, claim) => b.withClaim(claim, true))
        .sign(Algorithm.HMAC512("hardening-users-secret"))
      "Authorization" -> s"Bearer $token"
    }
    val byName = usersRoute(NgHasAllowedUsersValidatorConfig(usernames = Seq("alice"), profileNotMatch = Seq("$.banned")))
    withClue("an allowed username ") { call(byName, "/", user("alice", "alice@other.org")) mustBe Status.OK }
    withClue("another user, not banned ") { call(byName, "/", user("bob", "bob@other.org")) mustBe Status.FORBIDDEN }
    val byRules = usersRoute(NgHasAllowedUsersValidatorConfig(emailDomains = Seq("example.com"), profileMatch = Seq("$.admin")))
    withClue("an email of the domain ") { call(byRules, "/", user("carol", "carol@example.com")) mustBe Status.OK }
    withClue("an email of a subdomain ") { call(byRules, "/", user("dave", "dave@eu.example.com")) mustBe Status.OK }
    withClue("an email of a domain ending the same way ") {
      call(byRules, "/", user("eve", "eve@evilexample.com")) mustBe Status.FORBIDDEN
    }
    withClue("a profile matching a match rule alone ") {
      call(byRules, "/", user("frank", "frank@other.org", "admin")) mustBe Status.OK
    }
    deleteOtoroshiRoute(byName).futureValue
    deleteOtoroshiRoute(byRules).futureValue
  }

  def stop(): Unit = backend.stop()
}
