package otoroshi.plugins.jobs.kubernetes

import java.util.Base64
import java.util.concurrent.{Executors, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}
import akka.util.ByteString
import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.{Sink, Source}
import auth.AuthModuleConfig
import otoroshi.cluster.ClusterMode
import env.Env
import io.kubernetes.client.extended.leaderelection.resourcelock.EndpointsLock
import io.kubernetes.client.extended.leaderelection.{LeaderElectionConfig, LeaderElector}
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.credentials.AccessTokenAuthentication
import models._
import org.joda.time.DateTime
import otoroshi.models.{DataExporterConfig, SimpleOtoroshiAdmin, Team, Tenant}
import otoroshi.plugins.jobs.kubernetes.IngressSupport.IntOrString
import otoroshi.script._
import otoroshi.ssl.pki.models.GenCsrQuery
import otoroshi.tcp.TcpService
import otoroshi.utils.TypedMap
import otoroshi.utils.http.DN
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{Result, Results}
import otoroshi.security.IdGenerator
import otoroshi.ssl.{Cert, DynamicSSLEngineProvider}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class KubernetesOtoroshiCRDsControllerJob extends Job {

  private val logger = Logger("otoroshi-plugins-kubernetes-crds-controller-job")
  private val shouldRun = new AtomicBoolean(false)
  private val apiClientRef = new AtomicReference[ApiClient]()
  private val threadPool = Executors.newFixedThreadPool(1)
  private val stopCommand = new AtomicBoolean(false)
  private val watchCommand = new AtomicBoolean(false)
  private val lastWatchStopped = new AtomicBoolean(true)
  private val lastWatchSync = new AtomicLong(0L)

  override def uniqueId: JobId = JobId("io.otoroshi.plugins.jobs.kubernetes.KubernetesOtoroshiCRDsControllerJob")

  override def name: String = "Kubernetes Otoroshi CRDs Controller"

  override def defaultConfig: Option[JsObject] = KubernetesConfig.defaultConfig.some

  override def description: Option[String] =
    Some(
      s"""This plugin enables Otoroshi CRDs Controller
         |
         |```json
         |${Json.prettyPrint(defaultConfig.get)}
         |```
      """.stripMargin
    )

  override def visibility: JobVisibility = JobVisibility.UserLand

  override def kind: JobKind = JobKind.ScheduledEvery

  override def starting: JobStarting = JobStarting.FromConfiguration

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation = {
    Option(env)
      .flatMap(env => env.datastores.globalConfigDataStore.latestSafe.map(c => (env, c)))
      .map { case (env, c) => (env, KubernetesConfig.theConfig((c.scripts.jobConfig \ "KubernetesConfig").as[JsValue])(env, env.otoroshiExecutionContext)) }
      .map {
        case (env, cfg) =>
          env.clusterConfig.mode match {
            case ClusterMode.Off if !cfg.kubeLeader => JobInstantiation.OneInstancePerOtoroshiCluster
            case ClusterMode.Off if cfg.kubeLeader => JobInstantiation.OneInstancePerOtoroshiInstance
            case _ if cfg.kubeLeader => JobInstantiation.OneInstancePerOtoroshiLeaderInstance
            case _ => JobInstantiation.OneInstancePerOtoroshiCluster
          }
      }
      .getOrElse(JobInstantiation.OneInstancePerOtoroshiCluster)
  }

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 5.seconds.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = KubernetesConfig.theConfig(ctx)(env, env.otoroshiExecutionContext).syncIntervalSeconds.seconds.some

  override def jobStart(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    logger.info("start")
    stopCommand.set(false)
    lastWatchStopped.set(true)
    watchCommand.set(false)
    val config = KubernetesConfig.theConfig(ctx)
    if (config.kubeLeader) {
      val apiClient = new ClientBuilder()
        .setVerifyingSsl(!config.trust)
        .setAuthentication(new AccessTokenAuthentication(config.token.get))
        .setBasePath(config.endpoint)
        .setCertificateAuthority(config.caCert.map(c => c.getBytes).orNull)
        .build()
      apiClientRef.set(apiClient)
      val leaderElector = new LeaderElector(
        new LeaderElectionConfig(
          new EndpointsLock(
            "kube-system",
            "leader-election",
            "otoroshi-crds-controller",
            apiClient
          ),
          java.time.Duration.ofMillis(10000),
          java.time.Duration.ofMillis(8000),
          java.time.Duration.ofMillis(5000)
        )
      )
      threadPool.submit(new Runnable {
        override def run(): Unit = {
          leaderElector.run(
            () => shouldRun.set(true),
            () => shouldRun.set(false)
          )
        }
      })
    }

    handleWatch(config, ctx)
    ().future
  }

  def getNamespaces(client: KubernetesClient, conf: KubernetesConfig)(implicit env: Env, ec: ExecutionContext): Future[Seq[String]] = {
    if (conf.namespacesLabels.isEmpty) {
      conf.namespaces.future
    } else {
      client.fetchNamespacesAndFilterLabels().map { namespaces =>
        namespaces.map(_.name)
      }
    }
  }

  def handleWatch(config: KubernetesConfig, ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Unit = {
    if (config.watch && !watchCommand.get() && lastWatchStopped.get()) {
      logger.info("starting namespaces watch ...")
      implicit val mat = env.otoroshiMaterializer
      watchCommand.set(true)
      lastWatchStopped.set(false)
      env.otoroshiScheduler.scheduleOnce(5.minutes) {
        logger.info("trigger stop namespaces watch after 5 min.")
        watchCommand.set(false)
        lastWatchStopped.set(true)
      }
      val conf = KubernetesConfig.theConfig(ctx)
      val client = new KubernetesClient(conf, env)
      val source = Source.future(getNamespaces(client, conf))
        .flatMapConcat { nses =>
          client.watchOtoResources(nses, Seq(
            "service-groups",
            "service-descriptors",
            "apikeys",
            "certificates",
            "global-configs",
            "jwt-verifiers",
            "auth-modules",
            "scripts",
            "tcp-services",
            "admins",
            "data-exporters",
            "teams",
            "organizations",
          ), conf.watchTimeoutSeconds, !watchCommand.get()).merge(
            client.watchKubeResources(nses, Seq("secrets", "services", "pods", "endpoints"), conf.watchTimeoutSeconds, !watchCommand.get())
          )
        }
      source.takeWhile(_ => !watchCommand.get()).filterNot(_.isEmpty).alsoTo(Sink.onComplete {
        case _ => lastWatchStopped.set(true)
      }).runWith(Sink.foreach { group =>
        val now = System.currentTimeMillis()
        if ((lastWatchSync.get() + (conf.watchGracePeriodSeconds * 1000L)) < now) { // 10 sec
          logger.debug(s"sync triggered by a group of ${group.size} events")
          KubernetesCRDsJob.syncCRDs(conf, ctx.attrs, !stopCommand.get())
        }
      })
    } else if (!config.watch) {
      logger.info("stopping namespaces watch")
      watchCommand.set(false)
    } else {
      logger.info(s"watching already ...")
    }
  }

  override def jobStop(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    // Option(apiClientRef.get()).foreach(_.) // nothing to stop stuff here ...
    logger.info("stopping kubernetes controller job")
    stopCommand.set(true)
    watchCommand.set(false)
    lastWatchStopped.set(true)
    threadPool.shutdown()
    shouldRun.set(false)
    ().future
  }

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    logger.info("run")
    val conf = KubernetesConfig.theConfig(ctx)
    if (conf.crds) {
      if (conf.kubeLeader) {
        if (shouldRun.get()) {
          handleWatch(conf, ctx)
          KubernetesCRDsJob.patchCoreDnsConfig(conf, ctx)
          KubernetesCRDsJob.patchKubeDnsConfig(conf, ctx)
          KubernetesCRDsJob.patchOpenshiftDnsOperatorConfig(conf, ctx)
          KubernetesCRDsJob.patchValidatingAdmissionWebhook(conf, ctx)
          KubernetesCRDsJob.patchMutatingAdmissionWebhook(conf, ctx)
          KubernetesCRDsJob.createWebhookCerts(conf, ctx)
          KubernetesCRDsJob.createMeshCerts(conf, ctx)
          KubernetesCRDsJob.syncCRDs(conf, ctx.attrs, !stopCommand.get())
        } else {
          ().future
        }
      } else {
        handleWatch(conf, ctx)
        KubernetesCRDsJob.patchCoreDnsConfig(conf, ctx)
        KubernetesCRDsJob.patchKubeDnsConfig(conf, ctx)
        KubernetesCRDsJob.patchOpenshiftDnsOperatorConfig(conf, ctx)
        KubernetesCRDsJob.patchValidatingAdmissionWebhook(conf, ctx)
        KubernetesCRDsJob.patchMutatingAdmissionWebhook(conf, ctx)
        KubernetesCRDsJob.createWebhookCerts(conf, ctx)
        KubernetesCRDsJob.createMeshCerts(conf, ctx)
        KubernetesCRDsJob.syncCRDs(conf, ctx.attrs, !stopCommand.get())
      }
    } else {
      ().future
    }
  }
}

/*
class ClientSupportTest extends Job {

  def uniqueId: JobId = JobId("ClientSupportTest")
  override def visibility: JobVisibility            = JobVisibility.UserLand
  override def kind: JobKind                        = JobKind.ScheduledOnce
  override def starting: JobStarting                = JobStarting.Automatically
  override def instantiation: JobInstantiation      = JobInstantiation.OneInstancePerOtoroshiInstance
  override def initialDelay: Option[FiniteDuration] = Some(FiniteDuration(5000, TimeUnit.MILLISECONDS))

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    println("running ClientSupportTest")
    Try {
      val cliSupport = new ClientSupport(new KubernetesClient(KubernetesConfig.theConfig(Json.obj(
        "endpoint" -> "http://localhost:8080"
      )), env), Logger("test"))
      val resource = Json.parse(
        """{
          |  "apiVersion": "proxy.otoroshi.io/v1alpha1",
          |  "kind": "ApiKey",
          |  "metadata": {
          |    "name": "apikey-test",
          |    "namespace": "foo",
          |    "uid":"000000"
          |  },
          |  "spec": {
          |    "exportSecret": true,
          |    "secretName": "apikey-secret",
          |    "rotation": {
          |      "enabled": true,
          |      "rotationEvery": 2,
          |      "gracePeriod": 1
          |    }
          |  }
          |}""".stripMargin)

      def fake(a: String, b: String, apk: ApiKey): Unit = {
        println(s"$a:$b:$apk")
      }

      val res = cliSupport.customizeApiKey(resource.select("spec").as[JsValue], KubernetesOtoroshiResource(resource), Seq.empty, Seq.empty, fake)
      println(Json.prettyPrint(res))
    } match {
      case Failure(e) => e.printStackTrace()
      case Success(_) => ()
    }
    ().future
  }
}*/

