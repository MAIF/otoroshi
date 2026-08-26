package functional

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.typesafe.config.ConfigFactory
import otoroshi.api.Otoroshi
import otoroshi.auth.{GenericOauth2ModuleConfig, SessionCookieValues}
import otoroshi.env.Env
import otoroshi.models.{
  ApiIdentifier,
  ApiKey,
  EntityLocation,
  GlobalJwtVerifier,
  HSAlgoSettings,
  InHeader,
  PassThrough,
  VerificationSettings
}
import otoroshi.next.models.*
import otoroshi.next.plugins.AdditionalHeadersOut
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.security.IdGenerator
import otoroshi.ssl.{Cert, ClientAuth, DynamicSSLEngineProvider, FakeKeyStore}
import otoroshi.utils.syntax.implicits.*
import play.api.Configuration
import play.api.libs.json.*
import play.core.server.ServerConfig
import plugins.{ApiPlanApikeyProbe, ApiPlanApikeySeen}

import java.io.{BufferedReader, InputStreamReader}
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.cert.{Certificate, X509Certificate}
import java.security.{KeyStore, PrivateKey, SecureRandom}
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.{KeyManager, KeyManagerFactory, SNIHostName, SSLContext, SSLSocket, TrustManager, X509TrustManager}
import scala.concurrent.duration.*
import scala.util.Try

/**
 * End to end validation of an api plan of kind "mtls": the plan is expected to turn a client
 * certificate into a consumer identity, so that its quotas apply without any credential in the
 * request itself.
 *
 * It needs a real TLS handshake carrying a client certificate, so it runs its own Otoroshi instance
 * with an HTTPS listener rather than living in PluginsTestSpec. The client is a raw SSLSocket, like
 * FrontendTlsSpec, so the handshake outcome is observed for real.
 *
 * That instance also hosts the scenario of scripts/api-plans-demo.ts: one api publishing one plan of
 * each kind on a single route, called once per kind of consumer. It lives here because the mtls plan
 * of that api needs the very same HTTPS listener.
 */
class ApiPlanMtlsSpec(configurationSpec: => Configuration) extends OtoroshiSpec {

  private val otoRef      = new AtomicReference[Otoroshi]()
  private var otoEnv: Env = scala.compiletime.uninitialized

  private val apiDomain = "mtlsplan.foo.tools"

  private var matchingClient: (PrivateKey, Array[X509Certificate]) = scala.compiletime.uninitialized
  private var otherClient: (PrivateKey, Array[X509Certificate])    = scala.compiletime.uninitialized

  override def getTestConfiguration(configuration: Configuration): Configuration = {
    Configuration(
      ConfigFactory
        .parseString(s"""
          |otoroshi.next.state-sync-interval = 5
          |otoroshi.ssl.fromOutside.clientAuth = "Dynamic"
          |""".stripMargin)
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)
  }

  // -------------------------------------------------------------------------------------------------
  // instance, certificates and tls probe: same harness as FrontendTlsSpec
  // -------------------------------------------------------------------------------------------------

  private def startInstance(): Unit = {
    val otoroshi = Otoroshi(
      ServerConfig(
        address = "0.0.0.0",
        port = Some(port),
        sslPort = Some(httpsPort),
        rootDir = Files.createTempDirectory("otoroshi-api-plan-mtls").toFile
      ),
      getTestConfiguration(Configuration(ConfigFactory.parseString("").resolve())).underlying
    )
    otoRef.set(otoroshi.startAndStopOnShutdown())
    otoEnv = otoroshi.env
    awaitCond(30.seconds) {
      Try(
        wsClient.url(s"http://127.0.0.1:$port/health").withRequestTimeout(1.second).get().futureValue.status == 200
      ).getOrElse(false)
    }
  }

  private def awaitCond(timeout: FiniteDuration)(cond: => Boolean): Unit = {
    val deadlineMs = System.currentTimeMillis() + timeout.toMillis
    while (!cond && System.currentTimeMillis() < deadlineMs) { await(300.millis) }
    if (!cond) throw new RuntimeException("condition not met within timeout")
  }

