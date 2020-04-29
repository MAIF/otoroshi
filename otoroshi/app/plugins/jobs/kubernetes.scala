package otoroshi.plugins.jobs.kubernetes

import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.{Sink, Source}
import env.Env
import models.{ClientConfig, ServiceDescriptor, Target}
import org.joda.time.DateTime
import otoroshi.script._
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.libs.ws.WSRequest
import play.api.mvc.{Result, Results}
import security.{IdGenerator, OtoroshiClaim}
import ssl.{Cert, DynamicSSLEngineProvider, PemHeaders}
import utils.{TypedMap, UrlSanitizer}
import utils.http.MtlsConfig
import utils.RequestImplicits._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object KubernetesConfig {
  def theConfig(ctx: ContextWithConfig)(implicit env: Env, ec: ExecutionContext): KubernetesConfig = {
    val conf = ctx.configForOpt("KubernetesConfig").orElse((env.datastores.globalConfigDataStore.latest().scripts.jobConfig \ "KubernetesConfig").asOpt[JsValue]).getOrElse(Json.obj())
    KubernetesConfig(
      enabled = (conf \ "enabled").as[Boolean],
      endpoint = (conf \ "endpoint").asOpt[String].getOrElse("https://kube.cluster.dev"),
      token = (conf \ "token").asOpt[String].getOrElse("xxx"),
      namespaces = (conf \ "namespaces").asOpt[Seq[String]].getOrElse(Seq("*")),
      labels = (conf \ "labels").asOpt[Map[String, String]].getOrElse(Map.empty),
      ingressClass = (conf \ "ingressClass").asOpt[String],
      defaultGroup = (conf \ "defaultGroup").asOpt[String].getOrElse("default")
    )
  }
}

case class KubernetesConfig(enabled: Boolean, endpoint: String, token: String, namespaces: Seq[String], labels: Map[String, String], ingressClass: Option[String], defaultGroup: String)

// https://kubernetes.io/fr/docs/concepts/services-networking/ingress/
class KubernetesIngressControllerJob extends Job {

  override def uniqueId: JobId = JobId("io.otoroshi.plugins.jobs.kubernetes.KubernetesIngressController")

  override def name: String = "Kubernetes Ingress Controller"

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        "KubernetesConfig" -> Json.obj(
          "enabled"                -> false,
          "endpoint" ->   "https://kube.cluster.dev",
          "token" -> "xxx",
          "namespaces" -> JsArray(),
        )
      )
    )

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
      KubernetesIngressSyncJob.syncIngresses(conf, ctx.attrs)
    } else {
      ().future
    }
  }
}