class ClientSupport(val client: KubernetesClient, logger: Logger)(implicit ec: ExecutionContext, env: Env) {

  private[kubernetes] def customizeIdAndName(spec: JsValue, res: KubernetesOtoroshiResource): JsValue = {
    spec.applyOn(s =>
      (s \ "name").asOpt[String] match {
        case None => s.as[JsObject] ++ Json.obj("name" -> res.name)
        case Some(_) => s
      }
    ).applyOn(s =>
      (s \ "description").asOpt[String] match {
        case None => s.as[JsObject] ++ Json.obj("description" -> "--")
        case Some(_) => s
      }
    ).applyOn(s =>
      (s \ "desc").asOpt[String] match {
        case None => s.as[JsObject] ++ Json.obj("desc" -> "--")
        case Some(_) => s
      }
    ).applyOn(s =>
      s.as[JsObject] ++ Json.obj(
        "metadata" -> ((s \ "metadata").asOpt[JsObject].getOrElse(Json.obj()) ++ Json.obj(
          "otoroshi-provider" -> "kubernetes-crds",
          "kubernetes-name" -> res.name,
          "kubernetes-namespace" -> res.namespace,
          "kubernetes-path" -> res.path,
          "kubernetes-uid" -> res.uid
        ))
      )
    ).applyOn(s =>
      res.metaId match {
        case None => s.as[JsObject] ++ Json.obj("id" -> s"kubernetes-crd-import-${res.namespace}-${res.name}".slugifyWithSlash)
        case Some(id) => s.as[JsObject] ++ Json.obj("id" -> id)
      }
    )
  }

  private[kubernetes] def findEntityByIdAndPath[T](entities: Seq[T], name: String, res: KubernetesOtoroshiResource, metaExtr: T => Map[String, String], idExtr: T => String): Option[T] = {
    val existingById = entities.find(s => res.metaId.contains(idExtr(s)))
    val existingKubePath = entities.filter(e => metaExtr(e).get("otoroshi-provider").contains("kubernetes-crds")).find(e => metaExtr(e).get("kubernetes-path").contains(res.path))
    (existingById, existingKubePath) match {
      case (None, _) => existingKubePath
      case (Some(_), None) => existingById
      case (Some(s1), Some(s2)) if idExtr(s1) == idExtr(s2) => existingById
      case (Some(s1), Some(s2)) if idExtr(s1) != idExtr(s2) =>
        logger.warn(s"trying to reconcile 2 different entities of type $name with same id/path. entity with native id always win as fallback !")
        existingById
    }
  }

  private[kubernetes] def findAndMerge[T](_spec: JsValue, res: KubernetesOtoroshiResource, templateName: String, maybe: Option[T], entities: Seq[T], metaExtr: T => Map[String, String], idExtr: T => String, toJson: T => JsValue, enabledExtr: Option[T => Boolean] = None): JsObject = {
    val opt = maybe.orElse(findEntityByIdAndPath[T](entities, templateName, res, metaExtr, idExtr))
    val template = (client.config.templates \ templateName).asOpt[JsObject].getOrElse(Json.obj())
    val spec = template.deepMerge(opt.map(v => toJson(v).as[JsObject].deepMerge(_spec.as[JsObject])).getOrElse(_spec).as[JsObject])
    spec.applyOnIf(enabledExtr.isDefined) { s =>
      opt match {
        case None => s.as[JsObject] - "enabled" ++ Json.obj("enabled" -> true)
        case Some(entity) => s.as[JsObject] - "enabled" ++ Json.obj("enabled" -> enabledExtr.get(entity))
      }
    }
  }

  private[kubernetes] def customizeServiceDescriptor(_spec: JsValue, res: KubernetesOtoroshiResource, services: Seq[KubernetesService], endpoints: Seq[KubernetesEndpoint], otoServices: Seq[ServiceDescriptor], conf: KubernetesConfig): JsValue = {
    val spec = findAndMerge[ServiceDescriptor](_spec, res, "service-descriptor", None, otoServices, _.metadata, _.id, _.toJson, Some(_.enabled))
    val globalName = res.annotations.getOrElse("global-name/otoroshi.io", res.name)
    val coreDnsDomainEnv = conf.coreDnsEnv.map(e => s"$e.").getOrElse("")
    val additionalHosts = Json.arr(
      s"${globalName}.global.${coreDnsDomainEnv}otoroshi.mesh",
      s"${res.name}.${res.namespace}.${coreDnsDomainEnv}otoroshi.mesh",
      s"${res.name}.${res.namespace}.svc.${coreDnsDomainEnv}otoroshi.mesh",
      s"${res.name}.${res.namespace}.svc.${conf.clusterDomain}"
    )

    def handleTargetFrom(obj: JsObject, serviceDesc: JsValue): JsValue = {
      val serviceName = (obj \ "serviceName").asOpt[String]
      val servicePort = (obj \ "servicePort").asOpt[JsValue]
      val finalTargets: JsArray = (serviceName, servicePort) match {
        case (Some(sn), Some(sp)) => {
          val path = if (sn.contains("/")) sn else s"${res.namespace}/$sn"
          val service = services.find(s => s.path == path)
          val endpoint = endpoints.find(s => s.path == path)
          (service, endpoint) match {
            case (Some(s), p) => {
              val port = IntOrString(sp.asOpt[Int], sp.asOpt[String])
              val targets = KubernetesIngressToDescriptor.serviceToTargetsSync(s, p, port, obj, client, logger)
              JsArray(targets.map(_.toJson))
            }
            case _ => Json.arr()
          }
        }
        case _ => Json.arr()
      }
      serviceDesc.as[JsObject] ++ Json.obj("targets" -> finalTargets)
    }

    customizeIdAndName(spec, res).applyOn { s =>
      (s \ "env").asOpt[String] match {
        case Some(_) => s
        case None => s.as[JsObject] ++ Json.obj("env" -> "prod")
      }
    }.applyOn { s =>
      (s \ "domain").asOpt[String] match {
        case Some(_) => s
        case None => s.as[JsObject] ++ Json.obj("domain" -> env.domain)
      }
    }.applyOn { s =>
      (s \ "subdomain").asOpt[String] match {
        case Some(_) => s
        case None => s.as[JsObject] ++ Json.obj("subdomain" -> (s \ "id").as[String])
      }
    }.applyOn { serviceDesc =>
      (serviceDesc \ "targets").asOpt[JsValue] match {
        case Some(JsArray(targets)) => {
          serviceDesc.as[JsObject] ++ Json.obj("targets" -> JsArray(targets.map(item => item.applyOn(target =>
            ((target \ "url").asOpt[String] match {
              case None => target
              case Some(tv) =>
                val uri = Uri(tv)
                target.as[JsObject] ++ Json.obj(
                  "host" -> (uri.authority.host.toString() + ":" + uri.effectivePort),
                  "scheme" -> uri.scheme
                )
            })
          ))))
        }
        case Some(obj @ JsObject(_)) => handleTargetFrom(obj, serviceDesc)
        case _ => serviceDesc.as[JsObject] ++ Json.obj("targets" -> Json.arr())
      }
    }.applyOn(serviceDesc =>
      (serviceDesc \ "targetsFrom").asOpt[JsObject] match {
        case None => serviceDesc
        case Some(obj) => handleTargetFrom(obj, serviceDesc)
      }
    ).applyOn(s =>
      (s \ "group").asOpt[String] match {
        case None => s
        case Some(v) => s.as[JsObject] - "group" ++ Json.obj("groups" -> Json.arr(v))
      }
    ).applyOn(s =>
      (s \ "groups").asOpt[String] match {
        case None => s.as[JsObject] ++ Json.obj("groups" -> Json.arr("default"))
        case Some(_) => s
      }
    ).applyOn { s =>
      val enabledAdditionalHosts = (s \ "enabledAdditionalHosts").asOpt[Boolean].getOrElse(true)
      (s \ "hosts").asOpt[JsArray] match {
        case None if enabledAdditionalHosts => s.as[JsObject] ++ Json.obj("hosts" -> additionalHosts)
        case Some(arr) if enabledAdditionalHosts => s.as[JsObject] ++ Json.obj("hosts" -> (arr ++ additionalHosts))
        case _ => s.as[JsObject]
      }
    }.applyOn(s => s.as[JsObject] ++ Json.obj("useAkkaHttpClient" -> true))
  }

  def customizeApiKey(_spec: JsValue, res: KubernetesOtoroshiResource, secrets: Seq[KubernetesSecret], apikeys: Seq[ApiKey], registerApkToExport: Function3[String, String, ApiKey, Unit]): JsValue = {
    val dkToken = (_spec \ "daikokuToken").asOpt[String]
    val dkApkOpt: Option[ApiKey] = dkToken.flatMap(t => apikeys.find(_.metadata.get("daikoku_integration_token") == t.some))
    val spec = findAndMerge[ApiKey](_spec, res, "apikey", dkApkOpt, apikeys, _.metadata, _.clientId, _.toJson, Some(_.enabled))
    spec.applyOn(s =>
      (s \ "clientName").asOpt[String] match {
        case None => s.as[JsObject] ++ Json.obj("clientName" -> res.name)
        case Some(_) => s
      }
    ).applyOn(s =>
      (s \ "group").asOpt[String] match {
        case None => s
        case Some(v) => s.as[JsObject] - "group" ++ Json.obj("authorizedEntities" -> Json.arr("group_" + v))
      }
    ).applyOn(s =>
      (s \ "authorizedEntities").asOpt[String] match {
        case None => s.as[JsObject] ++ Json.obj("authorizedEntities" -> Json.arr("group_default"))
        case Some(v) => s
      }
    ).applyOn { s =>
      dkApkOpt match {
        case None => s
        case Some(apk) => s.as[JsObject] ++ Json.obj("authorizedEntities" -> JsArray(apk.authorizedEntities.map(_.json)))
      }
    }.applyOn { s =>
      val shouldExport = (s \ "exportSecret").asOpt[Boolean].getOrElse(false)
      (s \ "secretName").asOpt[String] match {
        case None => {
          s.applyOn(js =>
            (s \ "clientId").asOpt[String] match {
              case None => s.as[JsObject] ++ Json.obj("clientId" -> IdGenerator.token(64))
              case Some(v) => s
            }
          ).applyOn(js =>
            (s \ "clientSecret").asOpt[String] match {
              case None => s.as[JsObject] ++ Json.obj("clientSecret" -> IdGenerator.token(128))
              case Some(v) => s
            }
          )
        }
        case Some(v) => {
          val parts = v.split("/").toList.map(_.trim)
          val name = if (parts.size == 2) parts.last else v
          val namespace = if (parts.size == 2) parts.head else res.namespace
          val path = s"$namespace/$name"
          val apiKeyOpt: Option[ApiKey] = apikeys.filter(_.metadata.get("otoroshi-provider").contains("kubernetes-crds")).find(_.metadata.get("kubernetes-path").contains(res.path))
          val secretOpt = secrets.find(_.path == path)
          val possibleClientSecret = if (shouldExport) IdGenerator.token(128) else "secret-not-found"
          val clientId = apiKeyOpt.map(_.clientId).getOrElse(secretOpt.map(s => (s.raw \ "data" \ "clientId").as[String].applyOn(_.fromBase64)).getOrElse(IdGenerator.token(64)))
          val clientSecret = apiKeyOpt.map(_.clientSecret).getOrElse(secretOpt.map(s => (s.raw \ "data" \ "clientSecret").as[String].applyOn(_.fromBase64)).getOrElse(possibleClientSecret))
          if (shouldExport) {
            registerApkToExport(namespace, name, ApiKey(clientId, clientSecret,
              clientName = name,
              authorizedEntities = Seq(ServiceGroupIdentifier("default"))
            ))
          }
          s.as[JsObject] ++ Json.obj(
            "clientId" -> clientId,
            "clientSecret" -> clientSecret
          )
        }
      }
    }.applyOn(s =>
      s.as[JsObject] ++ Json.obj(
        "metadata" -> ((s \ "metadata").asOpt[JsObject].getOrElse(Json.obj()) ++ Json.obj(
          "otoroshi-provider" -> "kubernetes-crds",
          "kubernetes-name" -> res.name,
          "kubernetes-namespace" -> res.namespace,
          "kubernetes-path" -> res.path,
          "kubernetes-uid" -> res.uid
        ))
      )
    )
  }

