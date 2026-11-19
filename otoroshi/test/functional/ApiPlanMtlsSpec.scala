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
import otoroshi.next.plugins.{AdditionalHeadersOut, NgOidcApikeyExtractor, NgOidcApikeyExtractorConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.security.IdGenerator
import otoroshi.ssl.{Cert, ClientAuth, DynamicSSLEngineProvider, FakeKeyStore}
import otoroshi.utils.syntax.implicits.*
import play.api.Configuration
import play.api.libs.json.*
import play.api.libs.ws.readableAsString
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
  // CN=mtls-client, issued by a second authority of the store, trusted by the handshake too
  private var secondCaClient: (PrivateKey, Array[X509Certificate]) = scala.compiletime.uninitialized
  // CN=mtls-client, issued by an authority outside of the store that bears the dn of the test CA
  private var forgedClient: (PrivateKey, Array[X509Certificate])   = scala.compiletime.uninitialized
  // the issuer pattern of the certificates of the test CA
  private val testCaIssuer                                         = ".*CN=Otoroshi Frontend Test CA.*"
  // the backend of the apis of the mtls plans: a call depends on nothing but this instance
  private var mtlsBackend: TargetService                           = scala.compiletime.uninitialized

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

  private def clientCert(dn: String)(using e: Env): (PrivateKey, Array[X509Certificate]) =
    clientCertFrom(FrontendTlsSpec.testCa(e), dn)

  private def clientCertFrom(ca: otoroshi.ssl.pki.models.GenCertResponse, dn: String)(using
      e: Env
  ): (PrivateKey, Array[X509Certificate]) = {
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
      clientCert: Option[(PrivateKey, Array[X509Certificate])],
      path: String = "/"
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
      out.write(s"GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".getBytes("UTF-8"))
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
    given Env = otoEnv
    val api   = mtlsApi(planId, accessModeConfiguration, tag)
    otoEnv.datastores.apiDataStore.set(api).futureValue
    await(8.seconds) // let the proxy state job generate the routes
    api
  }

  private def mtlsApi(planId: String, accessModeConfiguration: JsObject, tag: String): Api = {
    given Env  = otoEnv
    val default = ApiBackend.empty
    val backend = default.copy(
      backend = default.backend.copy(
        targets = Seq(NgTarget(id = "target_1", hostname = "127.0.0.1", port = mtlsBackend.port, tls = false))
      )
    )
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
      mtlsBackend = TargetService
        .full(None, "/", "application/json", _ => (200, """{"message":"hello world"}""", List.empty))
        .await()
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
      // the authorities a plan names are read from the store: the test CA is stored too, with a second CA that the
      // handshake trusts as well
      val secondCa = FakeKeyStore.createCA("CN=Otoroshi Second Test CA, O=Otoroshi Test", 3650.days, None, None)
      otoEnv.datastores.certificatesDataStore
        .set(FrontendTlsSpec.testCa(otoEnv).toCert.copy(id = "mtlsplan-ca", name = "mtlsplan-ca", description = "ca", ca = true))
        .futureValue
      otoEnv.datastores.certificatesDataStore
        .set(secondCa.toCert.copy(id = "mtlsplan-second-ca", name = "mtlsplan-second-ca", description = "ca", ca = true))
        .futureValue
      secondCaClient = clientCertFrom(secondCa, "CN=mtls-client, O=Otoroshi Test")
      // another key pair under the very dn of the test CA, never stored
      val forgedCa = FakeKeyStore.createCA("CN=Otoroshi Frontend Test CA, O=Otoroshi Test", 3650.days, None, None)
      forgedClient = clientCertFrom(forgedCa, "CN=mtls-client, O=Otoroshi Test")
      awaitCond(30.seconds) {
        otoEnv.proxyState.certificate("mtlsplan-ca").isDefined && otoEnv.proxyState.certificate("mtlsplan-second-ca").isDefined
      }
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
        ApiPlanApikeySeen.get("mtls-cn") mustBe Some(s"mtls_${api.id}_plan-mtls-cn_mtls-client")
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
        first.get.startsWith(s"mtls_${api.id}_plan-mtls-derived_") mustBe true

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
        ApiPlanApikeySeen.get("mtls-open") mustBe Some(s"mtls_${api.id}_plan-mtls-open_other-client")
      } finally undeploy(api)
    }

    "require every list of patterns with dn_match all" in {
      val api = deployApi(
        "plan-mtls-all",
        Json.obj(
          "regex_subject_dns" -> Json.arr(".*CN=mtls-client.*"),
          "regex_issuer_dns"  -> Json.arr(testCaIssuer),
          "dn_match"          -> "all",
          "client_id_field"   -> "CN"
        ),
        "mtls-all"
      )
      try {
        call(Some(matchingClient)) mustBe Some(200)
        // the subject matches, not the issuer
        call(Some(secondCaClient)) mustBe Some(401)
        // the issuer matches, not the subject
        call(Some(otherClient)) mustBe Some(401)
      } finally undeploy(api)
    }

    "accept a certificate matching either list without dn_match, or with any" in {
      Seq(Json.obj(), Json.obj("dn_match" -> "any")).zipWithIndex.foreach { case (dnMatch, idx) =>
        val api = deployApi(
          s"plan-mtls-any-$idx",
          Json.obj(
            "regex_subject_dns" -> Json.arr(".*CN=mtls-client.*"),
            "regex_issuer_dns"  -> Json.arr(testCaIssuer),
            "client_id_field"   -> "CN"
          ) ++ dnMatch,
          s"mtls-any-$idx"
        )
        try {
          withClue(dnMatch.stringify) {
            Seq(matchingClient, secondCaClient, otherClient).map(c => call(Some(c))) mustBe Seq(
              Some(200),
              Some(200),
              Some(200)
            )
          }
        } finally undeploy(api)
      }
    }

    "only identify the certificates issued by the authorities of the plan" in {
      given Env = otoEnv
      val api   = deployApi(
        "plan-mtls-ca",
        Json.obj("trusted_ca_refs" -> Json.arr("mtlsplan-ca"), "client_id_field" -> "CN"),
        "mtls-ca"
      )
      val keyId = s"mtls_${api.id}_plan-mtls-ca_mtls-client"
      try {
        // trusted by the handshake, not by the plan: nothing is minted for it
        call(Some(secondCaClient)) mustBe Some(401)
        otoEnv.datastores.apiKeyDataStore.findById(keyId).futureValue mustBe None
        call(Some(matchingClient)) mustBe Some(200)
        // no pattern: any certificate of the authority
        call(Some(otherClient)) mustBe Some(200)
      } finally {
        otoEnv.datastores.apiKeyDataStore.delete(keyId).futureValue
        undeploy(api)
      }
    }

    "check the authorities of the plan, then its patterns" in {
      val api = deployApi(
        "plan-mtls-ca-all",
        Json.obj(
          "trusted_ca_refs"   -> Json.arr("mtlsplan-ca"),
          "regex_subject_dns" -> Json.arr(".*CN=mtls-client.*"),
          "dn_match"          -> "all",
          "client_id_field"   -> "CN"
        ),
        "mtls-ca-all"
      )
      try {
        call(Some(matchingClient)) mustBe Some(200)
        call(Some(secondCaClient)) mustBe Some(401)
        call(Some(otherClient)) mustBe Some(401)
      } finally undeploy(api)
    }

    "refuse every certificate when no authority of the plan is in the store" in {
      val api = deployApi(
        "plan-mtls-ca-missing",
        Json.obj("trusted_ca_refs" -> Json.arr("does-not-exist"), "client_id_field" -> "CN"),
        "mtls-ca-missing"
      )
      try {
        Seq(matchingClient, secondCaClient, otherClient).map(c => call(Some(c))) mustBe Seq(Some(401), Some(401), Some(401))
      } finally undeploy(api)
    }

    "refuse the certificates of an authority revoked in the store" in {
      given Env  = otoEnv
      val api    = deployApi(
        "plan-mtls-ca-revoked",
        Json.obj("trusted_ca_refs" -> Json.arr("mtlsplan-ca"), "client_id_field" -> "CN"),
        "mtls-ca-revoked"
      )
      val stored = otoEnv.datastores.certificatesDataStore.findById("mtlsplan-ca").futureValue.get
      try {
        call(Some(matchingClient)) mustBe Some(200)
        // the handshake keeps trusting the authority through the copy the warm up handed to the tls engine, which
        // expires after a few minutes: refreshed here, so that the refusal below is the one of the plan
        DynamicSSLEngineProvider.addCertificates(Seq(stored), otoEnv)
        otoEnv.datastores.certificatesDataStore.set(stored.copy(revoked = true)).futureValue
        awaitCond(30.seconds)(otoEnv.proxyState.certificate("mtlsplan-ca").forall(_.revoked))
        call(Some(matchingClient)) mustBe Some(401)
        call(Some(otherClient)) mustBe Some(401)
      } finally {
        otoEnv.datastores.certificatesDataStore.set(stored).futureValue
        awaitCond(30.seconds)(otoEnv.proxyState.certificate("mtlsplan-ca").exists(_.notRevoked))
        undeploy(api)
      }
    }

    "tie a plan to its authority whatever the trust mode of the handshake" in {
      given Env = otoEnv
      def trustMode(strict: Boolean): Unit = {
        val gc = getOtoroshiConfig(customPort = Some(port)).futureValue
        updateOtoroshiConfig(
          gc.copy(tlsSettings = gc.tlsSettings.copy(strictBackendServerValidation = strict)),
          customPort = Some(port)
        ).futureValue
        awaitCond(30.seconds)(otoEnv.strictBackendServerValidation == strict)
        // the trust mode is read when the tls contexts are built
        DynamicSSLEngineProvider.forceUpdate(otoEnv)
      }
      // the permissive mode: the handshake accepts any client chain
      trustMode(strict = false)
      try {
        // an issuer pattern only compares text: a certificate of a forged authority bearing the same dn passes it
        val byIssuer = deployApi(
          "plan-mtls-forged-dn",
          Json.obj("regex_issuer_dns" -> Json.arr(testCaIssuer), "client_id_field" -> "CN"),
          "mtls-forged-dn"
        )
        try call(Some(forgedClient)) mustBe Some(200)
        finally undeploy(byIssuer)
        val byCa = deployApi(
          "plan-mtls-forged-ca",
          Json.obj("trusted_ca_refs" -> Json.arr("mtlsplan-ca"), "client_id_field" -> "CN"),
          "mtls-forged-ca"
        )
        try {
          call(Some(forgedClient)) mustBe Some(401)
          call(Some(matchingClient)) mustBe Some(200)
        } finally undeploy(byCa)
      } finally trustMode(strict = true)
    }

    "refuse an mtls plan with an unknown dn_match, or trusted_ca_refs that are not ids" in {
      Seq(
        Json.obj("dn_match" -> "both")                 -> "plan 'plan-mtls-invalid': dn_match must be 'all' or 'any'",
        Json.obj("trusted_ca_refs" -> "mtlsplan-ca")   -> "plan 'plan-mtls-invalid': trusted_ca_refs must be an array of certificate ids",
        Json.obj("trusted_ca_refs" -> Json.arr("", 1)) -> "plan 'plan-mtls-invalid': trusted_ca_refs must be an array of certificate ids"
      ).foreach { case (conf, description) =>
        val api            = mtlsApi("plan-mtls-invalid", conf, "mtls-invalid").copy(state = ApiStaging)
        val (body, status) = otoroshiApiCall("POST", "/apis/apis.otoroshi.io/v1/apis", Some(api.json)).futureValue
        withClue(body.stringify) {
          status mustBe 400
          body.select("error").asString mustBe "invalid_plan"
          body.select("error_description").asString mustBe description
        }
      }
    }

    "apply the restrictions of an mtls plan to its consumers" in {
      val forbidden = Json.obj(
        "enabled"   -> true,
        "allowLast" -> true,
        "allowed"   -> Json.arr(),
        "notFound"  -> Json.arr(),
        "forbidden" -> Json.arr(Json.obj("method" -> "*", "path" -> "/admin/.*"))
      )
      val api       = deployApi(
        "plan-mtls-restricted",
        Json.obj(
          "regex_subject_dns" -> Json.arr(".*CN=mtls-client.*"),
          "client_id_field"   -> "CN",
          "restrictions"      -> forbidden
        ),
        "mtls-restricted"
      )
      try {
        tlsCall(apiDomain, Some(matchingClient), "/users").map(_._1) mustBe Some(200)
        tlsCall(apiDomain, Some(matchingClient), "/admin/users").map(_._1) mustBe Some(403)
      } finally undeploy(api)
    }

    "only serve the certificate of a consumer that subscribed, when the plan requires a subscription" in {
      given Env         = otoEnv
      val api           = deployApi(
        "plan-mtls-required",
        Json.obj(
          "regex_subject_dns"     -> Json.arr(".*CN=mtls-client.*"),
          "client_id_field"       -> "CN",
          "subscription_required" -> true
        ),
        "mtls-required"
      )
      val keyId         = s"mtls_${api.id}_plan-mtls-required_mtls-client"
      val subscriptions = "/apis/apis.otoroshi.io/v1/apisubscriptions"
      def awaitState(status: Option[String]): Unit = awaitCond(30.seconds) {
        otoEnv.proxyState.apiSubscription("sub-mtls-required").map(_.status.name) == status &&
        otoEnv.proxyState.apikey(keyId).map(_.enabled) ==
          otoEnv.datastores.apiKeyDataStore.findById(keyId).futureValue.map(_.enabled)
      }
      try {
        // no subscription: not served, and nothing is minted
        call(Some(matchingClient)) mustBe Some(401)
        otoEnv.datastores.apiKeyDataStore.findById(keyId).futureValue mustBe None

        val template = otoroshiApiCall("GET", s"$subscriptions/_template").futureValue._1.as[JsObject]
        otoroshiApiCall(
          "POST",
          subscriptions,
          Some(
            template ++ Json.obj(
              "id"                -> "sub-mtls-required",
              "name"              -> "sub-mtls-required",
              "api_ref"           -> api.id,
              "plan_ref"          -> "plan-mtls-required",
              "owner_ref"         -> "client-1",
              "subscription_kind" -> "mtls",
              "status"            -> "enabled",
              "client_id"         -> "mtls-client"
            )
          )
        ).futureValue._2 mustBe 201
        awaitState(Some("enabled"))
        call(Some(matchingClient)) mustBe Some(200)

        val current = otoroshiApiCall("GET", s"$subscriptions/sub-mtls-required").futureValue._1.as[JsObject]
        otoroshiApiCall("PUT", s"$subscriptions/sub-mtls-required", Some(current ++ Json.obj("status" -> "disabled")))
          .futureValue
          ._2 mustBe 200
        awaitState(Some("disabled"))
        call(Some(matchingClient)) mustBe Some(401)
      } finally {
        otoroshiApiCall("DELETE", s"$subscriptions/sub-mtls-required").futureValue
        otoEnv.datastores.apiKeyDataStore.delete(keyId).futureValue
        undeploy(api)
      }
    }

    "let a subscription with the client id of a certificate decide for its consumer" in {
      given Env         = otoEnv
      val api           = deployApi(
        "plan-mtls-sub",
        Json.obj("regex_subject_dns" -> Json.arr(".*CN=mtls-client.*"), "client_id_field" -> "CN"),
        "mtls-sub"
      )
      val keyId         = s"mtls_${api.id}_plan-mtls-sub_mtls-client"
      val subscriptions = "/apis/apis.otoroshi.io/v1/apisubscriptions"
      // a call with the certificate, once the router sees the key of the consumer as it is stored
      def callAsStored(): Option[Int] = {
        val stored = otoEnv.datastores.apiKeyDataStore.findById(keyId).futureValue
        awaitCond(30.seconds)(otoEnv.proxyState.apikey(keyId).map(_.enabled) == stored.map(_.enabled))
        call(Some(matchingClient))
      }
      def setStatus(status: String): Unit = {
        val current = otoroshiApiCall("GET", s"$subscriptions/sub-mtls").futureValue._1.as[JsObject]
        otoroshiApiCall("PUT", s"$subscriptions/sub-mtls", Some(current ++ Json.obj("status" -> status))).futureValue._2 mustBe 200
      }
      try {
        val template = otoroshiApiCall("GET", s"$subscriptions/_template").futureValue._1.as[JsObject]
        val (created, status) = otoroshiApiCall(
          "POST",
          subscriptions,
          Some(
            template ++ Json.obj(
              "id"                -> "sub-mtls",
              "name"              -> "sub-mtls",
              "api_ref"           -> api.id,
              "plan_ref"          -> "plan-mtls-sub",
              "owner_ref"         -> "client-1",
              "subscription_kind" -> "mtls",
              "status"            -> "enabled",
              "client_id"         -> "mtls-client"
            )
          )
        ).futureValue
        withClue(created.stringify)(status mustBe 201)
        val key = otoEnv.datastores.apiKeyDataStore.findById(keyId).futureValue
        key.map(_.enabled) mustBe Some(true)
        key.flatMap(_.apiRef) mustBe Some(ApiRef(api.id, "plan-mtls-sub", "sub-mtls"))
        key.map(_.metadata.get("access_mode")) mustBe Some(Some("mtls"))
        callAsStored() mustBe Some(200)

        setStatus("disabled")
        callAsStored() mustBe Some(401)
        setStatus("enabled")
        callAsStored() mustBe Some(200)

        otoroshiApiCall("DELETE", s"$subscriptions/sub-mtls").futureValue._2 mustBe 200
        otoEnv.datastores.apiKeyDataStore.findById(keyId).futureValue.map(_.enabled) mustBe Some(false)
        callAsStored() mustBe Some(401)
      } finally {
        otoEnv.datastores.apiKeyDataStore.delete(keyId).futureValue
        undeploy(api)
      }
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

  private def demoHttpCall(how: String, headers: (String, String)*): DemoCall = demoHttpCallOn("/", how, headers*)

  private def demoHttpCallOn(path: String, how: String, headers: (String, String)*): DemoCall = {
    val resp = ws
      .url(s"http://127.0.0.1:$port$path")
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

  // the six calls of the demo, one per kind of plan, and what each one gets when the plan of its kind serves it
  private case class Expected(call: DemoCall, kind: String, consumer: String)

  private def demoCalls(demoClient: (PrivateKey, Array[X509Certificate])): Seq[Expected] = Seq(
    Expected(demoHttpCall("no credential at all"), "keyless", s"keyless_${demoPrefix}_$demoPrefix-keyless-plan_127.0.0.1"),
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
      s"jwt_${demoPrefix}_$demoPrefix-jwt-plan_consumer-from-token"
    ),
    Expected(
      demoMtlsCall("client certificate", demoClient),
      "mtls",
      s"mtls_${demoPrefix}_$demoPrefix-mtls-plan_demo-consumer"
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
      s"oauth2_${demoPrefix}_$demoPrefix-oauth2-remote-plan_consumer-from-oidc"
    )
  )

  private def report(results: Seq[Expected]): String = results
    .map { e =>
      s"  ${e.call.how.padTo(30, ' ')} -> ${e.call.status} ${e.call.consumer.getOrElse("no consumer")}" +
      s" (plan ${e.call.plan.getOrElse("none")}, plan plugin ${e.call.planPlugin.getOrElse("none")})"
    }
    .mkString("\n", "\n", "\n")

  // the call is served by the plan of the kind given
  private def mustBeServedBy(e: Expected, kind: String, consumer: String): Unit =
    withClue(s"call '${e.call.how}': ") {
      e.call.status mustBe 200
      // an identity is what every plan is supposed to produce
      e.call.consumer mustBe Some(consumer)
      e.call.plan mustBe Some(s"$demoPrefix-$kind-plan")
      // and the chain of that very plan ran on the call
      e.call.planPlugin mustBe Some(s"from-$kind-plan")
    }

  // every call is served by the plan of its kind
  private def mustAllBeServed(results: Seq[Expected]): Unit =
    withClue(report(results)) {
      results.foreach(e => mustBeServedBy(e, e.kind, e.consumer))
    }

  // what the demo needs around the api: its backend, the userinfo endpoint of the oauth2-remote plan, the
  // certificates of the mtls plan, the verifier of the jwt plan, the auth module of the oauth2-remote plan and the
  // keys of the apikey and oauth2-local plans
  private case class Demo(
      backendServer: TargetService,
      oidcServer: TargetService,
      client: (PrivateKey, Array[X509Certificate]),
      verifier: GlobalJwtVerifier,
      authModule: GenericOauth2ModuleConfig,
      api: Api,
      apikey: ApiKey,
      oauth2Apikey: ApiKey
  )
  private var demo: Demo = scala.compiletime.uninitialized

  private val allDemoKinds = Seq("keyless", "apikey", "jwt", "mtls", "oauth2-local", "oauth2-remote")

  // the demo api with the plans of the kinds given in the status given, and without the plans dropped. The scenario
  // reaches the route through the metadata of the api, which tells when the router serves this version of the api
  private def deployDemo(
      scenario: String,
      statuses: Map[String, String],
      dropped: Set[String] = Set.empty,
      configs: Map[String, JsObject] = Map.empty
  ): Unit = {
    given Env = otoEnv
    val api   = demo.api.copy(
      metadata = demo.api.metadata + ("scenario" -> scenario),
      plans = demo.api.plans
        .filterNot(plan => dropped.exists(kind => plan.id == s"$demoPrefix-$kind-plan"))
        .map { plan =>
          statuses.collectFirst { case (kind, status) if plan.id == s"$demoPrefix-$kind-plan" => status } match {
            case Some(status) => ApiPlan(plan.raw ++ Json.obj("status" -> status))
            case None         => plan
          }
        }
        .map { plan =>
          configs.collectFirst { case (kind, conf) if plan.id == s"$demoPrefix-$kind-plan" => conf } match {
            case Some(conf) =>
              ApiPlan(plan.raw ++ Json.obj("access_mode_configuration" -> (plan.raw.select("access_mode_configuration").asOpt[JsObject].getOrElse(Json.obj()) ++ conf)))
            case None       => plan
          }
        },
      // one route id per scenario: the configs of the plugins of a route are cached for a few seconds by route id
      routes = demo.api.routes.map(_.copy(id = s"$demoPrefix-route-$scenario"))
    )
    otoEnv.datastores.apiDataStore.set(api).futureValue mustBe true
    awaitCond(30.seconds) {
      otoEnv.proxyState
        .allRoutes()
        .exists(route => route.apiRef.exists(_.id == api.id) && route.metadata.get("scenario").contains(scenario))
    }
    withClue(s"the route of the scenario '$scenario' reaches the router") {
      otoEnv.proxyState
        .allRoutes()
        .exists(route => route.apiRef.exists(_.id == api.id) && route.metadata.get("scenario").contains(scenario)) mustBe true
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
      demo = Demo(backendServer, oidcServer, demoClient, verifier, authModule, api, apikey, oauth2Apikey)

      otoEnv.datastores.globalJwtVerifierDataStore.set(verifier).futureValue
      otoEnv.datastores.authConfigsDataStore.set(authModule).futureValue
      otoEnv.datastores.apiKeyDataStore.set(apikey).futureValue
      otoEnv.datastores.apiKeyDataStore.set(oauth2Apikey).futureValue
      otoEnv.datastores.apiDataStore.set(api).futureValue

      // the proxy state job is what turns the api into a route and publishes the other entities
      awaitCond(30.seconds) {
        otoEnv.proxyState.allRoutes().exists(_.apiRef.exists(_.id == api.id)) &&
        otoEnv.proxyState.apikey(demoApikeyId).isDefined &&
        otoEnv.proxyState.apikey(demoOauth2ApikeyId).isDefined &&
        otoEnv.proxyState.jwtVerifier(demoVerifierId).isDefined &&
        otoEnv.proxyState.authModule(demoAuthModuleId).isDefined
      }

      val results = demoCalls(demoClient)
      mustAllBeServed(results)
      // fetch_user of the oauth2-remote plan: the profile of the token holder travels as apikey
      // metadata, so it reaches the backend through the expression language
      withClue(report(results)) {
        results.last.call.user.exists(_.contains("consumer-from-oidc")) mustBe true
      }
    }

    // a deprecated plan takes no new subscription, but keeps serving its consumers
    "keep serving the consumers of a deprecated plan, whatever its kind" in {
      allDemoKinds.foreach { kind =>
        withClue(s"the $kind plan deprecated: ") {
          deployDemo(s"deprecated-$kind", Map(kind -> "deprecated"))
          mustAllBeServed(demoCalls(demo.client))
        }
      }
    }

    "serve every consumer when every plan is deprecated" in {
      deployDemo("all-deprecated", allDemoKinds.map(_ -> "deprecated").toMap)
      mustAllBeServed(demoCalls(demo.client))
    }

    "still require a consumer when every plan is deprecated and none is keyless" in {
      deployDemo("all-deprecated-no-keyless", allDemoKinds.map(_ -> "deprecated").toMap, dropped = Set("keyless"))
      val results = demoCalls(demo.client)
      withClue(report(results)) {
        results.head.call.status mustBe 401
        results.tail.foreach(e => mustBeServedBy(e, e.kind, e.consumer))
      }
    }

    "not identify the consumers of a staging or a closed plan" in {
      Seq("staging", "closed").foreach { status =>
        withClue(s"the jwt plan $status: ") {
          deployDemo(s"jwt-$status", Map("jwt" -> status))
          val results = demoCalls(demo.client)
          withClue(report(results)) {
            val jwtCall = results.find(_.kind == "jwt").get
            mustBeServedBy(jwtCall, "keyless", s"keyless_${demoPrefix}_$demoPrefix-keyless-plan_127.0.0.1")
            results.filterNot(_.kind == "jwt").foreach(e => mustBeServedBy(e, e.kind, e.consumer))
          }
        }
      }
    }

    "apply the restrictions of an oauth2-remote plan to its consumers" in {
      deployDemo(
        "oauth2-remote-restrictions",
        Map.empty,
        configs = Map(
          "oauth2-remote" -> Json.obj(
            "restrictions" -> Json.obj(
              "enabled"   -> true,
              "allowLast" -> true,
              "allowed"   -> Json.arr(),
              "notFound"  -> Json.arr(),
              "forbidden" -> Json.arr(Json.obj("method" -> "*", "path" -> "/admin/.*"))
            )
          )
        )
      )
      // a consumer the plan has not seen yet: minted with the restrictions of the plan
      val token = s"Bearer ${demoJwt(demoOidcSecret, "iss" -> "demo-idp", "client_id" -> "consumer-restricted")}"
      val home  = demoHttpCallOn("/", "oidc token, client_id claim", "Authorization" -> token)
      withClue("on an allowed path") {
        home.status mustBe 200
        home.plan mustBe Some(s"$demoPrefix-oauth2-remote-plan")
      }
      withClue("on a forbidden path")(demoHttpCallOn("/admin/users", "oidc token", "Authorization" -> token).status mustBe 403)
    }

    "only serve the subscribed consumers of an oauth2-remote plan that requires a subscription" in {
      given Env         = otoEnv
      val subscriptions = "/apis/apis.otoroshi.io/v1/apisubscriptions"
      val planId        = s"$demoPrefix-oauth2-remote-plan"
      val consumer      = s"oauth2_${demoPrefix}_${planId}_consumer-from-oidc"
      // without the keyless plan, that would serve the call of a token the oauth2-remote plan does not identify
      deployDemo(
        "oauth2-remote-subscription-required",
        Map.empty,
        dropped = Set("keyless"),
        configs = Map("oauth2-remote" -> Json.obj("subscription_required" -> true))
      )
      def oidcCall(): DemoCall = demoHttpCall(
        "oidc token, client_id claim",
        "Authorization" -> s"Bearer ${demoJwt(demoOidcSecret, "iss" -> "demo-idp", "client_id" -> "consumer-from-oidc")}"
      )
      // the key minted on the fly by the previous scenarios is not a subscription
      withClue("without subscription")(oidcCall().status mustBe 401)

      val template          = otoroshiApiCall("GET", s"$subscriptions/_template").futureValue._1.as[JsObject]
      val (created, status) = otoroshiApiCall(
        "POST",
        subscriptions,
        Some(
          template ++ Json.obj(
            "id"                -> "sub-demo-oidc",
            "name"              -> "sub-demo-oidc",
            "api_ref"           -> demoPrefix,
            "plan_ref"          -> planId,
            "owner_ref"         -> "client-1",
            "subscription_kind" -> "oauth2-remote",
            "status"            -> "enabled",
            "client_id"         -> "consumer-from-oidc"
          )
        )
      ).futureValue
      withClue(created.stringify)(status mustBe 201)
      try {
        awaitCond(30.seconds) {
          otoEnv.proxyState.apiSubscription("sub-demo-oidc").isDefined &&
          otoEnv.proxyState.apikey(consumer).flatMap(_.apiRef).map(_.subscription).contains("sub-demo-oidc")
        }
        val served = oidcCall()
        withClue("with an enabled subscription") {
          served.status mustBe 200
          served.consumer mustBe Some(consumer)
          served.plan mustBe Some(planId)
        }
      } finally {
        otoroshiApiCall("DELETE", s"$subscriptions/sub-demo-oidc").futureValue
      }
    }

    "clean up" in {
      given Env = otoEnv
      if (demo != null) {
        otoEnv.datastores.apiDataStore.delete(demo.api).futureValue
        otoEnv.datastores.apiKeyDataStore.delete(demo.apikey).futureValue
        otoEnv.datastores.apiKeyDataStore.delete(demo.oauth2Apikey).futureValue
        otoEnv.datastores.globalJwtVerifierDataStore.delete(demo.verifier).futureValue
        otoEnv.datastores.authConfigsDataStore.delete(demo.authModule).futureValue
        demo.backendServer.stop()
        demo.oidcServer.stop()
        await(2.seconds)
      }
    }
  }

  // -------------------------------------------------------------------------------------------------
  // the claims an oauth2-remote plan expects: the issuer of the authorization server and one of the
  // audiences of the api. Every token below is signed with the keys of the same auth. module
  // -------------------------------------------------------------------------------------------------

  private val claimsPrefix   = "apiplansclaims"
  private val claimsDomain   = s"$claimsPrefix.foo.tools"
  private val claimsSecret   = "claims-oidc-secret"
  private val claimsModuleId = s"$claimsPrefix-auth-module"

  private case class ClaimsDemo(backendServer: TargetService, authModule: GenericOauth2ModuleConfig)
  private var claims: ClaimsDemo = scala.compiletime.uninitialized

  private def claimsJwt(clientId: String, iss: Option[String], aud: Seq[String]): String = {
    val builder = JWT.create().withClaim("client_id", clientId)
    iss.foreach(i => builder.withIssuer(i))
    aud match {
      case Seq()    => ()
      case Seq(one) => builder.withClaim("aud", one)
      case many     => builder.withArrayClaim("aud", many.toArray)
    }
    builder.sign(Algorithm.HMAC512(claimsSecret))
  }

  private def claimsPlan(id: String, accessModeConfiguration: JsObject): ApiPlan = ApiPlan(
    Json.obj(
      "id"                             -> id,
      "name"                           -> id,
      "status"                         -> "published",
      "access_mode_configuration_type" -> "oauth2-remote",
      "access_mode_configuration"      -> (Json.obj(
        "verifier"          -> claimsModuleId,
        "client_id_path"    -> "client_id",
        "create_if_missing" -> true
      ) ++ accessModeConfiguration)
    )
  )

  // the api of the claims, with the plans given. The scenario reaches the route through the metadata of the api
  private def deployClaimsApi(scenario: String, plans: ApiPlan*): Api = {
    given Env      = otoEnv
    val backend    = ApiBackend(
      id = s"$claimsPrefix-backend",
      name = "claims-backend",
      backend = NgBackend.empty.copy(
        targets =
          Seq(NgTarget(id = "target_1", hostname = "127.0.0.1", port = claims.backendServer.port, tls = false))
      ),
      client = "default_backend_client"
    )
    val flow       = ApiFlows.empty.copy(
      plugins = NgPlugins(
        ApiFlows.empty.plugins.slots :+ NgPluginInstance(
          plugin = NgPluginHelper.pluginId[AdditionalHeadersOut],
          config = NgPluginInstanceConfig(
            Json.obj(
              "headers" -> Json.obj("X-Demo-Consumer" -> "${apikey.clientId}", "X-Demo-Plan" -> "${apikey.api.plan}")
            )
          )
        )
      )
    )
    val api        = Api(
      location = EntityLocation.default,
      id = claimsPrefix,
      name = claimsPrefix,
      description = "the claims of an oauth2-remote plan",
      domain = claimsDomain,
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
      plans = plans,
      routes = Seq(
        ApiRoute(
          // one route id per scenario: the configs of the plugins of a route are cached for a few seconds by route id
          id = s"$claimsPrefix-route-$scenario",
          frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath("/"))),
          flowRef = flow.id,
          backend = backend.id
        )
      ),
      metadata = Map("scenario" -> scenario)
    )
    otoEnv.datastores.apiDataStore.set(api).futureValue mustBe true
    awaitCond(30.seconds) {
      otoEnv.proxyState
        .allRoutes()
        .exists(route => route.apiRef.exists(_.id == api.id) && route.metadata.get("scenario").contains(scenario))
    }
    api
  }

  // the body of the answer stands for the description of the call
  private def claimsCall(token: String, host: String = claimsDomain): DemoCall = {
    val resp = ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders("Host" -> host, "Authorization" -> s"Bearer $token")
      .get()
      .futureValue
    DemoCall(
      resp.body[String],
      resp.status,
      resp.headers.map { case (k, v) => (k.toLowerCase, v.headOption.getOrElse("")) }.toMap
    )
  }

  private def storedKey(clientId: String): Option[ApiKey] = {
    given Env = otoEnv
    otoEnv.datastores.apiKeyDataStore.findById(clientId).futureValue
  }

  "An oauth2-remote plan" should {

    "only identify the tokens of its issuer and of one of its audiences" in {
      given Env         = otoEnv
      val backendServer = TargetService
        .full(None, "/", "application/json", _ => (200, """{"message":"hello world"}""", List.empty))
        .await()
      val authModule    = GenericOauth2ModuleConfig(
        id = claimsModuleId,
        name = claimsModuleId,
        desc = "the auth. module signing every token of the claims tests",
        clientSideSessionEnabled = true,
        clientId = "claims-client",
        clientSecret = "claims-client-secret",
        jwtVerifier = HSAlgoSettings(512, claimsSecret).some,
        tags = Seq.empty,
        metadata = Map.empty,
        sessionCookieValues = SessionCookieValues()
      )
      claims = ClaimsDemo(backendServer, authModule)
      otoEnv.datastores.authConfigsDataStore.set(authModule).futureValue
      awaitCond(30.seconds)(otoEnv.proxyState.authModule(claimsModuleId).isDefined)

      val plan = claimsPlan(
        "claims-plan",
        Json.obj("issuer" -> "demo-idp", "audiences" -> Json.arr("my-api"))
      )
      deployClaimsApi("issuer-and-audiences", plan)

      // the plan read back carries what it checks
      val read = ws
        .url(s"http://127.0.0.1:$port/apis/apis.otoroshi.io/v1/apis/$claimsPrefix")
        .withHttpHeaders("Host" -> "otoroshi-api.oto.tools", "Accept" -> "application/json")
        .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", play.api.libs.ws.WSAuthScheme.BASIC)
        .get()
        .futureValue
      withClue(read.body[String]) {
        read.status mustBe 200
        val conf = (read.json \ "plans" \ 0 \ "access_mode_configuration")
        (conf \ "issuer").as[String] mustBe "demo-idp"
        (conf \ "audiences").as[Seq[String]] mustBe Seq("my-api")
      }

      val served = claimsCall(claimsJwt("client-1", Some("demo-idp"), Seq("my-api")))
      withClue(served.how) {
        served.status mustBe 200
        served.consumer mustBe Some(s"oauth2_${claimsPrefix}_claims-plan_client-1")
        served.plan mustBe Some("claims-plan")
      }
      val severalAudiences = claimsCall(claimsJwt("client-1", Some("demo-idp"), Seq("other-api", "my-api")))
      withClue(severalAudiences.how)(severalAudiences.status mustBe 200)

      // refused before the client id is read: nothing is minted for it
      val otherIssuer = claimsCall(claimsJwt("client-refused", Some("other-idp"), Seq("my-api")))
      withClue(otherIssuer.how)(otherIssuer.status mustBe 401)
      storedKey(s"oauth2_${claimsPrefix}_claims-plan_client-refused") mustBe None

      Seq(
        "another audience" -> claimsJwt("client-refused", Some("demo-idp"), Seq("other-api")),
        "no audience"      -> claimsJwt("client-refused", Some("demo-idp"), Seq.empty),
        "no issuer"        -> claimsJwt("client-refused", None, Seq("my-api"))
      ).foreach { case (what, token) =>
        val refused = claimsCall(token)
        withClue(s"$what: ${refused.how}")(refused.status mustBe 401)
      }
      storedKey(s"oauth2_${claimsPrefix}_claims-plan_client-refused") mustBe None

      // without issuer nor audiences, as before: any token signed with the keys of the module
      deployClaimsApi("no-claims", claimsPlan("claims-plan", Json.obj()))
      Seq(
        claimsJwt("client-1", Some("demo-idp"), Seq("my-api")),
        claimsJwt("client-1", Some("other-idp"), Seq("my-api")),
        claimsJwt("client-1", Some("demo-idp"), Seq("other-api")),
        claimsJwt("client-1", Some("demo-idp"), Seq.empty),
        claimsJwt("client-1", None, Seq("my-api"))
      ).foreach { token =>
        val call = claimsCall(token)
        withClue(call.how) {
          call.status mustBe 200
          call.consumer mustBe Some(s"oauth2_${claimsPrefix}_claims-plan_client-1")
        }
      }
    }

    "let another oauth2-remote plan of the same module identify a token of its audience" in {
      deployClaimsApi(
        "two-audiences",
        claimsPlan("claims-plan-a", Json.obj("audiences" -> Json.arr("api-a"))),
        claimsPlan("claims-plan-b", Json.obj("audiences" -> "api-b"))
      )
      val toA = claimsCall(claimsJwt("client-2", Some("demo-idp"), Seq("api-a")))
      withClue(toA.how) {
        toA.status mustBe 200
        toA.plan mustBe Some("claims-plan-a")
      }
      val toB = claimsCall(claimsJwt("client-2", Some("demo-idp"), Seq("api-b")))
      withClue(toB.how) {
        toB.status mustBe 200
        toB.plan mustBe Some("claims-plan-b")
        toB.consumer mustBe Some(s"oauth2_${claimsPrefix}_claims-plan-b_client-2")
      }
      val toNone = claimsCall(claimsJwt("client-2", Some("demo-idp"), Seq("api-c")))
      withClue(toNone.how)(toNone.status mustBe 401)
    }

    "refuse a token of another audience in strict mode, on a route of its own" in {
      given Env  = otoEnv
      val domain = s"$claimsPrefix-route.foo.tools"
      val route  = NgRoute.empty.copy(
        id = s"$claimsPrefix-strict-route",
        frontend = NgRoute.empty.frontend.copy(domains = Seq(NgDomainAndPath(domain))),
        backend = NgRoute.empty.backend.copy(
          targets =
            Seq(NgTarget(id = "target_1", hostname = "127.0.0.1", port = claims.backendServer.port, tls = false))
        ),
        plugins = NgPlugins(
          Seq(
            NgPluginInstance(
              plugin = NgPluginHelper.pluginId[NgOidcApikeyExtractor],
              config = NgPluginInstanceConfig(
                NgOidcApikeyExtractorConfig(ref = claimsModuleId.some, strict = true, audiences = Seq("my-api")).json
                  .as[JsObject]
              )
            )
          )
        )
      )
      otoEnv.datastores.routeDataStore.set(route).futureValue mustBe true
      try {
        awaitCond(30.seconds)(otoEnv.proxyState.route(route.id).isDefined)
        val refused = claimsCall(claimsJwt("client-3", Some("demo-idp"), Seq("other-api")), domain)
        withClue(refused.how) {
          refused.status mustBe 401
          (Json.parse(refused.how) \ "error_description").as[String] mustBe "token audience not accepted"
        }
        val accepted = claimsCall(claimsJwt("client-3", Some("demo-idp"), Seq("my-api")), domain)
        withClue(accepted.how)(accepted.status mustBe 200)
      } finally {
        otoEnv.datastores.routeDataStore.delete(route.id).futureValue
      }
    }

    "clean up the claims tests" in {
      given Env = otoEnv
      if (claims != null) {
        otoEnv.datastores.apiDataStore.delete(claimsPrefix).futureValue
        otoEnv.datastores.authConfigsDataStore.delete(claims.authModule).futureValue
        claims.backendServer.stop()
      }
    }
  }

  "The otoroshi instance" should {
    "shutdown" in {
      Try(mtlsBackend.stop())
      Try(otoRef.get().stop())
    }
  }
}
