package otoroshi.jobs.certs

import java.util.concurrent.TimeUnit

import env.Env
import otoroshi.script.{Job, JobContext, JobId, JobInstantiation, JobKind, JobStarting, JobVisibility}
import otoroshi.ssl.pki.models.{GenCsrQuery, GenKeyPairQuery}
import play.api.Logger
import otoroshi.utils.syntax.implicits._
import ssl.{Cert, FakeKeyStore}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

class InitialCertsJob extends Job {

  private val logger = Logger("otoroshi-initials-certs-job")

  override def uniqueId: JobId = JobId("io.otoroshi.core.jobs.InitialCertsJob")

  override def name: String = "Otoroshi initial certs. generation jobs"

  override def visibility: JobVisibility = JobVisibility.Internal

  override def kind: JobKind = JobKind.ScheduledEvery

  override def starting: JobStarting = JobStarting.Automatically

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation = JobInstantiation.OneInstancePerOtoroshiInstance

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 5.seconds.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = 24.hours.some

  @deprecated
  def runWithOldSchoolPki(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    env.datastores.certificatesDataStore
      .findAll()
      .map { certs =>
        val hasInitialCert = env.datastores.certificatesDataStore.hasInitialCerts()
        if (!hasInitialCert && certs.isEmpty) {
          val foundOtoroshiCa         = certs.find(c => c.ca && c.id == Cert.OtoroshiCA)
          val foundOtoroshiDomainCert = certs.find(c => c.domain == s"*.${env.domain}")
          val ca                      = FakeKeyStore.createCA(s"CN=Otoroshi Root", FiniteDuration(365, TimeUnit.DAYS), None, None)
          val caCert                  = Cert(ca.cert, ca.keyPair, None, false).enrich()
          if (foundOtoroshiCa.isEmpty) {
            logger.info(s"Generating CA certificate for Otoroshi self signed certificates ...")
            caCert.copy(id = Cert.OtoroshiCA).save()
          }
          if (foundOtoroshiDomainCert.isEmpty) {
            logger.info(s"Generating a self signed SSL certificate for https://*.${env.domain} ...")
            val cert1 = FakeKeyStore.createCertificateFromCA(s"*.${env.domain}",
              FiniteDuration(365, TimeUnit.DAYS),
              None,
              None,
              ca.cert,
              ca.keyPair)
            Cert(cert1.cert, cert1.keyPair, foundOtoroshiCa.getOrElse(caCert), false).enrich().save()
          }
        }
      }
  }

  def runWithNewPki()(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    env.datastores.certificatesDataStore
      .findAll()
      .map { certs =>
        //val hasInitialCert = env.datastores.certificatesDataStore.hasInitialCerts()
        //if (!hasInitialCert && certs.isEmpty) {
          val foundOtoroshiCa         = certs.find(c => c.ca && c.id == Cert.OtoroshiCA).filter(_.enrich().valid)
          val foundOtoroshiWilcardCert = certs.find(c => c.id == Cert.OtoroshiWildcard || c.domain == s"*.${env.domain}").filter(_.enrich().valid)
          val foundOtoroshiClientCert = certs.find(c => c.id == Cert.OtoroshiClient).filter(_.enrich().valid)
          // logger.info(s"found foundOtoroshiCa: ${foundOtoroshiCa.isDefined}, foundOtoroshiWilcardCert: ${foundOtoroshiWilcardCert.isDefined}, foundOtoroshiClientCert: ${foundOtoroshiClientCert.isDefined}, id: ${foundOtoroshiWilcardCert.get.id}")
          env.pki.genSelfSignedCA(GenCsrQuery(
            subject = s"SN=Otoroshi Default Root CA Certificate, OU=Otoroshi Certificates, O=Otoroshi".some,
            ca = true,
            duration = (10 * 365).days,
          )).flatMap {
            case Left(error) => logger.error(s"error while generating otoroshi root CA: $error").future
            case Right(response) => {
              val rootCA = response.toCert.enrich().copy(
                id = Cert.OtoroshiCA,
                autoRenew = true,
                ca = true,
                name = "Otoroshi Default Root CA Certificate",
                description = "Otoroshi root CA (auto-generated)"
              )
              if (foundOtoroshiCa.isEmpty) {
                logger.info(s"Generating root CA certificate for Otoroshi ... ")
                rootCA.save()
              }
              env.pki.genCert(GenCsrQuery(
                hosts = Seq(s"*.${env.domain}"),
                subject = s"CN=*.${env.domain}, SN=Otoroshi Default Wildcard Certificate, OU=Otoroshi Certificates, O=Otoroshi".some,
                duration = 365.days,
              ), response.cert, response.key).map {
                case Left(error) => logger.error(s"error while generating otoroshi wildcard cert: $error")
                case Right(responseWildcard) => {
                  if (foundOtoroshiWilcardCert.isEmpty) {
                    logger.info(s"Generating default wildcard certificate for https://*.${env.domain} ...")
                    responseWildcard.toCert.enrich().copy(
                      id = Cert.OtoroshiWildcard,
                      name = "Otoroshi Default Wildcard",
                      description = s"Default wildcard certificate for https://*.${env.domain} (auto-generated)",
                      autoRenew = true
                    ).save()
                  }
                }
              }
              env.pki.genCert(GenCsrQuery(
                subject = s"SN=Otoroshi Default Client Certificate, OU=Otoroshi Certificates, O=Otoroshi".some,
                duration = 365.days,
                client = true
              ), response.cert, response.key).map {
                case Left(error) => logger.error(s"error while generating otoroshi client cert: $error")
                case Right(responseClient) => {
                  if (foundOtoroshiClientCert.isEmpty) {
                    logger.info(s"Generating default client certificate ...")
                    responseClient.toCert.enrich().copy(
                      id = Cert.OtoroshiClient,
                      name = "Otoroshi Default Client Certificate",
                      description = "Default client certificate for otoroshi (auto-generated)",
                      autoRenew = true,
                      client = true
                    ).save()
                  }
                }
              }
            }
          }
      }
  }

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    runWithNewPki()
  }
}