  private[kubernetes] def foundACertWithSameIdAndCsr(id: String, csrJson: JsValue, caOpt: Option[Cert], certs: Seq[Cert]): Option[Cert] = {
    certs
      //.find(c => c.id == id)
      .filter(_.entityMetadata.get("otoroshi-provider").contains("kubernetes-crds"))
      .filter(_.entityMetadata.get("csr").contains(csrJson.stringify))
      .filter(c => caOpt.map(_.id) == c.caRef)
      .headOption
  }

  private[kubernetes] def customizeCert(_spec: JsValue, res: KubernetesOtoroshiResource, certs: Seq[Cert], registerCertToExport: Function3[String, String, Cert, Unit]): JsValue = {
    val spec = findAndMerge[Cert](_spec, res, "certificate", None, certs, _.entityMetadata, _.id, _.toJson)
    val id = s"kubernetes-crd-import-${res.namespace}-${res.name}".slugifyWithSlash
    spec.applyOn(s =>
      (s \ "name").asOpt[String] match {
        case None => s.as[JsObject] ++ Json.obj("name" -> res.name)
        case Some(_) => s
      }
    ).applyOn(s =>
      (s \ "description").asOpt[String] match {
        case None => s.as[JsObject] ++ Json.obj("description" -> "--")
        case Some(_) => s
      }
    ).applyOn(s =>
      s.as[JsObject] ++ Json.obj(
        "metadata" -> ((s \ "metadata").asOpt[JsObject].getOrElse(Json.obj()) ++ Json.obj(
          "otoroshi-provider" -> "kubernetes-crds",
          "kubernetes-name" -> res.name,
          "kubernetes-namespace" -> res.namespace,
          "kubernetes-path" -> res.path,
          "kubernetes-uid" -> res.uid
        ))
      )
    ).applyOn(s =>
      res.metaId match {
        case None => s.as[JsObject] ++ Json.obj("id" -> id)
        case Some(_id) => s.as[JsObject] ++ Json.obj("id" -> _id)
      }
    ).applyOn { s =>
      val shouldExport = (s \ "exportSecret").asOpt[Boolean].getOrElse(false)
      val secretName = (s \ "secretName").asOpt[String].getOrElse(res.name + "-secret")
      (s \ "csr").asOpt[JsValue] match {
        case None => s
        case Some(csrJson) => {
          val caOpt = (csrJson \ "issuer").asOpt[String] match {
            case None => None
            case Some(dn) =>
              DynamicSSLEngineProvider.certificates.find {
                case (_, cert) => cert.id == dn || cert.certificate.exists(c => DN(c.getSubjectDN.getName).isEqualsTo(DN(dn)))
              }
          }
          val maybeCert = foundACertWithSameIdAndCsr(id, csrJson, caOpt.map(_._2), certs)
          if (maybeCert.isDefined) {
            val c = maybeCert.get
            if (shouldExport) registerCertToExport(res.namespace, secretName, c)
            s.as[JsObject] ++ Json.obj(
              "caRef" -> caOpt.map(_._2.id).map(JsString.apply).getOrElse(JsNull).as[JsValue],
              "domain" -> c.domain,
              "chain" -> c.chain,
              "privateKey" -> c.privateKey,
              "metadata" -> ((s \ "metadata").asOpt[JsObject].getOrElse(Json.obj()) ++ Json.obj(
                "csr" -> csrJson.stringify
              ))
            )
          } else {
            GenCsrQuery.fromJson(csrJson) match {
              case Left(_) => s
              case Right(csr) => {
                (caOpt match {
                  case None => Await.result(env.pki.genSelfSignedCert(csr), 1.second)
                  case Some(ca) => Await.result(env.pki.genCert(csr, ca._2.certificate.get, ca._2.certificates.tail, ca._2.cryptoKeyPair.getPrivate), 1.second)
                }) match {
                  case Left(_) => s
                  case Right(cert) =>
                    val c = cert.toCert
                    if (shouldExport) registerCertToExport(res.namespace, secretName, c)
                    s.as[JsObject] ++ Json.obj(
                      "caRef" -> caOpt.map(_._2.id).map(JsString.apply).getOrElse(JsNull).as[JsValue],
                      "domain" -> c.domain,
                      "chain" -> c.chain,
                      "privateKey" -> c.privateKey,
                      "metadata" -> ((s \ "metadata").asOpt[JsObject].getOrElse(Json.obj()) ++ Json.obj(
                        "csr" -> csrJson.stringify
                      ))
                    )
                }
              }
            }
          }
        }
      }
    }
  }

  private[kubernetes] def customizeServiceGroup(_spec: JsValue, res: KubernetesOtoroshiResource, entities: Seq[ServiceGroup]): JsValue = {
    val spec = findAndMerge[ServiceGroup](_spec, res, "service-group", None, entities, _.metadata, _.id, _.toJson)
    customizeIdAndName(spec, res)
  }

  private[kubernetes] def customizeJwtVerifier(_spec: JsValue, res: KubernetesOtoroshiResource, entities: Seq[GlobalJwtVerifier]): JsValue = {
    val spec = findAndMerge[GlobalJwtVerifier](_spec, res, "jwt-verifier", None, entities, _.metadata, _.id, _.asJson, Some(_.enabled))
    customizeIdAndName(spec, res)
  }

  private[kubernetes] def customizeAuthModule(_spec: JsValue, res: KubernetesOtoroshiResource, entities: Seq[AuthModuleConfig]): JsValue = {
    val spec = findAndMerge[AuthModuleConfig](_spec, res, "auth-module", None, entities, _.metadata, _.id, _.asJson)
    customizeIdAndName(spec, res)
  }

  private[kubernetes] def customizeScripts(_spec: JsValue, res: KubernetesOtoroshiResource, entities: Seq[Script]): JsValue = {
    val spec = findAndMerge[Script](_spec, res, "script", None, entities, _.metadata, _.id, _.toJson)
    customizeIdAndName(spec, res)
  }

  private[kubernetes] def customizeTenant(_spec: JsValue, res: KubernetesOtoroshiResource, entities: Seq[Tenant]): JsValue = {
    val spec = findAndMerge[Tenant](_spec, res, "organization", None, entities, _.metadata, _.id.value, _.json)
    customizeIdAndName(spec, res)
  }

  private[kubernetes] def customizeTeam(_spec: JsValue, res: KubernetesOtoroshiResource, entities: Seq[Team]): JsValue = {
    val spec = findAndMerge[Team](_spec, res, "team", None, entities, _.metadata, _.id.value, _.json)
    customizeIdAndName(spec, res)
  }

  private[kubernetes] def customizeTcpService(_spec: JsValue, res: KubernetesOtoroshiResource, entities: Seq[TcpService]): JsValue = {
    val spec = findAndMerge[TcpService](_spec, res, "tcp-service", None, entities, _.metadata, _.id, _.json, Some(_.enabled))
    customizeIdAndName(spec, res)
  }

  private[kubernetes] def customizeDataExporter(_spec: JsValue, res: KubernetesOtoroshiResource, entities: Seq[DataExporterConfig]): JsValue = {
    val spec = findAndMerge[DataExporterConfig](_spec, res, "data-exporter", None, entities, _.metadata, _.id, _.json, Some(_.enabled))
    customizeIdAndName(spec, res)
  }

  private[kubernetes] def customizeAdmin(_spec: JsValue, res: KubernetesOtoroshiResource, entities: Seq[SimpleOtoroshiAdmin]): JsValue = {
    val spec = findAndMerge[SimpleOtoroshiAdmin](_spec, res, "admin", None, entities, _.metadata, _.username, _.json)
    customizeIdAndName(spec, res)
  }

  private[kubernetes] def customizeGlobalConfig(_spec: JsValue, res: KubernetesOtoroshiResource, entity: GlobalConfig): JsValue = {
    val template = (client.config.templates \ "global-config").asOpt[JsObject].getOrElse(Json.obj())
    template.deepMerge(entity.toJson.as[JsObject].deepMerge(_spec.as[JsObject]))
  }

