package otoroshi.plugins.jobs.kubernetes

import otoroshi.env.Env
import models.ServiceDescriptor
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import otoroshi.security.OtoroshiClaim
import otoroshi.ssl.{Cert, DynamicSSLEngineProvider}
import otoroshi.utils.syntax.implicits._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

trait KubernetesEntity {
  def raw: JsValue
  def pretty: String = raw.prettify
  lazy val spec: JsObject = (raw \ "spec").asOpt[JsObject].getOrElse(Json.obj())
  lazy val uid: String = (raw \ "metadata" \ "uid").as[String]
  lazy val name: String = (raw \ "metadata" \ "name").as[String]
  lazy val metaKind: Option[String] = annotations.get("io.otoroshi/kind").orElse(annotations.get("otoroshi.io/kind"))
  lazy val metaId: Option[String] = annotations.get("io.otoroshi/id").orElse(annotations.get("otoroshi.io/id"))
  lazy val namespace: String = (raw \ "metadata" \ "namespace").as[String]
  lazy val path: String = s"$namespace/$name"
  lazy val labels: Map[String, String] = (raw \ "metadata" \ "labels").asOpt[Map[String, String]].getOrElse(Map.empty)
  lazy val annotations: Map[String, String] = (raw \ "metadata" \ "annotations").asOpt[Map[String, String]].getOrElse(Map.empty)
}

case class KubernetesNamespace(raw: JsValue) extends KubernetesEntity
case class KubernetesService(raw: JsValue) extends KubernetesEntity {
  lazy val clusterIP: String = (raw \ "spec" \ "clusterIP").as[String]
}
case class KubernetesConfigMap(raw: JsValue) extends KubernetesEntity {
  lazy val rawObj = raw.as[JsObject]
  lazy val corefile: String = (raw \ "data" \ "Corefile").as[String]
  lazy val data: JsObject = (raw \ "data").asOpt[JsObject].getOrElse(Json.obj())
  lazy val stubDomains: JsObject = (data \ "stubDomains").asOpt[String].flatMap(str => Json.parse(str).asOpt[JsObject]).getOrElse(Json.obj())
  def hasOtoroshiMesh(conf: KubernetesConfig): Boolean = {
    val coreDnsNameEnv = conf.coreDnsEnv.map(e => s"$e-").getOrElse("")
    (raw \ "data" \ "Corefile").asOpt[String] match {
      case None => true // because Corefile should be there, so avoid to do something wrong
      case Some(coreFile) if coreFile.contains(s"### otoroshi-${coreDnsNameEnv}mesh-begin ###") && coreFile.contains(s"### otoroshi-${coreDnsNameEnv}mesh-end ###") => true
      case Some(_) => false
    }
  }
}

case class KubernetesValidatingWebhookConfiguration(raw: JsValue) extends KubernetesEntity {
  lazy val webhooks: JsArray = (raw \ "webhooks").as[JsArray]
}

case class KubernetesMutatingWebhookConfiguration(raw: JsValue) extends KubernetesEntity {
  lazy val webhooks: JsArray = (raw \ "webhooks").as[JsArray]
}

case class KubernetesOpenshiftDnsOperatorServer(raw: JsValue) {
  lazy val name: String = raw.select("name").as[String]
  lazy val zones: Seq[String] = raw.select("zones").asOpt[Seq[String]].getOrElse(Seq.empty)
  lazy val forwardPluginUpstreams: Seq[String] = raw.select("forwardPlugin").select("upstreams").asOpt[Seq[String]].getOrElse(Seq.empty)
}

case class KubernetesOpenshiftDnsOperator(raw: JsValue) extends KubernetesEntity {
  lazy val servers = (raw \ "spec" \ "servers").asOpt[JsArray].map(_.value.map(KubernetesOpenshiftDnsOperatorServer.apply)).getOrElse(Seq.empty)
}

case class KubernetesEndpoint(raw: JsValue) extends KubernetesEntity

case class KubernetesOtoroshiResource(raw: JsValue) extends KubernetesEntity

case class KubernetesIngressClassParameters(raw: JsValue) {
  lazy val apiGroup: String = (raw \ "apiGroup").as[String]
  lazy val kind: String = (raw \ "kind").as[String]
  lazy val name: String = (raw \ "name").as[String]
}

case class KubernetesIngressClass(raw: JsValue) extends KubernetesEntity {
  lazy val controller: String = (spec \ "controller").as[String]
  lazy val parameters: KubernetesIngressClassParameters = KubernetesIngressClassParameters((spec \ "parameters").as[JsValue])
  lazy val isDefault: Boolean = annotations.get("ingressclass.kubernetes.io/is-default-class").map(_ == "true").getOrElse(false)
}

case class KubernetesIngress(raw: JsValue) extends KubernetesEntity {
  lazy val ingressClazz: Option[String] = annotations.get("kubernetes.io/ingress.class").orElse(spec.select("ingressClassName").asOpt[String])
  lazy val ingressClassName: Option[String] = spec.select("ingressClassName").asOpt[String]
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
    KubernetesIngressToDescriptor.asDescriptors(this)(conf, otoConfig, client, logger)(env, ec)
  }
}

case class KubernetesPod(raw: JsValue) extends KubernetesEntity
case class KubernetesDeployment(raw: JsValue) extends KubernetesEntity

case class KubernetesCertSecret(raw: JsValue) extends KubernetesEntity {
  lazy val data: JsValue = (raw \ "data").as[JsValue]
  lazy val isOtoCert: Boolean = metaKind.isDefined
  def cert: Option[Cert] = Try {
    val crt = (data \ "tls.crt").asOpt[String]
    val key = (data \ "tls.key").asOpt[String]
    (crt, key) match {
      case (Some(crtData), keyDataOpt) =>
        Cert(
          "kubernetes - " + name,
          new String(DynamicSSLEngineProvider.base64Decode(crtData)),
          new String(DynamicSSLEngineProvider.base64Decode(keyDataOpt.getOrElse("")))
        ).some
      case _ => None
    }
  }.toOption.flatten
}

case class KubernetesSecret(raw: JsValue) extends KubernetesEntity {
  lazy val theType: String = (raw \ "type").as[String]
  lazy val base64Data: String = (raw \ "data").as[String]
  lazy val data = new String(OtoroshiClaim.decoder.decode(base64Data))
  def cert: KubernetesCertSecret = KubernetesCertSecret(raw)
}

case class OtoResHolder[T](raw: JsValue, typed: T) extends KubernetesEntity
