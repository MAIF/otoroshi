package otoroshi.jobs.certs

import java.util.concurrent.TimeUnit

import env.Env
import otoroshi.script.{Job, JobContext, JobId, JobInstantiation, JobKind, JobStarting, JobVisibility}
import otoroshi.ssl.pki.models.{GenCertResponse, GenCsrQuery, GenKeyPairQuery}
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
              Seq.empty,
              ca.keyPair)
            Cert(cert1.cert, cert1.keyPair, foundOtoroshiCa.getOrElse(caCert), false).enrich().save()
          }
        }
      }
  }

  def createOrFind(name: String, description: String, subject: String, hosts: Seq[String], duration: FiniteDuration, ca: Boolean, client: Boolean, keypair: Boolean, id: String, found: Option[Cert], from: Option[Cert])(implicit env: Env, ec: ExecutionContext): Future[Option[Cert]] = {
    found.filter(_.enrich().valid) match {
      case None => {
        val query = GenCsrQuery(
          hosts = hosts,
          subject = subject.some,
          ca = ca,
          client = client,
          duration = duration,
        )
        (from match {
          case None if ca => env.pki.genSelfSignedCA(query)
          case Some(c) if ca =>  env.pki.genSubCA(query, c.certificates.head, c.certificates.tail, c.cryptoKeyPair.getPrivate)
          case Some(c) => env.pki.genCert(query, c.certificates.head, c.certificates.tail, c.cryptoKeyPair.getPrivate)
          case _ => Left("bad configuration").future
        }).flatMap {
          case Left(error) =>
            logger.error(s"error while generating $name: $error")
            None.future
          case Right(response) => {
            val cert = response.toCert.enrich().copy(
              id = id,
              autoRenew = true,
              ca = ca,
              client = client,
              keypair = keypair,
              name = name,
              description = description
            )
            cert.save().map(_ => cert.some)
          }
        }
      }
      case found@Some(_) => found.future
    }
  }

  def runWithNewPki()(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val disableWildcardGen = !env.configuration.getOptional[Boolean]("otoroshi.ssl.genWildcardCert").getOrElse(true)
    for {
      cRoot         <- env.datastores.certificatesDataStore.findById(Cert.OtoroshiCA)
      cIntermediate <- env.datastores.certificatesDataStore.findById(Cert.OtoroshiIntermediateCA)
      cWildcard     <- env.datastores.certificatesDataStore.findById(Cert.OtoroshiWildcard)
      cClient       <- env.datastores.certificatesDataStore.findById(Cert.OtoroshiClient)
      cJwt          <- env.datastores.certificatesDataStore.findById(Cert.OtoroshiJwtSigning)
      root          <- createOrFind("Otoroshi Default Root CA Certificate",         "Otoroshi root CA (auto-generated)",              s"CN=Otoroshi Default Root CA Certificate, OU=Otoroshi Certificates, O=Otoroshi",                      Seq.empty,               (10 * 365).days, true,  false, false, Cert.OtoroshiCA,             cRoot,         None)
      intermediate  <- createOrFind("Otoroshi Default Intermediate CA Certificate", "Otoroshi intermediate CA (auto-generated)",      s"CN=Otoroshi Default Intermediate CA Certificate, OU=Otoroshi Certificates, O=Otoroshi",              Seq.empty,               (10 * 365).days, true,  false, false, Cert.OtoroshiIntermediateCA, cIntermediate, root)
      _             <- createOrFind("Otoroshi Default Client Certificate",          "Otoroshi client certificate (auto-generated)",   s"CN=Otoroshi Default Client Certificate, OU=Otoroshi Certificates, O=Otoroshi",                       Seq.empty,               (1 * 365).days,  false, true,  false, Cert.OtoroshiClient,         cClient,       intermediate)
      _             <- createOrFind("Otoroshi Default Jwt Signing Keypair",         "Otoroshi jwt signing keypair (auto-generated)",  s"CN=Otoroshi Default Jwt Signing Keypair, OU=Otoroshi Certificates, O=Otoroshi",                      Seq.empty,               (1 * 365).days,  false, false, true, Cert.OtoroshiJwtSigning,      cJwt,          intermediate)
      alreadyDone   <- env.datastores.rawDataStore.get(s"${env.storageRoot}:jobs:initials-certs-job:wildcard-gen").map(_.exists(_.utf8String == "done"))
      alreadyExists <- env.datastores.certificatesDataStore.findAll().map(_.exists(_.allDomains.contains(s"*.${env.domain}")))
      _             <- if (alreadyDone || alreadyExists || disableWildcardGen) ().future else {
        env.datastores.rawDataStore.set(s"${env.storageRoot}:jobs:initials-certs-job:wildcard-gen", "done".byteString, None)
        createOrFind("Otoroshi Default Wildcard Certificate",        "Otoroshi wildcard certificate (auto-generated)", s"CN=*.${env.domain}, SN=Otoroshi Default Wildcard Certificate, OU=Otoroshi Certificates, O=Otoroshi", Seq(s"*.${env.domain}"), (1 * 365).days,  false, false, false, Cert.OtoroshiWildcard,       cWildcard,     intermediate)
      }
    } yield ()
  }

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    runWithNewPki()
  }
}
