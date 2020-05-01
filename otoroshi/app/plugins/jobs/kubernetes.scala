package otoroshi.plugins.jobs.kubernetes

import java.io.File
import java.nio.file.{Files, Path}

import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.{Sink, Source}
import env.Env
import models._
import org.joda.time.DateTime
import otoroshi.plugins.jobs.kubernetes.IngressSupport.IntOrString
import otoroshi.script._
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.libs.ws.WSRequest
import play.api.mvc.{Result, Results}
import security.OtoroshiClaim
import ssl.{Cert, DynamicSSLEngineProvider}
import utils.RequestImplicits._
import utils.http.MtlsConfig
import utils.{TypedMap, UrlSanitizer}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object KubernetesConfig {
  import collection.JavaConverters._
  def theConfig(ctx: ContextWithConfig)(implicit env: Env, ec: ExecutionContext): KubernetesConfig = {
    val conf = ctx.configForOpt("KubernetesConfig").orElse((env.datastores.globalConfigDataStore.latest().scripts.jobConfig \ "KubernetesConfig").asOpt[JsValue]).getOrElse(Json.obj())
    // TODO: read $KUBECONFIG
    KubernetesConfig(
      enabled = (conf \ "enabled").as[Boolean],
      allIngress = (conf \ "allIngress").asOpt[Boolean].getOrElse(false),
      trust = (conf \ "trust").asOpt[Boolean].getOrElse(false),
      endpoint = (conf \ "endpoint").asOpt[String].getOrElse {
        val host = sys.env("KUBERNETES_SERVICE_HOST")
        val port = sys.env("KUBERNETES_SERVICE_PORT")
        s"https://$host:$port"
      },
      token = (conf \ "token").asOpt[String].getOrElse(
        Files.readAllLines(new File("/var/run/secrets/kubernetes.io/serviceaccount/token").toPath).asScala.mkString("\n")
      ),
      caCert = (conf \ "cert").asOpt[String]
        .orElse((conf \ "certPath").asOpt[String].map { path =>
          Files.readAllLines(new File(path).toPath).asScala.mkString("\n")
        })
        .orElse(
          new File("/var/run/secrets/kubernetes.io/serviceaccount/ca.crt").some.filter(_.exists()).map(f => Files.readAllLines(f.toPath).asScala.mkString("\n"))
        ),
      namespaces = (conf \ "namespaces").asOpt[Seq[String]].filter(_.nonEmpty).getOrElse(Seq("*")),
      labels = (conf \ "labels").asOpt[Map[String, String]].getOrElse(Map.empty),
      ingressClass = (conf \ "ingressClass").asOpt[String],
      defaultGroup = (conf \ "defaultGroup").asOpt[String].getOrElse("default"),
      ingressEndpointHostname = (conf \ "ingressEndpointHostname").asOpt[String],
      ingressEndpointIp = (conf \ "ingressEndpointIp").asOpt[String],
      ingressEndpointPublishedService = (conf \ "ingressEndpointPublishedServices").asOpt[String]
    )
  }
  def defaultConfig: JsObject = {
    Json.obj(
      "KubernetesConfig" -> Json.obj(
        "enabled" -> true,
        "endpoint" -> "https://kube.cluster.dev",
        "token" -> "xxx",
        "caCert" -> "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt",
        "trust" -> false,
        "namespaces" -> Json.arr("*"),
        "labels" -> JsNull,
        "allIngress" -> false,
        "ingressClass" -> "otoroshi",
        "defaultGroup" -> "default",
        "ingressEndpointHostname" -> JsNull,
        "ingressEndpointIp" -> JsNull,
        "ingressEndpointPublishedService" -> JsNull
      )
    )
  }
}

case class KubernetesConfig(
  enabled: Boolean,
  endpoint: String,
  token: String,
  caCert: Option[String],
  trust: Boolean,
  namespaces: Seq[String],
  labels: Map[String, String],
  allIngress: Boolean,
  ingressClass: Option[String],
  defaultGroup: String,
  ingressEndpointHostname: Option[String],
  ingressEndpointIp: Option[String],
  ingressEndpointPublishedService: Option[String]
)

// https://kubernetes.io/fr/docs/concepts/services-networking/ingress/
class KubernetesIngressControllerJob extends Job {

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

  override def instantiation: JobInstantiation = JobInstantiation.OneInstancePerOtoroshiCluster

