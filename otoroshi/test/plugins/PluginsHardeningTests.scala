package plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import functional.{PluginsTestSpecBase, TargetService}
import org.apache.pekko.Done
import org.apache.pekko.http.scaladsl.model.HttpRequest
import otoroshi.env.Env
import otoroshi.models.{
  ApiKey,
  DefaultToken,
  GlobalJwtVerifier,
  HSAlgoSettings,
  InHeader,
  PassThrough,
  RouteIdentifier,
  VerificationSettings
}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, NgRoute, NgTarget}
import otoroshi.next.plugins.*
import otoroshi.next.plugins.api.*
import otoroshi.next.plugins.wrappers.AccessValidatorWrapper
import otoroshi.plugins.hmac.HMACUtils
import otoroshi.security.IdGenerator
import otoroshi.utils.crypto.Signatures
import otoroshi.utils.JsonPathValidator
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.http.Status
import play.api.libs.json.*

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

// puts the apikey named by a header in the request without any check, the way an extractor plugin does
class PresetApikeyProbe extends NgPreRouting {
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgInternal
  override def categories: Seq[NgPluginCategory]           = Seq.empty
  override def steps: Seq[NgStep]                          = Seq(NgStep.PreRoute)
  override def multiInstance: Boolean                      = true
  override def defaultConfigObject: Option[NgPluginConfig] = None
  override def isPreRouteAsync: Boolean                    = false

