package otoroshi.utils.letsencrypt

import org.apache.pekko.actor.Scheduler
import org.apache.pekko.pattern.after
import org.apache.pekko.http.scaladsl.util.FastFuture
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}
import org.apache.pekko.util.ByteString
import org.shredzone.acme4j.*
import org.shredzone.acme4j.challenge.*
import org.shredzone.acme4j.util.*
import otoroshi.env.Env
import otoroshi.events.{Alerts, CertRenewalAlert}
import otoroshi.ssl.DynamicSSLEngineProvider.base64Decode
import otoroshi.ssl.{Cert, PemHeaders}
import otoroshi.utils.RegexPool
import otoroshi.utils.syntax.implicits.BetterFiniteDuration
import play.api.Logger
import play.api.libs.json.*

import java.io.StringWriter
import java.security.cert.X509Certificate
import java.security.spec.{PKCS8EncodedKeySpec, X509EncodedKeySpec}
import java.security.{KeyFactory, KeyPair}
import java.time.{Duration, Instant}
import java.util.Base64
import java.util.concurrent.Executors
import javax.naming.ldap.LdapName
import javax.security.auth.x500.X500Principal
import scala.jdk.CollectionConverters.*
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class LetsEncryptSettings(
    enabled: Boolean = false,
    server: String = "acme://letsencrypt.org/staging",
    emails: Seq[String] = Seq.empty,
    contacts: Seq[String] = Seq.empty,
    publicKey: String = "",
    privateKey: String = "",
    preferredChain: Option[String] = None,
    // renewal margin. `renewalPercentage` is the historical, previously hardcoded rule: renew once less
    // than N % of the total lifetime remains. It degrades on short-lived certificates (20 % of 47 days is
    // 9 days), so `renewBeforeDays` provides an absolute margin and takes precedence when set.
    renewalPercentage: Int = 20,
    renewBeforeDays: Option[Int] = None,
    // when true, a successful renewal of a let's encrypt certificate does not leave an
    // "[UNTIL EXPIRATION]" archive copy behind, and the archive copies of previous renewals are deleted.
    // only applies to letsEncrypt certificates: the archive copy of a keypair is what jwt verifiers use
    // to validate tokens signed with the previous key (see Cert.entityMetadata "nextCertificate").
    deleteOldCertificatesAfterRenewal: Boolean = false
) {

  def json: JsValue = LetsEncryptSettings.format.writes(this)

  // percentage of the total lifetime under which a certificate must be renewed. `renewBeforeDays`,
  // when set, is converted to a percentage of that certificate's own lifetime, so a single comparison
  // covers both rules.
  def renewalThresholdPercentage(lifetimeMillis: Long): Long = {
    renewBeforeDays.filter(_ > 0) match {
      case Some(days) if lifetimeMillis > 0L =>
        Math.min(100L, (days.toLong * 24L * 3600L * 1000L * 100L) / lifetimeMillis)
      case _                                 =>
        Math.max(0, Math.min(100, renewalPercentage)).toLong
    }
  }

  def keyPair: Option[KeyPair] = {
    for {
      privko <- Option(privateKey)
                  .filter(_.trim.nonEmpty)
                  .map(_.replace(PemHeaders.BeginPrivateKey, "").replace(PemHeaders.EndPrivateKey, "").trim())
                  .map { content =>
                    val encodedKey: Array[Byte] = base64Decode(content)
                    new PKCS8EncodedKeySpec(encodedKey)
                  }
      pubko  <- Option(publicKey)
                  .filter(_.trim.nonEmpty)
                  .map(_.replace(PemHeaders.BeginPublicKey, "").replace(PemHeaders.EndPublicKey, "").trim)
                  .map { content =>
                    val encodedKey: Array[Byte] = base64Decode(content)
                    new X509EncodedKeySpec(encodedKey)
                  }
    } yield {
      Try(KeyFactory.getInstance("RSA"))
        .orElse(Try(KeyFactory.getInstance("DSA")))
        .map { factor =>
          val prk = factor.generatePrivate(privko)
          val pbk = factor.generatePublic(pubko)
          new KeyPair(pbk, prk)
        }
        .get
    }
  }
}

