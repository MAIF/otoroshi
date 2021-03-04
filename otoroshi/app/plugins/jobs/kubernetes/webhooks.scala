package otoroshi.plugins.jobs.kubernetes

import akka.util.ByteString
import auth.AuthModuleConfig
import env.Env
import models._
import otoroshi.models.{DataExporterConfig, SimpleOtoroshiAdmin, Team, Tenant}
import otoroshi.script.{RequestOrigin, RequestSink, RequestSinkContext, Script}
import otoroshi.tcp.TcpService
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.yaml.Yaml
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{Result, Results}
import otoroshi.ssl.Cert

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class KubernetesAdmissionWebhookCRDValidator extends RequestSink {

  val logger = Logger("otoroshi-crd-validator")

  override def name: String = "Kubernetes admission validator webhook"

  override def description: Option[String] =
    Some(
      s"""This plugin exposes a webhook to kubernetes to handle manifests validation
    """.stripMargin
    )

  override def matches(ctx: RequestSinkContext)(implicit env: Env, ec: ExecutionContext): Boolean = {
    val config = KubernetesConfig.theConfig(ctx)
    (
      ctx.request.domain.contentEquals(s"${config.otoroshiServiceName}.${config.otoroshiNamespace}.svc.${config.clusterDomain}") ||
      ctx.request.domain.contentEquals(s"${config.otoroshiServiceName}.${config.otoroshiNamespace}.svc")
    ) &&
      ctx.request.path.contentEquals("/apis/webhooks/validation") &&
      ctx.origin == RequestOrigin.ReverseProxy &&
      ctx.request.method == "POST"
  }

  def success(uid: String): Future[Result] = {
    Results.Ok(Json.obj(
      "apiVersion" -> "admission.k8s.io/v1",
      "kind" -> "AdmissionReview",
      "response" -> Json.obj(
        "uid" -> uid,
        "allowed" -> true
      )
    )).future
  }

  def error(uid: String, errors: Seq[(JsPath, Seq[JsonValidationError])]): Future[Result] = {
    Results.Ok(Json.obj(
      "apiVersion" -> "admission.k8s.io/v1",
      "kind" -> "AdmissionReview",
      "response" -> Json.obj(
        "uid" -> uid,
        "allowed" -> false,
        "status" -> Json.obj(
          "code" -> 400,
          "message" -> s"Entity format errors: \n\n${errors.flatMap(err => err._2.map(verr => s" * ${err._1.toString()}: ${verr.message}")).mkString("\n")}"
        )
      )
    )).future
  }

  def regCert(arg1: String, arg2: String, arg3: Cert): Unit = ()
  def regApk(arg1: String, arg2: String, arg3: ApiKey): Unit = ()

  override def handle(ctx: RequestSinkContext)(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    implicit val mat = env.otoroshiMaterializer
    ctx.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
      val json: JsValue = ctx.request.contentType match {
        case Some(v) if v.contains("application/json") => Json.parse(bodyRaw.utf8String)
        case Some(v) if v.contains("application/yaml") => Yaml.parse(bodyRaw.utf8String)
        case _ => Json.parse(bodyRaw.utf8String)
      }
      val operation = (json \ "request" \ "operation").as[String]
      val obj = (json \ "request" \ "object").as[JsObject]
      val uid = (json \ "request" \ "uid").as[String]
      val version = (obj \ "apiVersion").as[String]
      val kind = (obj \ "kind").as[String]
      if (version.startsWith("proxy.otoroshi.io/") && operation == "UPDATE" || operation == "CREATE" ) {
        val client = new ClientSupport(new KubernetesClient(KubernetesConfig.theConfig(ctx), env), logger)
        kind match {
          case "ServiceGroup" => {
            env.datastores.serviceGroupDataStore.findAll().flatMap { groups =>
              val res = KubernetesOtoroshiResource(obj)
              val json = client.customizeServiceGroup(res.spec, res, groups)
              ServiceGroup._fmt.reads(json) match {
                case JsSuccess(_, _) => success(uid)
                case JsError(errors) => error(uid, errors)
              }
            }
          }
          case "Organization" => {
            env.datastores.tenantDataStore.findAll().flatMap { tenants =>
              val res = KubernetesOtoroshiResource(obj)
              val json = client.customizeTenant(res.spec, res, tenants)
              Tenant.format.reads(json) match {
                case JsSuccess(_, _) => success(uid)
                case JsError(errors) => error(uid, errors)
              }
            }
          }
          case "Team" => {
            env.datastores.teamDataStore.findAll().flatMap { teams =>
              val res = KubernetesOtoroshiResource(obj)
              val json = client.customizeTeam(res.spec, res, teams)
              Team.format.reads(json) match {
                case JsSuccess(_, _) => success(uid)
                case JsError(errors) => error(uid, errors)
              }
            }
          }
          case "ServiceDescriptor" => {
            env.datastores.serviceDescriptorDataStore.findAll().flatMap { services =>
              client.client.fetchServices().flatMap { kubeServices =>
                client.client.fetchEndpoints().flatMap { kubeEndpoints =>
                  val res = KubernetesOtoroshiResource(obj)
                  val json = client.customizeServiceDescriptor(res.spec, res, kubeServices, kubeEndpoints, services, client.client.config)
                  ServiceDescriptor._fmt.reads(json) match {
                    case JsSuccess(_, _) => success(uid)
                    case JsError(errors) => error(uid, errors)
                  }
                }
              }
            }
          }
          case "ApiKey" => {
            env.datastores.apiKeyDataStore.findAll().flatMap { entities =>
              client.client.fetchSecrets().flatMap { secrets =>
                val res = KubernetesOtoroshiResource(obj)
                val json = client.customizeApiKey(res.spec, res, secrets, entities, regApk)
                ApiKey._fmt.reads(json) match {
                  case JsSuccess(_, _) => success(uid)
                  case JsError(errors) => error(uid, errors)
                }
              }
            }
          }
          case "GlobalConfig" => {
            env.datastores.globalConfigDataStore.singleton().flatMap { entities =>
              val res = KubernetesOtoroshiResource(obj)
              val json = client.customizeGlobalConfig(res.spec, res, entities)
              GlobalConfig._fmt.reads(json) match {
                case JsSuccess(_, _) => success(uid)
                case JsError(errors) => error(uid, errors)
              }
            }
          }
          case "Certificate" => {
            env.datastores.certificatesDataStore.findAll().flatMap { entities =>
              val res = KubernetesOtoroshiResource(obj)
              val json = client.customizeCert(res.spec, res, entities, regCert)
              Cert._fmt.reads(json) match {
                case JsSuccess(_, _) => success(uid)
                case JsError(errors) => error(uid, errors)
              }
            }
          }
          case "JwtVerifier" => {
            env.datastores.globalJwtVerifierDataStore.findAll().flatMap { entities =>
              val res = KubernetesOtoroshiResource(obj)
              val json = client.customizeJwtVerifier(res.spec, res, entities)
              GlobalJwtVerifier._fmt.reads(json) match {
                case JsSuccess(_, _) => success(uid)
                case JsError(errors) => error(uid, errors)
              }
            }
          }
          case "AuthModule" => {
            env.datastores.authConfigsDataStore.findAll().flatMap { entities =>
              val res = KubernetesOtoroshiResource(obj)
              val json = client.customizeAuthModule(res.spec, res, entities)
              AuthModuleConfig._fmt.reads(json) match {
                case JsSuccess(_, _) => success(uid)
                case JsError(errors) => error(uid, errors)
              }
            }
          }
          case "Script" => {
            env.datastores.scriptDataStore.findAll().flatMap { entities =>
              val res = KubernetesOtoroshiResource(obj)
              val json = client.customizeScripts(res.spec, res, entities)
              Script._fmt.reads(json) match {
                case JsSuccess(_, _) => success(uid)
                case JsError(errors) => error(uid, errors)
              }
            }
          }
          case "TcpService" => {
            env.datastores.tcpServiceDataStore.findAll().flatMap { entities =>
              val res = KubernetesOtoroshiResource(obj)
              val json = client.customizeTcpService(res.spec, res, entities)
              TcpService.fmt.reads(json) match {
                case JsSuccess(_, _) => success(uid)
                case JsError(errors) => error(uid, errors)
              }
            }
          }
          case "DataExporter" => {
            env.datastores.dataExporterConfigDataStore.findAll().flatMap { entities =>
              val res = KubernetesOtoroshiResource(obj)
              val json = client.customizeDataExporter(res.spec, res, entities)
              DataExporterConfig.format.reads(json) match {
                case JsSuccess(_, _) => success(uid)
                case JsError(errors) => error(uid, errors)
              }
            }
          }
          case "Admin" => {
            env.datastores.simpleAdminDataStore.findAll().flatMap { admins =>
              val res = KubernetesOtoroshiResource(obj)
              val json = client.customizeAdmin(res.spec, res, admins)
              SimpleOtoroshiAdmin.fmt.reads(json) match {
                case JsSuccess(_, _) => success(uid)
                case JsError(errors) => error(uid, errors)
              }
            }
          }
          case _ => Results.Ok(Json.obj(
            "apiVersion" -> "admission.k8s.io/v1",
            "kind" -> "AdmissionReview",
            "response" -> Json.obj(
              "uid" -> uid,
              "allowed" -> false,
              "status" -> Json.obj(
                "code" -> 404,
                "message" -> s"Resource of kind ${kind} unknown !"
              )
            )
          )).future
        }
      } else {
        success(uid)
      }
    }
  }
}

