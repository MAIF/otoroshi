package otoroshi.plugins.jobs.kubernetes

import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Sink, Source}
import env.Env
import otoroshi.script._
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json.{JsObject, Json}
import ssl.DynamicSSLEngineProvider

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object KubernetesCertSyncJob {

  private val logger = Logger("otoroshi-plugins-kubernetes-cert-sync")
  private val running = new AtomicBoolean(false)
  private val shouldRunNext = new AtomicBoolean(false)

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
                case None if newCert.expired => ().future
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
                case None if newCert.expired => ().future
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

  def syncKubernetesSecretsToOtoroshiCerts(client: KubernetesClient, jobRunning: => Boolean)(implicit env: Env, ec: ExecutionContext): Future[Unit] = env.metrics.withTimerAsync("otoroshi.plugins.kubernetes.kube-certs.sync") {
    if (!jobRunning) {
      shouldRunNext.set(false)
      running.set(false)
    }
    if (jobRunning && running.compareAndSet(false, true)) {
      shouldRunNext.set(false)
      logger.info("fetching certs")
      client.fetchCertsAndFilterLabels().flatMap { certs =>
        logger.info("importing new certs")
        importCerts(certs).flatMap { _ =>
          logger.info("deleting outdated certs")
          deleteOutOfSyncCerts(certs)
        }
      }.flatMap { _ =>
        logger.info("certs sync done !")
        if (shouldRunNext.get()) {
          shouldRunNext.set(false)
          logger.info("restart job right now because sync was asked during sync ")
          syncKubernetesSecretsToOtoroshiCerts(client, jobRunning)
        } else {
          ().future
        }
      }.andThen {
        case e =>
          running.set(false)
      }
    } else {
      logger.info("Job already running, scheduling after ")
      shouldRunNext.set(true)
      ().future
    }
  }

  def syncOtoroshiCertToKubernetesSecrets(client: KubernetesClient, jobRunning: => Boolean)(implicit env: Env, ec: ExecutionContext): Future[Unit] = env.metrics.withTimerAsync("otoroshi.plugins.kubernetes.oto-certs.sync") {

    import ssl.SSLImplicits._

    implicit val mat = env.otoroshiMaterializer
    if (!jobRunning) {
      shouldRunNext.set(false)
      running.set(false)
    }
    if (jobRunning && running.compareAndSet(false, true)) {
      shouldRunNext.set(false)
      env.datastores.certificatesDataStore.findAll().flatMap { certs =>
        client.fetchCerts().flatMap { kubeCertsRaw =>
          val kubeCerts = kubeCertsRaw.filter(_.metaKind.contains("job/cert"))
          for {
            _ <- Source(certs.toList)
                  .mapAsync(1) { cert =>
                    kubeCerts.find(c => c.metaId.contains(cert.id)) match {
                      case None =>
                        client.createSecret("otoroshi", cert.name.slugifyWithSlash, "kubernetes.io/tls", Json.obj(
                          "tls.crt" -> cert.chain.base64,
                          "tls.key" -> cert.privateKey.base64,
                          "cert.crt" -> cert.certificates.head.asPem,
                          "ca-chain.crt" -> cert.certificates.tail.map(_.asPem).mkString("\n\n"),
                        ), "job/cert", cert.id)
                      case Some(c) =>
                        client.updateSecret(c.namespace, c.name, "kubernetes.io/tls", Json.obj(
                          "tls.crt" -> cert.chain.base64,
                          "tls.key" -> cert.privateKey.base64,
                          "cert.crt" -> cert.certificates.head.asPem,
                          "ca-chain.crt" -> cert.certificates.tail.map(_.asPem).mkString("\n\n"),
                        ), "job/cert", cert.id)
                    }
                  }.runWith(Sink.ignore)
            _ <- Source (kubeCerts.toList)
                  .mapAsync(1) { cert =>
                    certs.find(c => cert.metaId.contains(c.id)) match {
                      case None => client.deleteSecret(cert.namespace, cert.name)
                      case Some(secret) => FastFuture.successful(())
                    }
                  }.runWith(Sink.ignore)
          } yield ()
        }
      }
    } else {
      logger.info("Job already running, scheduling after ")
      shouldRunNext.set(true)
      ().future
    }
  }
}