object LetsEncryptSettings {
  val format = new Format[LetsEncryptSettings] {
    override def reads(json: JsValue): JsResult[LetsEncryptSettings] =
      Try {
        LetsEncryptSettings(
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
          server = (json \ "server").asOpt[String].getOrElse("acme://letsencrypt.org/staging"),
          emails = (json \ "emails")
            .asOpt[Seq[String]]
            .map(_.map(_.trim).filter(_.nonEmpty))
            .filter(_.nonEmpty)
            .getOrElse(Seq.empty).toSeq,
          contacts = (json \ "contacts")
            .asOpt[Seq[String]]
            .map(_.map(_.trim).filter(_.nonEmpty))
            .filter(_.nonEmpty)
            .getOrElse(Seq.empty).toSeq,
          publicKey = (json \ "publicKey").asOpt[String].getOrElse(""),
          privateKey = (json \ "privateKey").asOpt[String].getOrElse(""),
          preferredChain = (json \ "preferredChain").asOpt[String].map(_.trim).filter(_.nonEmpty),
          renewalPercentage = (json \ "renewalPercentage").asOpt[Int].filter(v => v > 0 && v <= 100).getOrElse(20),
          renewBeforeDays = (json \ "renewBeforeDays").asOpt[Int].filter(_ > 0),
          deleteOldCertificatesAfterRenewal =
            (json \ "deleteOldCertificatesAfterRenewal").asOpt[Boolean].getOrElse(false)
        )
      } match {
        case Success(s) => JsSuccess(s)
        case Failure(e) => JsError(e.getMessage)
      }

    override def writes(o: LetsEncryptSettings): JsValue =
      Json.obj(
        "enabled"    -> o.enabled,
        "server"     -> o.server,
        "emails"     -> JsArray(o.emails.map(JsString.apply)),
        "contacts"   -> JsArray(o.contacts.map(JsString.apply)),
        "publicKey"  -> o.publicKey,
        "privateKey" -> o.privateKey,
        "preferredChain" -> o.preferredChain,
        "renewalPercentage" -> o.renewalPercentage,
        "renewBeforeDays" -> o.renewBeforeDays,
        "deleteOldCertificatesAfterRenewal" -> o.deleteOldCertificatesAfterRenewal
      )
  }
}

object LetsEncryptHelper {

  private val logger = Logger("otoroshi-lets-encrypt-helper")