class KubernetesAdmissionWebhookSidecarInjector extends RequestSink {

  val logger = Logger("otoroshi-sidecar-injector")

  override def name: String = "Kubernetes sidecar injector webhook"

  override def description: Option[String] =
    Some(
      s"""This plugin exposes a webhook to kubernetes to inject otoroshi-sidecar in pods
    """.stripMargin
    )

  override def matches(ctx: RequestSinkContext)(implicit env: Env, ec: ExecutionContext): Boolean = {
    val config = KubernetesConfig.theConfig(ctx)
    (
      ctx.request.domain.contentEquals(s"${config.otoroshiServiceName}.${config.otoroshiNamespace}.svc.${config.clusterDomain}") ||
      ctx.request.domain.contentEquals(s"${config.otoroshiServiceName}.${config.otoroshiNamespace}.svc")
    ) &&
      ctx.request.path.contentEquals("/apis/webhooks/inject") &&
      ctx.origin == RequestOrigin.ReverseProxy &&
      ctx.request.method == "POST"
  }

  override def handle(ctx: RequestSinkContext)(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    implicit val mat = env.otoroshiMaterializer
    ctx.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
      val json: JsValue = ctx.request.contentType match {
        case Some(v) if v.contains("application/json") => Json.parse(bodyRaw.utf8String)
        case Some(v) if v.contains("application/yaml") => Yaml.parse(bodyRaw.utf8String)
        case _ => Json.parse(bodyRaw.utf8String)
      }
      val operation = (json \ "request" \ "operation").as[String]
      val obj = (json \ "request" \ "object").as[JsObject]
      // obj.prettify.debugPrintln
      val uid = (json \ "request" \ "uid").as[String]
      val version = (obj \ "apiVersion").as[String]
      val inject = obj.select("metadata").select("labels").select("otoroshi.io/sidecar").asOpt[String].contains("inject")
      if (inject) {
        Try {
          val conf = KubernetesConfig.theConfig(ctx)
          val meta = obj.select("metadata").as[JsObject]
          val apikey = meta.select("annotations").select("otoroshi.io/sidecar-apikey").as[String]
          val backendCert = meta.select("annotations").select("otoroshi.io/sidecar-backend-cert").as[String]
          val clientCert = meta.select("annotations").select("otoroshi.io/sidecar-client-cert").as[String]
          val tokenSecret = meta.select("annotations").select("otoroshi.io/token-secret").asOpt[String].getOrElse("secret")
          val expectedDN = meta.select("annotations").select("otoroshi.io/expected-dn").asOpt[String].getOrElse("cn=none")
          val localPort = obj.select("spec").select("containers").select(0).select("ports").select(0).select("containerPort").asOpt[Int].getOrElse(8081)
          val image = conf.image.getOrElse("maif/otoroshi-sidecar:latest")
          val template       = conf.templates.select("webhooks")

          val containerPort  = conf.templates.select("containerPort")
          val otoroshi       = template.select("otoroshi")
          val ports          = template.select("ports")
          val flags          = template.select("flags")

          val localPortV = ports.select("local").asOpt[Int].getOrElse(localPort)

          if (localPortV == 80) {
            throw new RuntimeException("The application service cannot use port 80 !")
          }

          val containerPortV = containerPort.select("value").asOpt[Int].getOrElse(8443)

          def envVariable(name: String, value: String): JsValue = Json.obj("name" -> name, "value" -> value)
          def envVariableInt(name: String, value: Int): JsValue = Json.obj("name" -> name, "value" -> value.toString)
          def envVariableBool(name: String, value: Boolean): JsValue = Json.obj("name" -> name, "value" -> value.toString)
          def volumeMount(name: String, path: String): JsValue = Json.obj("name" -> name, "mountPath" -> path, "readOnly" -> true)
          def secretVolume(volumeName: String, secretName: String): JsValue = Json.obj("name" -> volumeName, "secret" -> Json.obj("secretName" -> secretName))

          val base64patch = Json.arr(
            Json.obj(
              "op" -> "add",
              "path" -> "/spec/containers/-",
              "value" -> Json.obj(
                "image" -> image,
                "imagePullPolicy" -> "Always",
                "name" -> "otoroshi-sidecar",
                "ports" -> Json.arr(
                  Json.obj(
                    "name" -> JsString(containerPort.select("name").asOpt[String].getOrElse("https")),
                    "containerPort" -> containerPortV
                  ),
                  // Json.obj(
                  //   "name" -> "dns",
                  //   "containerPort" -> 53
                  // )
                ),
                // "securityContext" -> Json.obj(
                //   "allowPrivilegeEscalation" -> false,
                //   "capabilities" -> Json.obj(
                //     "add" -> Json.arr(
                //       "NET_BIND_SERVICE"
                //     ),
                //     "drop" -> Json.arr(
                //       "all"
                //     ),
                //     "readOnlyRootFilesystem" -> true
                //   )
                // ),
                "env" -> Json.arr(
                  envVariable("TOKEN_SECRET", tokenSecret),
                  envVariable("OTOROSHI_DOMAIN", otoroshi.select("domain").asOpt[String].getOrElse("otoroshi.mesh")),
                  envVariable("OTOROSHI_HOST", otoroshi.select("host").asOpt[String].getOrElse(s"${conf.otoroshiServiceName}.${conf.otoroshiNamespace}.svc.${conf.clusterDomain}")),
                  envVariableInt("OTOROSHI_PORT", otoroshi.select("port").asOpt[Int].getOrElse(8443)),
                  envVariableInt("LOCAL_PORT", ports.select("local").asOpt[Int].getOrElse(localPort)),
                  envVariableInt("EXTERNAL_PORT", ports.select("external").asOpt[Int].getOrElse(8443)),
                  envVariableInt("INTERNAL_PORT", ports.select("internal").asOpt[Int].getOrElse(8080)),
                  envVariableBool("REQUEST_CERT", flags.select("requestCert").asOpt[Boolean].getOrElse(true)),
                  envVariableBool("ENABLE_ORIGIN_CHECK", flags.select("originCheck").asOpt[Boolean].getOrElse(true)),
                  envVariableBool("DISABLE_TOKENS_CHECK", !flags.select("tokensCheck").asOpt[Boolean].getOrElse(true)),
                  envVariableBool("DISPLAY_ENV", flags.select("displayEnv").asOpt[Boolean].getOrElse(false)),
                  envVariableBool("ENABLE_TRACE", flags.select("tlsTrace").asOpt[Boolean].getOrElse(false)),
                  envVariableBool("ENABLE_CLIENT_CERT_CHECK", flags.select("clientCertCheck").asOpt[Boolean].getOrElse(true)),
                  envVariable("EXPECTED_DN", expectedDN)
                ),
                "volumeMounts" -> Json.arr(
                  volumeMount("apikey-volume", "/var/run/secrets/kubernetes.io/otoroshi.io/apikeys"),
                  volumeMount("backend-cert-volume", "/var/run/secrets/kubernetes.io/otoroshi.io/certs/backend"),
                  volumeMount("client-cert-volume", "/var/run/secrets/kubernetes.io/otoroshi.io/certs/client"),
                )
              )
            ),
            /*Json.obj(
              "op" -> "add",
              "path" -> "/spec/containers/-",
              "value" -> Json.obj(
                "image" -> "andyshinn/dnsmasq:latest",
                "imagePullPolicy" -> "Always",
                "name" -> "otoroshi-sidecar-dns",
                "ports" -> Json.arr(
                  Json.obj(
                    "name" -> "dns",
                    "containerPort" -> 53
                  )
                ),
                "securityContext" -> Json.obj(
                  "allowPrivilegeEscalation" -> false,
                  "capabilities" -> Json.obj(
                    "add" -> Json.arr(
                      "NET_BIND_SERVICE"
                    ),
                    "drop" -> Json.arr(
                      "all"
                    ),
                    "readOnlyRootFilesystem" -> true
                  )
                ),
                "command" -> Json.arr("dnsmasq")
                //"command" -> Json.arr("dnsmasq", "-k", "--conf-file=/etc/dnsmasq-custom.conf")
                //"command" -> Json.arr("dnsmasq", "-k", "--address=/otoroshi.mesh/127.0.0.1", "--resolv-file=/etc/resolv.back.conf", "--no-daemon")
                //"command" -> Json.arr("dnsmasq", "-k", "--address=/otoroshi.mesh/127.0.0.1", "--server=`cat /etc/resolv.back.conf | grep nameserver | awk '{print $2}'`", "--no-daemon")
              )
            ),*/
            Json.obj(
              "op" -> "add",
              "path" -> "/spec/initContainers",
              "value" -> Json.arr()
            ),
            Json.obj(
              "op" -> "add",
              "path" -> "/spec/initContainers/-",
              "value" -> Json.obj(
                "image" -> image.replace("otoroshi-sidecar:", "otoroshi-sidecar-init:"),
                "imagePullPolicy" -> "Always",
                "name" -> "otoroshi-sidecar-init",
                "securityContext" -> Json.obj(
                "capabilities" -> Json.obj(
                  "add" -> Json.arr(
                    "NET_ADMIN"
                    )
                  ),
                  "privileged" -> true
                ),
                "env" -> Json.arr(
                  envVariableInt("FROM", 80),
                  envVariableInt("TO",  ports.select("internal").asOpt[Int].getOrElse(8080))
                )
              )
            ),
            // Json.obj(
            //   "op" -> "add",
            //   "path" -> "/spec/dnsConfig",
            //   "value" -> Json.obj(
            //     "nameservers" -> Json.arr(
            //       "127.0.0.1"
            //     )
            //   )
            // ),
            Json.obj(
              "op" -> "add",
              "path" -> "/spec/volumes/-",
              "value" -> secretVolume("apikey-volume", apikey)
            ),
            Json.obj(
              "op" -> "add",
              "path" -> "/spec/volumes/-",
              "value" -> secretVolume("backend-cert-volume", backendCert)
            ),
            Json.obj(
              "op" -> "add",
              "path" -> "/spec/volumes/-",
              "value" -> secretVolume("client-cert-volume", clientCert)
            )
          )/*.debug(a => a.prettify.debugPrintln)*/.stringify.base64
          val patch = Json.obj(
            "apiVersion" -> "admission.k8s.io/v1",
            "kind" -> "AdmissionReview",
            "response" -> Json.obj(
              "uid" -> uid,
              "allowed" -> true,
              "patchType" -> "JSONPatch",
              "patch" -> base64patch
            )
          )
          Results.Ok(patch)
        } match {
          case Success(r) => r.future
          case Failure(e) =>
            Results.Ok(Json.obj(
            "apiVersion" -> "admission.k8s.io/v1",
            "kind" -> "AdmissionReview",
            "response" -> Json.obj(
              "uid" -> uid,
              "allowed" -> false,
              "status" -> Json.obj(
                "code" -> 400,
                "message" -> s"${e.getMessage}"
              )
            )
          )).future
        }
      } else {
        Results.Ok(Json.obj(
          "apiVersion" -> "admission.k8s.io/v1",
          "kind" -> "AdmissionReview",
          "response" -> Json.obj(
            "uid" -> uid,
            "allowed" -> true,
          )
        )).future
      }
    }
  }
}