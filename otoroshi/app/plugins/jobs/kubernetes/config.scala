package otoroshi.plugins.jobs.kubernetes

import java.io.File
import java.nio.file.Files

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import otoroshi.env.Env
import otoroshi.script._
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.yaml.Yaml
import play.api.libs.json._

import scala.concurrent.ExecutionContext
import scala.util.Try

case class KubernetesConfig(
    crds: Boolean,
    ingresses: Boolean,
    kubeLeader: Boolean,
    trust: Boolean,
    watch: Boolean,
    syncDaikokuApikeysOnly: Boolean,
    restartDependantDeployments: Boolean,
    coreDnsIntegration: Boolean,
    coreDnsIntegrationDryRun: Boolean,
    endpoint: String,
    token: Option[String],
    userPassword: Option[String],
    caCert: Option[String],
    namespaces: Seq[String],
    namespacesLabels: Map[String, String],
    labels: Map[String, String],
    ingressClasses: Seq[String],
    defaultGroup: String,
    ingressEndpointHostname: Option[String],
    ingressEndpointIp: Option[String],
    ingressEndpointPublishedService: Option[String],
    triggerKey: Option[String],
    triggerHost: Option[String],
    triggerPath: Option[String],
    templates: JsObject,
    kubeSystemNamespace: String,
    coreDnsConfigMapName: String,
    coreDnsDeploymentName: String,
    corednsPort: Int,
    otoroshiServiceName: String,
    otoroshiNamespace: String,
    clusterDomain: String,
    syncIntervalSeconds: Long,
    coreDnsEnv: Option[String],
    watchTimeoutSeconds: Int,
    watchGracePeriodSeconds: Int,
    mutatingWebhookName: String,
    validatingWebhookName: String,
    image: Option[String],
    meshDomain: String,
    openshiftDnsOperatorIntegration: Boolean,
    openshiftDnsOperatorCleanup: Boolean,
    openshiftDnsOperatorCleanupNames: Seq[String],
    openshiftDnsOperatorCleanupDomains: Seq[String],
    openshiftDnsOperatorCoreDnsNamespace: String,
    openshiftDnsOperatorCoreDnsName: String,
    openshiftDnsOperatorCoreDnsPort: Int,
    kubeDnsOperatorIntegration: Boolean,
    kubeDnsOperatorCoreDnsNamespace: String,
    kubeDnsOperatorCoreDnsName: String,
    kubeDnsOperatorCoreDnsPort: Int
)

