package otoroshi.utils.letsencrypt

import org.apache.pekko.http.scaladsl.util.FastFuture
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}
import org.apache.pekko.util.ByteString
import org.apache.pekko.pattern.after
import org.shredzone.acme4j._
import org.shredzone.acme4j.challenge._
import org.apache.pekko.actor.Scheduler
import org.shredzone.acme4j.util._
import otoroshi.env.Env
import otoroshi.events.{Alerts, CertRenewalAlert}
import otoroshi.ssl.DynamicSSLEngineProvider.base64Decode
import otoroshi.ssl.{Cert, PemHeaders}
import otoroshi.utils.RegexPool
import otoroshi.utils.syntax.implicits.BetterFiniteDuration
import play.api.Logger
import play.api.libs.json._
import java.time.{Duration, Instant}
import scala.annotation.tailrec

import java.io.StringWriter
import java.security.cert.X509Certificate
import java.security.spec.{PKCS8EncodedKeySpec, X509EncodedKeySpec}
import java.security.{KeyFactory, KeyPair}
import java.util.Base64
import java.util.concurrent.Executors
import scala.jdk.CollectionConverters._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class LetsEncryptSettings(
    enabled: Boolean = false,
    server: String = "acme://letsencrypt.org/staging",
    emails: Seq[String] = Seq.empty,
    contacts: Seq[String] = Seq.empty,
    publicKey: String = "",
    privateKey: String = ""
) {

  def json: JsValue = LetsEncryptSettings.format.writes(this)

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
            .getOrElse(Seq.empty),
          contacts = (json \ "contacts")
            .asOpt[Seq[String]]
            .map(_.map(_.trim).filter(_.nonEmpty))
            .filter(_.nonEmpty)
            .getOrElse(Seq.empty),
          publicKey = (json \ "publicKey").asOpt[String].getOrElse(""),
          privateKey = (json \ "privateKey").asOpt[String].getOrElse("")
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
        "privateKey" -> o.privateKey
      )
  }
}

object LetsEncryptHelper {

  private val logger = Logger("otoroshi-lets-encrypt-helper")