class KubernetesToOtoroshiCertSyncJob extends Job {

  private val logger = Logger("otoroshi-plugins-kubernetes-to-otoroshi-certs-job")
  private val stopCommand = new AtomicBoolean(false)
  private val watchCommand = new AtomicBoolean(false)
  private val lastWatchStopped = new AtomicBoolean(true)
  private val lastWatchSync = new AtomicLong(0L)

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

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation = JobInstantiation.OneInstancePerOtoroshiCluster

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 5.seconds.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = 60.seconds.some

  override def jobStart(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    stopCommand.set(false)
    lastWatchStopped.set(true)
    watchCommand.set(false)
    val config = KubernetesConfig.theConfig(ctx)
    handleWatch(config, ctx)
    ().future
  }

  override def jobStop(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    stopCommand.set(true)
    watchCommand.set(false)
    lastWatchStopped.set(true)
    ().future
  }

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val conf = KubernetesConfig.theConfig(ctx)
    val client = new KubernetesClient(conf, env)
    logger.info("Running kubernetes to otoroshi certs. sync ...")
    handleWatch(conf, ctx)
    KubernetesCertSyncJob.syncKubernetesSecretsToOtoroshiCerts(client, !stopCommand.get())
  }

  def handleWatch(config: KubernetesConfig, ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Unit = {
    if (config.watch && !watchCommand.get() && lastWatchStopped.get()) {
      logger.info("starting namespaces watch ...")
      implicit val mat = env.otoroshiMaterializer
      watchCommand.set(true)
      lastWatchStopped.set(false)
      val conf = KubernetesConfig.theConfig(ctx)
      val client = new KubernetesClient(conf, env)
      val source = client.watchKubeResources(conf.namespaces, Seq("secrets", "endpoints"), 30, !watchCommand.get())
      source.takeWhile(_ => !watchCommand.get())/*.throttle(1, 10.seconds)*/.filterNot(_.isEmpty).alsoTo(Sink.onComplete {
        case _ => lastWatchStopped.set(true)
      }).runWith(Sink.foreach { group =>
        val now = System.currentTimeMillis()
        if ((lastWatchSync.get() + 10000) < now) { // 10 sec
          logger.debug(s"sync triggered by a group of ${group.size} events")
          KubernetesCertSyncJob.syncKubernetesSecretsToOtoroshiCerts(client, !stopCommand.get())
        }
      })
    } else if (!config.watch) {
      logger.info("stopping namespaces watch")
      watchCommand.set(false)
    } else {
      logger.info(s"watching already ...")
    }
  }
}

class OtoroshiToKubernetesCertSyncJob extends Job {

  private val logger = Logger("otoroshi-plugins-otoroshi-certs-to-kubernetes-secrets-job")
  private val stopCommand = new AtomicBoolean(false)

  override def uniqueId: JobId = JobId("io.otoroshi.plugins.jobs.kubernetes.OtoroshiCertToKubernetesSecretsSyncJob")

  override def name: String = "Otoroshi certs. to Kubernetes secrets synchronizer"

  override def defaultConfig: Option[JsObject] = KubernetesConfig.defaultConfig.some

  override def description: Option[String] =
    Some(
      s"""This plugin syncs. Otoroshi certs to Kubernetes TLS secrets
         |
         |```json
         |${Json.prettyPrint(defaultConfig.get)}
         |```
      """.stripMargin
    )

  override def visibility: JobVisibility = JobVisibility.UserLand

  override def kind: JobKind = JobKind.ScheduledEvery

  override def starting: JobStarting = JobStarting.FromConfiguration

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation = JobInstantiation.OneInstancePerOtoroshiCluster

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 5.seconds.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = 60.seconds.some

  override def jobStart(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    stopCommand.set(false)
    ().future
  }

  override def jobStop(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    stopCommand.set(true)
    ().future
  }

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val conf = KubernetesConfig.theConfig(ctx)
    val client = new KubernetesClient(conf, env)
    logger.info("Running otoroshi certs. to kubernetes ssecrets  sync ...")
    KubernetesCertSyncJob.syncOtoroshiCertToKubernetesSecrets(client, !stopCommand.get())
  }
}


