package otoroshi.plugins.jobs.kubernetes

import env.Env
import otoroshi.script._
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json.{JsObject, Json}
import ssl.DynamicSSLEngineProvider

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object KubernetesCertSyncJob {

  val logger = Logger("otoroshi-plugins-kubernetes-cert-sync")

  def syncOtoroshiCertsToKubernetesSecrets(client: KubernetesClient): Future[Unit] = ().future // TODO: implements

  def importCerts(certs: Seq[KubernetesCertSecret])(implicit env: Env, ec: ExecutionContext): Future[Unit] = {

    val hashs = DynamicSSLEngineProvider.certificates.map {
      case (_, value) => (value.contentHash, value)
    }

    Future.sequence(certs.map { cert =>
      cert.cert match {
        case None => ().future
        case Some(found) => {
          val certId = s"kubernetes-certs-import-${cert.namespace}-${cert.name}".slugifyWithSlash
          val newCert = found.copy(id = certId).enrich()
          env.datastores.certificatesDataStore.findById(certId).flatMap {
            case None =>
              hashs.get(newCert.contentHash) match {
                case None =>
                  logger.info(s"importing cert. ${cert.namespace} - ${cert.name}")
                  newCert.copy(entityMetadata = newCert.entityMetadata ++ Map(
                    "otoroshi-provider" -> "kubernetes-certs",
                    "kubernetes-name" -> cert.name,
                    "kubernetes-namespace" -> cert.namespace,
                    "kubernetes-path" -> s"${cert.namespace}/${cert.name}",
                    "kubernetes-uid" -> cert.uid
                  )).save().map(_ => ())
                case Some(_) => ().future
              }
            case Some(existingCert) if existingCert.contentHash == newCert.contentHash => ().future
            case Some(existingCert) if existingCert.contentHash != newCert.contentHash =>
              hashs.get(newCert.contentHash) match {
                case None =>
                  logger.info(s"updating cert. ${cert.namespace} - ${cert.name}")
                  newCert.copy(entityMetadata = newCert.entityMetadata ++ Map(
                    "otoroshi-provider" -> "kubernetes-certs",
                    "kubernetes-name" -> cert.name,
                    "kubernetes-namespace" -> cert.namespace,
                    "kubernetes-path" -> s"${cert.namespace}/${cert.name}",
                    "kubernetes-uid" -> cert.uid
                  )).save().map(_ => ())
                case Some(_) => ().future
              }
          }
        }
      }
    }).map(_ => ())
  }

  def deleteOutOfSyncCerts(currentCerts: Seq[KubernetesCertSecret])(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val paths = currentCerts.map(c => s"${c.namespace}/${c.name}")
    env.datastores.certificatesDataStore.findAll().flatMap { certs =>
      val ids = certs
        .filter(c => c.entityMetadata.get("otoroshi-provider") == "kubernetes-certs".some)
        .filterNot(c => paths.contains(c.entityMetadata.get("kubernetes-path").get))
        .map(_.id)
      env.datastores.certificatesDataStore.deleteByIds(ids).map(_ => ())
    }
  }

  def syncKubernetesSecretsToOtoroshiCerts(client: KubernetesClient)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    client.fetchCertsAndFilterLabels().flatMap { certs =>
      importCerts(certs).flatMap { _ =>
        deleteOutOfSyncCerts(certs)
      }
    }
  }
}

class KubernetesToOtoroshiCertSyncJob extends Job {

  private val logger = Logger("otoroshi-plugins-kubernetes-to-otoroshi-certs-job")

  override def uniqueId: JobId = JobId("io.otoroshi.plugins.jobs.kubernetes.KubernetesToOtoroshiCertSyncJob")

  override def name: String = "Kubernetes to Otoroshi certs. synchronizer"

  override def defaultConfig: Option[JsObject] = KubernetesConfig.defaultConfig.some

  override def description: Option[String] =
    Some(
      s"""This plugin syncs. TLS secrets from Kubernetes to Otoroshi
         |
         |```json
         |${Json.prettyPrint(defaultConfig.get)}
         |```
      """.stripMargin
    )

  override def visibility: JobVisibility = JobVisibility.UserLand

  override def kind: JobKind = JobKind.ScheduledEvery

  override def starting: JobStarting = JobStarting.FromConfiguration

  override def instantiation: JobInstantiation = JobInstantiation.OneInstancePerOtoroshiCluster

  override def initialDelay: Option[FiniteDuration] = 10.seconds.some

  override def interval: Option[FiniteDuration] = 10.seconds.some

  override def jobStart(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = super.jobStart(ctx)

  override def jobStop(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = super.jobStop(ctx)

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val conf = KubernetesConfig.theConfig(ctx)
    val client = new KubernetesClient(conf, env)
    if (conf.enabled) {
      logger.info("Running kubernetes to otoroshi certs. sync ...")
      KubernetesCertSyncJob.syncKubernetesSecretsToOtoroshiCerts(client)
    } else {
      ().future
    }
  }
}

class OtoroshiToKubernetesCertSyncJob extends Job {

  private val logger = Logger("otoroshi-plugins-otoroshi-to-kubernetes-certs-job")

  override def uniqueId: JobId = JobId("io.otoroshi.plugins.jobs.kubernetes.OtoroshiToKubernetesCertSyncJob")

  override def name: String = "Otoroshi to Kubernetes certs. synchronizer"

  override def defaultConfig: Option[JsObject] = KubernetesConfig.defaultConfig.some

  override def description: Option[String] =
    Some(
      s"""This plugin syncs. Otoroshi Certs to Kubernetes TLS secrets
         |
         |```json
         |${Json.prettyPrint(defaultConfig.get)}
         |```
      """.stripMargin
    )

  override def visibility: JobVisibility = JobVisibility.UserLand

  override def kind: JobKind = JobKind.ScheduledEvery

  override def starting: JobStarting = JobStarting.FromConfiguration

  override def instantiation: JobInstantiation = JobInstantiation.OneInstancePerOtoroshiCluster

  override def initialDelay: Option[FiniteDuration] = 10.seconds.some

  override def interval: Option[FiniteDuration] = 10.seconds.some

  override def jobStart(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = super.jobStart(ctx)

  override def jobStop(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = super.jobStop(ctx)

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val conf = KubernetesConfig.theConfig(ctx)
    val client = new KubernetesClient(conf, env)
    if (conf.enabled) {
      logger.info("Running otoroshi to kubernetes certs. sync ...")
      KubernetesCertSyncJob.syncOtoroshiCertsToKubernetesSecrets(client)
    } else {
      ().future
    }
  }
}