  private val blockingEc = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(16))

  // Shared, domain-keyed anti-concurrency lock covering every ACME order, issuance AND renewal.
  // It used to be keyed by certificate id on the renewal path and by host on the creation path, so the
  // two never saw each other and two entities holding the same domain each ordered their own
  // certificate - which is what burns let's encrypt "5 duplicate certificates per week" rate limit.
  private def orderLockKey(domain: String)(using env: Env): String =
    s"${env.storageRoot}:letsencrypt:order:${domain.trim.toLowerCase}"

  // Must cover a whole order: challenge propagation, then polling of the authorization and of the order.
  private val orderLockTtl: FiniteDuration = 15.minutes

  private def withOrderLock[A](domain: String)(f: => Future[Either[String, A]])(using
      ec: ExecutionContext,
      env: Env,
      mat: Materializer
  ): Future[Either[String, A]] = {
    val key = orderLockKey(domain)
    env.datastores.rawDataStore.get(key).flatMap {
      case Some(_) =>
        logger.warn(s"an acme order is already in progress for $domain, skipping")
        FastFuture.successful(Left(s"an acme order is already in progress for $domain"))
      case None    =>
        env.datastores.rawDataStore.set(key, ByteString("true"), Some(orderLockTtl.toMillis)).flatMap { _ =>
          f.andThen { case _ =>
            env.datastores.rawDataStore.del(Seq(key))
          }
        }
    }
  }

  /**
   * Orders a certificate through ACME and returns it WITHOUT persisting it: whether the resulting chain
   * becomes a new entity (issuance) or replaces the chain of an existing one (renewal) is the caller's
   * decision.
   *
   * This used to persist a brand new entity unconditionally, while the renewal path ALSO persisted the
   * existing one with the very same chain. Both copies carried autoRenew + letsEncrypt, so the renewable
   * population doubled at every renewal cycle (1 -> 2 -> 4 -> 8), each lineage ordering its own
   * certificate for the same domain.
   *
   * The first domain becomes the certificate CN, the others are added as SANs.
   */
  def issueCertificate(
      domains: Seq[String]
  )(using ec: ExecutionContext, env: Env, mat: Materializer): Future[Either[String, Cert]] = {
    val orderedDomains = domains.map(_.trim).filter(_.nonEmpty).distinct
    orderedDomains.headOption match {
      case None         => FastFuture.successful(Left("no domain to order a certificate for"))
      case Some(domain) =>
        env.datastores.globalConfigDataStore.singleton().flatMap { config =>
          val letsEncryptSettings = config.letsEncryptSettings

          val session = new Session(letsEncryptSettings.server)

          (letsEncryptSettings.keyPair match {
            case None     =>
              val kp          = KeyPairUtils.createKeyPair(2048)
              val newSettings = letsEncryptSettings.copy(
                privateKey =
                  s"${PemHeaders.BeginPrivateKey}\n${Base64.getEncoder.encodeToString(kp.getPrivate.getEncoded)}\n${PemHeaders.EndPrivateKey}",
                publicKey =
                  s"${PemHeaders.BeginPublicKey}\n${Base64.getEncoder.encodeToString(kp.getPublic.getEncoded)}\n${PemHeaders.EndPublicKey}"
              )
              config.copy(letsEncryptSettings = newSettings).save().map(_ => kp)
            case Some(kp) => FastFuture.successful(kp)
          }).flatMap { userKeyPair =>
            createAccount(session, letsEncryptSettings, userKeyPair)
          }.flatMap { account =>
            if (logger.isDebugEnabled)
              logger.debug(s"ordering lets encrypt certificate for ${orderedDomains.mkString(", ")}")
            orderLetsEncryptCertificate(account, orderedDomains).flatMap { order =>
              if (logger.isDebugEnabled) logger.debug(s"waiting for challenge challenge $domain")
              doChallenges(order).flatMap {
                case Left(err) =>
                  if (logger.isDebugEnabled) logger.error(s"challenges failed: $err")
                  FastFuture.successful(Left(err))
                case Right(_)  => {

                  if (logger.isDebugEnabled) logger.debug(s"building csr for $domain")
                  val keyPair       = KeyPairUtils.createKeyPair(2048)
                  val csrByteString = buildCsr(orderedDomains, keyPair)

                  if (logger.isDebugEnabled) logger.debug(s"ordering certificate for $domain")

                  orderCertificate(order, csrByteString).flatMap {
                    case Left(err)       =>
                      logger.error(s"ordering certificate failed: $err")
                      FastFuture.successful(Left(err))
                    case Right(newOrder) => {
                      Option(newOrder.getCertificate) match {
                        case None    =>
                          logger.error(s"storing certificate failed: No certificate found !")
                          FastFuture.successful(Left("No certificate found !"))
                        case Some(c) => {
                          selectCertificateChain(c, domain, letsEncryptSettings.preferredChain).map { chain =>
                            Right(
                              Cert
                                .apply(chain, keyPair, false)
                                .copy(letsEncrypt = true, autoRenew = true)
                                .enrich()
                            )
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        // acme4j reports a rejected order by THROWING: a blocked domain, an exhausted rate limit, an
        // unreachable directory, a bad nonce, all of them come out as an AcmeException. Without this the
        // future fails instead of carrying a Left, the renewal stream dies on the first bad certificate,
        // and the caller cannot tell which one failed.
        }.recover { case e: Throwable =>
          logger.error(s"error while ordering a certificate for ${orderedDomains.mkString(", ")}", e)
          Left(s"error while ordering a certificate for $domain: ${e.getMessage}")
        }
    }
  }

  private def createAccount(session: Session, settings: LetsEncryptSettings, userKeyPair: KeyPair)(using
      ec: ExecutionContext
  ): Future[Account] = {
    Future {
      val builder = new AccountBuilder()
        .agreeToTermsOfService()
        .useKeyPair(userKeyPair)
      (settings.emails.map(e => s"mailto:$e") ++ settings.contacts)
        .foldLeft(builder)((a, e) => a.addContact(e))
        .create(session)
    }(using blockingEc)
  }

  /**
   * Orders a certificate for `domain` and persists it as a NEW entity. This is the issuance path only
   * (manual creation from the UI or the admin api, auto-issuance from routes); renewals must go through
   * [[renew]], which reuses the existing entity instead of adding one.
   */
  def createCertificate(
      domain: String
  )(using ec: ExecutionContext, env: Env, mat: Materializer): Future[Either[String, Cert]] = {
    withOrderLock(domain) {
      issueCertificate(Seq(domain)).flatMap {
        case Left(err)   => FastFuture.successful(Left(err))
        case Right(cert) => cert.save().map(_ => Right(cert))
      }
    }
  }

  // Extracts the Common Name (CN) of an X.500 principal, tolerant to the other RDN
  // components (O, C, ...) that real-world CA certificates always carry. Parsing the
  // DN is required because raw string equality (as acme4j's Certificate.isIssuedBy does)
  // never matches a real Let's Encrypt anchor, whose issuer DN is e.g.
  // "CN=ISRG Root X1,O=Internet Security Research Group,C=US".
  private def commonNameOf(principal: X500Principal): Option[String] = {
    Try {
      new LdapName(principal.getName).getRdns.asScala
        .find(_.getType.equalsIgnoreCase("CN"))
        .map(_.getValue.toString.trim)
    }.toOption.flatten.filter(_.nonEmpty)
  }

  // The trust anchor a chain terminates at is identified by the issuer of its topmost
  // certificate: the highest cert included in the bundle is signed by the root, which
  // is itself usually not bundled. This is the same signal certbot uses for its
  // `--preferred-chain` option.
  private def chainAnchorCn(chain: Seq[X509Certificate]): Option[String] = {
    chain.lastOption.flatMap(top => commonNameOf(top.getIssuerX500Principal))
  }

  // Selects, among the default chain and the ACME `alternate` chains advertised by the
  // server, the one whose trust anchor CN matches `preferredChain`. When no preference
  // is set (or none of the available chains match), the default chain is returned as-is,
  // preserving the historical behavior. Runs on the blocking pool since fetching the
  // alternate chains performs network calls.
  private def selectCertificateChain(
      certificate: org.shredzone.acme4j.Certificate,
      domain: String,
      preferredChain: Option[String]
  ): Future[Seq[X509Certificate]] = {
    Future {
      val defaultChain = certificate.getCertificateChain.asScala.toSeq
      preferredChain.map(_.trim).filter(_.nonEmpty) match {
        case None         => defaultChain
        case Some(anchor) =>
          // resolving the alternate chains performs extra network calls: any failure here
          // must never break the issuance, we just fall back to the default chain.
          Try {
            val candidateChains = (certificate +: certificate.getAlternateCertificates.asScala.toSeq)
              .map(_.getCertificateChain.asScala.toSeq)
            candidateChains.find(chain => chainAnchorCn(chain).exists(_.equalsIgnoreCase(anchor))) match {
              case Some(chain) =>
                if (logger.isDebugEnabled)
                  logger.debug(s"using let's encrypt chain anchored at '$anchor' for $domain")
                chain
              case None        =>
                logger.warn(
                  s"no let's encrypt chain matching preferred trust anchor '$anchor' for $domain " +
                  s"(available anchors: ${candidateChains.flatMap(chainAnchorCn).distinct.mkString(", ")}). " +
                  s"falling back to the default chain"
                )
                defaultChain
            }
          } match {
            case Success(chain) => chain
            case Failure(e)     =>
              logger.error(
                s"error while selecting preferred let's encrypt chain '$anchor' for $domain, " +
                s"falling back to the default chain",
                e
              )
              defaultChain
          }
      }
    }(using blockingEc)
  }

  def getChallengeForToken(domain: String, token: String)(using
      ec: ExecutionContext,
      env: Env,
      mat: Materializer
  ): Future[Option[ByteString]] = {
    env.datastores.rawDataStore.get(s"${env.storageRoot}:letsencrypt:challenges:$domain:$token").map {
      case None        =>
        if (logger.isDebugEnabled) logger.debug(s"Trying to access token ${token} for domain ${domain} but none found")
        None
      case s @ Some(_) =>
        if (logger.isDebugEnabled) logger.debug(s"Trying to access token ${token} for domain ${domain}: found !")
        s
    }
  }

  /**
   * Renews an existing let's encrypt certificate IN PLACE: the new chain is written into the existing
   * entity, which keeps its id, so a renewal never adds an entity.
   *
   * Returns a Left when the ACME order failed so that the caller can skip the "[UNTIL EXPIRATION]"
   * archive copy and the renewal alert. A failure used to be returned as a *successful* future carrying
   * the old certificate, so a rate-limited instance kept archiving a copy and alerting once an hour for
   * the whole renewal window while the certificate quietly expired.
   */
  def renew(cert: Cert)(using ec: ExecutionContext, env: Env, mat: Materializer): Future[Either[String, Cert]] = {
    val enriched = cert.enrich()
    // renew for every domain the certificate covers and not only its CN, otherwise a multi-SAN
    // certificate loses all of its SANs at the first renewal.
    val domains  = enriched.allDomains match {
      case Nil    => Seq(enriched.domain)
      case others => others
    }
    withOrderLock(enriched.domain) {
      issueCertificate(domains).flatMap {
        case Left(err) =>
          logger.error(s"Error while renewing certificate ${cert.id} for ${enriched.domain}: $err")
          FastFuture.successful(Left(err))
        case Right(c)  =>
          val cenriched = c.enrich()
          val renewed   = enriched
            .copy(
              chain = cenriched.chain,
              privateKey = cenriched.privateKey,
              autoRenew = true,
              letsEncrypt = true
            )
            .enrich()
          renewed.save().map(_ => Right(renewed))
      }
    }
  }

  def createFromServices()(using ec: ExecutionContext, env: Env, mat: Materializer): Future[Unit] = {
    env.datastores.certificatesDataStore.findAll().flatMap { certificates =>
      env.datastores.serviceDescriptorDataStore.findAll().flatMap { services =>
        val letsEncryptCertificates  = certificates.filter(_.letsEncrypt)
        val letsEncryptServicesHosts = services
          .filter(_.letsEncrypt)
          .flatMap(_.allHosts)
          .filterNot(s => letsEncryptCertificates.exists(c => RegexPool(c.domain).matches(s)))
          .distinct
        Source(letsEncryptServicesHosts.toList)
          .mapAsync(1) { host =>
            // no local lock here anymore: createCertificate takes the domain-keyed order lock, which is
            // the same one the renewal path takes, so creation and renewal of a domain exclude each other.
            createCertificate(host).map {
              case Left(err) => logger.error(s"Error while creating let's encrypt certificate for $host. $err")
              case Right(_)  => logger.info(s"Successfully created let's encrypt certificate for $host")
            }
          }
          .runWith(Sink.ignore)
          .map(_ => ())
      }
    }
  }

  private def orderLetsEncryptCertificate(account: Account, domains: Seq[String])(using
      ec: ExecutionContext,
      env: Env,
      mat: Materializer
  ): Future[Order] = {
    Future {
      account.newOrder().domains(domains.asJava).create()
    }(using blockingEc)
  }

  private def doChallenges(order: Order)(using
      ec: ExecutionContext,
      env: Env,
      mat: Materializer
  ): Future[Either[String, Seq[Status]]] = {
    Source(order.getAuthorizations.asScala.toList)
      .mapAsync(1) { auth =>
        Future {
          (auth, auth.findChallenge(classOf[Http01Challenge]))
        }(using blockingEc)
      }
      .collect {
        case (auth, opt) if opt.isPresent => (auth, opt.get())
      }
      .mapAsync(1) { case (authorization, challenge) =>
        // the challenge must be stored under the domain of THIS authorization: with a multi-SAN order,
        // each domain is validated with its own token and the http-01 request arrives with that domain
        // as Host. Keying everything under the CN made every SAN validation fail with a 404.
        val domain = authorization.getIdentifier.getDomain
        logger.info(s"setting challenge content in datastore for $domain")
        env.datastores.rawDataStore
          .set(
            s"${env.storageRoot}:letsencrypt:challenges:$domain:${challenge.getToken}",
            ByteString(challenge.getAuthorization),
            Some(10.minutes.toMillis)
          )
          .flatMap { _ =>
            3.seconds.timeout.flatMap { _ =>
              authorizeOrder(domain, authorization.getStatus, challenge)
            }
          }
      }
      .toMat(Sink.seq)(Keep.right)
      .run()
      .map { seq =>
        seq.find(_.isLeft).map(v => Left(v.swap.toOption.get)).getOrElse(Right(seq.map(_.toOption.get)))
      }
  }

  // Core polling logic that respects retry-after headers
  private def pollUntil[T](
                            fetchAndCheck: () => Future[(Option[Instant], T)], // Returns (retryAfter, currentState)
                            isComplete: T => Boolean,
                            attemptsLeft: Int,
                            defaultDelay: FiniteDuration = 3.seconds,
                            maxDelay: FiniteDuration = 30.seconds
                          )(using ec: ExecutionContext, scheduler: Scheduler): Future[T] = {

    def calculateDelay(retryAfterOpt: Option[Instant], attemptNumber: Int): FiniteDuration = {
      retryAfterOpt match {
        case Some(retryAfterInstant) =>
          val suggestedDelay = Duration.between(Instant.now(), retryAfterInstant)
          val delayMillis = Math.max(100, suggestedDelay.toMillis) // min 100ms
          Math.min(delayMillis, maxDelay.toMillis).millis

        case None =>
          // Exponential backoff with jitter
          val backoff = Math.min(
            defaultDelay.toMillis * Math.pow(1.5, attemptNumber).toLong,
            maxDelay.toMillis
          )
          (backoff + (Math.random() * 0.2 * backoff).toLong).millis
      }
    }

    def pollOnce(remainingAttempts: Int, attemptNumber: Int): Future[T] = {
      if (remainingAttempts <= 0) {
        Future.failed(new Exception(s"Max attempts exceeded"))
      } else {
        fetchAndCheck().flatMap { case (retryAfterOpt, currentState) =>
          if (isComplete(currentState)) {
            Future.successful(currentState)
          } else {
            val delay = calculateDelay(retryAfterOpt, attemptNumber)
            after(delay, scheduler)(
              pollOnce(remainingAttempts - 1, attemptNumber + 1)
            )
          }
        }
      }
    }

    pollOnce(attemptsLeft, 0)
  }

  private def pollAcmeResource[T](
                                   resource: T,
                                   fetch: T => Option[Instant], // fetch method that returns retry-after
                                   getStatus: T => Status,
                                   maxAttempts: Int = 10,
                                   defaultDelay: FiniteDuration = 3.seconds
                                 )(using ec: ExecutionContext, scheduler: Scheduler): Future[T] = {

    val fetchAndCheck = () =>
      Future {
        val retryAfter = fetch(resource)
        (retryAfter, resource)
      }

    pollUntil(
      fetchAndCheck,
      (r: T) => getStatus(r) == Status.VALID,
      maxAttempts,
      defaultDelay
    )
  }

  private def authorizeOrder(
                              domain: String,
                              status: Status,
                              challenge: Http01Challenge
                            )(using ec: ExecutionContext, env: Env, mat: Materializer): Future[Either[String, Status]] = {

    logger.info(s"authorizing order $domain")

    if (status == Status.VALID || challenge.getStatus == Status.VALID) {
      FastFuture.successful(Right(Status.VALID))
    } else {
      Future {
        challenge.trigger()
      }(using blockingEc).flatMap { _ =>
        pollAcmeResource(
          challenge,
          fetch = (c: Http01Challenge) => Option(c.fetch().orElse(null)),
          getStatus = (c: Http01Challenge) => c.getStatus,
          maxAttempts = 10,
          defaultDelay = 3.seconds
        )(using ec, mat.system.scheduler)
          .map(_ => Right(Status.VALID))
          .recover { case e =>
            Left(s"Failed to authorize certificate for domain, ${e.getMessage}")
          }
      }
    }
  }

  private def orderCertificate(
                                order: Order,
                                csr: Array[Byte]
                              )(using ec: ExecutionContext, env: Env, mat: Materializer): Future[Either[String, Order]] = {

    Future {
      order.execute(csr)
    }(using blockingEc).flatMap { _ =>
      pollAcmeResource(
        order,
        fetch = (o: Order) => Option(o.fetch().orElse(null)),
        getStatus = (o: Order) => o.getStatus,
        maxAttempts = 10,
        defaultDelay = 5.seconds
      )(using ec, mat.system.scheduler)
        .map(Right(_))
        .recover { case e =>
          Left(s"Failed to order certificate for domain, ${e.getMessage}")
        }
    }
  }

  /*private def authorizeOrder(
      domain: String,
      status: Status,
      challenge: Http01Challenge
  )(using ec: ExecutionContext, env: Env, mat: Materializer): Future[Either[String, Status]] = {
    logger.info(s"authorizing order $domain")
    if (status == Status.VALID) {
      FastFuture.successful(Right(Status.VALID))
    } else {
      if (challenge.getStatus == Status.VALID) {
        FastFuture.successful(Right(Status.VALID))
      } else {
        challenge.trigger()
        Source
          .tick(3.seconds, 3.seconds, ())
          .mapAsync(1) { _ =>
            Future {
              challenge.fetch()
              challenge.getStatus
            }(using blockingEc)
          }
          .take(10)
          .filter(_ == Status.VALID)
          .take(1)
          .map(o => Right(o))
          .recover { case e => Left(s"Failed to authorize certificate for domain, ${e.getMessage}") }
          .toMat(Sink.headOption)(Keep.right)
          .run()
          .map {
            case None    => Left(s"Failed to authorize certificate for domain, empty")
            case Some(e) => e
          }
      }
    }
  }

  private def orderCertificate(order: Order, csr: Array[Byte])(using
      ec: ExecutionContext,
      env: Env,
      mat: Materializer
  ): Future[Either[String, Order]] = {
    Future {
      order.execute(csr)
    }(using blockingEc).flatMap { _ =>
      Source
        .tick(3.seconds, 5.seconds, ())
        .mapAsync(1) { _ =>
          Future {
            order.fetch()
            order
          }(using blockingEc)
        }
        .take(10)
        .filter(_.getStatus == Status.VALID)
        .take(1)
        .map(o => Right(o))
        .recover { case e => Left(s"Failed to order certificate for domain, ${e.getMessage}") }
        .toMat(Sink.headOption)(Keep.right)
        .run()
        .map {
          case None    => Left(s"Failed to order certificate for domain, empty")
          case Some(e) => e
        }
    }
  }*/

  private def buildCsr(domains: Seq[String], keyPair: KeyPair): Array[Byte] = {
    val csrb         = new CSRBuilder()
    csrb.addDomains(domains.asJava)
    csrb.sign(keyPair)
    val stringWriter = new StringWriter()
    csrb.write(stringWriter)
    csrb.getEncoded
  }
}
