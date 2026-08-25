package functional

import com.typesafe.config.ConfigFactory
import otoroshi.api.Otoroshi
import otoroshi.env.Env
import otoroshi.models.EntityLocation
import otoroshi.next.models.*
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

  private def call(clientCert: Option[(PrivateKey, Array[X509Certificate])]): Option[Int] = {
    val ctx    = clientSslContext(clientCert)
    val socket = ctx.getSocketFactory.createSocket().asInstanceOf[SSLSocket]
    try {
      socket.connect(new InetSocketAddress("127.0.0.1", httpsPort), 5000)
      socket.setSoTimeout(5000)
      socket.setEnabledProtocols(Array("TLSv1.2"))
      val params = socket.getSSLParameters
      params.setServerNames(java.util.List.of[javax.net.ssl.SNIServerName](new SNIHostName(apiDomain)))
      socket.setSSLParameters(params)
      socket.startHandshake()
      val out    = socket.getOutputStream
      out.write(s"GET / HTTP/1.1\r\nHost: $apiDomain\r\nConnection: close\r\n\r\n".getBytes("UTF-8"))
      out.flush()
      val in         = new BufferedReader(new InputStreamReader(socket.getInputStream))
      val statusLine = in.readLine()
      Option(statusLine).flatMap(l => Try(l.split(" ")(1).toInt).toOption)
    } catch {
      case _: Throwable => None
    } finally Try(socket.close())
  }

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

    "shutdown" in {
      Try(otoRef.get().stop())
    }
  }
}