  override def preRouteSync(
      ctx: NgPreRoutingContext
  )(using env: Env, ec: ExecutionContext): Either[NgPreRoutingError, Done] = {
    ctx.request.headers.get("X-Preset-Apikey").flatMap(id => env.proxyState.apikey(id)).foreach { apikey =>
      ctx.attrs.put(otoroshi.plugins.Keys.ApiKeyKey -> apikey)
    }
    Right(Done)
  }
}

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

  // the expression language of the openfga config used to be evaluated on the stringified json: a request header
  // could rewrite the json, the url of the check included
  def openFgaElInjection(): Unit = {
    val injectedCalls = new AtomicInteger(0)
    val fga           = TargetService.full(None, "/", "application/json", _ => (200, """{"allowed":false}""", List.empty)).await()
    val injected      = TargetService
      .full(
        None,
        "/",
        "application/json",
        _ => {
          injectedCalls.incrementAndGet()
          (200, """{"allowed":true}""", List.empty)
        }
      )
      .await()
    val r             = route(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OpenFGAValidator],
        config = NgPluginInstanceConfig(
          OpenFGAValidatorConfig(
            url = s"http://127.0.0.1:${fga.port}",
            storeId = "store",
            modelId = "model",
            tupleKey = Json.obj("user" -> "user:anne", "relation" -> "reader", "object" -> "doc:${req.headers.X-Doc}")
          ).json.as[JsObject]
        )
      )
    )
    val header        =
      s"""1"},"url":"http://127.0.0.1:${injected.port}","tuple_key":{"user":"u","relation":"r","object":"doc:1"""
    withClue("a check with a tuple from a header ") { call(r, "/", "X-Doc" -> header) mustBe Status.UNAUTHORIZED }
    withClue("checks sent to the url injected by a header ") { injectedCalls.get() mustBe 0 }
    deleteOtoroshiRoute(r).futureValue
    fga.stop()
    injected.stop()
  }

  // an apikey another plugin put in the request went through none of the checks of the apikey plugin
  def presetApikey(): Unit = {
    val r         = route(
      NgPluginInstance(plugin = NgPluginHelper.pluginId[PresetApikeyProbe]),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[ApikeyCalls],
        config = NgPluginInstanceConfig(NgApikeyCallsConfig().json.as[JsObject])
      )
    )
    val active    = ApiKey(
      clientId = "hardening-preset-active",
      clientSecret = "hardening-preset-secret",
      clientName = "hardening-preset-active",
      authorizedEntities = Seq(RouteIdentifier(r.id))
    )
    val disabled  = active.copy(clientId = "hardening-preset-disabled", enabled = false)
    val elsewhere = active.copy(
      clientId = "hardening-preset-elsewhere",
      authorizedEntities = Seq(RouteIdentifier("hardening-another-route"))
    )
    // through /api/apikeys: the group endpoint of createOtoroshiApiKey would authorize them on the default group too
    Seq(active, disabled, elsewhere).foreach { apikey =>
      otoroshiApiCall("POST", "/api/apikeys", Some(apikey.toJson)).futureValue._2 mustBe Status.CREATED
    }
    await(1.second)
    withClue("an active apikey authorized on the route ") {
      call(r, "/", "X-Preset-Apikey" -> active.clientId) mustBe Status.OK
    }
    withClue("a disabled apikey ") { call(r, "/", "X-Preset-Apikey" -> disabled.clientId) mustBe Status.UNAUTHORIZED }
    withClue("an apikey authorized on another route ") {
      call(r, "/", "X-Preset-Apikey" -> elsewhere.clientId) mustBe Status.UNAUTHORIZED
    }
    Seq(active, disabled, elsewhere).foreach { apikey =>
      otoroshiApiCall("DELETE", s"/api/apikeys/${apikey.clientId}").futureValue
    }
    deleteOtoroshiRoute(r).futureValue
  }

  // without client certificate, the legacy matcher used to check a fake certificate of its own
  def legacyClientCertWithoutCertificate(): Unit = {
    val r = route(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[AccessValidatorWrapper],
        config = NgPluginInstanceConfig(
          Json.obj(
            "plugin"        -> "cp:otoroshi.plugins.clientcert.HasClientCertMatchingValidator",
            "serialNumbers" -> Json.arr("1234567890")
          )
        )
      )
    )
    withClue("a request without client certificate ") { call(r, "/") must not be Status.OK }
    deleteOtoroshiRoute(r).futureValue
  }

  // an hmac signature used to cover neither the date nor the request: a captured one was replayed forever
  def hmacReplay(): Unit = {
    val secret                                                    = "hardening-hmac-secret"
    val bounded                                                   = route(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[HMACValidator],
        config = NgPluginInstanceConfig(
          HMACValidatorConfig(secret = Some(secret), maxDateSkewSeconds = Some(60L), signRequestTarget = true).json
            .as[JsObject]
        )
      )
    )
    val unbounded                                                 = route(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[HMACValidator],
        config = NgPluginInstanceConfig(HMACValidatorConfig(secret = Some(secret)).json.as[JsObject])
      )
    )
    def authorization(headers: String, signingString: String): String = {
      val signature =
        Base64.getEncoder.encodeToString(Signatures.hmac(HMACUtils.Algo("HMAC-SHA256"), signingString, secret))
      s"""hmac algorithm="hmac-sha256", headers="$headers", signature="$signature""""
    }
    val now                                                       = System.currentTimeMillis().toString
    val old                                                       = (System.currentTimeMillis() - 600000L).toString
    val onA                                                       = authorization("(request-target) Date", s"get /a $now")
    withClue("a fresh signature of the request ") {
      call(bounded, "/a", "Date" -> now, "Authorization" -> onA) mustBe Status.OK
    }
    withClue("the same signature on another path ") {
      call(bounded, "/b", "Date" -> now, "Authorization" -> onA) mustBe Status.BAD_REQUEST
    }
    withClue("an old signature ") {
      call(bounded, "/a", "Date" -> old, "Authorization" -> authorization("(request-target) Date", s"get /a $old")) mustBe
      Status.BAD_REQUEST
    }
    withClue("a signature of the date only ") {
      call(bounded, "/a", "Date" -> now, "Authorization" -> authorization("Date", now)) mustBe Status.BAD_REQUEST
    }
    withClue("without the options, a signature of the date only ") {
      call(unbounded, "/a", "Date" -> now, "Authorization" -> authorization("Date", now)) mustBe Status.OK
    }
    deleteOtoroshiRoute(bounded).futureValue
    deleteOtoroshiRoute(unbounded).futureValue
  }

  // fail_if_present used to refuse the requests without a token and to let the ones with a token through
  def jwtSignerFailIfPresent(): Unit = {
    val verifier = GlobalJwtVerifier(
      id = "hardening-signer-verifier",
      name = "hardening-signer-verifier",
      desc = "hardening-signer-verifier",
      strict = true,
      source = InHeader(name = "X-Signed-Token"),
      algoSettings = HSAlgoSettings(512, "hardening-signer-secret"),
      strategy = DefaultToken(token = Json.obj("sub" -> "signed"))
    )
    createOtoroshiVerifier(verifier).futureValue
    val failing  = route(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[JwtSigner],
        config = NgPluginInstanceConfig(
          NgJwtSignerConfig(verifier = Some(verifier.id), failIfPresent = true).json.as[JsObject]
        )
      )
    )
    val default  = route(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[JwtSigner],
        config = NgPluginInstanceConfig(Json.obj("verifier" -> verifier.id))
      )
    )
    withClue("fail if present, without token ") { call(failing, "/") mustBe Status.OK }
    withClue("fail if present, with a token ") {
      call(failing, "/", "X-Signed-Token" -> "a-token") mustBe Status.BAD_REQUEST
    }
    withClue("default config, with a token ") { call(default, "/", "X-Signed-Token" -> "a-token") mustBe Status.OK }
    deleteOtoroshiRoute(failing).futureValue
    deleteOtoroshiRoute(default).futureValue
  }

  // without created, the max age of an http signature did not apply
  def httpSignatureWithoutCreated(): Unit = {
    val secret                                   = "hardening-httpsig-secret-0123456789".getBytes(StandardCharsets.UTF_8)
    val secretB64                                = Base64.getEncoder.encodeToString(secret)
    val r                                        = route(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[HttpSignatureVerifyRequest],
        config = NgPluginInstanceConfig(
          HttpSignatureVerifyRequestConfig(
            keys = List(HttpSigKeyInline(s"base64:$secretB64", Some("hardening-key"), Some("hmac-sha256"))),
            maxAgeSeconds = Some(300L),
            mandatory = true
          ).json.as[JsObject]
        )
      )
    )
    val host                                     = r.frontend.domains.head.domain
    def signed(withCreated: Boolean): Seq[(String, String)] = {
      val params     = Seq("keyid" -> HttpSigStructuredFields.ParamString("hardening-key")) ++
        (if (withCreated)
           Seq("created" -> HttpSigStructuredFields.ParamInt(System.currentTimeMillis() / 1000L))
         else Seq.empty) ++
        Seq("alg" -> HttpSigStructuredFields.ParamString("hmac-sha256"))
      val components = List(
        HttpSigStructuredFields.ComponentId("@method", Nil),
        HttpSigStructuredFields.ComponentId("@target-uri", Nil)
      )
      val sigInput   = HttpSigStructuredFields.SignatureInputValue(components, params.toList)
      val message    = SimpleSigMessage(
        method = "GET",
        fullUri = s"http://$host/api",
        headers = Seq("Host" -> host),
        status = None
      )
      val base       = HttpSigBase.build(message, sigInput, None).toOption.get
      val sigBytes   =
        HttpSigAlgorithms.sign("hmac-sha256", base.getBytes(StandardCharsets.UTF_8), Left(secret)).toOption.get
      Seq(
        "Signature-Input" -> HttpSigStructuredFields.serializeSignatureInputDict(List("sig1" -> sigInput)),
        "Signature"       -> HttpSigStructuredFields.serializeSignatureDict(List("sig1" -> sigBytes))
      )
    }
    withClue("a signature with created ") { call(r, "/api", signed(withCreated = true)*) mustBe Status.OK }
    withClue("a signature without created ") { call(r, "/api", signed(withCreated = false)*) must not be Status.OK }
    deleteOtoroshiRoute(r).futureValue
  }

  def stop(): Unit = backend.stop()
}