  def crdsFetchTenants(tenants: Seq[Tenant]): Future[Seq[OtoResHolder[Tenant]]] = client.fetchOtoroshiResources[Tenant]("organizations", Tenant.format, (a, b) => customizeTenant(a, b, tenants))
  def crdsFetchTeams(teams: Seq[Team]): Future[Seq[OtoResHolder[Team]]] = client.fetchOtoroshiResources[Team]("teams", Team.format, (a, b) => customizeTeam(a, b, teams))
  def crdsFetchServiceGroups(groups: Seq[ServiceGroup]): Future[Seq[OtoResHolder[ServiceGroup]]] = client.fetchOtoroshiResources[ServiceGroup]("service-groups", ServiceGroup._fmt, (a, b) => customizeServiceGroup(a, b, groups))
  def crdsFetchServiceDescriptors(services: Seq[KubernetesService], endpoints: Seq[KubernetesEndpoint], otoServices: Seq[ServiceDescriptor], conf: KubernetesConfig): Future[Seq[OtoResHolder[ServiceDescriptor]]] = {
    client.fetchOtoroshiResources[ServiceDescriptor]("service-descriptors", ServiceDescriptor._fmt, (a, b) => customizeServiceDescriptor(a, b, services, endpoints, otoServices, conf))
  }
  def crdsFetchApiKeys(secrets: Seq[KubernetesSecret], apikeys: Seq[ApiKey], registerApkToExport: Function3[String, String, ApiKey, Unit], conf: KubernetesConfig): Future[Seq[OtoResHolder[ApiKey]]] = {
    val otoApikeySecrets = secrets.filter(_.theType == "otoroshi.io/apikey-secret")
    client.fetchOtoroshiResources[ApiKey]("apikeys", ApiKey._fmt, (a, b) => customizeApiKey(a, b, otoApikeySecrets, apikeys, registerApkToExport))
      .map {
        case apikeys if conf.syncDaikokuApikeysOnly => apikeys.filter(_.typed.metadata.contains("daikoku_integration_token"))
        case apikeys => apikeys
      }
  }
  def crdsFetchCertificates(certs: Seq[Cert], registerCertToExport: Function3[String, String, Cert, Unit]): Future[Seq[OtoResHolder[Cert]]] = {
    client.fetchOtoroshiResources[Cert]("certificates", Cert._fmt, (a, b) => customizeCert(a, b, certs, registerCertToExport))
  }
  def crdsFetchGlobalConfig(config: GlobalConfig): Future[Seq[OtoResHolder[GlobalConfig]]] = client.fetchOtoroshiResources[GlobalConfig]("global-configs", GlobalConfig._fmt, (a, b) => customizeGlobalConfig(a, b, config))
  def crdsFetchJwtVerifiers(verifiers: Seq[GlobalJwtVerifier]): Future[Seq[OtoResHolder[GlobalJwtVerifier]]] = client.fetchOtoroshiResources[GlobalJwtVerifier]("jwt-verifiers", GlobalJwtVerifier._fmt, (a, b) => customizeJwtVerifier(a, b, verifiers))
  def crdsFetchAuthModules(modules: Seq[AuthModuleConfig]): Future[Seq[OtoResHolder[AuthModuleConfig]]] = client.fetchOtoroshiResources[AuthModuleConfig]("auth-modules", AuthModuleConfig._fmt, (a, b) => customizeAuthModule(a, b, modules))
  def crdsFetchScripts(scripts: Seq[Script]): Future[Seq[OtoResHolder[Script]]] = client.fetchOtoroshiResources[Script]("scripts", Script._fmt, (a, b) => customizeScripts(a, b, scripts))
  def crdsFetchTcpServices(services: Seq[TcpService]): Future[Seq[OtoResHolder[TcpService]]] = client.fetchOtoroshiResources[TcpService]("tcp-services", TcpService.fmt, (a, b) => customizeTcpService(a, b, services))
  def crdsFetchDataExporters(exporters: Seq[DataExporterConfig]): Future[Seq[OtoResHolder[DataExporterConfig]]] = client.fetchOtoroshiResources[DataExporterConfig]("data-exporters", DataExporterConfig.format, (a, b) => customizeDataExporter(a, b, exporters))
  def crdsFetchSimpleAdmins(admins: Seq[SimpleOtoroshiAdmin]): Future[Seq[OtoResHolder[SimpleOtoroshiAdmin]]] = client.fetchOtoroshiResources[SimpleOtoroshiAdmin]("admins", v => SimpleOtoroshiAdmin.reads(v), (a, b) => customizeAdmin(a, b, admins))
}

case class ExportersCtx(
  dataExporters: Seq[OtoResHolder[DataExporterConfig]],
  otodataexporters: Seq[DataExporterConfig],
)

case class TenantAndTeamsCtx(
  tenants: Seq[OtoResHolder[Tenant]],
  teams: Seq[OtoResHolder[Team]],
  ototenants: Seq[Tenant],
  ototeams: Seq[Team],
)

case class CRDContext(
  serviceGroups: Seq[OtoResHolder[ServiceGroup]],
  serviceDescriptors: Seq[OtoResHolder[ServiceDescriptor]],
  apiKeys: Seq[OtoResHolder[ApiKey]],
  certificates: Seq[OtoResHolder[Cert]],
  globalConfigs: Seq[OtoResHolder[GlobalConfig]],
  jwtVerifiers: Seq[OtoResHolder[GlobalJwtVerifier]],
  authModules: Seq[OtoResHolder[AuthModuleConfig]],
  scripts: Seq[OtoResHolder[Script]],
  tcpServices: Seq[OtoResHolder[TcpService]],
  simpleAdmins: Seq[OtoResHolder[SimpleOtoroshiAdmin]],
  otoserviceGroups: Seq[ServiceGroup],
  otoserviceDescriptors: Seq[ServiceDescriptor],
  otoapiKeys: Seq[ApiKey],
  otocertificates: Seq[Cert],
  otoglobalConfigs: Seq[GlobalConfig],
  otojwtVerifiers: Seq[GlobalJwtVerifier],
  otoauthModules: Seq[AuthModuleConfig],
  otoscripts: Seq[Script],
  ototcpServices: Seq[TcpService],
  otosimpleAdmins: Seq[SimpleOtoroshiAdmin],
  expctx: ExportersCtx,
  ttctx: TenantAndTeamsCtx
)

object KubernetesCRDsJob {

  private val logger = Logger("otoroshi-plugins-kubernetes-crds-sync")
  private val running = new AtomicBoolean(false)
  private val shouldRunNext = new AtomicBoolean(false)
  private val lastDnsConfigRef = new AtomicReference[String]("--")

  def compareAndSave[T](entities: Seq[OtoResHolder[T]])(all: => Seq[T], id: T => String, save: T => Future[Boolean]): Seq[(T, () => Future[Boolean])] = {
    val existing = all.map(v => (id(v), v)).toMap
    val kube = entities.map(_.typed).map(v => (id(v), v))
    kube.filter {
      case (key, value) => existing.get(key) match {
        case None                                          => true
        case Some(existingValue) if value == existingValue => false
        case Some(existingValue) if value != existingValue => true
      }
    } map {
      case (_, value) => (value, () => save(value))
    }
  }

  def getNamespaces(client: KubernetesClient, conf: KubernetesConfig)(implicit env: Env, ec: ExecutionContext): Future[Seq[String]] = {
    if (conf.namespacesLabels.isEmpty) {
      conf.namespaces.future
    } else {
      client.fetchNamespacesAndFilterLabels().map { namespaces =>
        namespaces.map(_.name)
      }
    }
  }

  def context(conf: KubernetesConfig, attrs: TypedMap, clientSupport: ClientSupport, registerApkToExport: Function3[String, String, ApiKey, Unit], registerCertToExport: Function3[String, String, Cert, Unit])(implicit env: Env, ec: ExecutionContext): Future[CRDContext] = {
    for {

      otoserviceGroups <- env.datastores.serviceGroupDataStore.findAll()
      otoserviceDescriptors <- env.datastores.serviceDescriptorDataStore.findAll()
      otoapiKeys <- env.datastores.apiKeyDataStore.findAll()
      otocertificates <- env.datastores.certificatesDataStore.findAll()
      otoglobalConfigs <- env.datastores.globalConfigDataStore.findAll()
      otojwtVerifiers <- env.datastores.globalJwtVerifierDataStore.findAll()
      otoauthModules <- env.datastores.authConfigsDataStore.findAll()
      otoscripts <- env.datastores.scriptDataStore.findAll()
      ototcpServices <- env.datastores.tcpServiceDataStore.findAll()
      otosimpleAdmins <- env.datastores.simpleAdminDataStore.findAll()
      otodataexporters <- env.datastores.dataExporterConfigDataStore.findAll()
      ototeams <- env.datastores.teamDataStore.findAll()
      ototenants <- env.datastores.tenantDataStore.findAll()

      services <- clientSupport.client.fetchServices()
      endpoints <- clientSupport.client.fetchEndpoints()
      secrets <- clientSupport.client.fetchSecrets()
      serviceGroups <- clientSupport.crdsFetchServiceGroups(otoserviceGroups)
      serviceDescriptors <- clientSupport.crdsFetchServiceDescriptors(services, endpoints, otoserviceDescriptors, conf)
      apiKeys <- clientSupport.crdsFetchApiKeys(secrets, otoapiKeys, registerApkToExport, conf)
      certificates <- clientSupport.crdsFetchCertificates(otocertificates, registerCertToExport)
      globalConfigs <- clientSupport.crdsFetchGlobalConfig(otoglobalConfigs.head)
      jwtVerifiers <- clientSupport.crdsFetchJwtVerifiers(otojwtVerifiers)
      authModules <- clientSupport.crdsFetchAuthModules(otoauthModules)
      scripts <- clientSupport.crdsFetchScripts(otoscripts)
      tcpServices <- clientSupport.crdsFetchTcpServices(ototcpServices)
      simpleAdmins <- clientSupport.crdsFetchSimpleAdmins(otosimpleAdmins)
      dataExporters <- clientSupport.crdsFetchDataExporters(otodataexporters)
      teams <- clientSupport.crdsFetchTeams(ototeams)
      tenants <- clientSupport.crdsFetchTenants(ototenants)

    } yield {
      CRDContext(
        serviceGroups = serviceGroups,
        serviceDescriptors = serviceDescriptors,
        apiKeys = apiKeys,
        certificates = certificates,
        globalConfigs = globalConfigs,
        jwtVerifiers = jwtVerifiers,
        authModules = authModules,
        scripts = scripts,
        tcpServices = tcpServices,
        simpleAdmins = simpleAdmins,
        otoserviceGroups = otoserviceGroups,
        otoserviceDescriptors = otoserviceDescriptors,
        otoapiKeys = otoapiKeys,
        otocertificates = otocertificates,
        otoglobalConfigs = otoglobalConfigs,
        otojwtVerifiers = otojwtVerifiers,
        otoauthModules = otoauthModules,
        otoscripts = otoscripts,
        ototcpServices = ototcpServices,
        otosimpleAdmins = otosimpleAdmins,
        expctx = ExportersCtx(
          dataExporters = dataExporters,
          otodataexporters = otodataexporters,
        ),
        ttctx = TenantAndTeamsCtx(
          teams = teams,
          tenants = tenants,
          ototeams = ototeams,
          ototenants = ototenants,
        )
      )
    }
  }