  override def initialDelay: Option[FiniteDuration] = 2.seconds.some

  override def interval: Option[FiniteDuration] = 5.seconds.some

  override def jobStart(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = super.jobStart(ctx)

  override def jobStop(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = super.jobStop(ctx)

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val conf = KubernetesConfig.theConfig(ctx)
    if (conf.enabled) {
      logger.info("Running kubernetes ingresses sync ...")
      KubernetesIngressSyncJob.syncIngresses(conf, ctx.attrs)
    } else {
      ().future
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

  override def interval: Option[FiniteDuration] = 1.minute.some

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

  override def interval: Option[FiniteDuration] = 1.minute.some

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

case class OtoAnnotationConfig(annotations: Map[String, String]) {
  def asSeqString(value: String): Seq[String] = value.split(",").map(_.trim)
  def asMapString(value: String): Map[String, String] = value.split(",").map(_.trim).map { v =>
    val parts = v.split("=")
    (parts.head, parts.last)
  }.toMap
  def apply(desc: ServiceDescriptor): ServiceDescriptor = {
    annotations.filter {
      case (key, _) if key.startsWith("otoroshi.ingress.kubernetes.io/") => true
      case _ => false
    }.map {
      case (key, value) => (key.replace("otoroshi.ingress.kubernetes.io/", ""), value)
    }.foldLeft(desc) {
      case (d, (key, value)) => key match {
        case "raw" => {
          val raw = Json.parse(value).as[JsObject]
          val current = desc.toJson.as[JsObject]
          ServiceDescriptor.fromJsonSafe(current.deepMerge(raw)).get
        }
        case "group" => d.copy(groupId = value)
        case "groupId" => d.copy(groupId = value)
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
        // case "canary" =>
        // case "metadata" =>
        // case "chaosConfig" =>
        case "jwtVerifier.ids" => d.copy(jwtVerifier = d.jwtVerifier.asInstanceOf[RefJwtVerifier].copy(ids = asSeqString(value)))
        case "jwtVerifier.enabled" => d.copy(jwtVerifier = d.jwtVerifier.asInstanceOf[RefJwtVerifier].copy(enabled = value.toBoolean))
        case "jwtVerifier.excludedPatterns" => d.copy(jwtVerifier = d.jwtVerifier.asInstanceOf[RefJwtVerifier].copy(excludedPatterns = asSeqString(value)))
        case "authConfigRef" => d.copy(authConfigRef = value.some)
        case "cors.enabled" => d.copy(cors = d.cors.copy(enabled = value.toBoolean))
        case "cors.allowOrigin" => d.copy(cors = d.cors.copy(allowOrigin = value))
        case "cors.exposeHeaders" => d.copy(cors = d.cors.copy(exposeHeaders = asSeqString(value)))
        case "cors.allowHeaders" => d.copy(cors = d.cors.copy(allowHeaders = asSeqString(value)))
        case "cors.allowMethods" => d.copy(cors = d.cors.copy(allowMethods = asSeqString(value)))
        case "cors.excludedPatterns" => d.copy(cors = d.cors.copy(excludedPatterns = asSeqString(value)))
        case "cors.maxAge" => d.copy(cors = d.cors.copy(maxAge = value.toInt.millis.some))
        case "cors.allowCredentials" => d.copy(cors = d.cors.copy(allowCredentials = value.toBoolean))
        case "redirection.enabled" => d.copy(redirection = d.redirection.copy(enabled = value.toBoolean))
        case "redirection.code" => d.copy(redirection = d.redirection.copy(code = value.toInt))
        case "redirection.to" => d.copy(redirection = d.redirection.copy(to = value))
        case "clientValidatorRef" => d.copy(authConfigRef = value.some)
        case "transformerRefs" => d.copy(transformerRefs = asSeqString(value))
        case "transformerConfig" => d.copy(transformerConfig = Json.parse(value))
        case "accessValidator.enabled" => d.copy(accessValidator = d.accessValidator.copy(enabled = value.toBoolean))
        case "accessValidator.excludedPatterns" => d.copy(accessValidator = d.accessValidator.copy(excludedPatterns = asSeqString(value)))
        case "accessValidator.refs" => d.copy(accessValidator = d.accessValidator.copy(refs = asSeqString(value)))
        case "accessValidator.config" => d.copy(accessValidator = d.accessValidator.copy(config = Json.parse(value)))
        case "preRouting.enabled" => d.copy(preRouting = d.preRouting.copy(enabled = value.toBoolean))
        case "preRouting.excludedPatterns" => d.copy(preRouting = d.preRouting.copy(excludedPatterns = asSeqString(value)))
        case "preRouting.refs" => d.copy(preRouting = d.preRouting.copy(refs = asSeqString(value)))
        case "preRouting.config" => d.copy(preRouting = d.preRouting.copy(config = Json.parse(value)))
        case "gzip.enabled" => d.copy(gzip = d.gzip.copy(enabled = value.toBoolean))
        case "gzip.excludedPatterns" => d.copy(gzip = d.gzip.copy(excludedPatterns = asSeqString(value)))
        case "gzip.whiteList" => d.copy(gzip = d.gzip.copy(whiteList = asSeqString(value)))
        case "gzip.blackList" => d.copy(gzip = d.gzip.copy(blackList = asSeqString(value)))
        case "gzip.bufferSize" => d.copy(gzip = d.gzip.copy(bufferSize = value.toInt))
        case "gzip.chunkedThreshold" => d.copy(gzip = d.gzip.copy(chunkedThreshold = value.toInt))
        case "gzip.compressionLevel" => d.copy(gzip = d.gzip.copy(compressionLevel = value.toInt))
        // case "thirdPartyApiKey" =>
        // case "apiKeyConstraints" =>
        // case "restrictions" =>
        case "hosts" => d.copy(hosts = asSeqString(value))
        case "paths" => d.copy(paths = asSeqString(value))
        case "issueCert" => d.copy(issueCert = value.toBoolean)
        case "issueCertCA" => d.copy(issueCertCA = value.some)
        case _ => d
      }
    }
  }
}

// TODO: remove for release
class KubernetesIngressControllerTrigger extends RequestSink {

  override def name: String = "KubernetesIngressControllerTrigger"

  override def description: Option[String] = "KubernetesIngressControllerTrigger".some

  override def defaultConfig: Option[JsObject] = None

  override def matches(ctx: RequestSinkContext)(implicit env: Env, ec: ExecutionContext): Boolean = {
    ctx.request.theDomain.toLowerCase().equals("kubernetes-ingress-controller.oto.tools") &&
      ctx.request.relativeUri.equals("/.well-known/otoroshi/plugins/kubernetes/ingress-controller/trigger")
  }

  override def handle(ctx: RequestSinkContext)(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    val conf = KubernetesConfig.theConfig(ctx)
    KubernetesIngressSyncJob.syncIngresses(conf, ctx.attrs).map { _ =>
      Results.Ok(Json.obj("done" -> true))
    }
  }
}

object KubernetesIngressSyncJob {

  val logger = Logger("otoroshi-plugins-kubernetes-ingress-sync")

  private def shouldProcessIngress(ingressClass: Option[String], ingressClassAnnotation: Option[String], conf: KubernetesConfig): Boolean = {
    ingressClass match {
      case None if ingressClassAnnotation.isEmpty && conf.allIngress => true
      case None => ingressClassAnnotation == "otoroshi".some
      case Some(v) if ingressClassAnnotation.isDefined => v == ingressClassAnnotation.get
      case Some(v) if ingressClassAnnotation.isEmpty => true
      case _ => true
    }
  }

  private def parseConfig(annotations: Map[String, String]): OtoAnnotationConfig = {
    OtoAnnotationConfig(annotations)
  }

  def syncIngresses(conf: KubernetesConfig, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    implicit val mat = env.otoroshiMaterializer
    val client = new KubernetesClient(conf, env)
    client.fetchCerts().flatMap { certs =>
      client.fetchIngressesAndFilterLabels().flatMap { ingresses =>
        Source(ingresses.toList)
          .mapAsync(1) { ingressRaw =>
              if (shouldProcessIngress(conf.ingressClass, ingressRaw.ingressClazz, conf)) {
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

          val existingInKube = ingresses.map(k => (k.namespace, k.name))

          env.datastores.serviceDescriptorDataStore.findAll().flatMap { services =>
            val toDelete = services.filter { service =>
              service.metadata.get("otoroshi-service-provider").contains("kubernetes")
            }.map { service =>
              (service.metadata.get("kubernetes-namespace").get, service.metadata.get("kubernetes-name").get, service.id)
            }.filterNot {
              case (namespace, name, id) => existingInKube.contains((namespace, name))
            }.map(_._3)
            env.datastores.serviceDescriptorDataStore.deleteByIds(toDelete).map { _ =>
              ().future
            }
          }
        }
      }
    }
  }
}

object KubernetesCertSyncJob {

  val logger = Logger("otoroshi-plugins-kubernetes-cert-sync")

  def syncOtoroshiCertsToKubernetesSecrets(client: KubernetesClient): Future[Unit] = ().future // TODO: implements

  def importCerts(certs: Seq[KubernetesCertSecret])(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    Future.sequence(certs.map { cert =>
      cert.cert match {
        case None => ().future
        case Some(found) => {
          val certId = s"kubernetes_secrets_${cert.namespace}_${cert.name}".slugifyWithSlash
          val newCert = found.copy(id = certId).enrich()
          env.datastores.certificatesDataStore.findById(certId).flatMap {
            case None =>
              logger.info(s"importing cert. ${cert.namespace} - ${cert.name}")
              newCert.save().map(_ => ())
            case Some(existingCert) if existingCert.contentHash == newCert.contentHash => ().future
            case Some(existingCert) if existingCert.contentHash != newCert.contentHash =>
              logger.info(s"updating cert. ${cert.namespace} - ${cert.name}")
              newCert.save().map(_ => ())
          }
        }
      }
    }).map(_ => ())
  }

  def syncKubernetesSecretsToOtoroshiCerts(client: KubernetesClient)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    client.fetchCertsAndFilterLabels().flatMap { certs =>
      importCerts(certs)
    }
  }
}

trait KubernetesEntity {
  def raw: JsValue
  def pretty: String = raw.prettify
  lazy val uid: String = (raw \ "metadata" \ "uid").as[String]
  lazy val name: String = (raw \ "metadata" \ "name").as[String]
  lazy val namespace: String = (raw \ "metadata" \ "namespace").as[String]
  lazy val labels: Map[String, String] = (raw \ "metadata" \ "labels").asOpt[Map[String, String]].getOrElse(Map.empty)
  lazy val annotations: Map[String, String] = (raw \ "metadata" \ "annotations").asOpt[Map[String, String]].getOrElse(Map.empty)
}
case class KubernetesService(raw: JsValue) extends KubernetesEntity
case class KubernetesEndpoint(raw: JsValue) extends KubernetesEntity

object KubernetesIngress {
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

                val serviceType = (kubeService.raw \ "spec" \ "type").as[String]
                val maybePortSpec: Option[JsValue] = (kubeService.raw \ "spec" \ "ports").as[JsArray].value.find { value =>
                  path.backend.servicePort match {
                    case IntOrString(Some(v), _) => (value \ "port").asOpt[Int].contains(v)
                    case IntOrString(_, Some(v)) => (value \ "port").asOpt[String].contains(v)
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
                            Seq(Target(s"$serviceIp:$portValue", protocol))
                          case "NodePort" =>
                            val serviceIp = (kubeService.raw \ "spec" \ "clusterIP").as[String]
                            Seq(Target(s"$serviceIp:$portValue", protocol))
                          case "LoadBalancer" =>
                            val serviceIp = (kubeService.raw \ "spec" \ "clusterIP").as[String]
                            Seq(Target(s"$serviceIp:$portValue", protocol))
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
                                Target(s"$serviceIp:$endpointPort", endpointProtocol)
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
                          groupId = conf.defaultGroup,
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
                          metadata = Map(
                            "otoroshi-service-provider" -> "kubernetes",
                            "created-at" -> creationDate,
                            "updated-at" -> DateTime.now().toString,
                            "kubernetes-name" -> name,
                            "kubernetes-namespace" -> namespace,
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
}
case class KubernetesIngress(raw: JsValue) extends KubernetesEntity {
  lazy val ingressClazz: Option[String] = annotations.get("kubernetes.io/ingress.class")
  lazy val ingress: IngressSupport.NetworkingV1beta1IngressItem = {
    IngressSupport.NetworkingV1beta1IngressItem.reader.reads(raw).get
  }
  def isValid(): Boolean = true
  def updateIngressStatus(client: KubernetesClient): Future[Unit] = {
    if (client.config.ingressEndpointPublishedService.isEmpty || (client.config.ingressEndpointHostname.isEmpty || client.config.ingressEndpointIp.isEmpty)) {
      ().future
    } else {
      client.config.ingressEndpointPublishedService match {
        case None => {
          // TODO: update with ingressEndpointHostname and ingressEndpointIp
        }
        case Some(pubService) => {
          // TODO: update with pubService
        }
      }
    }
    ().future
  }
  def asDescriptors(conf: KubernetesConfig, otoConfig: OtoAnnotationConfig, client: KubernetesClient, logger: Logger)(implicit env: Env, ec: ExecutionContext): Future[Seq[ServiceDescriptor]] = {
    KubernetesIngress.asDescriptors(this)(conf, otoConfig, client, logger)(env, ec)
  }
}
case class KubernetesDeployments(raw: JsValue) extends KubernetesEntity
case class KubernetesCertSecret(raw: JsValue) extends KubernetesEntity {
  lazy val data: JsValue = (raw \ "data").as[JsValue]
  def cert: Option[Cert] = {
    val crt = (data \ "tls.crt").asOpt[String]
    val key = (data \ "tls.key").asOpt[String]
    (crt, key) match {
      case (Some(crtData), Some(keyData)) =>
        Cert(
          "kubernetes - " + name,
          new String(DynamicSSLEngineProvider.base64Decode(crtData)),
          new String(DynamicSSLEngineProvider.base64Decode(keyData))
        ).some
      case _ => None
    }
  }
}
case class KubernetesSecret(raw: JsValue) extends KubernetesEntity {
  lazy val theType: String = (raw \ "type").as[String]
  lazy val base64Data: String = (raw \ "data").as[String]
  lazy val data = new String(OtoroshiClaim.decoder.decode(base64Data))
  def cert: KubernetesCertSecret = KubernetesCertSecret(raw)
}

class KubernetesClient(val config: KubernetesConfig, env: Env) {

  implicit val ec = env.otoroshiExecutionContext

  config.caCert.foreach { cert =>
    val caCert = Cert.apply("kubernetes-ca-cert", cert, "").copy(id = "kubernetes-ca-cert")
    DynamicSSLEngineProvider.certificates.find {
      case (k, c) => c.id == "kubernetes-ca-cert"
    } match {
      case None => caCert.enrich().save()(ec, env)
      case Some((k, c)) if c.contentHash == caCert.contentHash  => ()
      case Some((k, c)) if c.contentHash != caCert.contentHash  => caCert.enrich().save()(ec, env)
    }
  }

  private def client(url: String, wildcard: Boolean = true): WSRequest = {
    val _uri = UrlSanitizer.sanitize(config.endpoint + url)
    val uri = if (wildcard) Uri(_uri.replace("/namespaces/*", "")) else Uri(_uri)
    env.Ws.akkaUrlWithTarget(
      uri.toString(),
      Target(
        host = uri.authority.host.toString(),
        scheme = uri.scheme,
        mtlsConfig = MtlsConfig(
          mtls = true,
          loose = config.trust,
          trustAll = config.trust,
          trustedCerts = config.caCert.map(_ => Seq("kubernetes-ca-cert")).getOrElse(Seq.empty)
        )
      ),
      ClientConfig()
    ).withHttpHeaders(
      "Authorization" -> s"Bearer ${config.token}"
    )
  }
  private def filterLabels[A <: KubernetesEntity](items: Seq[A]): Seq[A] = {
    // TODO: handle kubernetes label expressions
    if (config.labels.isEmpty) {
      items
    } else {
      items.filter(i => config.labels.forall(t => i.labels.get(t._1) == t._2.some))
    }
  }
  def fetchServices(): Future[Seq[KubernetesService]] = {
    Future.sequence(config.namespaces.map { namespace =>
      val cli: WSRequest = client(s"/api/v1/namespaces/$namespace/services")
      cli.addHttpHeaders(
        "Accept" -> "application/json"
      ).get().map { resp =>
        (resp.json \ "items").as[JsArray].value.map { item =>
          KubernetesService(item)
        }
      }
    }).map(_.flatten)
  }
  def fetchService(namespace: String, name: String): Future[Option[KubernetesService]] = {
    val cli: WSRequest = client(s"/api/v1/namespaces/$namespace/services/$name", false)
    cli.addHttpHeaders(
      "Accept" -> "application/json"
    ).get().map { resp =>
      if (resp.status == 200) {
        KubernetesService(resp.json).some
      } else {
        None
      }
    }
  }
  def fetchEndpoints(): Future[Seq[KubernetesEndpoint]] = {
    Future.sequence(config.namespaces.map { namespace =>
      val cli: WSRequest = client(s"/api/v1/namespaces/$namespace/endpoints")
      cli.addHttpHeaders(
        "Accept" -> "application/json"
      ).get().map { resp =>
        (resp.json \ "items").as[JsArray].value.map { item =>
          KubernetesEndpoint(item)
        }
      }
    }).map(_.flatten)
  }
  def fetchEndpoint(namespace: String, name: String): Future[Option[KubernetesEndpoint]] = {
    val cli: WSRequest = client(s"/api/v1/namespaces/$namespace/endpoints/$name", false)
    cli.addHttpHeaders(
      "Accept" -> "application/json"
    ).get().map { resp =>
      if (resp.status == 200) {
        KubernetesEndpoint(resp.json).some
      } else {
        None
      }
    }
  }
  def fetchIngressesAndFilterLabels(): Future[Seq[KubernetesIngress]] = {
    Future.sequence(config.namespaces.map { namespace =>
      val cli: WSRequest = client(s"/apis/networking.k8s.io/v1beta1/namespaces/$namespace/ingresses")
      cli.addHttpHeaders(
        "Accept" -> "application/json"
      ).get().map { resp =>
        filterLabels((resp.json \ "items").as[JsArray].value.map { item =>
          KubernetesIngress(item)
        })
      }
    }).map(_.flatten)
  }
  def fetchIngresses(): Future[Seq[KubernetesIngress]] = {
    Future.sequence(config.namespaces.map { namespace =>
      val cli: WSRequest = client(s"/apis/networking.k8s.io/v1beta1/namespaces/$namespace/ingresses")
      cli.addHttpHeaders(
        "Accept" -> "application/json"
      ).get().map { resp =>
        (resp.json \ "items").as[JsArray].value.map { item =>
          KubernetesIngress(item)
        }
      }
    }).map(_.flatten)
  }
  def fetchDeployments(): Future[Seq[KubernetesDeployments]] = {
    Future.sequence(config.namespaces.map { namespace =>
      val cli: WSRequest = client(s"/api/v1/namespaces/$namespace/pods")
      cli.addHttpHeaders(
        "Accept" -> "application/json"
      ).get().map { resp =>
        (resp.json \ "items").as[JsArray].value.map { item =>
          KubernetesDeployments(item)
        }
      }
    }).map(_.flatten)
  }
  def fetchCerts(): Future[Seq[KubernetesCertSecret]] = {
    fetchSecrets().map(secrets => secrets.filter(_.theType == "kubernetes.io/tls").map(_.cert))
  }
  def fetchCertsAndFilterLabels(): Future[Seq[KubernetesCertSecret]] = {
    fetchSecretsAndFilterLabels().map(secrets => secrets.filter(_.theType == "kubernetes.io/tls").map(_.cert))
  }
  def fetchSecrets(): Future[Seq[KubernetesSecret]] = {
    Future.sequence(config.namespaces.map { namespace =>
      val cli: WSRequest = client(s"/api/v1/namespaces/$namespace/secrets")
      cli.addHttpHeaders(
        "Accept" -> "application/json"
      ).get().map { resp =>
        (resp.json \ "items").as[JsArray].value.map { item =>
          KubernetesSecret(item)
        }
      }
    }).map(_.flatten)
  }
  def fetchSecretsAndFilterLabels(): Future[Seq[KubernetesSecret]] = {
    Future.sequence(config.namespaces.map { namespace =>
      val cli: WSRequest = client(s"/api/v1/namespaces/$namespace/secrets")
      cli.addHttpHeaders(
        "Accept" -> "application/json"
      ).get().map { resp =>
        filterLabels((resp.json \ "items").as[JsArray].value.map { item =>
          KubernetesSecret(item)
        })
      }
    }).map(_.flatten)
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

  case class NetworkingV1beta1IngressItem(/*metadata: V1ObjectMeta, */spec: NetworkingV1beta1IngressSpec, status: NetworkingV1beta1IngressStatus)

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
      KubernetesIngress.asDescriptors("default-backend", "default-backend", namespace, ingress, conf, otoConfig, client, logger)(env, ec).map(_.headOption)
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