case class OtoAnnotationConfig() {
  def apply(desc: ServiceDescriptor): ServiceDescriptor = desc
}

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

  def importCerts(client: KubernetesClient)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    client.fetchCerts().flatMap { certs =>
      Future.sequence(certs.map { cert =>
        cert.cert match {
          case None => ().future
          case Some(found) => {
            val certId = s"kubernetes_secrets_${cert.namespace}_${cert.name}" // TODO: add cluster id ????
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
  }

  private def shouldProcessIngress(ingressClass: Option[String], ingressClassAnnotation: Option[String]): Boolean = {
    println(ingressClass, ingressClassAnnotation)
    ingressClass match {
      case None if ingressClassAnnotation.isEmpty => true
      case None => ingressClassAnnotation == "otoroshi".some
      case Some(v) if ingressClassAnnotation.isDefined => v == ingressClassAnnotation.get
      case Some(v) if ingressClassAnnotation.isEmpty => true
      case _ => true
    }
  }

  private def parseConfig(annotations: Map[String, String]): OtoAnnotationConfig = {
    OtoAnnotationConfig() // TODO: manage it
    /*
      stripPath =,
      privateApp =,
      forceHttps =,
      maintenanceMode =,
      buildMode =,
      sendOtoroshiHeadersBack =,
      readOnly =,
      xForwardedHeaders =,
      overrideHost =,
      allowHttp10 =,

        publicPatterns =,
        privatePatterns =,
        additionalHeaders =,
        additionalHeadersOut =,
        missingOnlyHeadersIn =,
        missingOnlyHeadersOut =,
        removeHeadersIn =,
        removeHeadersOut =,
        headersVerification =,
        matchingHeaders =,
         healthCheck =,
        clientConfig =,
                      targetsLoadBalancing =,

      */
  }

  def syncIngresses(conf: KubernetesConfig, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    implicit val mat = env.otoroshiMaterializer
    logger.info("Starting kubernetes sync.")
    val client = new KubernetesClient(conf, env)
    importCerts(client).flatMap { _ =>
      client.fetchIngress().flatMap { ingresses =>
        Source(ingresses.toList)
            .mapAsync(1) { ingressRaw =>
              if (shouldProcessIngress(conf.ingressClass, ingressRaw.ingressClazz)) {
                println(1)
                val otoroshiConfig = parseConfig(ingressRaw.annotations)
                if (ingressRaw.isValid()) {
                  println(2)
                  // TODO: handle case where ingress has a default backend
                  ingressRaw.updateIngressStatus(client).flatMap { _ =>
                    println(3)
                    ingressRaw.asDescriptors(conf, otoroshiConfig).flatMap { descs =>
                      println(4)
                      Future.sequence(descs.map(_.save()))
                    }
                  }
                } else {
                  println(5)
                  ().future
                }
              } else {
                println(6)
                ().future
              }
            }.runWith(Sink.ignore).map(_ => ())
        }
      }
  }
}

object KubernetesCertSyncJob {
  def syncOtoroshiCertsToKubernetesSecrets(): Future[Unit] = ???
  def syncKubernetesSecretsToOtoroshiCerts(): Future[Unit] = ???
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
case class KubernetesIngress(raw: JsValue) extends KubernetesEntity {
  lazy val ingressClazz: Option[String] = annotations.get("kubernetes.io/ingress.class")
  lazy val ingress: IngressSupport.NetworkingV1beta1IngressItem = {
    // println(raw.prettify)
    IngressSupport.NetworkingV1beta1IngressItem.reader.reads(raw).get
  }
  def isValid(): Boolean = true // TODO:
  def updateIngressStatus(client: KubernetesClient): Future[Unit] = ().future // TODO:
  def asDescriptors(conf: KubernetesConfig, otoConfig: OtoAnnotationConfig)(implicit env: Env, ec: ExecutionContext): Future[Seq[ServiceDescriptor]] = {
    Future.sequence(
      ingress.spec.rules.flatMap { rule =>
        rule.http.paths.map { path =>
          val id = "kubernetes_service_" + namespace + "_" + name + "_" + rule.host.getOrElse("wildcard") + "_" + path.path.getOrElse("-")
          println(id)
          env.datastores.serviceDescriptorDataStore.findById(id).map {
            case None => env.datastores.serviceDescriptorDataStore.initiateNewDescriptor()
            case Some(desc) => desc
          }.map { desc =>
            desc.copy(
              id = id,
              groupId = conf.defaultGroup,
              name = "kubernetes - " + name + " - " + rule.host.getOrElse("*") + " - " + path.path.getOrElse("/"),
              env = "prod",
              domain = "internal.kube.cluster",
              subdomain = id,
              targets = Seq(Target("www.google.fr", "https")),
              root = path.path.getOrElse("/"),
              matchingRoot = path.path,
              metadata = Map.empty,
              hosts = Seq(rule.host.getOrElse("*")),
              paths = path.path.toSeq
            )
          }.map { desc =>
            otoConfig.apply(desc)
          }
        }
      }
    )
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

class KubernetesClient(config: KubernetesConfig, env: Env) {

  implicit val ec = env.otoroshiExecutionContext

  private def client(url: String): WSRequest = {
    val uri = Uri(UrlSanitizer.sanitize(config.endpoint + url).replace("/namespaces/*", ""))
    env.Ws.akkaUrlWithTarget(
      uri.toString(),
      Target(
        host = uri.authority.host.toString(),
        scheme = uri.scheme,
        mtlsConfig = MtlsConfig(
          mtls = true,
          loose = true,
          trustAll = true
        )
      ),
      ClientConfig()
    ).withHttpHeaders(
      "Authorization" -> s"Bearer ${config.token}"
    )
  }
  private def filterLabels[A <: KubernetesEntity](items: Seq[A]): Seq[A] = {
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
        filterLabels((resp.json \ "items").as[JsArray].value.map { item =>
          KubernetesService(item)
        })
      }
    }).map(_.flatten)
  }
  def fetchEndpoints(): Future[Seq[KubernetesEndpoint]] = {
    Future.sequence(config.namespaces.map { namespace =>
      val cli: WSRequest = client(s"/api/v1/namespaces/$namespace/endpoints")
      cli.addHttpHeaders(
        "Accept" -> "application/json"
      ).get().map { resp =>
        filterLabels((resp.json \ "items").as[JsArray].value.map { item =>
          KubernetesEndpoint(item)
        })
      }
    }).map(_.flatten)
  }
  def fetchIngress(): Future[Seq[KubernetesIngress]] = {
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
  def fetchDeployments(): Future[Seq[KubernetesDeployments]] = {
    Future.sequence(config.namespaces.map { namespace =>
      val cli: WSRequest = client(s"/api/v1/namespaces/$namespace/pods")
      cli.addHttpHeaders(
        "Accept" -> "application/json"
      ).get().map { resp =>
        filterLabels((resp.json \ "items").as[JsArray].value.map { item =>
          KubernetesDeployments(item)
        })
      }
    }).map(_.flatten)
  }
  def fetchCerts(): Future[Seq[KubernetesCertSecret]] = {
    fetchSecrets().map(secrets => secrets.filter(_.theType == "kubernetes.io/tls").map(_.cert))
  }
  def fetchSecrets(): Future[Seq[KubernetesSecret]] = {
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
    def actualValue(): Int = ??? // TODO:
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

  case class NetworkingV1beta1IngressBackend(serviceName: String, servicePort: IntOrString)

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