  def importCRDEntities(conf: KubernetesConfig, attrs: TypedMap, clientSupport: ClientSupport, ctx: CRDContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    implicit val mat = env.otoroshiMaterializer
    val CRDContext(
      serviceGroups,
      serviceDescriptors,
      apiKeys,
      certificates,
      globalConfigs,
      jwtVerifiers,
      authModules,
      scripts,
      tcpServices,
      simpleAdmins,
      otoserviceGroups,
      otoserviceDescriptors,
      otoapiKeys,
      otocertificates,
      otoglobalConfigs,
      otojwtVerifiers,
      otoauthModules,
      otoscripts,
      ototcpServices,
      otosimpleAdmins,
      expctx,
      ttctx
    ) = ctx
    val ExportersCtx(
      dataExporters,
      otodataexporters,
    ) = expctx
    val TenantAndTeamsCtx(
      tenants,
      teams,
      ototenants,
      ototeams,
    ) = ttctx
    if (globalConfigs.size > 1) {
      Future.failed(new RuntimeException("There can only be one GlobalConfig entity !"))
    } else {
      val entities = (
        compareAndSave(globalConfigs)(otoglobalConfigs, _ => "global", _.save()) ++
          compareAndSave(simpleAdmins)(otosimpleAdmins, v => v.username, v => env.datastores.simpleAdminDataStore.registerUser(v)) ++
          compareAndSave(dataExporters)(otodataexporters, _.id, _.save()) ++
          compareAndSave(tenants)(ototenants, _.id.value, _.save()) ++
          compareAndSave(teams)(ototeams, _.id.value, _.save()) ++
          compareAndSave(serviceGroups)(otoserviceGroups, _.id, _.save()) ++
          compareAndSave(certificates)(otocertificates, _.id, _.save()) ++
          compareAndSave(jwtVerifiers)(otojwtVerifiers, _.asGlobal.id, _.asGlobal.save()) ++
          compareAndSave(authModules)(otoauthModules, _.id, _.save()) ++
          compareAndSave(scripts)(otoscripts, _.id, _.save()) ++
          compareAndSave(tcpServices)(ototcpServices, _.id, _.save()) ++
          compareAndSave(serviceDescriptors)(otoserviceDescriptors, _.id, _.save()) ++
          compareAndSave(apiKeys)(otoapiKeys, _.clientId, _.save())
        ).toList
      logger.info(s"Will now sync ${entities.size} entities !")
      Source(entities).mapAsync(1) { entity =>
        entity._2().recover { case _ => false }.andThen {
          case Failure(e) => logger.error(s"failed to save resource ${entity._1}", e)
          case Success(_) =>
        }
      }.runWith(Sink.ignore).map(_ => ())
    }
  }

  def deleteOutDatedEntities(conf: KubernetesConfig, attrs: TypedMap, ctx: CRDContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val CRDContext(
      serviceGroups,
      serviceDescriptors,
      apiKeys,
      certificates,
      globalConfigs,
      jwtVerifiers,
      authModules,
      scripts,
      tcpServices,
      simpleAdmins,
      otoserviceGroups,
      otoserviceDescriptors,
      otoapiKeys,
      otocertificates,
      otoglobalConfigs,
      otojwtVerifiers,
      otoauthModules,
      otoscripts,
      ototcpServices,
      otosimpleAdmins,
      expctx,
      ttctx
    ) = ctx
    val ExportersCtx(
      dataExporters,
      otodataexporters,
    ) = expctx
    val TenantAndTeamsCtx(
      tenants,
      teams,
      ototenants,
      ototeams,
    ) = ttctx
    for {
      _ <- ototenants
        .filter(sg => sg.metadata.get("otoroshi-provider").contains("kubernetes-crds"))
        .filterNot(sg => tenants.exists(ssg => sg.metadata.get("kubernetes-path").contains(ssg.path)))
        .map(_.id.value)
        .debug(seq => logger.info(s"Will delete ${seq.size} out of date tenants entities"))
        .applyOn(env.datastores.tenantDataStore.deleteByIds)

      _ <- ototeams
        .filter(sg => sg.metadata.get("otoroshi-provider").contains("kubernetes-crds"))
        .filterNot(sg => teams.exists(ssg => sg.metadata.get("kubernetes-path").contains(ssg.path)))
        .map(_.id.value)
        .debug(seq => logger.info(s"Will delete ${seq.size} out of date teams entities"))
        .applyOn(env.datastores.teamDataStore.deleteByIds)

      _ <- otoserviceGroups
      .filter(sg => sg.metadata.get("otoroshi-provider").contains("kubernetes-crds"))
      .filterNot(sg => serviceGroups.exists(ssg => sg.metadata.get("kubernetes-path").contains(ssg.path)))
      .map(_.id)
      .debug(seq => logger.info(s"Will delete ${seq.size} out of date service-group entities"))
      .applyOn(env.datastores.serviceGroupDataStore.deleteByIds)

      _ <- otoserviceDescriptors
      .filter(sg => sg.metadata.get("otoroshi-provider").contains("kubernetes-crds"))
      .filterNot(sg => serviceDescriptors.exists(ssg => sg.metadata.get("kubernetes-path").contains(ssg.path)))
      .map(_.id)
      .debug(seq => logger.info(s"Will delete ${seq.size} out of date service-descriptor entities"))
      .applyOn(env.datastores.serviceDescriptorDataStore.deleteByIds)

      _ <- otoapiKeys
      .filter(sg => sg.metadata.get("otoroshi-provider").contains("kubernetes-crds"))
      .filterNot(sg => apiKeys.exists(ssg => sg.metadata.get("kubernetes-path").contains(ssg.path)))
      .map(_.clientId)
      .debug(seq => logger.info(s"Will delete ${seq.size} out of date apikey entities"))
      .applyOn(env.datastores.apiKeyDataStore.deleteByIds)

      _ <- otocertificates
      .filter(sg => sg.entityMetadata.get("otoroshi-provider").contains("kubernetes-crds"))
      .filterNot(sg => certificates.exists(ssg => sg.entityMetadata.get("kubernetes-path").contains(ssg.path)))
      .map(_.id)
      .debug(seq => logger.info(s"Will delete ${seq.size} out of date certificate entities"))
      .applyOn(env.datastores.certificatesDataStore.deleteByIds)

     _ <- otojwtVerifiers
      .map(_.asGlobal)
      .filter(sg => sg.metadata.get("otoroshi-provider").contains("kubernetes-crds"))
      .filterNot(sg => jwtVerifiers.exists(ssg => sg.metadata.get("kubernetes-path").contains(ssg.path)))
      .map(_.id)
      .debug(seq => logger.info(s"Will delete ${seq.size} out of date jwt-verifier entities"))
      .applyOn(env.datastores.globalJwtVerifierDataStore.deleteByIds)

     _ <- otoauthModules
      .filter(sg => sg.metadata.get("otoroshi-provider").contains("kubernetes-crds"))
      .filterNot(sg => authModules.exists(ssg => sg.metadata.get("kubernetes-path").contains(ssg.path)))
      .map(_.id)
      .debug(seq => logger.info(s"Will delete ${seq.size} out of date auth-module entities"))
      .applyOn(env.datastores.authConfigsDataStore.deleteByIds)

     _ <- otoscripts
      .filter(sg => sg.metadata.get("otoroshi-provider").contains("kubernetes-crds"))
      .filterNot(sg => scripts.exists(ssg => sg.metadata.get("kubernetes-path").contains(ssg.path)))
      .map(_.id)
      .debug(seq => logger.info(s"Will delete ${seq.size} out of date script entities"))
      .applyOn(env.datastores.scriptDataStore.deleteByIds)

     _ <- ototcpServices
      .filter(sg => sg.metadata.get("otoroshi-provider").contains("kubernetes-crds"))
      .filterNot(sg => tcpServices.exists(ssg => sg.metadata.get("kubernetes-path").contains(ssg.path)))
      .map(_.id)
      .debug(seq => logger.info(s"Will delete ${seq.size} out of date tcp-service entities"))
      .applyOn(env.datastores.tcpServiceDataStore.deleteByIds)

    _ <- otosimpleAdmins
      .filter(sg => sg.metadata.get("otoroshi-provider").contains("kubernetes-crds"))
      .filterNot(sg => simpleAdmins.exists(ssg => sg.metadata.get("kubernetes-path").contains(ssg.path)))
      .map(_.username)
      .debug(seq => logger.info(s"Will delete ${seq.size} out of date admin entities"))
      .applyOn(env.datastores.simpleAdminDataStore.deleteUsers)

    _ <- otodataexporters
      .filter(sg => sg.metadata.get("otoroshi-provider").contains("kubernetes-crds"))
      .filterNot(sg => dataExporters.exists(ssg => sg.metadata.get("kubernetes-path").contains(ssg.path)))
      .map(_.id)
      .debug(seq => logger.info(s"Will delete ${seq.size} out of date data-exporters entities"))
      .applyOn(env.datastores.dataExporterConfigDataStore.deleteByIds)
    } yield ()
  }

  def exportApiKeys(conf: KubernetesConfig, attrs: TypedMap, clientSupport: ClientSupport, ctx: CRDContext, apikeys: Seq[(String, String, ApiKey)], updatedSecrets: AtomicReference[Seq[(String, String)]])(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    logger.info(s"will export ${apikeys.size} apikeys as secrets")
    implicit val mat = env.otoroshiMaterializer
    Source(apikeys.toList)
      .mapAsync(1) {
        case (namespace, name, apikey) => clientSupport.client.fetchSecret(namespace, name).flatMap {
          case None =>
            // println(s"create $namespace/$name with ${apikey.clientId} and ${apikey.clientSecret}")
            updatedSecrets.updateAndGet(seq => seq :+ (namespace, name))
            clientSupport.client.createSecret(namespace, name, "otoroshi.io/apikey-secret", Json.obj(
              "clientId" -> apikey.clientId.base64,
              "clientSecret" -> apikey.clientSecret.base64,
              "userPwd" -> s"${apikey.clientId}:${apikey.clientSecret}".base64,
              "basicAuth" -> s"${apikey.clientId}:${apikey.clientSecret}".base64.base64,
              "basicAuthHeader" -> ("Basic " + s"${apikey.clientId}:${apikey.clientSecret}".base64).base64,
            ), "crd/apikey", apikey.clientId)
          case Some(secret) =>
            val clientId = (secret.raw \ "data" \ "clientId").as[String].applyOn(s => s.fromBase64)
            val clientSecret = (secret.raw \ "data" \ "clientSecret").as[String].applyOn(s => s.fromBase64)
            if ((clientId != apikey.clientId) || (clientSecret != apikey.clientSecret)) {
              // println(s"updating $namespace/$name  with ${apikey.clientId} and ${apikey.clientSecret}")
              updatedSecrets.updateAndGet(seq => seq :+ (namespace, name))
              clientSupport.client.updateSecret(namespace, name, "otoroshi.io/apikey-secret", Json.obj(
                "clientId" -> apikey.clientId.base64,
                "clientSecret" -> apikey.clientSecret.base64,
                "userPwd" -> s"${apikey.clientId}:${apikey.clientSecret}".base64,
                "basicAuth" -> s"${apikey.clientId}:${apikey.clientSecret}".base64.base64,
                "basicAuthHeader" -> ("Basic " + s"${apikey.clientId}:${apikey.clientSecret}".base64).base64,
              ), "crd/apikey", apikey.clientId)
            } else {
              ().future
            }
          }
        }.runWith(Sink.ignore).map(_ => ())
  }