  private def serverCert(host: String)(using e: Env): Cert = {
    val ca = FrontendTlsSpec.testCa(e)
    FakeKeyStore
      .createCertificateFromCA(host, 3650.days, None, None, ca.cert, ca.caChain, ca.keyPair)(using e)
      .toCert
      .copy(id = s"mtlsplan-srv-${IdGenerator.token(8)}", name = host, description = host)
  }

  private def clientCert(dn: String)(using e: Env): (PrivateKey, Array[X509Certificate]) = {
    val ca   = FrontendTlsSpec.testCa(e)
    val resp = FakeKeyStore.createClientCertificateFromCA(dn, 3650.days, None, None, ca.cert, ca.caChain, ca.keyPair)(
      using e
    )
    (resp.key, Array(resp.cert, resp.ca))
  }

  private def clientSslContext(clientCert: Option[(PrivateKey, Array[X509Certificate])]): SSLContext = {
    val trustAll: Array[TrustManager] = Array(new X509TrustManager {
      def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit = ()
      def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit = ()
      def getAcceptedIssuers: Array[X509Certificate]                                = Array.empty
    })
    val kms: Array[KeyManager]        = clientCert match {
      case None               => null
      case Some((key, chain)) =>
        val ks = KeyStore.getInstance("JKS")
        ks.load(null, null)
        ks.setKeyEntry("client", key, Array.emptyCharArray, chain.asInstanceOf[Array[Certificate]])
        val kmf = KeyManagerFactory.getInstance("SunX509")
        kmf.init(ks, Array.emptyCharArray)
        kmf.getKeyManagers
    }
    val ctx                           = SSLContext.getInstance("TLS")
    ctx.init(kms, trustAll, new SecureRandom())
    ctx
  }

  // the response headers are read too: the plan demo below needs them to tell which plan served the
  // call. header names come back lowercased.
  private def tlsCall(
      host: String,
      clientCert: Option[(PrivateKey, Array[X509Certificate])]
  ): Option[(Int, Map[String, String])] = {
    val ctx    = clientSslContext(clientCert)
    val socket = ctx.getSocketFactory.createSocket().asInstanceOf[SSLSocket]
    try {
      socket.connect(new InetSocketAddress("127.0.0.1", httpsPort), 5000)
      socket.setSoTimeout(5000)
      socket.setEnabledProtocols(Array("TLSv1.2"))
      val params = socket.getSSLParameters
      params.setServerNames(java.util.List.of[javax.net.ssl.SNIServerName](new SNIHostName(host)))
      socket.setSSLParameters(params)
      socket.startHandshake()
      val out    = socket.getOutputStream
      out.write(s"GET / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".getBytes("UTF-8"))
      out.flush()
      val in         = new BufferedReader(new InputStreamReader(socket.getInputStream))
      val statusLine = in.readLine()
      Option(statusLine).flatMap(l => Try(l.split(" ")(1).toInt).toOption).map { status =>
        // the headers stop at the first empty line, the body is of no interest here
        val headers = Iterator
          .continually(in.readLine())
          .takeWhile(line => line != null && line.trim.nonEmpty)
          .flatMap { line =>
            val idx = line.indexOf(':')
            if (idx > 0) Seq(line.substring(0, idx).trim.toLowerCase -> line.substring(idx + 1).trim) else Seq.empty
          }
          .toMap
        (status, headers)
      }
    } catch {
      case _: Throwable => None
    } finally Try(socket.close())
  }

  private def call(clientCert: Option[(PrivateKey, Array[X509Certificate])]): Option[Int] =
    tlsCall(apiDomain, clientCert).map(_._1)

  // -------------------------------------------------------------------------------------------------
  // the api under test
  // -------------------------------------------------------------------------------------------------

  private def probePlugin(tag: String): NgPluginInstance = NgPluginInstance(
    plugin = NgPluginHelper.pluginId[ApiPlanApikeyProbe],
    config = NgPluginInstanceConfig(Json.obj("tag" -> tag))
  )