  private val blockingEc = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(16))

  def createCertificate(
      domain: String
  )(implicit ec: ExecutionContext, env: Env, mat: Materializer): Future[Either[String, Cert]] = {

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
        val _account = new AccountBuilder()
          .agreeToTermsOfService()
          .useKeyPair(userKeyPair)

        val account = (letsEncryptSettings.emails.map(e => s"mailto:$e") ++ letsEncryptSettings.contacts)
          .foldLeft(_account)((a, e) => a.addContact(e))
          .create(session)

        if (logger.isDebugEnabled) logger.debug(s"ordering lets encrypt certificate for $domain")
        orderLetsEncryptCertificate(account, domain).flatMap { order =>
          if (logger.isDebugEnabled) logger.debug(s"waiting for challenge challenge $domain")
          doChallenges(order, domain).flatMap {
            case Left(err) =>
              if (logger.isDebugEnabled) logger.error(s"challenges failed: $err")
              FastFuture.successful(Left(err))
            case Right(_)  =>

              if (logger.isDebugEnabled) logger.debug(s"building csr for $domain")
              val keyPair       = KeyPairUtils.createKeyPair(2048)
              val csrByteString = buildCsr(domain, keyPair)

              if (logger.isDebugEnabled) logger.debug(s"ordering certificate for $domain")

              orderCertificate(order, csrByteString).flatMap {
                case Left(err)       =>
                  logger.error(s"ordering certificate failed: $err")
                  FastFuture.successful(Left(err))
                case Right(newOrder) =>
                  if (logger.isDebugEnabled) logger.debug(s"storing certificate for $domain")
                  Option(newOrder.getCertificate) match {
                    case None    =>
                      logger.error(s"storing certificate failed: No certificate found !")
                      FastFuture.successful(Left("No certificate found !"))
                    case Some(c) =>
                      // env.datastores.rawDataStore.del(Seq(s"${env.storageRoot}:letsencrypt:challenges:$domain:$token"))
                      val ca: X509Certificate          = c.getCertificateChain.get(1)
                      val certificate: X509Certificate = c.getCertificate
                      val cert                         =
                        Cert.apply(certificate, keyPair, ca, false).copy(letsEncrypt = true, autoRenew = true).enrich()
                      cert.save().map(_ => Right(cert))
                  }
              }
          }
        }
      }
    }
  }

  def getChallengeForToken(domain: String, token: String)(implicit
      ec: ExecutionContext,
      env: Env,
      mat: Materializer
  ): Future[Option[ByteString]] = {
    env.datastores.rawDataStore.get(s"${env.storageRoot}:letsencrypt:challenges:$domain:$token").map {
      case None        =>
        if (logger.isDebugEnabled) logger.debug(s"Trying to access token $token for domain $domain but none found")
        None
      case s @ Some(_) =>
        if (logger.isDebugEnabled) logger.debug(s"Trying to access token $token for domain $domain: found !")
        s
    }
  }

  def renew(cert: Cert)(implicit ec: ExecutionContext, env: Env, mat: Materializer): Future[Cert] = {
    env.datastores.rawDataStore.get(s"${env.storageRoot}:letsencrypt:renew:${cert.id}").flatMap {
      case Some(_) =>
        logger.warn(s"Certificate already in renewing process: ${cert.id} for ${cert.domain}")
        FastFuture.successful(cert)
      case None    =>
        val enriched = cert.enrich()
        env.datastores.rawDataStore
          .set(s"${env.storageRoot}:letsencrypt:renew:${cert.id}", ByteString("true"), Some(10.minutes.toMillis))
          .flatMap { _ =>
            createCertificate(enriched.domain)
              .flatMap {
                case Left(err) =>
                  logger.error(s"Error while renewing certificate ${cert.id} for ${enriched.domain}: $err")
                  FastFuture.successful(enriched)
                case Right(c)  =>
                  val cenriched = c.enrich()
                  Alerts.send(
                    CertRenewalAlert(
                      env.snowflakeGenerator.nextIdStr(),
                      env.env,
                      cenriched
                    )
                  )
                  enriched
                    .copy(
                      chain = cenriched.chain,
                      privateKey = cenriched.privateKey,
                      autoRenew = true,
                      letsEncrypt = true
                    )
                    .save()
                    .map(_ => cenriched)
              }
              .andThen { case _ =>
                env.datastores.rawDataStore.del(Seq(s"${env.storageRoot}:letsencrypt:renew:${cert.id}"))
              }
          }
    }
  }

  def createFromServices()(implicit ec: ExecutionContext, env: Env, mat: Materializer): Future[Unit] = {
    env.datastores.certificatesDataStore.findAll().flatMap { certificates =>
      env.datastores.serviceDescriptorDataStore.findAll().flatMap { services =>
        val letsEncryptCertificates  = certificates.filter(_.letsEncrypt)
        val letsEncryptServicesHosts = services
          .filter(_.letsEncrypt)
          .flatMap(_.allHosts)
          .filterNot(s => letsEncryptCertificates.exists(c => RegexPool(c.domain).matches(s)))
        Source(letsEncryptServicesHosts.toList)
          .mapAsync(1) { host =>
            env.datastores.rawDataStore.get(s"${env.storageRoot}:certs-issuer:letsencrypt:create:$host").flatMap {
              case Some(_) =>
                logger.warn(s"Certificate already in creating process: $host")
                FastFuture.successful(())
              case None    =>
                env.datastores.rawDataStore
                  .set(
                    s"${env.storageRoot}:certs-issuer:letsencrypt:create:$host",
                    ByteString("true"),
                    Some(4.minutes.toMillis)
                  )
                  .flatMap { _ =>
                    createCertificate(host).map(e => (host, e))
                  }
                  .andThen { case _ =>
                    env.datastores.rawDataStore.del(Seq(s"${env.storageRoot}:certs-issuer:letsencrypt:create:$host"))
                  }
            }
          }
          .map {
            case (host, Left(err)) => logger.error(s"Error while creating let's encrypt certificate for $host. $err")
            case (host, Right(_))  => logger.info(s"Successfully created let's encrypt certificate for $host")
          }
          .runWith(Sink.ignore)
          .map(_ => ())
      }
    }
  }

  private def orderLetsEncryptCertificate(account: Account, domain: String)(implicit
      ec: ExecutionContext,
      env: Env,
      mat: Materializer
  ): Future[Order] = {
    Future {
      account.newOrder().domains(domain).create()
    }(blockingEc)
  }

  private def doChallenges(order: Order, domain: String)(implicit
      ec: ExecutionContext,
      env: Env,
      mat: Materializer
  ): Future[Either[String, Seq[Status]]] = {
    Source(order.getAuthorizations.asScala.toList)
      .mapAsync(1) { auth =>
        Future {
          (auth, auth.findChallenge(classOf[Http01Challenge]))
        }(blockingEc)
      }
      .collect {
        case (auth, opt) if opt.isPresent => (auth, opt.get())
      }
      .mapAsync(1) { case (authorization, challenge) =>
        logger.info("setting challenge content in datastore")
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
        seq.find(_.isLeft).map(v => Left(v.swap.getOrElse(throw new RuntimeException("Invalid state")))).getOrElse(Right(seq.map(_.toOption.get)))
      }
  }

  // Core polling logic that respects retry-after headers
  private def pollUntil[T](
                      fetchAndCheck: () => Future[(Option[Instant], T)], // Returns (retryAfter, currentState)
                      isComplete: T => Boolean,
                      attemptsLeft: Int,
                      defaultDelay: FiniteDuration = 3.seconds,
                      maxDelay: FiniteDuration = 30.seconds
                  )(implicit ec: ExecutionContext, scheduler: Scheduler): Future[T] = {

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
                             fetch: T => Option[Instant],  // fetch method that returns retry-after
                             getStatus: T => Status,
                             maxAttempts: Int = 10,
                             defaultDelay: FiniteDuration = 3.seconds
                         )(implicit ec: ExecutionContext, scheduler: Scheduler): Future[T] = {

    val fetchAndCheck = () => Future {
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

  // Now your methods become:
  private def authorizeOrder(
                                domain: String,
                                status: Status,
                                challenge: Http01Challenge
                            )(implicit ec: ExecutionContext, env: Env, mat: Materializer): Future[Either[String, Status]] = {

    logger.info(s"authorizing order $domain")

    if (status == Status.VALID || challenge.getStatus == Status.VALID) {
      FastFuture.successful(Right(Status.VALID))
    } else {
      Future {
        challenge.trigger()
      }(blockingEc).flatMap { _ =>
        pollAcmeResource(
              challenge,
              fetch = (c: Http01Challenge) => Option(c.fetch().orElse(null)),
              getStatus = (c: Http01Challenge) => c.getStatus,
              maxAttempts = 10,
              defaultDelay = 3.seconds
            )(ec, mat.system.scheduler)
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
                              )(implicit ec: ExecutionContext, env: Env, mat: Materializer): Future[Either[String, Order]] = {

    Future {
      order.execute(csr)
    }(blockingEc).flatMap { _ =>
      pollAcmeResource(
            order,
            fetch = (o: Order) => Option(o.fetch().orElse(null)),
            getStatus = (o: Order) => o.getStatus,
            maxAttempts = 10,
            defaultDelay = 5.seconds
          )(ec, mat.system.scheduler)
          .map(Right(_))
          .recover { case e =>
            Left(s"Failed to order certificate for domain, ${e.getMessage}")
          }
    }
  }

  private def buildCsr(domain: String, keyPair: KeyPair): Array[Byte] = {
    val csrb         = new CSRBuilder()
    csrb.addDomains(domain)
    csrb.sign(keyPair)
    val stringWriter = new StringWriter()
    csrb.write(stringWriter)
    csrb.getEncoded
  }
}