  def exportCerts(conf: KubernetesConfig, attrs: TypedMap, clientSupport: ClientSupport, ctx: CRDContext, certs: Seq[(String, String, Cert)], updatedSecrets: AtomicReference[Seq[(String, String)]])(implicit env: Env, ec: ExecutionContext): Future[Unit] = {

    import otoroshi.ssl.SSLImplicits._

    logger.info(s"will export ${certs.size} certificates as secrets")
    implicit val mat = env.otoroshiMaterializer
    Source(certs.toList)
      .mapAsync(1) {
        case (namespace, name, cert) => clientSupport.client.fetchSecret(namespace, name).flatMap {
          case None =>
            updatedSecrets.updateAndGet(seq => seq :+ (namespace, name))
            clientSupport.client.createSecret(namespace, name, "kubernetes.io/tls", Json.obj(
              "tls.crt" -> cert.chain.base64,
              "tls.key" -> cert.privateKey.base64,
              "cert.crt" -> cert.certificates.head.asPem.base64,
              "ca-chain.crt" -> cert.certificates.tail.map(_.asPem).mkString("\n\n").base64,
              "ca.crt" -> cert.certificates.last.asPem.base64,
            ), "crd/cert", cert.id)
          case Some(secret) =>
            val chain = (secret.raw \ "data" \ "tls.crt").as[String].applyOn(s => s.fromBase64)
            val privateKey = (secret.raw \ "data" \ "tls.key").as[String].applyOn(s => s.fromBase64)
            //if ((chain != cert.chain) || (privateKey != cert.privateKey)) {
            updatedSecrets.updateAndGet(seq => seq :+ (namespace, name))
            if (!(chain == cert.chain && privateKey == cert.privateKey)) {
              logger.info(s"updating secret: $namespace/$name")
              clientSupport.client.updateSecret(namespace, name, "kubernetes.io/tls", Json.obj(
                "tls.crt" -> cert.chain.base64,
                "tls.key" -> cert.privateKey.base64,
                "cert.crt" -> cert.certificates.head.asPem.base64,
                "ca-chain.crt" -> cert.certificates.tail.map(_.asPem).mkString("\n\n").base64,
                "ca.crt" -> cert.certificates.last.asPem.base64,
              ), "crd/cert", cert.id)
            } else {
              ().future
            }
        }
      }.runWith(Sink.ignore).map(_ => ())
  }

  def restartDependantDeployments(conf: KubernetesConfig, attrs: TypedMap, clientSupport: ClientSupport, ctx: CRDContext, _updatedSecrets: Seq[(String, String)])(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    if (conf.restartDependantDeployments) {
      implicit val mat = env.otoroshiMaterializer
      clientSupport.client.fetchDeployments().flatMap { deployments =>
        Source(deployments.toList)
          .mapAsync(1) { deployment =>
            val deploymentNamespace = deployment.namespace
            val templateNamespace = (deployment.raw \ "spec" \ "template" \ "metadata" \ "namespace").asOpt[String].getOrElse(deploymentNamespace)
            val volumeSecrets = (deployment.raw \ "spec" \ "template" \ "spec" \ "volumes").asOpt[JsArray].map(_.value).getOrElse(Seq.empty[JsValue])
              .filter(item => (item \ "secret").isDefined)
              .map(item => (item \ "secret" \ "secretName").as[String])

            val envSecrets: Seq[String] = (deployment.raw \ "spec" \ "template" \ "spec" \ "containers").asOpt[JsArray].map(_.value).getOrElse(Seq.empty[JsValue])
              .filter { item =>
                val envs = (item \ "env").asOpt[JsArray].map(_.value).getOrElse(Seq.empty)
                envs.exists(v => (v \ "valueFrom" \ "secretKeyRef").isDefined)
              }
              .flatMap { item =>
                val envs = (item \ "env").asOpt[JsArray].map(_.value).getOrElse(Seq.empty)
                envs.map(v => (v \ "valueFrom" \ "secretKeyRef" \ "name").as[String])
              }.distinct

            val updatedSecrets = _updatedSecrets.map { case (ns, n) => s"$ns/$n" }.toSet

            (volumeSecrets ++ envSecrets).find(sn => updatedSecrets.contains(s"$templateNamespace/$sn")) match {
              case None => ().future
              case Some(_) => {
                logger.info(s"Restarting deployment ${deployment.namespace}/${deployment.name}")
                clientSupport.client.patchDeployment(deployment.namespace, deployment.name, Json.obj(
                  "apiVersion" -> "apps/v1",
                  "kind" -> "Deployment",
                  "spec" -> Json.obj(
                    "template" -> Json.obj(
                      "meta" -> Json.obj(
                        "annotations" -> Json.obj(
                          "crds.otoroshi.io/restartedAt" -> DateTime.now().toString()
                        )
                      )
                    )
                  )
                ))
              }
            }
          }.runWith(Sink.ignore).map(_ => ())
      }
    } else {
      ().future
    }
  }

  def deleteOutDatedSecrets(conf: KubernetesConfig, attrs: TypedMap, clientSupport: ClientSupport, ctx: CRDContext, updatedSecretsRef: AtomicReference[Seq[(String, String)]])(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    implicit val mat = env.otoroshiMaterializer
    val lastSecrets = updatedSecretsRef.get().map(t => t._1 + "/" + t._2)
    clientSupport.client.fetchSecrets().flatMap { allSecretsRaw =>
      val allSecrets = allSecretsRaw.filter(_.metaId.isDefined).map(_.path)
      val outOfSync = allSecrets.filterNot(v => lastSecrets.contains(v))
      Source(outOfSync.toList)
        .mapAsync(1) { path =>
          val parts = path.split("/")
          val namespace = parts.head
          val name = parts.last
          // logger.info(s"delete secret: $namespace/$name")
          clientSupport.client.deleteSecret(namespace, name)
        }.runWith(Sink.ignore).map(_ => ())
    }
  }

  def syncCRDs(_conf: KubernetesConfig, attrs: TypedMap, jobRunning: => Boolean)(implicit env: Env, ec: ExecutionContext): Future[Unit] = env.metrics.withTimerAsync("otoroshi.plugins.kubernetes.crds.sync") {
    val _client = new KubernetesClient(_conf, env)
    if (!jobRunning) {
      shouldRunNext.set(false)
      running.set(false)
    }
    if (jobRunning && _conf.crds && running.compareAndSet(false, true)) {
      shouldRunNext.set(false)
      logger.info(s"Sync. otoroshi CRDs at ${DateTime.now()}")
      getNamespaces(_client, _conf).flatMap { namespaces =>
        logger.info(s"otoroshi will sync CRDs for the following namespaces: [ ${namespaces.mkString(", ")} ]")
        val conf = _conf.copy(namespaces = namespaces)
        val client = new KubernetesClient(conf, env)
        KubernetesCertSyncJob.syncKubernetesSecretsToOtoroshiCerts(client, jobRunning).flatMap { _ =>
          val clientSupport = new ClientSupport(client, logger)
          val apiKeysToExport = new AtomicReference[Seq[(String, String, ApiKey)]](Seq.empty)
          val certsToExport = new AtomicReference[Seq[(String, String, Cert)]](Seq.empty)
          val updatedSecrets = new AtomicReference[Seq[(String, String)]](Seq.empty)
          for {
            _ <- ().future
            _ = logger.info("starting sync !")
            ctx <- context(conf, attrs, clientSupport, (ns, n, apk) => apiKeysToExport.getAndUpdate(s => s :+ (ns, n, apk)), (ns, n, cert) => certsToExport.getAndUpdate(c => c :+ (ns, n, cert)))
            _ = logger.info("importing CRDs entities")
            _ <- importCRDEntities(conf, attrs, clientSupport, ctx)
            _ = logger.info("deleting outdated entities")
            _ <- deleteOutDatedEntities(conf, attrs, ctx)
            _ = logger.info("exporting apikeys as secrets")
            _ <- exportApiKeys(conf, attrs, clientSupport, ctx, apiKeysToExport.get(), updatedSecrets)
            _ = logger.info("exporting certs as secrets")
            _ <- exportCerts(conf, attrs, clientSupport, ctx, certsToExport.get(), updatedSecrets)
            _ = logger.info("deleting unused secrets")
            _ <- deleteOutDatedSecrets(conf, attrs, clientSupport, ctx, updatedSecrets)
            _ = logger.info("restarting dependant deployments")
            _ <- restartDependantDeployments(conf, attrs, clientSupport, ctx, updatedSecrets.get())
            _ = logger.info("sync done !")
          } yield ()
        }
      }.flatMap { _ =>
        if (shouldRunNext.get()) {
          shouldRunNext.set(false)
          logger.info("restart job right now because sync was asked during sync ")
          env.otoroshiScheduler.scheduleOnce(_conf.watchGracePeriodSeconds.seconds) {
            syncCRDs(_conf, attrs, jobRunning)
          }
          ().future
        } else {
          ().future
        }
      }.andThen {
        case Failure(e) =>
          logger.error(s"Job failed with ${e.getMessage}", e)
          running.set(false)
        case Success(_) =>
          running.set(false)
      }
    } else {
      logger.info("Job already running, scheduling after ")
      shouldRunNext.set(true)
      ().future
    }
  }