  private def deployApi(planId: String, accessModeConfiguration: JsObject, tag: String): Api = {
    given Env  = otoEnv
    val backend = ApiBackend.empty
    val flow    = ApiFlows.empty.copy(plugins = NgPlugins(ApiFlows.empty.plugins.slots :+ probePlugin(tag)))
    val api     = Api(
      location = EntityLocation.default,
      id = s"api_${IdGenerator.uuid}",
      name = "mtls-plan-api",
      description = "",
      domain = apiDomain,
      contextPath = "",
      version = "0.0.1",
      debugFlow = false,
      capture = false,
      exportReporting = false,
      groups = Seq.empty,
      state = ApiPublished,
      blueprint = ApiBlueprint.REST,
      testing = ApiTesting(),
      backends = Seq(backend),
      flows = Seq(flow),
      plans = Seq(
        ApiPlan(
          Json.obj(
            "id"                             -> planId,
            "name"                           -> planId,
            "status"                         -> "published",
            "access_mode_configuration_type" -> "mtls",
            "access_mode_configuration"      -> accessModeConfiguration
          )
        )
      ),
      routes = Seq(
        ApiRoute(
          id = s"apiroute_${IdGenerator.uuid}",
          frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath("/"))),
          flowRef = flow.id,
          backend = backend.id
        )
      )
    )
    otoEnv.datastores.apiDataStore.set(api).futureValue
    await(8.seconds) // let the proxy state job generate the routes
    api
  }

  private def undeploy(api: Api): Unit = {
    given Env = otoEnv
    otoEnv.datastores.apiDataStore.delete(api).futureValue
    await(8.seconds)
  }

  "An api plan of kind mtls" should {

    "warm up" in {
      startInstance()
      given Env = otoEnv
      DynamicSSLEngineProvider.addCertificates(
        Seq(
          // the test CA has to be known so that client certs signed by it are trusted
          FrontendTlsSpec.testCa(otoEnv).toCert.copy(id = "mtlsplan-ca", name = "mtlsplan-ca", description = "ca", ca = true),
          serverCert(apiDomain)
        ),
        otoEnv
      )
      matchingClient = clientCert("CN=mtls-client, O=Otoroshi Test")
      otherClient = clientCert("CN=other-client, O=Otoroshi Test")
      // the handshake must complete without a cert too, so that the plan itself decides
      val gc = getOtoroshiConfig(customPort = Some(port)).futureValue
      updateOtoroshiConfig(
        gc.copy(tlsSettings = gc.tlsSettings.copy(clientAuth = ClientAuth.Want)),
        customPort = Some(port)
      ).futureValue
      await(2.seconds)
    }

    "turn a matching client certificate into a consumer identity" in {
      ApiPlanApikeySeen.reset()
      val api = deployApi(
        "plan-mtls-cn",
        Json.obj("regex_subject_dns" -> Json.arr(".*CN=mtls-client.*"), "client_id_field" -> "CN"),
        "mtls-cn"
      )
      try {
        call(Some(matchingClient)) mustBe Some(200)
        // the named DN attribute becomes the consumer identity
        ApiPlanApikeySeen.get("mtls-cn") mustBe Some("mtls_plan-mtls-cn_mtls-client")
      } finally undeploy(api)
    }

    "reject a client certificate that does not match the plan patterns" in {
      ApiPlanApikeySeen.reset()
      val api = deployApi(
        "plan-mtls-reject",
        Json.obj("regex_subject_dns" -> Json.arr(".*CN=mtls-client.*"), "client_id_field" -> "CN"),
        "mtls-reject"
      )
      try {
        // no apikey is minted, so the expected consumer check rejects the call
        call(Some(otherClient)) mustBe Some(401)
        ApiPlanApikeySeen.get("mtls-reject") mustBe None
      } finally undeploy(api)
    }

    "reject a call with no client certificate at all" in {
      ApiPlanApikeySeen.reset()
      val api = deployApi(
        "plan-mtls-nocert",
        Json.obj("regex_subject_dns" -> Json.arr(".*CN=mtls-client.*"), "client_id_field" -> "CN"),
        "mtls-nocert"
      )
      try {
        call(None) mustBe Some(401)
        ApiPlanApikeySeen.get("mtls-nocert") mustBe None
      } finally undeploy(api)
    }

    "derive a stable identity from the certificate when no DN attribute is named" in {
      ApiPlanApikeySeen.reset()
      val api = deployApi(
        "plan-mtls-derived",
        Json.obj("regex_subject_dns" -> Json.arr(".*CN=mtls-client.*")),
        "mtls-derived"
      )
      try {
        call(Some(matchingClient)) mustBe Some(200)
        val first = ApiPlanApikeySeen.get("mtls-derived")
        first.isDefined mustBe true
        first.get.startsWith("mtls_plan-mtls-derived_") mustBe true

        // the same certificate always maps to the same consumer, so its quotas add up
        ApiPlanApikeySeen.reset()
        call(Some(matchingClient)) mustBe Some(200)
        ApiPlanApikeySeen.get("mtls-derived") mustBe first
      } finally undeploy(api)
    }

    "accept any trusted certificate when the plan declares no pattern" in {
      ApiPlanApikeySeen.reset()
      val api = deployApi("plan-mtls-open", Json.obj("client_id_field" -> "CN"), "mtls-open")
      try {
        // with no pattern the handshake is the only requirement, but each certificate still gets
        // its own identity
        call(Some(otherClient)) mustBe Some(200)
        ApiPlanApikeySeen.get("mtls-open") mustBe Some("mtls_plan-mtls-open_other-client")
      } finally undeploy(api)
    }
  }

  // -------------------------------------------------------------------------------------------------
  // the scenario of scripts/api-plans-demo.ts, run on the instance of this spec: one api, one route
  // and one plan of each kind, then one call per kind of consumer.
  //
  // every published plan stacks its own extractor on the single route, ordered by plugin index:
  // ApikeyCalls sits at 2.00 and the three other extractors right after it, the keyless one last at
  // 900. They all write to the same attribute and the first one to resolve an identity wins, so
  // X-Demo-Consumer and X-Demo-Plan tell which plan actually served a call, and X-Plan-Plugin proves
  // that the plugin chain of that very plan ran too.
  // -------------------------------------------------------------------------------------------------

  private val demoPrefix         = "apiplansdemo"
  private val demoDomain         = s"$demoPrefix.foo.tools"
  private val demoJwtSecret      = "demo-jwt-secret"
  private val demoOidcSecret     = "demo-oidc-secret"
  private val demoOauth2Secret   = "demo-oauth2-secret"
  private val demoApikeySecret   = "demo-secret"
  private val demoVerifierId     = s"$demoPrefix-verifier"
  private val demoAuthModuleId   = s"$demoPrefix-auth-module"
  private val demoApikeyId       = s"$demoPrefix-apikey"
  private val demoOauth2ApikeyId = s"$demoPrefix-oauth2-apikey"

  private def demoJwt(secret: String, claims: (String, String)*): String = {
    val builder = JWT.create()
    claims.foreach { case (name, value) => builder.withClaim(name, value) }
    builder.sign(Algorithm.HMAC512(secret))
  }

  // the plugins of a plan reach the runtime through the apiRef of the apikey: pluginFlow resolves
  // api + plan, and handleApikeyPluginsFlow merges them into the chain of the call. same header
  // everywhere, one value per plan, so a call proves which plan chain actually ran.
  private def demoPlan(kind: String, accessModeConfiguration: JsObject): ApiPlan = ApiPlan(
    Json.obj(
      "id"                             -> s"$demoPrefix-$kind-plan",
      "name"                           -> s"$kind plan",
      "description"                    -> s"a $kind plan",
      "status"                         -> "published",
      "access_mode_configuration_type" -> kind,
      "access_mode_configuration"      -> accessModeConfiguration,
      "visibility"                     -> Json.obj("kind" -> "public", "config" -> Json.obj()),
      "validation"                     -> Json.obj("kind" -> "auto", "config" -> Json.obj()),
      "pricing"                        -> Json.obj(
        "id"       -> "free",
        "name"     -> "free",
        "enabled"  -> false,
        "price"    -> 0.0,
        "currency" -> "EUR",
        "params"   -> Json.obj()
      ),
      "tags"                           -> Json.arr(s"$demoPrefix-$kind"),
      "metadata"                       -> Json.obj("demo" -> kind),
      "plugins"                        -> ApiPlanPlugins(
        plugins = NgPlugins(
          Seq(
            NgPluginInstance(
              plugin = NgPluginHelper.pluginId[AdditionalHeadersOut],
              config = NgPluginInstanceConfig(
                Json.obj("headers" -> Json.obj("X-Plan-Plugin" -> s"from-$kind-plan"))
              )
            )
          )
        ),
        overrides = false
      ).json
    )
  )

  private def demoApi(targetPort: Int)(using env: Env): Api = {
    val backend = ApiBackend(
      id = s"$demoPrefix-backend",
      name = "demo-backend",
      backend = NgBackend.empty.copy(
        targets = Seq(NgTarget(id = "target_1", hostname = "127.0.0.1", port = targetPort, tls = false))
      ),
      client = "default_backend_client"
    )
    // surfaces the identity that actually reached the backend, whichever plan produced it
    val flow    = ApiFlows.empty.copy(
      plugins = NgPlugins(
        ApiFlows.empty.plugins.slots :+ NgPluginInstance(
          plugin = NgPluginHelper.pluginId[AdditionalHeadersOut],
          config = NgPluginInstanceConfig(
            Json.obj(
              "headers" -> Json.obj(
                "X-Demo-Consumer" -> "${apikey.clientId}",
                "X-Demo-Plan"     -> "${apikey.api.plan}",
                "X-Demo-User"     -> "${apikey.metadata.user_profile:none}"
              )
            )
          )
        )
      )
    )
    Api(
      location = EntityLocation.default,
      id = demoPrefix,
      name = s"$demoPrefix demo",
      description = "one api, one route, one plan of each kind",
      domain = demoDomain,
      contextPath = "",
      version = "0.0.1",
      debugFlow = false,
      capture = false,
      exportReporting = false,
      groups = Seq.empty,
      state = ApiPublished,
      blueprint = ApiBlueprint.REST,
      testing = ApiTesting(),
      backends = Seq(backend),
      flows = Seq(flow),
      plans = Seq(
        demoPlan("keyless", Json.obj("expr" -> "${req.ip}", "create_if_missing" -> true)),
        demoPlan("apikey", Json.obj()),
        demoPlan(
          "jwt",
          Json.obj("verifier" -> demoVerifierId, "client_id_path" -> "client_id", "create_if_missing" -> true)
        ),
        demoPlan(
          "mtls",
          Json.obj(
            "regex_subject_dns" -> Json.arr(s".*CN=$demoPrefix-client.*"),
            "client_id_field"   -> "UID",
            "create_if_missing" -> true
          )
        ),
        demoPlan("oauth2-local", Json.obj()),
        demoPlan(
          "oauth2-remote",
          Json.obj(
            "verifier"          -> demoAuthModuleId,
            "client_id_path"    -> "client_id",
            "fetch_user"        -> true,
            "user_metadata_key" -> "user_profile",
            "create_if_missing" -> true
          )
        )
      ),
      routes = Seq(
        ApiRoute(
          id = s"$demoPrefix-route",
          name = "demo-route".some,
          frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath("/"))),
          flowRef = flow.id,
          backend = backend.id
        )
      )
    )
  }

  private def demoApikey(id: String, secret: String, planId: String): ApiKey = ApiKey(
    clientId = id,
    clientSecret = secret,
    clientName = id,
    authorizedEntities = Seq(ApiIdentifier(demoPrefix)),
    apiRef = ApiRef(demoPrefix, planId, "xxx").some,
    enabled = true
  )

  private case class DemoCall(how: String, status: Int, headers: Map[String, String]) {
    // an unresolved expression means no apikey was in the context
    private def resolved(name: String): Option[String] = headers.get(name).filterNot(_.contains("${"))
    def consumer: Option[String]                       = resolved("x-demo-consumer")
    def plan: Option[String]                           = resolved("x-demo-plan")
    def user: Option[String]                           = resolved("x-demo-user")
    def planPlugin: Option[String]                     = headers.get("x-plan-plugin")
  }

  private def demoHttpCall(how: String, headers: (String, String)*): DemoCall = {
    val resp = ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders(Seq("Host" -> demoDomain) ++ headers*)
      .get()
      .futureValue
    DemoCall(how, resp.status, resp.headers.map { case (k, v) => (k.toLowerCase, v.headOption.getOrElse("")) }.toMap)
  }

  private def demoMtlsCall(how: String, cert: (PrivateKey, Array[X509Certificate])): DemoCall = {
    tlsCall(demoDomain, Some(cert)) match {
      case Some((status, headers)) => DemoCall(how, status, headers)
      case None                    => DemoCall(how, 0, Map.empty)
    }
  }

  "An api publishing one plan of each kind" should {

    "serve one consumer of each kind on a single route" in {
      given Env = otoEnv

      // the backend of the api, and the fake oidc userinfo endpoint the oauth2-remote plan calls to
      // fetch the profile of the token holder. otoroshi posts the access token to it as a form, so
      // no OIDC flow is implemented here, only that endpoint.
      val backendServer = TargetService
        .full(None, "/", "application/json", _ => (200, """{"message":"hello world"}""", List.empty))
        .await()
      val oidcServer    = TargetService
        .full(
          None,
          "/userinfo",
          "application/json",
          _ =>
            (
              200,
              Json
                .obj(
                  "sub"    -> "consumer-from-oidc",
                  "name"   -> "Demo Consumer",
                  "email"  -> "demo.consumer@example.com",
                  "groups" -> Json.arr("demo"),
                  "mock"   -> true
                )
                .stringify,
              List.empty
            )
        )
        .await()

      // a server certificate for the demo domain, and a client certificate carrying the UID that the
      // mtls plan turns into a consumer identity. both are signed by the CA trusted in the warm up.
      DynamicSSLEngineProvider.addCertificates(Seq(serverCert(demoDomain)), otoEnv)
      val demoClient = clientCert(s"UID=demo-consumer, CN=$demoPrefix-client, O=Otoroshi Test")

      val verifier   = GlobalJwtVerifier(
        id = demoVerifierId,
        name = demoVerifierId,
        desc = "demo verifier for the jwt plan",
        strict = true,
        source = InHeader(name = "Authorization", remove = "Bearer "),
        algoSettings = HSAlgoSettings(512, demoJwtSecret),
        strategy = PassThrough(verificationSettings = VerificationSettings())
      )
      // NgOidcApikeyExtractor only needs the jwtVerifier of the module: it mounts a LocalJwtVerifier
      // on those algo settings and never talks to the authorization server, so an HS512 secret is
      // enough. the userinfo endpoint above is only needed by fetch_user.
      val authModule = GenericOauth2ModuleConfig(
        id = demoAuthModuleId,
        name = demoAuthModuleId,
        desc = "demo oidc module for the oauth2-remote plan",
        clientSideSessionEnabled = true,
        clientId = "demo-client",
        clientSecret = "demo-client-secret",
        userInfoUrl = s"http://127.0.0.1:${oidcServer.port}/userinfo",
        jwtVerifier = HSAlgoSettings(512, demoOidcSecret).some,
        tags = Seq.empty,
        metadata = Map.empty,
        sessionCookieValues = SessionCookieValues()
      )

      val api          = demoApi(backendServer.port)
      val apikey       = demoApikey(demoApikeyId, demoApikeySecret, s"$demoPrefix-apikey-plan")
      val oauth2Apikey = demoApikey(demoOauth2ApikeyId, demoOauth2Secret, s"$demoPrefix-oauth2-local-plan")

      otoEnv.datastores.globalJwtVerifierDataStore.set(verifier).futureValue
      otoEnv.datastores.authConfigsDataStore.set(authModule).futureValue
      otoEnv.datastores.apiKeyDataStore.set(apikey).futureValue
      otoEnv.datastores.apiKeyDataStore.set(oauth2Apikey).futureValue
      otoEnv.datastores.apiDataStore.set(api).futureValue

      try {
        // the proxy state job is what turns the api into a route and publishes the other entities
        awaitCond(30.seconds) {
          otoEnv.proxyState.allRoutes().exists(_.apiRef.exists(_.id == api.id)) &&
          otoEnv.proxyState.apikey(demoApikeyId).isDefined &&
          otoEnv.proxyState.apikey(demoOauth2ApikeyId).isDefined &&
          otoEnv.proxyState.jwtVerifier(demoVerifierId).isDefined &&
          otoEnv.proxyState.authModule(demoAuthModuleId).isDefined
        }

        case class Expected(call: DemoCall, kind: String, consumer: String)
        val results = Seq(
          Expected(demoHttpCall("no credential at all"), "keyless", s"keyless_$demoPrefix-keyless-plan_127.0.0.1"),
          Expected(
            demoHttpCall(
              "Otoroshi-Client-Id / Secret",
              "Otoroshi-Client-Id"     -> demoApikeyId,
              "Otoroshi-Client-Secret" -> demoApikeySecret
            ),
            "apikey",
            demoApikeyId
          ),
          Expected(
            demoHttpCall(
              "Bearer token, client_id claim",
              "Authorization" -> s"Bearer ${demoJwt(demoJwtSecret, "iss" -> "demo", "client_id" -> "consumer-from-token")}"
            ),
            "jwt",
            "consumer-from-token"
          ),
          Expected(
            demoMtlsCall("client certificate", demoClient),
            "mtls",
            s"mtls_$demoPrefix-mtls-plan_demo-consumer"
          ),
          // the apikey doubles as the signing key: ApikeyCalls reads the clientId claim, looks the
          // apikey up, then validates the HS512 signature against its own clientSecret
          Expected(
            demoHttpCall(
              "apikey as a signed jwt",
              "Authorization" -> s"Bearer ${demoJwt(demoOauth2Secret, "clientId" -> demoOauth2ApikeyId)}"
            ),
            "oauth2-local",
            demoOauth2ApikeyId
          ),
          Expected(
            demoHttpCall(
              "oidc token, client_id claim",
              "Authorization" -> s"Bearer ${demoJwt(demoOidcSecret, "iss" -> "demo-idp", "client_id" -> "consumer-from-oidc")}"
            ),
            "oauth2-remote",
            "consumer-from-oidc"
          )
        )

        val report = results
          .map { e =>
            s"  ${e.call.how.padTo(30, ' ')} -> ${e.call.status} ${e.call.consumer.getOrElse("no consumer")}" +
            s" (plan ${e.call.plan.getOrElse("none")}, plan plugin ${e.call.planPlugin.getOrElse("none")})"
          }
          .mkString("\n", "\n", "\n")

        withClue(report) {
          results.foreach { e =>
            withClue(s"call '${e.call.how}': ") {
              e.call.status mustBe 200
              // an identity is what every plan is supposed to produce
              e.call.consumer mustBe Some(e.consumer)
              e.call.plan mustBe Some(s"$demoPrefix-${e.kind}-plan")
              // and the chain of that very plan ran on the call
              e.call.planPlugin mustBe Some(s"from-${e.kind}-plan")
            }
          }
          // fetch_user of the oauth2-remote plan: the profile of the token holder travels as apikey
          // metadata, so it reaches the backend through the expression language
          results.last.call.user.exists(_.contains("consumer-from-oidc")) mustBe true
        }
      } finally {
        otoEnv.datastores.apiDataStore.delete(api).futureValue
        otoEnv.datastores.apiKeyDataStore.delete(apikey).futureValue
        otoEnv.datastores.apiKeyDataStore.delete(oauth2Apikey).futureValue
        otoEnv.datastores.globalJwtVerifierDataStore.delete(verifier).futureValue
        otoEnv.datastores.authConfigsDataStore.delete(authModule).futureValue
        backendServer.stop()
        oidcServer.stop()
        await(2.seconds)
      }
    }
  }

  "The otoroshi instance" should {
    "shutdown" in {
      Try(otoRef.get().stop())
    }
  }
}
