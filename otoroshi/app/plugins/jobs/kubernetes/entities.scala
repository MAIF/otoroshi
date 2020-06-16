package otoroshi.plugins.jobs.kubernetes

import env.Env
import models.ServiceDescriptor
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json.JsValue
import security.OtoroshiClaim
import ssl.{Cert, DynamicSSLEngineProvider}

import scala.concurrent.{ExecutionContext, Future}

trait KubernetesEntity {
  def raw: JsValue
  def pretty: String = raw.prettify
  lazy val uid: String = (raw \ "metadata" \ "uid").as[String]
  lazy val name: String = (raw \ "metadata" \ "name").as[String]
  lazy val metaId: Option[String] = (raw \ "metadata" \ "io.otoroshi/id").asOpt[String]
  lazy val namespace: String = (raw \ "metadata" \ "namespace").as[String]
  lazy val path: String = s"$namespace/$name"
  lazy val labels: Map[String, String] = (raw \ "metadata" \ "labels").asOpt[Map[String, String]].getOrElse(Map.empty)
  lazy val annotations: Map[String, String] = (raw \ "metadata" \ "annotations").asOpt[Map[String, String]].getOrElse(Map.empty)
}

case class KubernetesService(raw: JsValue) extends KubernetesEntity

case class KubernetesEndpoint(raw: JsValue) extends KubernetesEntity

case class KubernetesOtoroshiResource(raw: JsValue) extends KubernetesEntity

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
    KubernetesIngressToDescriptor.asDescriptors(this)(conf, otoConfig, client, logger)(env, ec)
  }
}

case class KubernetesPod(raw: JsValue) extends KubernetesEntity
case class KubernetesDeployment(raw: JsValue) extends KubernetesEntity

case class KubernetesCertSecret(raw: JsValue) extends KubernetesEntity {
  lazy val data: JsValue = (raw \ "data").as[JsValue]
  def cert: Option[Cert] = {
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
  }
}

case class KubernetesSecret(raw: JsValue) extends KubernetesEntity {
  lazy val theType: String = (raw \ "type").as[String]
  lazy val base64Data: String = (raw \ "data").as[String]
  lazy val data = new String(OtoroshiClaim.decoder.decode(base64Data))
  def cert: KubernetesCertSecret = KubernetesCertSecret(raw)
}

case class OtoResHolder[T](raw: JsValue, typed: T) extends KubernetesEntity