  def patchCoreDnsConfig(conf: KubernetesConfig, ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    logger.debug("patchCoreDnsConfig")
    val client = new KubernetesClient(conf, env)
    val hash = Seq(
      conf.kubeSystemNamespace,
      conf.coreDnsConfigMapName,
      conf.coreDnsDeploymentName,
      conf.coreDnsIntegrationDryRun,
      conf.corednsPort,
      conf.clusterDomain,
      conf.otoroshiServiceName,
      conf.otoroshiNamespace,
      conf.coreDnsEnv
    ).mkString("-").sha256

    def patchConfig(coredns: Option[KubernetesDeployment], configMap: KubernetesConfigMap, append: Boolean): Future[Unit] = Try {
      logger.debug("patching coredns config. with otoroshi mesh")
      val coredns17: Boolean = {
        coredns.flatMap { cdns =>
          val container = (cdns.raw \ "spec" \ "containers").as[JsArray].value.find(_.select("name").asOpt[String].contains("coredns"))
          container.flatMap(_.select("image").asOpt[String].map(_.split(":").last.replace(".", "")).map {
            case version if version.length == 2 => Try(version.toInt > 16).getOrElse(false)
            case version if version.length == 3 => Try(version.toInt > 169).getOrElse(false)
            case version if version.length == 4 => Try(version.toInt > 1699).getOrElse(false)
            case version if version.length == 5 => Try(version.toInt > 16999).getOrElse(false)
            case _ => false
          })
        }.getOrElse(false)
      }
      val upstream = if (coredns17) "" else "upstream"
      val coreDnsNameEnv = conf.coreDnsEnv.map(e => s"$e-").getOrElse("")
      val coreDnsDomainEnv = conf.coreDnsEnv.map(e => s"$e.").getOrElse("")
      val coreDnsDomainRegexEnv = conf.coreDnsEnv.map(e => s"$e\\.").getOrElse("")
      val otoMesh =
        s"""### otoroshi-${coreDnsNameEnv}mesh-begin ###
           |### config-hash: $hash
           |${coreDnsDomainEnv}otoroshi.mesh:${conf.corednsPort} {
           |    errors
           |    health
           |    ready
           |    kubernetes ${conf.clusterDomain} in-addr.arpa ip6.arpa {
           |        pods insecure
           |        $upstream
           |        fallthrough in-addr.arpa ip6.arpa
           |    }
           |    rewrite name regex (.*)\\.${coreDnsDomainRegexEnv}otoroshi\\.mesh ${conf.otoroshiServiceName}.${conf.otoroshiNamespace}.svc.${conf.clusterDomain}
           |    forward . /etc/resolv.conf
           |    cache 30
           |    loop
           |    reload
           |    loadbalance
           |}
           |### otoroshi-${coreDnsNameEnv}mesh-end ###""".stripMargin

      val coreFile = configMap.corefile
      lastDnsConfigRef.set(hash)
      if (append) {
        val newData = (configMap.raw \ "data").as[JsObject] ++ Json.obj("Corefile" -> (otoMesh + coreFile))
        val newRaw = configMap.raw.as[JsObject] ++ Json.obj("data" -> newData)
        if (conf.coreDnsIntegrationDryRun) {
          logger.debug(s"new coredns config append: ${Json.prettyPrint(newRaw)}")
          ().future
        } else {
          client.updateConfigMap(configMap.namespace, configMap.name, KubernetesConfigMap(newRaw)).andThen {
            case Failure(e) => logger.error("error while patching coredns config. (append)", e)
            case Success(Left((status, body))) => logger.error(s"error while patching coredns config. got status $status and body '$body' (append)")
          }.map(_ => ())
        }
      } else {
        val head = coreFile.split(s"### otoroshi-${coreDnsNameEnv}mesh-begin ###").toSeq.head
        val tail = coreFile.split(s"### otoroshi-${coreDnsNameEnv}mesh-end ###").toSeq.last
        val newData = (configMap.raw \ "data").as[JsObject] ++ Json.obj("Corefile" -> (head + otoMesh + tail))
        val newRaw = configMap.raw.as[JsObject] ++ Json.obj("data" -> newData)
        if (conf.coreDnsIntegrationDryRun) {
          logger.debug(s"new coredns config: ${Json.prettyPrint(newRaw)}")
          ().future
        } else {
          client.updateConfigMap(configMap.namespace, configMap.name, KubernetesConfigMap(newRaw)).andThen {
            case Failure(e) => logger.error("error while patching coredns config.", e)
            case Success(Left((status, body))) => logger.error(s"error while patching coredns config. got status $status and body '$body'")
          }.map(_ => ())
        }
      }
    } match {
      case Success(future) => future
      case Failure(error) =>
        logger.error("error while patching coredns config", error)
        Future.failed(error)
    }

    def fetchCorednsConfig(coredns: Option[KubernetesDeployment]): Future[Unit] = {
      client.fetchConfigMap(conf.kubeSystemNamespace, conf.coreDnsConfigMapName).flatMap {
        case None =>
          logger.error("no coredns config.")
          ().future
        case Some(configMap) if configMap.hasOtoroshiMesh(conf) => {
          logger.debug(s"configMap 1 ${configMap.corefile}")
          val hashFromConfigMap = configMap.corefile
            .split("\\n")
            .find(_.trim.startsWith("### config-hash: "))
            .map(_.replace("### config-hash: ", "").replace("\n", ""))
            .getOrElse("--")
          val configHasChanged = hashFromConfigMap != hash
          logger.debug(s"current hash: $hash, hash from coredns configmap: $hashFromConfigMap, config has changed: $configHasChanged")
          if (configHasChanged) {
            patchConfig(coredns, configMap, false)
            ().future
          } else {
            logger.info("coredns has latest otoroshi config. ")
            ().future
          }
        }
        case Some(configMap) => {
          logger.debug(s"configMap 2 ${configMap.corefile}")
          patchConfig(coredns, configMap, true)
          ().future
        }
      }
    }

    def deleteOtoroshiMeshFromCoreDnsConfig() = {
      client.fetchConfigMap(conf.kubeSystemNamespace, conf.coreDnsConfigMapName).flatMap {
        case Some(configMap) if configMap.hasOtoroshiMesh(conf) => {
          val coreFile = configMap.corefile
          val coreDnsNameEnv = conf.coreDnsEnv.map(e => s"$e-").getOrElse("")
          val head = coreFile.split(s"### otoroshi-${coreDnsNameEnv}mesh-begin ###").toSeq.head
          val tail = coreFile.split(s"### otoroshi-${coreDnsNameEnv}mesh-end ###").toSeq.last
          val newData = (configMap.raw \ "data").as[JsObject] ++ Json.obj("Corefile" -> (head + tail))
          val newRaw = configMap.raw.as[JsObject] ++ Json.obj("data" -> newData)
          if (conf.coreDnsIntegrationDryRun) {
            logger.debug(s"new coredns config: ${Json.prettyPrint(newRaw)}")
            ().future
          } else {
            client.updateConfigMap(configMap.namespace, configMap.name, KubernetesConfigMap(newRaw)).andThen {
              case Failure(e) => logger.error("error while patching coredns config.", e)
              case Success(Left((status, body))) => logger.error(s"error while patching coredns config. got status $status and body '$body'")
            }.map(_ => ())
          }
        }
        case _ => ().future
      }
    }

    if (conf.coreDnsIntegration) {
      client.fetchDeployment(conf.kubeSystemNamespace, conf.coreDnsDeploymentName).flatMap {
        case None =>
          logger.info("no coredns deployment.")
          fetchCorednsConfig(None)
        case Some(coredns) => {
          fetchCorednsConfig(coredns.some)
        }
      }
    } else {
      deleteOtoroshiMeshFromCoreDnsConfig()
      ().future
    }
  }

  def patchKubeDnsConfig(conf: KubernetesConfig, ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    if (conf.kubeDnsOperatorIntegration) {
      val client = new KubernetesClient(conf, env)
      val otoDomain = s"${conf.coreDnsEnv.map(e => s"$e.").getOrElse("")}otoroshi.mesh"
      for {
        kubeDnsDep  <- client.fetchDeployment(conf.kubeSystemNamespace, "kube-dns")
        kubeDnsCm   <- client.fetchConfigMap(conf.kubeSystemNamespace, "kube-dns")
        service      <- client.fetchService(conf.kubeDnsOperatorCoreDnsNamespace, conf.kubeDnsOperatorCoreDnsName)
        hasOtoroshiDnsServer = kubeDnsDep.isDefined && kubeDnsCm.isDefined && kubeDnsCm.exists(_.stubDomains.keys.contains(otoDomain))
        shouldNotUpdate      = kubeDnsDep.isDefined && kubeDnsCm.isDefined && kubeDnsCm.exists(cm =>
          (cm.stubDomains \ otoDomain).as[Seq[String]].contains(service.map(_.clusterIP).getOrElse("--"))
        )
        _ = logger.debug(s"kube-dns Operator has dns server: ${hasOtoroshiDnsServer} and should not be updated ${shouldNotUpdate}")
        _ = logger.debug(s"kube-dns Operator config: ${kubeDnsCm.map(_.data.prettify).getOrElse("--")}")
        _ = logger.debug(s"Otoroshi CoreDNS config: ${service.map(_.spec.prettify).getOrElse("--")}")
        _ <- if (service.isDefined && kubeDnsDep.isDefined && kubeDnsCm.isDefined && !hasOtoroshiDnsServer) {
          val cm = kubeDnsCm.get
          val stubDomains = cm.stubDomains ++ Json.obj(
            otoDomain -> Json.arr(s"${service.get.clusterIP}:${conf.kubeDnsOperatorCoreDnsPort}")
          )
          val newCm = KubernetesConfigMap(cm.rawObj ++ Json.obj("data" -> (cm.data ++ Json.obj("stubDomains" -> stubDomains))))
          client.updateConfigMap(conf.kubeSystemNamespace, "kube-dns", newCm).andThen {
            case Success(Right(_)) => logger.info("Successfully patched kube-dns Operator to add coredns as upstream server")
          }
        } else ().future
        _            <- if (service.isDefined && kubeDnsDep.isDefined && kubeDnsCm.isDefined && hasOtoroshiDnsServer && !shouldNotUpdate) {
          val cm = kubeDnsCm.get
          val stubDomains = cm.stubDomains ++ Json.obj(
            otoDomain -> Json.arr(s"${service.get.clusterIP}:${conf.kubeDnsOperatorCoreDnsPort}")
          )
          val newCm = KubernetesConfigMap(cm.rawObj ++ Json.obj("data" -> (cm.data ++ Json.obj("stubDomains" -> stubDomains))))
          client.updateConfigMap(conf.kubeSystemNamespace, "kube-dns", newCm).andThen {
            case Success(Right(_)) => logger.info("Successfully patched kube-dns Operator to add coredns as upstream server")
          }
        } else ().future
      } yield ()
    } else {
      ().future
    }
  }

