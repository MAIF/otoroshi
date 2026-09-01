package functional

import com.dimafeng.testcontainers.GenericContainer
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.time.{Millis, Seconds, Span}
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import otoroshi.env.Env
import otoroshi.security.IdGenerator
import otoroshi.ssl.Cert
import otoroshi.ssl.pki.models.GenCsrQuery
import otoroshi.utils.letsencrypt.{LetsEncryptHelper, LetsEncryptSettings}
import play.api.Configuration
import play.api.http.Status
import play.api.libs.json.Json

import scala.concurrent.duration.*

/**
 * End-to-end ACME coverage against a real ACME server: [[https://github.com/letsencrypt/pebble Pebble]],
 * the test server maintained by Let's Encrypt, started in a container.
 *
 * Pebble validates the http-01 challenge for real, so the whole loop is exercised: otoroshi orders a
 * certificate, publishes the challenge, pebble comes back to otoroshi's `/.well-known/acme-challenge/`
 * endpoint over plain http, and the certificate is issued and persisted. Two knobs make that possible:
 *
 *   - pebble's `httpPort` is set to the port of the otoroshi instance under test, since it validates on a
 *     fixed port rather than on 80,
 *   - the test domains are added to the container's `/etc/hosts` pointing at `host-gateway`, so pebble
 *     resolves them to the docker host, where otoroshi listens.
 *
 * `otoroshi.ssl.trust.all` is enabled because pebble serves its ACME directory over https with its own
 * throwaway CA: acme4j builds its http client on the JVM default SSLContext, which otoroshi owns.
 *
 * What this pins down is the set of bugs behind "let's encrypt certificates renewed far too often, and
 * old ones never purged" (#2649, #2650): renewals must not duplicate the entity, must keep the SANs, and
 * must report their failures instead of returning the old certificate as if nothing had happened.
 */
class LetsEncryptSpec(configurationSpec: => Configuration) extends OtoroshiSpec with BeforeAndAfterAll {

