package otoroshi.utils.letsencrypt

import java.io.StringWriter
import java.security.cert.X509Certificate
import java.security.spec.{PKCS8EncodedKeySpec, X509EncodedKeySpec}
import java.security.{KeyFactory, KeyPair}
import java.util.Base64
import java.util.concurrent.Executors
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.events.{Alerts, CertRenewalAlert}
import org.shredzone.acme4j._
import org.shredzone.acme4j.challenge._
import org.shredzone.acme4j.util._
import otoroshi.utils.RegexPool
import play.api.Logger
import play.api.libs.json._
import otoroshi.ssl.DynamicSSLEngineProvider.base64Decode
import otoroshi.ssl.{Cert, PemHeaders}

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class LetsEncryptSettings(enabled: Boolean = false,
                               server: String = "acme://letsencrypt.org/staging",
                               emails: Seq[String] = Seq.empty,
                               contacts: Seq[String] = Seq.empty,
                               publicKey: String = "",
                               privateKey: String = "") {

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
      pubko <- Option(publicKey)
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
          emails = (json \ "emails").asOpt[Seq[String]].map(_.map(_.trim).filter(_.nonEmpty)).filter(_.nonEmpty).getOrElse(Seq.empty),
          contacts = (json \ "contacts").asOpt[Seq[String]].map(_.map(_.trim).filter(_.nonEmpty)).filter(_.nonEmpty).getOrElse(Seq.empty),
          publicKey = (json \ "publicKey").asOpt[String].getOrElse(""),
          privateKey = (json \ "privateKey").asOpt[String].getOrElse("")
        )
      } match {
        case Success(s) => JsSuccess(s)
        case Failure(e) => JsError(e.getMessage)
      }

    override def writes(o: LetsEncryptSettings): JsValue = Json.obj(
      "enabled"    -> o.enabled,
      "server"     -> o.server,
      "emails"     -> JsArray(o.emails.map(JsString.apply)),
      "contacts"     -> JsArray(o.emails.map(JsString.apply)),
      "publicKey"  -> o.publicKey,
      "privateKey" -> o.privateKey,
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
        case None =>
          val kp = KeyPairUtils.createKeyPair(2048)
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

        val account = (letsEncryptSettings.emails.map(e => s"mailto:$e") ++ letsEncryptSettings.contacts).foldLeft(_account)((a, e) => a.addContact(e))
          .create(session)

        logger.debug(s"ordering lets encrypt certificate for $domain")
        orderLetsEncryptCertificate(account, domain).flatMap { order =>
          logger.debug(s"waiting for challenge challenge $domain")
          doChallenges(order, domain).flatMap {
            case Left(err) =>
              logger.error(s"challenges failed: $err")
              FastFuture.successful(Left(err))
            case Right(_) => {

              logger.debug(s"building csr for $domain")
              val keyPair       = KeyPairUtils.createKeyPair(2048)
              val csrByteString = buildCsr(domain, keyPair)

              logger.debug(s"ordering certificate for $domain")

              orderCertificate(order, csrByteString).flatMap {
                case Left(err) =>
                  logger.error(s"ordering certificate failed: $err")
                  FastFuture.successful(Left(err))
                case Right(newOrder) => {
                  logger.debug(s"storing certificate for $domain")
                  Option(newOrder.getCertificate) match {
                    case None =>
                      logger.error(s"storing certificate failed: No certificate found !")
                      FastFuture.successful(Left("No certificate found !"))
                    case Some(c) => {
                      // env.datastores.rawDataStore.del(Seq(s"${env.storageRoot}:letsencrypt:challenges:$domain:$token"))
                      val ca: X509Certificate          = c.getCertificateChain.get(1)
                      val certificate: X509Certificate = c.getCertificate
                      val cert =
                        Cert.apply(certificate, keyPair, ca, false).copy(letsEncrypt = true, autoRenew = true).enrich()
                      cert.save().map(_ => Right(cert))
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  def getChallengeForToken(domain: String, token: String)(implicit ec: ExecutionContext,
                                                          env: Env,
                                                          mat: Materializer): Future[Option[ByteString]] = {
    env.datastores.rawDataStore.get(s"${env.storageRoot}:letsencrypt:challenges:$domain:$token").map {
      case None =>
        logger.debug(s"Trying to access token ${token} for domain ${domain} but none found")
        None
      case s @ Some(_) =>
        logger.debug(s"Trying to access token ${token} for domain ${domain}: found !")
        s
    }
  }

  def renew(cert: Cert)(implicit ec: ExecutionContext, env: Env, mat: Materializer): Future[Cert] = {
    env.datastores.rawDataStore.get(s"${env.storageRoot}:letsencrypt:renew:${cert.id}").flatMap {
      case Some(_) =>
        logger.warn(s"Certificate already in renewing process: ${cert.id} for ${cert.domain}")
        FastFuture.successful(cert)
      case None => {
        val enriched = cert.enrich()
        env.datastores.rawDataStore
          .set(s"${env.storageRoot}:letsencrypt:renew:${cert.id}", ByteString("true"), Some(10.minutes.toMillis))
          .flatMap { _ =>
            createCertificate(enriched.domain)
              .flatMap {
                case Left(err) =>
                  logger.error(s"Error while renewing certificate ${cert.id} for ${enriched.domain}: $err")
                  FastFuture.successful(enriched)
                case Right(c) =>
                  val cenriched = c.enrich()
                  Alerts.send(
                    CertRenewalAlert(
                      env.snowflakeGenerator.nextIdStr(),
                      env.env,
                      cenriched
                    )
                  )
                  enriched
                    .copy(chain = cenriched.chain,
                          privateKey = cenriched.privateKey,
                          autoRenew = true,
                          letsEncrypt = true)
                    .save()
                    .map(_ => cenriched)
              }
              .andThen {
                case _ => env.datastores.rawDataStore.del(Seq(s"${env.storageRoot}:letsencrypt:renew:${cert.id}"))
              }
          }
      }
    }
  }

  def createFromServices()(implicit ec: ExecutionContext, env: Env, mat: Materializer): Future[Unit] = {
    env.datastores.certificatesDataStore.findAll().flatMap { certificates =>
      env.datastores.serviceDescriptorDataStore.findAll().flatMap { services =>
        val letsEncryptCertificates = certificates.filter(_.letsEncrypt)
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
              case None => {
                env.datastores.rawDataStore
                  .set(s"${env.storageRoot}:certs-issuer:letsencrypt:create:$host",
                       ByteString("true"),
                       Some(4.minutes.toMillis))
                  .flatMap { _ =>
                    createCertificate(host).map(e => (host, e))
                  }
                  .andThen {
                    case _ =>
                      env.datastores.rawDataStore.del(Seq(s"${env.storageRoot}:certs-issuer:letsencrypt:create:$host"))
                  }
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

  private def orderLetsEncryptCertificate(account: Account, domain: String)(implicit ec: ExecutionContext,
                                                                            env: Env,
                                                                            mat: Materializer): Future[Order] = {
    Future {
      account.newOrder().domains(domain).create()
    }(blockingEc)
  }

  private def doChallenges(order: Order, domain: String)(implicit ec: ExecutionContext,
                                                         env: Env,
                                                         mat: Materializer): Future[Either[String, Seq[Status]]] = {
    Source(order.getAuthorizations.asScala.toList)
      .mapAsync(1) { authorization =>
        val challenge = authorization.findChallenge(classOf[Http01Challenge])
        logger.info("setting challenge content in datastore")
        env.datastores.rawDataStore.set(s"${env.storageRoot}:letsencrypt:challenges:$domain:${challenge.getToken}",
                                        ByteString(challenge.getAuthorization),
                                        Some(10.minutes.toMillis))
        authorizeOrder(domain, authorization.getStatus, challenge)
      }
      .toMat(Sink.seq)(Keep.right)
      .run()
      .map { seq =>
        seq.find(_.isLeft).map(v => Left(v.left.get)).getOrElse(Right(seq.map(_.right.get)))
      }
  }

  private def authorizeOrder(
      domain: String,
      status: Status,
      challenge: Http01Challenge
  )(implicit ec: ExecutionContext, env: Env, mat: Materializer): Future[Either[String, Status]] = {
    logger.info(s"authorizing order $domain")
    if (status == Status.VALID) {
      FastFuture.successful(Right(Status.VALID))
    } else {
      if (challenge.getStatus == Status.VALID) {
        FastFuture.successful(Right(Status.VALID))
      } else {
        challenge.trigger()
        Source
          .tick(1.seconds, 3.seconds, ())
          .mapAsync(1) { _ =>
            Future {
              challenge.update()
              challenge.getStatus
            }(blockingEc)
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

  private def orderCertificate(order: Order, csr: Array[Byte])(implicit ec: ExecutionContext,
                                                               env: Env,
                                                               mat: Materializer): Future[Either[String, Order]] = {
    Future {
      order.execute(csr)
    }(blockingEc).flatMap { _ =>
      Source
        .tick(1.seconds, 5.seconds, ())
        .mapAsync(1) { _ =>
          Future {
            order.update()
            order
          }(blockingEc)
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
  }

  private def buildCsr(domain: String, keyPair: KeyPair): Array[Byte] = {
    val csrb = new CSRBuilder()
    csrb.addDomains(domain)
    csrb.sign(keyPair)
    val stringWriter = new StringWriter()
    csrb.write(stringWriter)
    csrb.getEncoded
  }
}