  def patchOpenshiftDnsOperatorConfig(conf: KubernetesConfig, ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    if (conf.openshiftDnsOperatorIntegration) {
      val client = new KubernetesClient(conf, env)
      for {
        dnsOperator  <- client.fetchOpenshiftDnsOperator()
        service      <- client.fetchService(conf.openshiftDnsOperatorCoreDnsNamespace, conf.openshiftDnsOperatorCoreDnsName)
        hasOtoroshiDnsServer = dnsOperator.exists(_.servers.exists(_.name == conf.openshiftDnsOperatorCoreDnsName))
        shouldNotUpdate      = dnsOperator.exists(dnso =>
          dnso.servers.find(_.name == conf.openshiftDnsOperatorCoreDnsName).exists(_.forwardPluginUpstreams.exists(_.startsWith(service.map(_.clusterIP).getOrElse("--")))) && 
          dnso.servers.find(_.name == conf.openshiftDnsOperatorCoreDnsName).exists(_.zones.exists(_.contains(s"${conf.coreDnsEnv.map(e => s"$e.").getOrElse("")}otoroshi.mesh")))
        )
        _ = logger.debug(s"Openshift DNS Operator has dns server: ${hasOtoroshiDnsServer} and should not be updated ${shouldNotUpdate}")
        _ = logger.debug(s"Openshift DNS Operator config: ${dnsOperator.map(_.spec.prettify).getOrElse("--")}")
        _ = logger.debug(s"Otoroshi CoreDNS config: ${service.map(_.spec.prettify).getOrElse("--")}")
        _ <- if (service.isDefined && dnsOperator.isDefined && !hasOtoroshiDnsServer) {
          val servers = dnsOperator.get.servers.map(_.raw) :+ Json.obj(
            "name" -> conf.openshiftDnsOperatorCoreDnsName,
            "zones" -> Json.arr(
               s"${conf.coreDnsEnv.map(e => s"$e.").getOrElse("")}otoroshi.mesh"
            ),
            "forwardPlugin" -> Json.obj(
              "upstreams" -> Json.arr(
                s"${service.get.clusterIP}:${conf.openshiftDnsOperatorCoreDnsPort}"
              )
            )
          )
          client.updateOpenshiftDnsOperator(KubernetesOpenshiftDnsOperator(Json.obj("spec" -> dnsOperator.get.spec.++(Json.obj("servers" -> servers))))).andThen {
            case Success(Some(_)) => logger.info("Successfully patched Openshift DNS Operator to add coredns as upstream server")
          }
        } else ().future
        _            <- if (service.isDefined && dnsOperator.isDefined && hasOtoroshiDnsServer && !shouldNotUpdate) {
          val servers = dnsOperator.get.servers.map(_.raw).map { server =>
            if (server.select("name").as[String] == conf.openshiftDnsOperatorCoreDnsName) {
              Json.obj(
                "name" -> conf.openshiftDnsOperatorCoreDnsName,
                "zones" -> Json.arr(
                  s"${conf.coreDnsEnv.map(e => s"$e.").getOrElse("")}otoroshi.mesh"
                ),
                "forwardPlugin" -> Json.obj(
                  "upstreams" -> Json.arr(
                    s"${service.get.clusterIP}:${conf.openshiftDnsOperatorCoreDnsPort}"
                  )
                )
              )
            } else {
              server
            }
          }
          client.updateOpenshiftDnsOperator(KubernetesOpenshiftDnsOperator(Json.obj("spec" -> dnsOperator.get.spec.++(Json.obj("servers" -> servers))))).andThen {
            case Success(Some(_)) => logger.info("Successfully patched Openshift DNS Operator to update coredns as upstream server")
          }
        } else ().future
      } yield ()
    } else {
      ().future
    }
  }

  def createWebhookCerts(config: KubernetesConfig, ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {

    def doIt(ca: Cert) = {
      val query = GenCsrQuery(
        hosts = Seq(
          s"${config.otoroshiServiceName}.${config.otoroshiNamespace}.svc.${config.clusterDomain}",
          s"${config.otoroshiServiceName}.${config.otoroshiNamespace}.svc"
        ),
        subject = "SN=Kubernetes Webhooks Certificate, OU=Otoroshi Certificates, O=Otoroshi".some,
        duration = 365.days,
      )
      logger.info("generating certificate for kubernetes webhooks")
      env.pki.genCert(query, ca.certificates.head, ca.certificates.tail, ca.cryptoKeyPair.getPrivate).flatMap {
        case Left(e) =>
          logger.info(s"error while generating certificate for kubernetes webhooks: $e")
          ().future
        case Right(response) => {
          val cert = response.toCert.enrich().applyOn(c => c.copy(
            id = "kubernetes-webhooks-cert",
            autoRenew = true,
            name = "Kubernetes Webhooks Certificate",
            description = "Kubernetes Webhooks Certificate (auto-generated)",
            entityMetadata = c.entityMetadata ++ Map("domain" -> s"${config.otoroshiServiceName}.${config.otoroshiNamespace}.svc")
          ))
          cert.save().map(_ => ())
        }
      }
    }

    env.datastores.certificatesDataStore.findById(Cert.OtoroshiCA).flatMap {
      case None => ().future
      case Some(ca) => {
        env.datastores.certificatesDataStore.findById("kubernetes-webhooks-cert").flatMap {
          case Some(c) if c.entityMetadata.get("domain") == s"${config.otoroshiServiceName}.${config.otoroshiNamespace}.svc".some => ().future
          case _ => doIt(ca)
        }
      }
    }
  }

  def createMeshCerts(config: KubernetesConfig, ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {

    val certId = "kubernetes-mesh-cert"

    val query = GenCsrQuery(
      hosts = Seq(
        s"*.otoroshi.mesh",
        s"*.svc.otoroshi.mesh",
        s"*.global.otoroshi.mesh"
      ),
      subject = "SN=Kubernetes Mesh Certificate, OU=Otoroshi Certificates, O=Otoroshi".some,
      duration = 365.days,
    )

    val queryJson = query.json.stringify

    def doIt(ca: Cert) = {

      logger.info("generating certificate for kubernetes mesh")
      env.pki.genCert(query, ca.certificates.head, ca.certificates.tail, ca.cryptoKeyPair.getPrivate).flatMap {
        case Left(e) =>
          logger.info(s"error while generating certificate for kubernetes mesh: $e")
          ().future
        case Right(response) => {
          val cert = response.toCert.enrich().applyOn(c => c.copy(
            id = certId,
            autoRenew = true,
            name = "Kubernetes Mesh Certificate",
            description = "Kubernetes Mesh Certificate (auto-generated)",
            entityMetadata = c.entityMetadata ++ Map("csr" -> queryJson)
          ))
          cert.save().map(_ => ())
        }
      }
    }

    env.datastores.certificatesDataStore.findById(Cert.OtoroshiIntermediateCA).flatMap {
      case None => ().future
      case Some(ca) => {
        env.datastores.certificatesDataStore.findById(certId).flatMap {
          case Some(c) if c.entityMetadata.get("csr") == queryJson.some => ().future
          case _ => doIt(ca)
        }
      }
    }
  }

  def patchValidatingAdmissionWebhook(conf: KubernetesConfig, ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val client = new KubernetesClient(conf, env)
    client.fetchValidatingWebhookConfiguration(conf.validatingWebhookName) // otoroshi-admission-webhook-validation
      .flatMap {
        case None =>
          logger.info("no validating webhook found, moving along ...")
          ().future
        case Some(webhookSpec) => {
          DynamicSSLEngineProvider.certificates.get(Cert.OtoroshiCA) match {
            case None =>
              logger.info("no otoroshi root ca found, moving along ...")
              ().future
            case Some(ca) => {
              val webhook = webhookSpec.webhooks.value.head
              val caBundle = webhook.select("clientConfig").select("caBundle").asOpt[String].getOrElse("")
              val failurePolicy = webhook.select("failurePolicy").asOpt[String].getOrElse("Ignore")
              val base64ca: String = ca.chain.base64 // Base64.getEncoder.encodeToString(ca.certificates.head.getEncoded)
              // println(s"caBundle: ${caBundle}, failurePolicy: $failurePolicy, base64: $base64ca, eq: ${caBundle == base64ca}")
              if (caBundle.trim.isEmpty || failurePolicy == "Ignore" || caBundle != base64ca) {
                logger.info("updating otoroshi validating admission webhook ...")
                client.patchValidatingWebhookConfiguration(conf.validatingWebhookName, Json.arr(
                  Json.obj(
                    "op" -> "add",
                    "path" -> "/webhooks/0/clientConfig/caBundle",
                    "value" -> base64ca
                  ),
                  Json.obj(
                    "op" -> "replace",
                    "path" -> "/webhooks/0/failurePolicy",
                    "value" -> "Fail"
                  )
                )).map(_ => ())
              } else {
                ().future
              }
            }
          }
        }
      }
  }

  def patchMutatingAdmissionWebhook(conf: KubernetesConfig, ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val client = new KubernetesClient(conf, env)
    client.fetchMutatingWebhookConfiguration(conf.mutatingWebhookName) // otoroshi-admission-webhook-injector
      .flatMap {
        case None =>
          logger.info("no mutating webhook found, moving along ...")
          ().future
        case Some(webhookSpec) => {
          DynamicSSLEngineProvider.certificates.get(Cert.OtoroshiCA) match {
            case None =>
              logger.info("no otoroshi root ca found, moving along ...")
              ().future
            case Some(ca) => {
              val webhook = webhookSpec.webhooks.value.head
              val caBundle = webhook.select("clientConfig").select("caBundle").asOpt[String].getOrElse("")
              val failurePolicy = webhook.select("failurePolicy").asOpt[String].getOrElse("Ignore")
              val base64ca: String = ca.chain.base64 // Base64.getEncoder.encodeToString(ca.certificates.head.getEncoded)
              if (caBundle.trim.isEmpty || failurePolicy == "Ignore" || caBundle != base64ca) {
                logger.info("updating otoroshi mutating admission webhook ...")
                client.patchMutatingWebhookConfiguration(conf.mutatingWebhookName, Json.arr(
                  Json.obj(
                    "op" -> "add",
                    "path" -> "/webhooks/0/clientConfig/caBundle",
                    "value" -> base64ca
                  ),
                  Json.obj(
                    "op" -> "replace",
                    "path" -> "/webhooks/0/failurePolicy",
                    "value" -> "Fail"
                  )
                )).map(_ => ())
              } else {
                ().future
              }
            }
          }
        }
      }
  }
}