  // an acme order against pebble takes a few seconds (challenge propagation + two polling loops), and the
  // multi-domain one runs a full authorization per domain.
  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(180, Seconds), interval = Span(500, Millis))

  private val singleDomain = "acme-single.oto.tools"
  private val mainDomain   = "acme-main.oto.tools"
  private val sanDomain    = "acme-san.oto.tools"
  // listed in pebble's `domainBlocklist` below: ordering for it always fails, which is how a rate limited
  // or misconfigured domain behaves in production.
  private val blockedDomain = "blocked-domain.example"

  private val allDomains = Seq(singleDomain, mainDomain, sanDomain)

  // `ec` comes from OtoroshiSpec. both of these are lazy (a `given` without parameters is), so they are
  // only resolved once the instance is up.
  private given env: Env                                  = otoroshiComponents.env
  private given mat: org.apache.pekko.stream.Materializer = otoroshiComponents.env.otoroshiMaterializer

  override def getTestConfiguration(configuration: Configuration): Configuration = {
    Configuration(
      ConfigFactory
        .parseString(s"""
          |otoroshi.next.state-sync-interval = 5
          |otoroshi.ssl.trust.all = true
          |""".stripMargin)
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)
  }

  private def pebbleConfig(httpPort: Int): String = Json.stringify(
    Json.obj(
      "pebble" -> Json.obj(
        "listenAddress"                  -> "0.0.0.0:14000",
        "managementListenAddress"        -> "0.0.0.0:15000",
        "certificate"                    -> "test/certs/localhost/cert.pem",
        "privateKey"                     -> "test/certs/localhost/key.pem",
        // where pebble goes to validate an http-01 challenge: the otoroshi instance under test
        "httpPort"                       -> httpPort,
        "tlsPort"                        -> 5001,
        "ocspResponderURL"               -> "",
        "externalAccountBindingRequired" -> false,
        "domainBlocklist"                -> Json.arr(blockedDomain),
        // default is 3s/5s, which would make every order twice as slow for no benefit here
        "retryAfter"                     -> Json.obj("authz" -> 1, "order" -> 1),
        "keyAlgorithm"                   -> "ecdsa",
        "profiles"                       -> Json.obj(
          "default" -> Json.obj("description" -> "default profile", "validityPeriod" -> 7776000)
        )
      )
    )
  )

  private lazy val pebble: GenericContainer = {
    val container = GenericContainer(
      dockerImage = "ghcr.io/letsencrypt/pebble:latest",
      exposedPorts = Seq(14000, 15000),
      env = Map(
        // pebble sleeps a random 0-15s before each validation and rejects 5% of the nonces on purpose,
        // to shake out lazy clients. Neither is what this spec is about, and both make it flaky.
        "PEBBLE_VA_NOSLEEP"      -> "1",
        "PEBBLE_WFE_NONCEREJECT" -> "0",
        "PEBBLE_AUTHZREUSE"      -> "0"
      ),
      waitStrategy = Wait.forLogMessage(".*ACME directory available at.*", 1)
    )
    val underlying = container.underlyingUnsafeContainer
    allDomains.foreach(domain => underlying.withExtraHost(domain, "host-gateway"))
    underlying.withCopyToContainer(Transferable.of(pebbleConfig(port)), "/test/config/pebble-config.json")
    container
  }

  private def letsEncryptCerts(): Seq[Cert] =
    env.datastores.certificatesDataStore.findAll().futureValue.filter(_.letsEncrypt).map(_.enrich())

  private def certsFor(domain: String): Seq[Cert] =
    letsEncryptCerts().filter(c => c.allDomains.contains(domain))

  private def updateLetsEncryptSettings(f: LetsEncryptSettings => LetsEncryptSettings): Unit = {
    val config = getOtoroshiConfig().futureValue
    updateOtoroshiConfig(config.copy(letsEncryptSettings = f(config.letsEncryptSettings))).futureValue
    await(1.second)
  }

  // an acme certificate signed for `domain`, obtained through the very path the ui and the admin api use
  private def issueAndSave(domain: String): Cert = {
    LetsEncryptHelper.createCertificate(domain).futureValue match {
      case Left(err)   => fail(s"could not issue a certificate for $domain: $err")
      case Right(cert) => cert.enrich()
    }
  }

  override def beforeAll(): Unit = {
    startOtoroshi()
    pebble.start()
    updateLetsEncryptSettings(_ =>
      LetsEncryptSettings(
        enabled = true,
        server = s"https://${pebble.host}:${pebble.mappedPort(14000)}/dir",
        emails = Seq("acme@otoroshi.io")
      )
    )
    await(3.seconds)
  }

  override def afterAll(): Unit = {
    stopAll()
    pebble.stop()
  }

  "Otoroshi ACME support" should {

    "issue a certificate through a real acme server" in {
      val before = letsEncryptCerts().size

      val cert = issueAndSave(singleDomain)

      cert.letsEncrypt mustBe true
      cert.autoRenew mustBe true
      cert.domain mustBe singleDomain
      cert.to.isAfterNow mustBe true

      // exactly one new entity: issuance used to be the only path that persisted, and it must stay so
      letsEncryptCerts().size mustBe before + 1
      certsFor(singleDomain).map(_.id) mustBe Seq(cert.id)
    }

    "renew a certificate in place instead of duplicating it" in {
      val cert   = certsFor(singleDomain).head
      val before = letsEncryptCerts().size

      val renewed = cert.renew().futureValue match {
        case Left(err) => fail(s"renewal failed: $err")
        case Right(c)  => c.enrich()
      }

      // the whole point of #2650: renewing writes the new chain into the existing entity. It used to
      // persist a second one with the very same chain, both flagged autoRenew + letsEncrypt, so the
      // renewable population doubled at every cycle and each lineage ordered its own certificate.
      renewed.id mustBe cert.id
      letsEncryptCerts().size mustBe before
      certsFor(singleDomain).map(_.id) mustBe Seq(cert.id)

      // and it really is a new certificate, not the old one handed back
      renewed.serialNumber must not be cert.serialNumber
      renewed.to.isAfter(cert.from) mustBe true
    }

    "keep the SANs of a multi-domain certificate when renewing it" in {
      val issued = LetsEncryptHelper.issueCertificate(Seq(mainDomain, sanDomain)).futureValue match {
        case Left(err)   => fail(s"could not issue a multi-domain certificate: $err")
        case Right(cert) => cert
      }
      issued.save().futureValue
      await(1.second)

      issued.enrich().allDomains must contain allOf (mainDomain, sanDomain)

      val renewed = issued.renew().futureValue match {
        case Left(err) => fail(s"renewal failed: $err")
        case Right(c)  => c.enrich()
      }

      // renewal used to re-order for the CN only, so a multi-SAN certificate silently lost every SAN the
      // first time it was renewed
      renewed.id mustBe issued.id
      renewed.allDomains must contain allOf (mainDomain, sanDomain)
    }

    "report a failed acme order instead of returning the previous certificate" in {
      val response = env.pki
        .genSelfSignedCert(GenCsrQuery(hosts = Seq(blockedDomain), subject = Some(s"CN=$blockedDomain")))
        .futureValue
        .toOption
        .get
      val blocked  = response.toCert
        .copy(letsEncrypt = true, autoRenew = true, name = blockedDomain, description = "blocked")
        .enrich()
      blocked.save().futureValue
      await(1.second)

      val before = letsEncryptCerts().size

      // pebble rejects this domain outright, exactly like let's encrypt does once the "5 duplicate
      // certificates per week" limit is reached. This used to come back as a *successful* future carrying
      // the old certificate, so the caller could not tell a failure from a success.
      blocked.renew().futureValue.isLeft mustBe true

      // and a failed order must not leave anything behind
      letsEncryptCerts().size mustBe before
    }

    "not archive nor renew-alert when the renewal failed" in {
      // make every certificate eligible for renewal, so the job actually runs on our two certificates
      // instead of waiting for them to be 80% through their lifetime
      updateLetsEncryptSettings(_.copy(renewBeforeDays = Some(3650), deleteOldCertificatesAfterRenewal = true))

      val good      = certsFor(singleDomain).head
      val blocked   = letsEncryptCerts().find(_.domain == blockedDomain).get
      val goodSerial = good.serialNumber

      env.datastores.certificatesDataStore.renewCertificates().futureValue
      await(2.seconds)

      def archivesOf(cert: Cert): Seq[Cert] =
        env.datastores.certificatesDataStore
          .findAll()
          .futureValue
          .filter(_.entityMetadata.get("nextCertificate").contains(cert.id))

      // the failing one: no "[UNTIL EXPIRATION]" copy. That copy used to be created unconditionally, once
      // an hour for the whole renewal window, while the certificate quietly expired.
      archivesOf(blocked) mustBe empty
      // ... and it is untouched
      env.datastores.certificatesDataStore.findById(blocked.id).futureValue.isDefined mustBe true

      // the healthy one: renewed in place, and with the purge enabled no archive copy either
      val renewedGood = env.datastores.certificatesDataStore.findById(good.id).futureValue.get.enrich()
      renewedGood.serialNumber must not be goodSerial
      archivesOf(good) mustBe empty

      updateLetsEncryptSettings(_.copy(renewBeforeDays = None, deleteOldCertificatesAfterRenewal = false))
    }

    "collapse the duplicated certificates left by the old renewal path" in {
      val original = certsFor(singleDomain).head
      // what an instance that ran the duplicating renewal looks like: several entities, same chain
      val clones   = (1 to 2).map(_ => original.copy(id = IdGenerator.token(32)))
      clones.foreach(_.save().futureValue)
      await(1.second)

      certsFor(singleDomain).size mustBe 3

      val (dryRun, dryRunStatus) = otoroshiApiCall("POST", "/api/certificates/_dedupLetsEncrypt").futureValue
      dryRunStatus mustBe Status.OK
      (dryRun \ "dryRun").as[Boolean] mustBe true
      (dryRun \ "duplicates").as[Seq[String]].size mustBe 2
      (dryRun \ "deleted").as[Int] mustBe 0
      // a dry run really is one
      certsFor(singleDomain).size mustBe 3

      val (result, status) =
        otoroshiApiCall("POST", "/api/certificates/_dedupLetsEncrypt?dryRun=false").futureValue
      status mustBe Status.OK
      (result \ "deleted").as[Int] mustBe 2
      await(1.second)

      // exactly one entity survives, holding the very same certificate. Which of the three it is cannot
      // be decided on content - the duplicates the bug produced are byte-identical, same name and same
      // description included - so the endpoint picks a deterministic one rather than pretending to know
      // which was the original.
      val survivors = certsFor(singleDomain)
      survivors.size mustBe 1
      survivors.head.contentHash mustBe original.contentHash
    }
  }
}
