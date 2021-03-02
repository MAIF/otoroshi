package otoroshi.plugins.jobs.kubernetes

import java.util.concurrent.Executors
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}

import akka.stream.scaladsl.{Sink, Source}
import cluster.ClusterMode
import com.google.common.base.CaseFormat
import env.Env
import io.kubernetes.client.extended.leaderelection.resourcelock.EndpointsLock
import io.kubernetes.client.extended.leaderelection.{LeaderElectionConfig, LeaderElector}
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.credentials.AccessTokenAuthentication
import models._
import org.joda.time.DateTime
import otoroshi.plugins.jobs.kubernetes.IngressSupport.IntOrString
import otoroshi.script._
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{Result, Results}
import ssl.DynamicSSLEngineProvider
import utils.RequestImplicits._
import utils.{RegexPool, TypedMap}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class KubernetesIngressControllerJob extends Job {

  private val shouldRun = new AtomicBoolean(false)
  private val apiClientRef = new AtomicReference[ApiClient]()
  private val threadPool = Executors.newFixedThreadPool(1)
  private val stopCommand = new AtomicBoolean(false)
  private val watchCommand = new AtomicBoolean(false)
  private val lastWatchStopped = new AtomicBoolean(true)
  private val lastWatchSync = new AtomicLong(0L)

  private val logger = Logger("otoroshi-plugins-kubernetes-ingress-controller-job")

  override def uniqueId: JobId = JobId("io.otoroshi.plugins.jobs.kubernetes.KubernetesIngressControllerJob")

  override def name: String = "Kubernetes Ingress Controller"

  override def defaultConfig: Option[JsObject] = KubernetesConfig.defaultConfig.some

  override def description: Option[String] =
    Some(
      s"""This plugin enables Otoroshi as an Ingress Controller
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
    // TODO: should be dynamic
    if (config.watch) {
      implicit val mat = env.otoroshiMaterializer
      val conf = KubernetesConfig.theConfig(ctx)
      val client = new KubernetesClient(conf, env)
      val source =
        client.watchKubeResources(conf.namespaces, Seq("secrets", "services", "pods", "endpoints"), 30, stopCommand.get())
          .merge(client.watchNetResources(conf.namespaces, Seq("ingresses"), 30, stopCommand.get()))
      source.throttle(1, 5.seconds).runWith(Sink.foreach(_ => KubernetesIngressSyncJob.syncIngresses(conf, ctx.attrs)))
    }
    val conf = KubernetesConfig.theConfig(ctx)
    handleWatch(conf, ctx)
    ().future
  }

  override def jobStop(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    // Option(apiClientRef.get()).foreach(_.) // nothing to stop stuff here ...
    stopCommand.set(true)
    watchCommand.set(false)
    lastWatchStopped.set(true)
    threadPool.shutdown()
    shouldRun.set(false)
    ().future
  }

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val conf = KubernetesConfig.theConfig(ctx)
    if (conf.ingresses) {
      if (conf.kubeLeader) {
        if (shouldRun.get()) {
          logger.info("Running kubernetes ingresses sync ...")
          KubernetesIngressSyncJob.syncIngresses(conf, ctx.attrs)
        } else {
          ().future
        }
      } else {
        logger.info("Running kubernetes ingresses sync ...")
        KubernetesIngressSyncJob.syncIngresses(conf, ctx.attrs)
      }
    } else {
      ().future
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
          client.watchKubeResources(nses, Seq("secrets", "services", "pods", "endpoints"), conf.watchTimeoutSeconds, !watchCommand.get())
          .merge(client.watchNetResources(nses, Seq("ingresses"), conf.watchTimeoutSeconds, !watchCommand.get()))
      }
      source.takeWhile(_ => !watchCommand.get()).filterNot(_.isEmpty).alsoTo(Sink.onComplete {
        case _ => lastWatchStopped.set(true)
      }).runWith(Sink.foreach { group =>
        val now = System.currentTimeMillis()
        if ((lastWatchSync.get() + (conf.watchGracePeriodSeconds * 1000L)) < now) { // 10 sec
          logger.debug(s"sync triggered by a group of ${group.size} events")
          KubernetesIngressSyncJob.syncIngresses(conf, ctx.attrs)
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

/*class KubernetesIngressControllerTrigger extends RequestSink {

  override def name: String = "KubernetesIngressControllerTrigger"

  override def description: Option[String] = "Allow to trigger kubernetes controller jobs".some

  override def defaultConfig: Option[JsObject] = None

  override def matches(ctx: RequestSinkContext)(implicit env: Env, ec: ExecutionContext): Boolean = {
    val conf = KubernetesConfig.theConfig(ctx)
    val host = conf.triggerHost.getOrElse("kubernetes-controllers.oto.tools")
    val path = conf.triggerPath.getOrElse("/.well-known/otoroshi/plugins/kubernetes/controllers/trigger")
    val keyMatch = conf.triggerKey match {
      case None => true
      case Some(key) => ctx.request.getQueryString("key").contains(key)
    }
    ctx.request.theDomain.toLowerCase().equals(host) && ctx.request.relativeUri.startsWith(path) && keyMatch
  }

  override def handle(ctx: RequestSinkContext)(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    val conf = KubernetesConfig.theConfig(ctx)
    val client = new KubernetesClient(conf, env)
    if (conf.crds) {
      val trigger = new AtomicBoolean(true)
      KubernetesCRDsJob.syncCRDs(conf, ctx.attrs, trigger.get()).andThen {
        case _ => trigger.set(false)
      }
    }
    if (conf.ingresses) {
      KubernetesIngressSyncJob.syncIngresses(conf, ctx.attrs)
    }
    Results.Ok(Json.obj("done" -> true)).future
  }
}*/

case class OtoAnnotationConfig(annotations: Map[String, String]) {
  def asSeqString(value: String): Seq[String] = value.split(",").map(_.trim)
  def asMapString(value: String): Map[String, String] = value.split(",").map(_.trim).map { v =>
    val parts = v.split("=")
    (parts.head, parts.last)
  }.toMap
  def apply(desc: ServiceDescriptor): ServiceDescriptor = {
    annotations.filter {
      case (key, _) if key.startsWith("ingress.otoroshi.io/") => true
      case _ => false
    }.map {
      case (key, value) => (key.replace("ingress.otoroshi.io/", ""), value)
    }.foldLeft(desc) {
      case (d, (key, value)) => toCamelCase(key) match {
        case "raw" => {
          val raw = Json.parse(value).as[JsObject]
          val current = desc.toJson.as[JsObject]
          ServiceDescriptor.fromJsonSafe(current.deepMerge(raw)).get
        }
        case "group" => d.copy(groups = Seq(value))
        case "groupId" => d.copy(groups = Seq(value))
        case "groups" => d.copy(groups = value.split(",").map(_.trim).toSeq)
        case "name" => d.copy(name = value)
        // case "env" =>
        // case "domain" =>
        // case "subdomain" =>
        case "targetsLoadBalancing" => d.copy(targetsLoadBalancing = value match {
          case "RoundRobin" => RoundRobin
          case "Random" => Random
          case "Sticky" => Sticky
          case "IpAddressHash" => IpAddressHash
          case "BestResponseTime" => BestResponseTime
          case _ => RoundRobin
        })
        // case "targets" =>
        // case "root" =>
        // case "matchingRoot" =>
        case "stripPath" => d.copy(stripPath = value.toBoolean)
        // case "localHost" =>
        // case "localScheme" =>
        // case "redirectToLocal" =>
        case "enabled" => d.copy(enabled = value.toBoolean)
        case "userFacing" => d.copy(userFacing = value.toBoolean)
        case "privateApp" => d.copy(privateApp = value.toBoolean)
        case "forceHttps" => d.copy(forceHttps = value.toBoolean)
        case "maintenanceMode" => d.copy(maintenanceMode = value.toBoolean)
        case "buildMode" => d.copy(buildMode = value.toBoolean)
        case "strictlyPrivate" => d.copy(strictlyPrivate = value.toBoolean)
        case "sendOtoroshiHeadersBack" => d.copy(sendOtoroshiHeadersBack = value.toBoolean)
        case "readOnly" => d.copy(readOnly = value.toBoolean)
        case "xForwardedHeaders" => d.copy(xForwardedHeaders = value.toBoolean)
        case "overrideHost" => d.copy(overrideHost = value.toBoolean)
        case "allowHttp10" => d.copy(allowHttp10 = value.toBoolean)
        case "logAnalyticsOnServer" => d.copy(logAnalyticsOnServer = value.toBoolean)
        case "useAkkaHttpClient" => d.copy(useAkkaHttpClient = value.toBoolean)
        case "useNewWSClient" => d.copy(useNewWSClient = value.toBoolean)
        case "tcpUdpTunneling" => d.copy(tcpUdpTunneling = value.toBoolean)
        case "detectApiKeySooner" => d.copy(detectApiKeySooner = value.toBoolean)
        case "letsEncrypt" => d.copy(letsEncrypt = value.toBoolean)

        case _ if key.startsWith("secCom") || key == "enforceSecureCommunication"
          || key == "sendInfoToken"
          || key == "sendStateChallenge"
          || key == "securityExcludedPatterns" => securityOptions(key, value, d)

        case "publicPatterns" => d.copy(publicPatterns = asSeqString(value))
        case "privatePatterns" => d.copy(privatePatterns = asSeqString(value))
        case "additionalHeaders" => d.copy(additionalHeaders = asMapString(value))
        case "additionalHeadersOut" => d.copy(additionalHeadersOut = asMapString(value))
        case "missingOnlyHeadersIn" => d.copy(missingOnlyHeadersIn = asMapString(value))
        case "missingOnlyHeadersOut" => d.copy(missingOnlyHeadersOut = asMapString(value))
        case "removeHeadersIn" => d.copy(removeHeadersIn = asSeqString(value))
        case "removeHeadersOut" => d.copy(removeHeadersOut = asSeqString(value))
        case "headersVerification" => d.copy(headersVerification = asMapString(value))
        case "matchingHeaders" => d.copy(matchingHeaders = asMapString(value))
        case "ipFiltering.whitelist" => d.copy(ipFiltering = d.ipFiltering.copy(whitelist = asSeqString(value)))
        case "ipFiltering.blacklist" => d.copy(ipFiltering = d.ipFiltering.copy(blacklist = asSeqString(value)))
        case "api.exposeApi" => d.copy(api = d.api.copy(exposeApi = value.toBoolean))
        case "api.openApiDescriptorUrl" => d.copy(api = d.api.copy(openApiDescriptorUrl = value.some))
        case "healthCheck.enabled" => d.copy(healthCheck = d.healthCheck.copy(enabled = value.toBoolean))
        case "healthCheck.url" => d.copy(healthCheck = d.healthCheck.copy(url = value))
        case _ if key.startsWith("clientConfig.") => clientConfigOptions(key, value, d)
        case _ if key.startsWith("cors.") => corsConfigOptions(key, value, d)
        case _ if key.startsWith("gzip.") => gzipConfigOptions(key, value, d)
        // case "canary" =>
        // case "metadata" =>
        // case "chaosConfig" =>
        case "jwtVerifier.ids" => d.copy(jwtVerifier = d.jwtVerifier.asInstanceOf[RefJwtVerifier].copy(ids = asSeqString(value)))
        case "jwtVerifier.enabled" => d.copy(jwtVerifier = d.jwtVerifier.asInstanceOf[RefJwtVerifier].copy(enabled = value.toBoolean))
        case "jwtVerifier.excludedPatterns" => d.copy(jwtVerifier = d.jwtVerifier.asInstanceOf[RefJwtVerifier].copy(excludedPatterns = asSeqString(value)))
        case "authConfigRef" => d.copy(authConfigRef = value.some)
        case "redirection.enabled" => d.copy(redirection = d.redirection.copy(enabled = value.toBoolean))
        case "redirection.code" => d.copy(redirection = d.redirection.copy(code = value.toInt))
        case "redirection.to" => d.copy(redirection = d.redirection.copy(to = value))
        // case "clientValidatorRef" => d.copy(authConfigRef = value.some)
        // case "transformerRefs" => d.copy(transformerRefs = asSeqString(value))
        // case "transformerConfig" => d.copy(transformerConfig = Json.parse(value))
        // case "accessValidator.enabled" => d.copy(accessValidator = d.accessValidator.copy(enabled = value.toBoolean))
        // case "accessValidator.excludedPatterns" => d.copy(accessValidator = d.accessValidator.copy(excludedPatterns = asSeqString(value)))
        // case "accessValidator.refs" => d.copy(accessValidator = d.accessValidator.copy(refs = asSeqString(value)))
        // case "accessValidator.config" => d.copy(accessValidator = d.accessValidator.copy(config = Json.parse(value)))
        // case "preRouting.enabled" => d.copy(preRouting = d.preRouting.copy(enabled = value.toBoolean))
        // case "preRouting.excludedPatterns" => d.copy(preRouting = d.preRouting.copy(excludedPatterns = asSeqString(value)))
        // case "preRouting.refs" => d.copy(preRouting = d.preRouting.copy(refs = asSeqString(value)))
        // case "preRouting.config" => d.copy(preRouting = d.preRouting.copy(config = Json.parse(value)))
        // case "thirdPartyApiKey" =>
        // case "apiKeyConstraints" =>
        // case "restrictions" =>
        // case "hosts" => d.copy(hosts = asSeqString(value))
        // case "paths" => d.copy(paths = asSeqString(value))
        case "issueCert" => d.copy(issueCert = value.toBoolean)
        case "issueCertCA" => d.copy(issueCertCA = value.some)
        case _ => d
      }
    }
  }

  private def toCamelCase(key: String): String = {
    CaseFormat.LOWER_HYPHEN.to(CaseFormat.LOWER_CAMEL, key)
  }

  private def gzipConfigOptions(key: String, value: String, d: ServiceDescriptor): ServiceDescriptor = key match {
    case "gzip.enabled" => d.copy(gzip = d.gzip.copy(enabled = value.toBoolean))
    case "gzip.excludedPatterns" => d.copy(gzip = d.gzip.copy(excludedPatterns = asSeqString(value)))
    case "gzip.whiteList" => d.copy(gzip = d.gzip.copy(whiteList = asSeqString(value)))
    case "gzip.blackList" => d.copy(gzip = d.gzip.copy(blackList = asSeqString(value)))
    case "gzip.bufferSize" => d.copy(gzip = d.gzip.copy(bufferSize = value.toInt))
    case "gzip.chunkedThreshold" => d.copy(gzip = d.gzip.copy(chunkedThreshold = value.toInt))
    case "gzip.compressionLevel" => d.copy(gzip = d.gzip.copy(compressionLevel = value.toInt))
  }

  private def corsConfigOptions(key: String, value: String, d: ServiceDescriptor): ServiceDescriptor = key match {
    case "cors.enabled" => d.copy(cors = d.cors.copy(enabled = value.toBoolean))
    case "cors.allowOrigin" => d.copy(cors = d.cors.copy(allowOrigin = value))
    case "cors.exposeHeaders" => d.copy(cors = d.cors.copy(exposeHeaders = asSeqString(value)))
    case "cors.allowHeaders" => d.copy(cors = d.cors.copy(allowHeaders = asSeqString(value)))
    case "cors.allowMethods" => d.copy(cors = d.cors.copy(allowMethods = asSeqString(value)))
    case "cors.excludedPatterns" => d.copy(cors = d.cors.copy(excludedPatterns = asSeqString(value)))
    case "cors.maxAge" => d.copy(cors = d.cors.copy(maxAge = value.toInt.millis.some))
    case "cors.allowCredentials" => d.copy(cors = d.cors.copy(allowCredentials = value.toBoolean))
    case _ => d
  }

  private def clientConfigOptions(key: String, value: String, d: ServiceDescriptor): ServiceDescriptor = key match {
    case "clientConfig.useCircuitBreaker" => d.copy(clientConfig = d.clientConfig.copy(useCircuitBreaker = value.toBoolean))
    case "clientConfig.retries" => d.copy(clientConfig = d.clientConfig.copy(retries = value.toInt))
    case "clientConfig.maxErrors" => d.copy(clientConfig = d.clientConfig.copy(maxErrors = value.toInt))
    case "clientConfig.retryInitialDelay" => d.copy(clientConfig = d.clientConfig.copy(retryInitialDelay = value.toLong))
    case "clientConfig.backoffFactor" => d.copy(clientConfig = d.clientConfig.copy(backoffFactor = value.toLong))
    case "clientConfig.connectionTimeout" => d.copy(clientConfig = d.clientConfig.copy(connectionTimeout = value.toLong))
    case "clientConfig.idleTimeout" => d.copy(clientConfig = d.clientConfig.copy(idleTimeout = value.toLong))
    case "clientConfig.callAndStreamTimeout" => d.copy(clientConfig = d.clientConfig.copy(callAndStreamTimeout = value.toLong))
    case "clientConfig.callTimeout" => d.copy(clientConfig = d.clientConfig.copy(callTimeout = value.toLong))
    case "clientConfig.globalTimeout" => d.copy(clientConfig = d.clientConfig.copy(globalTimeout = value.toLong))
    case "clientConfig.sampleInterval" => d.copy(clientConfig = d.clientConfig.copy(sampleInterval = value.toLong))
    case _ => d
  }

  private def securityOptions(key: String, value: String, d: ServiceDescriptor): ServiceDescriptor = key match {
    case "enforceSecureCommunication" => d.copy(enforceSecureCommunication = value.toBoolean)
    case "sendInfoToken" => d.copy(sendInfoToken = value.toBoolean)
    case "sendStateChallenge" => d.copy(sendStateChallenge = value.toBoolean)
    case "secComHeaders.claimRequestName" => d.copy(secComHeaders = d.secComHeaders.copy(claimRequestName = value.some))
    case "secComHeaders.stateRequestName" => d.copy(secComHeaders = d.secComHeaders.copy(stateRequestName = value.some))
    case "secComHeaders.stateResponseName" => d.copy(secComHeaders = d.secComHeaders.copy(stateResponseName = value.some))
    case "secComTtl" => d.copy(secComTtl = value.toInt.millis)
    case "secComVersion" => d.copy(secComVersion = SecComVersion.apply(value).getOrElse(SecComVersion.V2))
    case "secComInfoTokenVersion" => d.copy(secComInfoTokenVersion = SecComInfoTokenVersion.apply(value).getOrElse(SecComInfoTokenVersion.Latest))
    case "secComExcludedPatterns" => d.copy(secComExcludedPatterns = asSeqString(value))
    case "secComSettings.size" => d.copy(secComSettings = d.secComSettings.asInstanceOf[HSAlgoSettings].copy(size = value.toInt))
    case "secComSettings.secret" => d.copy(secComSettings = d.secComSettings.asInstanceOf[HSAlgoSettings].copy(secret = value))
    case "secComSettings.base64" => d.copy(secComSettings = d.secComSettings.asInstanceOf[HSAlgoSettings].copy(base64 = value.toBoolean))
    case "secComUseSameAlgo" => d.copy(secComUseSameAlgo = value.toBoolean)
    case "secComAlgoChallengeOtoToBack.size" => d.copy(secComAlgoChallengeOtoToBack = d.secComAlgoChallengeOtoToBack.asInstanceOf[HSAlgoSettings].copy(size = value.toInt))
    case "secComAlgoChallengeOtoToBack.secret" => d.copy(secComAlgoChallengeOtoToBack = d.secComAlgoChallengeOtoToBack.asInstanceOf[HSAlgoSettings].copy(secret = value))
    case "secComAlgoChallengeOtoToBack.base64" => d.copy(secComAlgoChallengeOtoToBack = d.secComAlgoChallengeOtoToBack.asInstanceOf[HSAlgoSettings].copy(base64 = value.toBoolean))
    case "secComAlgoChallengeBackToOto.size" => d.copy(secComAlgoChallengeBackToOto = d.secComAlgoChallengeBackToOto.asInstanceOf[HSAlgoSettings].copy(size = value.toInt))
    case "secComAlgoChallengeBackToOto.secret" => d.copy(secComAlgoChallengeBackToOto = d.secComAlgoChallengeBackToOto.asInstanceOf[HSAlgoSettings].copy(secret = value))
    case "secComAlgoChallengeBackToOto.base64" => d.copy(secComAlgoChallengeBackToOto = d.secComAlgoChallengeBackToOto.asInstanceOf[HSAlgoSettings].copy(base64 = value.toBoolean))
    case "secComAlgoInfoToken.size" => d.copy(secComAlgoInfoToken = d.secComAlgoInfoToken.asInstanceOf[HSAlgoSettings].copy(size = value.toInt))
    case "secComAlgoInfoToken.secret" => d.copy(secComAlgoInfoToken = d.secComAlgoInfoToken.asInstanceOf[HSAlgoSettings].copy(secret = value))
    case "secComAlgoInfoToken.base64" => d.copy(secComAlgoInfoToken = d.secComAlgoInfoToken.asInstanceOf[HSAlgoSettings].copy(base64 = value.toBoolean))
    case "securityExcludedPatterns" => d.copy(securityExcludedPatterns = asSeqString(value))
    case _ => d
  }
}

object KubernetesIngressSyncJob {

  private val logger = Logger("otoroshi-plugins-kubernetes-ingress-sync")
  private val running = new AtomicBoolean(false)
  private val shouldRunNext = new AtomicBoolean(false)

  private def shouldProcessIngress(ingressClasses: Seq[String], clusterIngressClasses: Seq[KubernetesIngressClass], ingressClassAnnotation: Option[String], ingressClassName: Option[String], conf: KubernetesConfig): Boolean = {

    val defaultIngressController = clusterIngressClasses.find(_.isDefault).map { ic =>
      ic.controller match {
        case "otoroshi" => true
        case "otoroshi.io/ingress-controller" => true
        case clazz => ingressClasses.exists(c => RegexPool(c).matches(clazz))
      }
    }

    val fromIngressClazz: Option[Boolean] = ingressClassName.flatMap { cn =>
      clusterIngressClasses.find(_.name == cn)
    }.map { ic =>
      ic.controller match {
        case "otoroshi" => true
        case "otoroshi.io/ingress-controller" => true
        case _ if ingressClasses.contains("*") => true
        case _ if defaultIngressController.getOrElse(false) => true
        case clazz => ingressClasses.exists(c => RegexPool(c).matches(clazz))
      }
    }

    fromIngressClazz match {
      case Some(value) => value
      case None => ingressClassAnnotation match {
        case None if ingressClasses.contains("*") => true
        case Some("otoroshi") => true
        case Some(annotation) => ingressClasses.exists(c => RegexPool(c).matches(annotation))
        case _ => false
      }
    }
  }

  private def parseConfig(annotations: Map[String, String]): OtoAnnotationConfig = {
    OtoAnnotationConfig(annotations)
  }

  def syncIngresses(_conf: KubernetesConfig, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Unit] = env.metrics.withTimerAsync("otoroshi.plugins.kubernetes.ingresses.sync")  {
    implicit val mat = env.otoroshiMaterializer
    val _client = new KubernetesClient(_conf, env)
    if (running.compareAndSet(false, true)) {
      shouldRunNext.set(false)
      KubernetesCRDsJob.getNamespaces(_client, _conf).flatMap { namespaces =>
        logger.info(s"otoroshi will sync ingresses for the following namespaces: [ ${namespaces.mkString(", ")} ]")
        val conf = _conf.copy(namespaces = namespaces)
        val client = new KubernetesClient(conf, env)
        logger.info("sync certs ...")
        client.fetchCerts().flatMap { certs =>
          client.fetchIngressClasses().flatMap { clusterIngressClasses =>
            logger.info("fetch ingresses")
            client.fetchIngressesAndFilterLabels().flatMap { ingresses =>
              logger.info("update ingresses")
              Source(ingresses.toList)
                .mapAsync(1) { ingressRaw =>
                  if (shouldProcessIngress(conf.ingressClasses, clusterIngressClasses, ingressRaw.ingressClazz, ingressRaw.ingressClassName, conf)) {
                    val otoroshiConfig = parseConfig(ingressRaw.annotations)
                    if (ingressRaw.isValid()) {
                      val certNames = ingressRaw.ingress.spec.tls.map(_.secretName).map(_.toLowerCase)
                      val certsToImport = certs.filter(c => certNames.contains(c.name.toLowerCase()))
                      (ingressRaw.ingress.spec.backend match {
                        case Some(backend) => {
                          backend.asDescriptor(ingressRaw.namespace, conf, otoroshiConfig, client, logger).flatMap {
                            case None => ().future
                            case Some(desc) => desc.save()
                          }
                        }
                        case None => {
                          ingressRaw.updateIngressStatus(client).flatMap { _ =>
                            ingressRaw.asDescriptors(conf, otoroshiConfig, client, logger).flatMap { descs =>
                              Future.sequence(descs.map(_.save()))
                            }
                          }
                        }
                      }) andThen {
                        case _ => KubernetesCertSyncJob.importCerts(certsToImport)
                      }
                    } else {
                      ().future
                    }
                  } else {
                    ().future
                  }
                }.runWith(Sink.ignore).map(_ => ()).flatMap { _ =>

                val existingInKube = ingresses.flatMap { ingress =>
                  ingress.ingress.spec.rules.flatMap { rule =>
                    val host = rule.host.getOrElse("*")
                    rule.http.paths.map { path =>
                      val root = path.path.getOrElse("/")
                      s"${ingress.namespace}-${ingress.name}-$host-$root".slugifyWithSlash
                    }
                  }
                }

                env.datastores.serviceDescriptorDataStore.findAll().flatMap { services =>
                  val toDelete = services.filter { service =>
                    service.metadata.get("otoroshi-provider").contains("kubernetes-ingress")
                  }.map { service =>
                    (service.metadata.getOrElse("kubernetes-ingress-id", "--"), service.id, service.name)
                  }.filterNot {
                    case (ingressId, _, _) => existingInKube.contains(ingressId)
                  }
                  logger.info(s"Deleting services: ${toDelete.map(_._3).mkString(", ")}")
                  env.datastores.serviceDescriptorDataStore.deleteByIds(toDelete.map(_._2)).andThen {
                    case Failure(e) => e.printStackTrace()
                  }.map { _ =>
                    ()
                  }
                }
              }
            }
          }
        }
      }.flatMap { _ =>
        logger.info("sync done !")
        if (shouldRunNext.get()) {
          shouldRunNext.set(false)
          logger.info("restart job right now because sync was asked during sync ")
          syncIngresses(_conf, attrs)
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
}

object KubernetesIngressToDescriptor {
  def asDescriptors(obj: KubernetesIngress)(conf: KubernetesConfig, otoConfig: OtoAnnotationConfig, client: KubernetesClient, logger: Logger)(implicit env: Env, ec: ExecutionContext): Future[Seq[ServiceDescriptor]] = {
    val uid = obj.uid
    val name = obj.name
    val namespace = obj.namespace
    val ingress = obj.ingress
    asDescriptors(uid, name, namespace, ingress, conf, otoConfig, client, logger)(env, ec)
  }

  def asDescriptors(uid: String, name: String, namespace: String, ingress: IngressSupport.NetworkingV1beta1IngressItem, conf: KubernetesConfig, otoConfig: OtoAnnotationConfig, client: KubernetesClient, logger: Logger)(implicit env: Env, ec: ExecutionContext): Future[Seq[ServiceDescriptor]] = {
    implicit val mat = env.otoroshiMaterializer
    Source(ingress.spec.rules.flatMap(r => r.http.paths.map(p => (r, p))).toList)
      .mapAsync(1) {
        case (rule, path) => {
          client.fetchService(namespace, path.backend.serviceName).flatMap {
            case None =>
              logger.info(s"Service ${path.backend.serviceName} not found on namespace $namespace")
              None.future
            case Some(kubeService) =>
              client.fetchEndpoint(namespace, path.backend.serviceName).flatMap { kubeEndpointOpt =>

                val id = ("kubernetes-service-" + namespace + "-" + name + "-" + rule.host.getOrElse("wildcard") + path.path.filterNot(_ == "/").map(v => "-" + v).getOrElse("")).slugifyWithSlash

                val serviceName = kubeService.name
                val serviceType = (kubeService.raw \ "spec" \ "type").as[String]
                val maybePortSpec: Option[JsValue] = (kubeService.raw \ "spec" \ "ports").as[JsArray].value.find { value =>
                  path.backend.servicePort match {
                    case IntOrString(Some(v), _) => (value \ "port").asOpt[Int].contains(v)
                    case IntOrString(_, Some(v)) => (value \ "name").asOpt[String].contains(v)
                    case _ => false
                  }
                }
                maybePortSpec match {
                  case None =>
                    logger.info(s"Service port not found")
                    None.future
                  case Some(portSpec) => {
                    val portName = (portSpec \ "name").as[String]
                    val portValue = (portSpec \ "port").as[Int]
                    val protocol = if (portValue == 443 || portName == "https") "https" else "http"
                    val targets: Seq[Target] = serviceType match {
                      case "ExternalName" =>
                        val serviceExternalName = (kubeService.raw \ "spec" \ "externalName").as[String]
                        Seq(Target(s"$serviceExternalName:$portValue", protocol))
                      case _ => kubeEndpointOpt match {
                        case None => serviceType match {
                          case "ClusterIP" =>
                            val serviceIp = (kubeService.raw \ "spec" \ "clusterIP").as[String]
                            Seq(Target(s"$serviceName:$portValue", protocol, ipAddress = Some(serviceIp)))
                          case "NodePort" =>
                            val serviceIp = (kubeService.raw \ "spec" \ "clusterIP").as[String] // TODO: does it actually work ?
                            Seq(Target(s"$serviceName:$portValue", protocol, ipAddress = Some(serviceIp)))
                          case "LoadBalancer" =>
                            val serviceIp = (kubeService.raw \ "spec" \ "clusterIP").as[String] // TODO: does it actually work ?
                            Seq(Target(s"$serviceName:$portValue", protocol, ipAddress = Some(serviceIp)))
                          case _ => Seq.empty
                        }
                        case Some(kubeEndpoint) => {
                          val subsets = (kubeEndpoint.raw \ "subsets").as[JsArray].value
                          if (subsets.isEmpty) {
                            Seq.empty
                          } else {
                            subsets.flatMap { subset =>
                              val endpointPort: Int = (subset \ "ports").as[JsArray].value.find { port =>
                                (port \ "name").as[String] == portName
                              }.map(v => (v \ "port").as[Int]).getOrElse(80)
                              val endpointProtocol = if (endpointPort == 443 || portName == "https") "https" else "http"
                              val addresses = (subset \ "addresses").asOpt[JsArray].map(_.value).getOrElse(Seq.empty)
                              addresses.map { address =>
                                val serviceIp = (address \ "ip").as[String]
                                Target(s"$serviceName:$endpointPort", endpointProtocol, ipAddress = Some(serviceIp))
                              }
                            }
                          }
                        }
                      }
                    }
                    env.datastores.serviceDescriptorDataStore.findById(id).map {
                      case None => ("create", env.datastores.serviceDescriptorDataStore.initiateNewDescriptor())
                      case Some(desc) => ("update", desc)
                    }.map {
                      case (action, desc) =>
                        val creationDate: String = if (action == "create") DateTime.now().toString else desc.metadata.getOrElse("created-at", DateTime.now().toString)
                        val newDesc = desc.copy(
                          id = id,
                          groups = Seq(conf.defaultGroup),
                          name = "kubernetes - " + name + " - " + rule.host.getOrElse("*") + " - " + path.path.getOrElse("/"),
                          env = "prod",
                          domain = "otoroshi.internal.kube.cluster",
                          subdomain = id,
                          targets = targets,
                          root = path.path.getOrElse("/"),
                          matchingRoot = path.path,
                          hosts = Seq(rule.host.getOrElse("*")),
                          paths = path.path.toSeq,
                          publicPatterns = Seq("/.*"),
                          useAkkaHttpClient = true,
                          metadata = Map(
                            "otoroshi-provider" -> "kubernetes-ingress",
                            "created-at" -> creationDate,
                            "updated-at" -> DateTime.now().toString,
                            "kubernetes-name" -> name,
                            "kubernetes-namespace" -> namespace,
                            "kubernetes-path" -> s"$namespace/$name",
                            "kubernetes-ingress-id" -> s"$namespace-$name-${rule.host.getOrElse("*")}-${path.path.getOrElse("/")}".slugifyWithSlash,
                            "kubernetes-uid" -> uid
                          )
                        )
                        action match {
                          case "create" => logger.info(s"""Creating service "${newDesc.name}" from "$namespace/$name"""")
                          case "update" => logger.info(s"""Updating service "${newDesc.name}" from "$namespace/$name"""")
                          case _ =>
                        }
                        newDesc
                    }.map { desc =>
                      otoConfig.apply(desc).some
                    }
                  }
                }
              }
          }
        }
      }.runWith(Sink.seq).map(_.flatten)
  }

  def serviceToTargetsSync(kubeService: KubernetesService, kubeEndpointOpt: Option[KubernetesEndpoint], port: IntOrString, template: JsObject, client: KubernetesClient, logger: Logger): Seq[Target] = {
      val templateTarget = Target.format.reads(template ++ Json.obj("host" -> "--")).get
      val serviceType = (kubeService.raw \ "spec" \ "type").as[String]
      val serviceName = kubeService.name
      val serviceNamespace = kubeService.namespace
      val maybePortSpec: Option[JsValue] = (kubeService.raw \ "spec" \ "ports").as[JsArray].value.find { value =>
        port match {
          case IntOrString(Some(v), _) => (value \ "port").asOpt[Int].contains(v)
          case IntOrString(_, Some(v)) => (value \ "name").asOpt[String].contains(v)
          case _ => false
        }
      }
      maybePortSpec match {
        case None =>
          logger.info(s"Service port not found")
          Seq.empty
        case Some(portSpec) => {
          val portName = (portSpec \ "name").as[String]
          val portValue = (portSpec \ "port").as[Int]
          val protocol = if (portValue == 443 || portName == "https") "https" else "http"
          serviceType match {
            case "ExternalName" =>
              val serviceExternalName = (kubeService.raw \ "spec" \ "externalName").as[String]
              Seq(templateTarget.copy(host = s"$serviceExternalName:$portValue", scheme = protocol))
            case _ => kubeEndpointOpt match {
              case None => serviceType match {
                case "ClusterIP" =>
                  val serviceIp = (kubeService.raw \ "spec" \ "clusterIP").asOpt[String]
                  Seq(templateTarget.copy(host = s"$serviceName.$serviceNamespace.svc.${client.config.clusterDomain}:$portValue", scheme = protocol, ipAddress = serviceIp))
                case "NodePort" =>
                  val serviceIp = (kubeService.raw \ "spec" \ "clusterIP").asOpt[String]
                  Seq(templateTarget.copy(host = s"$serviceName.$serviceNamespace.svc.${client.config.clusterDomain}:$portValue", scheme = protocol, ipAddress = serviceIp))
                case "LoadBalancer" =>
                  val serviceIp = (kubeService.raw \ "spec" \ "clusterIP").asOpt[String]
                  Seq(templateTarget.copy(host = s"$serviceName.$serviceNamespace.svc.${client.config.clusterDomain}:$portValue", scheme = protocol, ipAddress = serviceIp))
                case _ => Seq.empty
              }
              case Some(kubeEndpoint) => {
                val subsets = (kubeEndpoint.raw \ "subsets").as[JsArray].value
                if (subsets.isEmpty) {
                  Seq.empty
                } else {
                  subsets.flatMap { subset =>
                    val endpointPort: Int = (subset \ "ports").as[JsArray].value.find { port =>
                      (port \ "name").as[String] == portName
                    }.map(v => (v \ "port").as[Int]).getOrElse(80)
                    val endpointProtocol = if (endpointPort == 443 || portName == "https") "https" else "http"
                    val addresses = (subset \ "addresses").asOpt[JsArray].map(_.value).getOrElse(Seq.empty)
                    addresses.map { address =>
                      val serviceIp = (address \ "ip").as[String]
                      templateTarget.copy(host =s"$serviceName.$serviceNamespace.svc.${client.config.clusterDomain}:$endpointPort", scheme = endpointProtocol, ipAddress = Some(serviceIp))
                    }
                  }
                }
              }
            }
          }
        }
      }
  }

  def serviceToTargets(namespace: String, name: String, port: IntOrString, template: JsObject, client: KubernetesClient, logger: Logger)(implicit ec: ExecutionContext, env: Env): Future[Seq[Target]] = {
    client.fetchService(namespace, name).flatMap {
      case None =>
        logger.info(s"Service $name not found on namespace $namespace")
        Seq.empty.future
      case Some(kubeService) => client.fetchEndpoint(namespace, name).map { kubeEndpointOpt =>
        serviceToTargetsSync(kubeService, kubeEndpointOpt, port, template, client, logger)
      }
    }
  }
}

object IngressSupport {

  object IntOrString {
    val reader = new Reads[IntOrString] {
      override def reads(json: JsValue): JsResult[IntOrString] = Try(
        json.asOpt[Int].map(v => IntOrString(v.some, None))
          .orElse(json.asOpt[String].map(v => IntOrString(None, v.some)))
          .get
      ) match {
        case Failure(e) => JsError(e.getMessage)
        case Success(v) => JsSuccess(v)
      }
    }
  }

  case class IntOrString(value: Option[Int], nameRef: Option[String]) {
    def actualValue(): Int = (value, nameRef) match {
      case (Some(v), _) => v
      case (_, Some(v)) => v.toInt
      case _ => 8080 // yeah !
    }
  }

  object NetworkingV1beta1Ingress {
    val reader = new Reads[NetworkingV1beta1Ingress] {
      override def reads(json: JsValue): JsResult[NetworkingV1beta1Ingress] = Try(
        NetworkingV1beta1Ingress(
          apiVersion = (json \ "apiVersion").as[String],
          kind = (json \ "kind").as[String],
          metadata = (json \ "metadata").as(V1ObjectMeta.reader),
          spec = (json \ "spec").as(NetworkingV1beta1IngressSpec.reader),
          status = (json \ "status").as(NetworkingV1beta1IngressStatus.reader)
        )
      ) match {
        case Failure(e) => JsError(e.getMessage)
        case Success(v) => JsSuccess(v)
      }
    }
  }

  case class NetworkingV1beta1Ingress(apiVersion: String, kind: String, metadata: V1ObjectMeta, spec: NetworkingV1beta1IngressSpec, status: NetworkingV1beta1IngressStatus)

  object NetworkingV1beta1IngressItem {
    val reader = new Reads[NetworkingV1beta1IngressItem] {
      override def reads(json: JsValue): JsResult[NetworkingV1beta1IngressItem] = Try(
        NetworkingV1beta1IngressItem(
          // metadata = (json \ "metadata").as(V1ObjectMeta.reader),
          spec = (json \ "spec").as(NetworkingV1beta1IngressSpec.reader),
          status = (json \ "status").as(NetworkingV1beta1IngressStatus.reader)
        )
      ) match {
        case Failure(e) => JsError(e.getMessage)
        case Success(v) => JsSuccess(v)
      }
    }
  }

  case class NetworkingV1beta1IngressItem(spec: NetworkingV1beta1IngressSpec, status: NetworkingV1beta1IngressStatus)

  object NetworkingV1beta1IngressBackend {
    val reader = new Reads[NetworkingV1beta1IngressBackend] {
      override def reads(json: JsValue): JsResult[NetworkingV1beta1IngressBackend] = Try(
        NetworkingV1beta1IngressBackend(
          serviceName = (json \ "serviceName").as[String],
          servicePort = (json \ "servicePort").as(IntOrString.reader)
        )
      ) match {
        case Failure(e) => JsError(e.getMessage)
        case Success(v) => JsSuccess(v)
      }
    }
  }

  case class NetworkingV1beta1IngressBackend(serviceName: String, servicePort: IntOrString) {
    def asDescriptor(namespace: String, conf: KubernetesConfig, otoConfig: OtoAnnotationConfig, client: KubernetesClient, logger: Logger)(implicit env: Env, ec: ExecutionContext): Future[Option[ServiceDescriptor]] = {
      val ingress = IngressSupport.NetworkingV1beta1IngressItem(
        spec =  NetworkingV1beta1IngressSpec(backend = None, rules = Seq(NetworkingV1beta1IngressRule(
          host = "*".some,
          http = NetworkingV1beta1HTTPIngressRuleValue.apply(Seq(NetworkingV1beta1HTTPIngressPath(
            backend = this,
            path = "/".some
          )))
        )), tls = Seq.empty),
        status = NetworkingV1beta1IngressStatus(V1LoadBalancerStatus(Seq.empty))
      )
      KubernetesIngressToDescriptor.asDescriptors("default-backend", "default-backend", namespace, ingress, conf, otoConfig, client, logger)(env, ec).map(_.headOption)
    }
  }

  object NetworkingV1beta1IngressRule {
    val reader = new Reads[NetworkingV1beta1IngressRule] {
      override def reads(json: JsValue): JsResult[NetworkingV1beta1IngressRule] = Try(
        NetworkingV1beta1IngressRule(
          host = (json \ "host").asOpt[String],
          http = (json \ "http").as(NetworkingV1beta1HTTPIngressRuleValue.reader)
        )
      ) match {
        case Failure(e) => JsError(e.getMessage)
        case Success(v) => JsSuccess(v)
      }
    }
  }

  case class NetworkingV1beta1IngressRule(host: Option[String], http: NetworkingV1beta1HTTPIngressRuleValue)

  object NetworkingV1beta1IngressSpec {
    val reader = new Reads[NetworkingV1beta1IngressSpec] {
      override def reads(json: JsValue): JsResult[NetworkingV1beta1IngressSpec] = Try(
        NetworkingV1beta1IngressSpec(
          backend = (json \ "backend").asOpt(NetworkingV1beta1IngressBackend.reader),
          rules = (json \ "rules").asOpt(Reads.seq(NetworkingV1beta1IngressRule.reader)).getOrElse(Seq.empty),
          tls = (json \ "tls").asOpt(Reads.seq(NetworkingV1beta1IngressTLS.reader)).getOrElse(Seq.empty),
        )
      ) match {
        case Failure(e) => JsError(e.getMessage)
        case Success(v) => JsSuccess(v)
      }
    }
  }

  case class NetworkingV1beta1IngressSpec(backend: Option[NetworkingV1beta1IngressBackend], rules: Seq[NetworkingV1beta1IngressRule], tls: Seq[NetworkingV1beta1IngressTLS])

  object NetworkingV1beta1IngressList {
    val reader = new Reads[NetworkingV1beta1IngressList] {
      override def reads(json: JsValue): JsResult[NetworkingV1beta1IngressList] = Try(
        NetworkingV1beta1IngressList(
          apiVersion = (json \ "apiVersion").as[String],
          items = (json \ "items").as(Reads.seq(NetworkingV1beta1Ingress.reader)),
          kind = (json \ "kind").as[String],
        )
      ) match {
        case Failure(e) => JsError(e.getMessage)
        case Success(v) => JsSuccess(v)
      }
    }
  }

  case class NetworkingV1beta1IngressList(apiVersion: String, items: Seq[NetworkingV1beta1Ingress], kind: String)

  object NetworkingV1beta1IngressStatus {
    val reader = new Reads[NetworkingV1beta1IngressStatus] {
      override def reads(json: JsValue): JsResult[NetworkingV1beta1IngressStatus] = Try(
        NetworkingV1beta1IngressStatus(
          loadBalancer = (json \ "loadBalancer").as(V1LoadBalancerStatus.reader),
        )
      ) match {
        case Failure(e) => JsError(e.getMessage)
        case Success(v) => JsSuccess(v)
      }
    }
  }

  case class NetworkingV1beta1IngressStatus(loadBalancer: V1LoadBalancerStatus)

  object NetworkingV1beta1IngressTLS {
    val reader = new Reads[NetworkingV1beta1IngressTLS] {
      override def reads(json: JsValue): JsResult[NetworkingV1beta1IngressTLS] = Try(
        NetworkingV1beta1IngressTLS(
          secretName = (json \ "secretName").as[String],
          hosts = (json \ "hosts").as(Reads.seq[String]),
        )
      ) match {
        case Failure(e) => JsError(e.getMessage)
        case Success(v) => JsSuccess(v)
      }
    }
  }

  case class NetworkingV1beta1IngressTLS(hosts: Seq[String], secretName: String)

  object NetworkingV1beta1HTTPIngressPath {
    val reader = new Reads[NetworkingV1beta1HTTPIngressPath] {
      override def reads(json: JsValue): JsResult[NetworkingV1beta1HTTPIngressPath] = Try(
        NetworkingV1beta1HTTPIngressPath(
          backend = (json \ "backend").as(NetworkingV1beta1IngressBackend.reader),
          path = (json \ "path").asOpt[String],
        )
      ) match {
        case Failure(e) => JsError(e.getMessage)
        case Success(v) => JsSuccess(v)
      }
    }
  }

  case class NetworkingV1beta1HTTPIngressPath(backend: NetworkingV1beta1IngressBackend, path: Option[String])

  object NetworkingV1beta1HTTPIngressRuleValue {
    val reader = new Reads[NetworkingV1beta1HTTPIngressRuleValue] {
      override def reads(json: JsValue): JsResult[NetworkingV1beta1HTTPIngressRuleValue] = Try(
        NetworkingV1beta1HTTPIngressRuleValue(
          paths = (json \ "paths").as(Reads.seq(NetworkingV1beta1HTTPIngressPath.reader)),
        )
      ) match {
        case Failure(e) => JsError(e.getMessage)
        case Success(v) => JsSuccess(v)
      }
    }
  }

  case class NetworkingV1beta1HTTPIngressRuleValue(paths: Seq[NetworkingV1beta1HTTPIngressPath])

  object V1LoadBalancerStatus {
    val reader = new Reads[V1LoadBalancerStatus] {
      override def reads(json: JsValue): JsResult[V1LoadBalancerStatus] = Try(
        V1LoadBalancerStatus(
          ingress = (json \ "ingress").as(Reads.seq(V1LoadBalancerIngress.reader)),
        )
      ) match {
        case Failure(e) => JsError(e.getMessage)
        case Success(v) => JsSuccess(v)
      }
    }
  }

  case class V1LoadBalancerStatus(ingress: Seq[V1LoadBalancerIngress])

  object V1ObjectMeta {
    val reader = new Reads[V1ObjectMeta] {
      override def reads(json: JsValue): JsResult[V1ObjectMeta] = Try(
        V1ObjectMeta(
          annotations = (json \ "annotations").as[Map[String, String]],
          clusterName = (json \ "clusterName").asOpt[String],
          creationTimestamp = new DateTime((json \ "creationTimestamp").as[Long]),
          deletionGracePeriodSeconds = (json \ "deletionGracePeriodSeconds").as[Long],
          deletionTimestamp = new DateTime((json \ "deletionTimestamp").as[Long]),
          finalizers = (json \ "finalizers").as[Seq[String]],
          generateName = (json \ "generateName").as[String],
          generation = (json \ "generation").as[Long],
          labels = (json \ "labels").as[Map[String, String]],
          name = (json \ "name").as[String],
          namespace = (json \ "namespace").as[String],
          ownerReferences = (json \ "ownerReferences").as(Reads.seq(V1OwnerReference.reader)),
          resourceVersion = (json \ "resourceVersion").as[String],
          selfLink = (json \ "selfLink").as[String],
          uid = (json \ "uid").as[String],
        )
      ) match {
        case Failure(e) => JsError(e.getMessage)
        case Success(v) => JsSuccess(v)
      }
    }
  }

  case class V1ObjectMeta(annotations: Map[String, String], clusterName: Option[String], creationTimestamp: DateTime,
                          deletionGracePeriodSeconds: Long, deletionTimestamp: DateTime, finalizers: Seq[String],
                          generateName: String, generation: Long, labels: Map[String, String], name: String,
                          namespace: String, ownerReferences: Seq[V1OwnerReference], resourceVersion: String,
                          selfLink: String, uid: String)

  object V1OwnerReference {
    val reader = new Reads[V1OwnerReference] {
      override def reads(json: JsValue): JsResult[V1OwnerReference] = Try(
        V1OwnerReference(
          apiVersion = (json \ "apiVersion").as[String],
          blockOwnerDeletion = (json \ "blockOwnerDeletion").as[Boolean],
          controller = (json \ "controller").as[Boolean],
          kind = (json \ "kind").as[String],
          name = (json \ "name").as[String],
          uid = (json \ "uid").as[String],
        )
      ) match {
        case Failure(e) => JsError(e.getMessage)
        case Success(v) => JsSuccess(v)
      }
    }
  }

  case class V1OwnerReference(apiVersion: String, blockOwnerDeletion: Boolean, controller: Boolean, kind: String, name: String, uid: String)

  object V1LoadBalancerIngress {
    val reader = new Reads[V1LoadBalancerIngress] {
      override def reads(json: JsValue): JsResult[V1LoadBalancerIngress] = Try(
        V1LoadBalancerIngress(
          hostname = (json \ "hostname").asOpt[String],
          ip = (json \ "ip").asOpt[String],
        )
      ) match {
        case Failure(e) => JsError(e.getMessage)
        case Success(v) => JsSuccess(v)
      }
    }
  }

  case class V1LoadBalancerIngress(hostname: Option[String], ip: Option[String])

}