object KubernetesConfig {
  import collection.JavaConverters._
  def theConfig(ctx: ContextWithConfig)(implicit env: Env, ec: ExecutionContext): KubernetesConfig = {
    val conf = ctx
      .configForOpt("KubernetesConfig")
      .orElse((env.datastores.globalConfigDataStore.latest().scripts.jobConfig \ "KubernetesConfig").asOpt[JsValue])
      .getOrElse(Json.obj())
    theConfig(conf)
  }
  def theConfig(conf: JsValue)(implicit _env: Env, ec: ExecutionContext): KubernetesConfig = {
    sys.env.get("KUBECONFIG") match {
      case Some(configPath) => {
        val configContent         = Files.readAllLines(new File(configPath).toPath).asScala.mkString("\n").trim()
        // val yamlReader = new ObjectMapper(new YAMLFactory())
        // val obj = yamlReader.readValue(configContent, classOf[Object])
        // val jsonWriter = new ObjectMapper()
        // val json = Json.parse(jsonWriter.writeValueAsString(obj))
        val json                  = Yaml.parse(configContent)
        val currentContextName    = (json \ "current-context").as[String]
        val currentContextUser    = (json \ "contexts")
          .as[JsArray]
          .value
          .find(v => (v \ "name").as[String] == currentContextName)
          .get
          .\("context")
          .\("user")
          .as[String]
        val currentContextCluster = (json \ "contexts")
          .as[JsArray]
          .value
          .find(v => (v \ "name").as[String] == currentContextName)
          .get
          .\("context")
          .\("cluster")
          .as[String]
        KubernetesConfig(
          trust = (conf \ "trust").asOpt[Boolean].getOrElse(false),
          endpoint = (json \ "clusters")
            .as[JsArray]
            .value
            .find(v => (v \ "name").as[String] == currentContextCluster)
            .map { defaultUser =>
              (defaultUser \ "cluster" \ "server").as[String]
            }
            .getOrElse(
              (conf \ "endpoint").asOpt[String].getOrElse {
                val host = sys.env("KUBERNETES_SERVICE_HOST")
                val port = sys.env("KUBERNETES_SERVICE_PORT")
                s"https://$host:$port"
              }
            ),
          token = None,
          userPassword =
            (json \ "users").as[JsArray].value.find(v => (v \ "name").as[String] == currentContextUser).map {
              defaultUser =>
                val username = (defaultUser \ "user" \ "username").as[String]
                val password = (defaultUser \ "user" \ "password").as[String]
                s"$username:$password"
            },
          caCert =
            (json \ "clusters").as[JsArray].value.find(v => (v \ "name").as[String] == currentContextCluster).map {
              defaultUser =>
                (defaultUser \ "cluster" \ "certificate-authority-data").as[String]
            },
          namespaces = (conf \ "namespaces").asOpt[Seq[String]].filter(_.nonEmpty).getOrElse(Seq("*")),
          namespacesLabels = (conf \ "namespacesLabels").asOpt[Map[String, String]].getOrElse(Map.empty),
          labels = (conf \ "labels").asOpt[Map[String, String]].getOrElse(Map.empty),
          ingressClasses = (conf \ "ingressClasses")
            .asOpt[Seq[String]]
            .orElse((conf \ "ingressClass").asOpt[String].map(v => Seq(v)))
            .getOrElse(Seq("otoroshi")), // can be *
          defaultGroup = (conf \ "defaultGroup").asOpt[String].getOrElse("default"),
          ingressEndpointHostname = (conf \ "ingressEndpointHostname").asOpt[String],
          ingressEndpointIp = (conf \ "ingressEndpointIp").asOpt[String],
          ingressEndpointPublishedService = (conf \ "ingressEndpointPublishedServices").asOpt[String],
          coreDnsIntegration = (conf \ "coreDnsIntegration").asOpt[Boolean].getOrElse(false),
          coreDnsIntegrationDryRun = (conf \ "coreDnsIntegrationDryRun").asOpt[Boolean].getOrElse(false),
          ingresses = (conf \ "ingresses").asOpt[Boolean].getOrElse(true),
          crds = (conf \ "crds").asOpt[Boolean].getOrElse(true),
          kubeLeader = (conf \ "kubeLeader").asOpt[Boolean].getOrElse(false),
          restartDependantDeployments = (conf \ "restartDependantDeployments").asOpt[Boolean].getOrElse(false),
          watch = (conf \ "watch").asOpt[Boolean].getOrElse(true),
          syncDaikokuApikeysOnly = (conf \ "syncDaikokuApikeysOnly").asOpt[Boolean].getOrElse(false),
          triggerKey = (conf \ "triggerKey").asOpt[String],
          triggerHost = (conf \ "triggerHost").asOpt[String],
          triggerPath = (conf \ "triggerPath").asOpt[String],
          templates = (conf \ "templates").asOpt[JsObject].getOrElse(Json.obj()),
          kubeSystemNamespace = (conf \ "kubeSystemNamespace").asOpt[String].getOrElse("kube-system"),
          coreDnsConfigMapName = (conf \ "coreDnsConfigMapName").asOpt[String].getOrElse("coredns"),
          coreDnsDeploymentName = (conf \ "coreDnsDeploymentName").asOpt[String].getOrElse("coredns"),
          otoroshiServiceName = (conf \ "otoroshiServiceName").asOpt[String].getOrElse("otoroshi-service"),
          otoroshiNamespace = (conf \ "otoroshiNamespace").asOpt[String].getOrElse("otoroshi"),
          corednsPort = (conf \ "corednsPort").asOpt[Int].getOrElse(53),
          clusterDomain = (conf \ "clusterDomain").asOpt[String].getOrElse("cluster.local"),
          syncIntervalSeconds = (conf \ "syncIntervalSeconds").asOpt[Long].getOrElse(60L),
          coreDnsEnv = (conf \ "coreDnsEnv").asOpt[String].filterNot(_.trim.isEmpty),
          watchTimeoutSeconds = (conf \ "watchTimeoutSeconds").asOpt[Int].getOrElse(60),
          watchGracePeriodSeconds = (conf \ "watchGracePeriodSeconds").asOpt[Int].getOrElse(5),
          mutatingWebhookName =
            (conf \ "mutatingWebhookName").asOpt[String].getOrElse("otoroshi-admission-webhook-injector"),
          validatingWebhookName =
            (conf \ "validatingWebhookName").asOpt[String].getOrElse("otoroshi-admission-webhook-validation"),
          image = (conf \ "image").asOpt[String].filter(_.trim.nonEmpty),
          meshDomain = (conf \ "meshDomain").asOpt[String].filter(_.trim.nonEmpty).getOrElse("otoroshi.mesh"),
          openshiftDnsOperatorIntegration = (conf \ "openshiftDnsOperatorIntegration").asOpt[Boolean].getOrElse(false),
          openshiftDnsOperatorCoreDnsNamespace =
            (conf \ "openshiftDnsOperatorCoreDnsNamespace").asOpt[String].getOrElse("otoroshi"),
          openshiftDnsOperatorCoreDnsName =
            (conf \ "openshiftDnsOperatorCoreDnsName").asOpt[String].getOrElse("otoroshi-dns"),
          openshiftDnsOperatorCoreDnsPort = (conf \ "openshiftDnsOperatorCoreDnsPort").asOpt[Int].getOrElse(5353),
          openshiftDnsOperatorCleanup = (conf \ "openshiftDnsOperatorCleanup").asOpt[Boolean].getOrElse(false),
          openshiftDnsOperatorCleanupNames =
            (conf \ "openshiftDnsOperatorCleanupNames").asOpt[Seq[String]].getOrElse(Seq.empty),
          openshiftDnsOperatorCleanupDomains =
            (conf \ "openshiftDnsOperatorCleanupDomains").asOpt[Seq[String]].getOrElse(Seq.empty),
          kubeDnsOperatorIntegration = (conf \ "kubetDnsOperatorIntegration").asOpt[Boolean].getOrElse(false),
          kubeDnsOperatorCoreDnsNamespace =
            (conf \ "kubetDnsOperatorCoreDnsNamespace").asOpt[String].getOrElse("otoroshi"),
          kubeDnsOperatorCoreDnsName = (conf \ "kubetDnsOperatorCoreDnsName").asOpt[String].getOrElse("otoroshi-dns"),
          kubeDnsOperatorCoreDnsPort = (conf \ "kubetDnsOperatorCoreDnsPort").asOpt[Int].getOrElse(5353)
        )
      }
      case None             => {
        KubernetesConfig(
          trust = (conf \ "trust").asOpt[Boolean].getOrElse(false),
          endpoint = (conf \ "endpoint").asOpt[String].getOrElse {
            val host = sys.env("KUBERNETES_SERVICE_HOST")
            val port = sys.env("KUBERNETES_SERVICE_PORT")
            s"https://$host:$port"
          },
          token = (conf \ "token")
            .asOpt[String]
            .orElse(
              Try(
                Files
                  .readAllLines(new File("/var/run/secrets/kubernetes.io/serviceaccount/token").toPath)
                  .asScala
                  .mkString("\n")
                  .trim()
              ).toOption
            ),
          userPassword = (conf \ "userPassword").asOpt[String],
          caCert = (conf \ "cert")
            .asOpt[String]
            .orElse((conf \ "certPath").asOpt[String].map { path =>
              Files.readAllLines(new File(path).toPath).asScala.mkString("\n").trim()
            })
            .orElse(
              new File("/var/run/secrets/kubernetes.io/serviceaccount/ca.crt").some
                .filter(_.exists())
                .map(f => Files.readAllLines(f.toPath).asScala.mkString("\n").trim())
            ),
          namespaces = (conf \ "namespaces").asOpt[Seq[String]].filter(_.nonEmpty).getOrElse(Seq("*")),
          namespacesLabels = (conf \ "namespacesLabels").asOpt[Map[String, String]].getOrElse(Map.empty),
          labels = (conf \ "labels").asOpt[Map[String, String]].getOrElse(Map.empty),
          ingressClasses = (conf \ "ingressClasses")
            .asOpt[Seq[String]]
            .orElse((conf \ "ingressClass").asOpt[String].map(v => Seq(v)))
            .getOrElse(Seq("otoroshi")), // can be *
          defaultGroup = (conf \ "defaultGroup").asOpt[String].getOrElse("default"),
          ingressEndpointHostname = (conf \ "ingressEndpointHostname").asOpt[String],
          ingressEndpointIp = (conf \ "ingressEndpointIp").asOpt[String],
          ingressEndpointPublishedService = (conf \ "ingressEndpointPublishedServices").asOpt[String],
          ingresses = (conf \ "ingresses").asOpt[Boolean].getOrElse(true),
          coreDnsIntegration = (conf \ "coreDnsIntegration").asOpt[Boolean].getOrElse(false),
          coreDnsIntegrationDryRun = (conf \ "coreDnsIntegrationDryRun").asOpt[Boolean].getOrElse(false),
          crds = (conf \ "crds").asOpt[Boolean].getOrElse(true),
          kubeLeader = (conf \ "kubeLeader").asOpt[Boolean].getOrElse(false),
          restartDependantDeployments = (conf \ "restartDependantDeployments").asOpt[Boolean].getOrElse(false),
          watch = (conf \ "watch").asOpt[Boolean].getOrElse(true),
          syncDaikokuApikeysOnly = (conf \ "syncDaikokuApikeysOnly").asOpt[Boolean].getOrElse(false),
          triggerKey = (conf \ "triggerKey").asOpt[String],
          triggerHost = (conf \ "triggerHost").asOpt[String],
          triggerPath = (conf \ "triggerPath").asOpt[String],
          templates = (conf \ "templates").asOpt[JsObject].getOrElse(Json.obj()),
          kubeSystemNamespace = (conf \ "kubeSystemNamespace").asOpt[String].getOrElse("kube-system"),
          coreDnsConfigMapName = (conf \ "coreDnsConfigMapName").asOpt[String].getOrElse("coredns"),
          coreDnsDeploymentName = (conf \ "coreDnsDeploymentName").asOpt[String].getOrElse("coredns"),
          otoroshiServiceName = (conf \ "otoroshiServiceName").asOpt[String].getOrElse("otoroshi-service"),
          otoroshiNamespace = (conf \ "otoroshiNamespace").asOpt[String].getOrElse("otoroshi"),
          corednsPort = (conf \ "corednsPort").asOpt[Int].getOrElse(53),
          clusterDomain = (conf \ "clusterDomain").asOpt[String].getOrElse("cluster.local"),
          syncIntervalSeconds = (conf \ "syncIntervalSeconds").asOpt[Long].getOrElse(60L),
          coreDnsEnv = (conf \ "coreDnsEnv").asOpt[String].filterNot(_.trim.isEmpty),
          watchTimeoutSeconds = (conf \ "watchTimeoutSeconds").asOpt[Int].getOrElse(60),
          watchGracePeriodSeconds = (conf \ "watchGracePeriodSeconds").asOpt[Int].getOrElse(5),
          mutatingWebhookName =
            (conf \ "mutatingWebhookName").asOpt[String].getOrElse("otoroshi-admission-webhook-injector"),
          validatingWebhookName =
            (conf \ "validatingWebhookName").asOpt[String].getOrElse("otoroshi-admission-webhook-validation"),
          image = (conf \ "image").asOpt[String].filter(_.trim.nonEmpty),
          meshDomain = (conf \ "meshDomain").asOpt[String].filter(_.trim.nonEmpty).getOrElse("otoroshi.mesh"),
          openshiftDnsOperatorIntegration = (conf \ "openshiftDnsOperatorIntegration").asOpt[Boolean].getOrElse(false),
          openshiftDnsOperatorCoreDnsNamespace =
            (conf \ "openshiftDnsOperatorCoreDnsNamespace").asOpt[String].getOrElse("otoroshi"),
          openshiftDnsOperatorCoreDnsName =
            (conf \ "openshiftDnsOperatorCoreDnsName").asOpt[String].getOrElse("otoroshi-dns"),
          openshiftDnsOperatorCoreDnsPort = (conf \ "openshiftDnsOperatorCoreDnsPort").asOpt[Int].getOrElse(5353),
          openshiftDnsOperatorCleanup = (conf \ "openshiftDnsOperatorCleanup").asOpt[Boolean].getOrElse(false),
          openshiftDnsOperatorCleanupNames =
            (conf \ "openshiftDnsOperatorCleanupNames").asOpt[Seq[String]].getOrElse(Seq.empty),
          openshiftDnsOperatorCleanupDomains =
            (conf \ "openshiftDnsOperatorCleanupDomains").asOpt[Seq[String]].getOrElse(Seq.empty),
          kubeDnsOperatorIntegration = (conf \ "kubetDnsOperatorIntegration").asOpt[Boolean].getOrElse(false),
          kubeDnsOperatorCoreDnsNamespace =
            (conf \ "kubetDnsOperatorCoreDnsNamespace").asOpt[String].getOrElse("otoroshi"),
          kubeDnsOperatorCoreDnsName = (conf \ "kubetDnsOperatorCoreDnsName").asOpt[String].getOrElse("otoroshi-dns"),
          kubeDnsOperatorCoreDnsPort = (conf \ "kubetDnsOperatorCoreDnsPort").asOpt[Int].getOrElse(5353)
        )
      }
    }
  }
  def defaultConfig: JsObject = {
    Json.obj(
      "KubernetesConfig" -> Json.obj(
        "endpoint"                             -> "https://kube.cluster.dev",
        "token"                                -> "xxx",
        "userPassword"                         -> "user:password",
        "caCert"                               -> "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt",
        "trust"                                -> false,
        "namespaces"                           -> Json.arr("*"),
        "labels"                               -> Json.obj(),
        "namespacesLabels"                     -> Json.obj(),
        "ingressClasses"                       -> Json.arr("otoroshi"),
        "defaultGroup"                         -> "default",
        "ingresses"                            -> true,
        "crds"                                 -> true,
        "coreDnsIntegration"                   -> false,
        "coreDnsIntegrationDryRun"             -> false,
        "kubeLeader"                           -> false,
        "restartDependantDeployments"          -> true,
        "watch"                                -> true,
        "syncDaikokuApikeysOnly"               -> false,
        "kubeSystemNamespace"                  -> "kube-system",
        "coreDnsConfigMapName"                 -> "coredns",
        "coreDnsDeploymentName"                -> "coredns",
        "corednsPort"                          -> 53,
        "otoroshiServiceName"                  -> "otoroshi-service",
        "otoroshiNamespace"                    -> "otoroshi",
        "clusterDomain"                        -> "cluster.local",
        "syncIntervalSeconds"                  -> 60,
        "coreDnsEnv"                           -> JsNull,
        "watchTimeoutSeconds"                  -> 60,
        "watchGracePeriodSeconds"              -> 5,
        "mutatingWebhookName"                  -> "otoroshi-admission-webhook-injector",
        "validatingWebhookName"                -> "otoroshi-admission-webhook-validation",
        "meshDomain"                           -> "otoroshi.mesh",
        "openshiftDnsOperatorIntegration"      -> false,
        "openshiftDnsOperatorCoreDnsNamespace" -> "otoroshi",
        "openshiftDnsOperatorCoreDnsName"      -> "otoroshi-dns",
        "openshiftDnsOperatorCoreDnsPort"      -> 5353,
        "kubeDnsOperatorIntegration"           -> false,
        "kubeDnsOperatorCoreDnsNamespace"      -> "otoroshi",
        "kubeDnsOperatorCoreDnsName"           -> "otoroshi-dns",
        "kubeDnsOperatorCoreDnsPort"           -> 5353,
        "templates"                            -> Json.obj(
          "service-group"      -> Json.obj(),
          "service-descriptor" -> Json.obj(),
          "apikeys"            -> Json.obj(),
          "global-config"      -> Json.obj(),
          "jwt-verifier"       -> Json.obj(),
          "tcp-service"        -> Json.obj(),
          "certificate"        -> Json.obj(),
          "auth-module"        -> Json.obj(),
          "script"             -> Json.obj(),
          "data-exporters"     -> Json.obj(),
          "organizations"      -> Json.obj(),
          "teams"              -> Json.obj(),
          "admins"             -> Json.obj(),
          "webhooks"           -> Json.obj()
        )
      )
    )
  }
}
