package otoroshi.plugins.jobs.kubernetes

import akka.util.ByteString
import auth.AuthModuleConfig
import env.Env
import models._
import otoroshi.models.{SimpleOtoroshiAdmin, Team, Tenant}
import otoroshi.script.{RequestOrigin, RequestSink, RequestSinkContext, Script}
import otoroshi.tcp.TcpService
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{Result, Results}
import ssl.Cert

import scala.concurrent.{ExecutionContext, Future}

class KubernetesAdmissionWebhookCRDValidator extends RequestSink {

  val logger = Logger("otoroshi-crd-validator")

  override def matches(ctx: RequestSinkContext)(implicit env: Env, ec: ExecutionContext): Boolean = {
    val config = KubernetesConfig.theConfig(ctx)
    ctx.request.domain.contentEquals(s"${config.otoroshiServiceName}.${config.otoroshiNamespace}.svc.${config.clusterDomain}") &&
      ctx.request.path.contentEquals("/apis/webhooks/validator") &&
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
      val json = Json.parse(bodyRaw.utf8String)
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
        // Results.Ok(Json.obj(
        //   "apiVersion" -> "admission.k8s.io/v1",
        //   "kind" -> "AdmissionReview",
        //   "response" -> Json.obj(
        //     "uid" -> uid,
        //     "allowed" -> true,
        //     "patchType" -> "JSONPatch",
        //     "patch" -> "W3sib3AiOiAiYWRkIiwgInBhdGgiOiAiL3NwZWMvcmVwbGljYXMiLCAidmFsdWUiOiAzfV0=" // Base64 jsonpath
        //   )
        // )).future
      } else {
        success(uid)
      }
    }
  }
}

class KubernetesAdmissionWebhookSidecarInjector extends RequestSink {

  val logger = Logger("otoroshi-sidecar-injector")

  override def matches(ctx: RequestSinkContext)(implicit env: Env, ec: ExecutionContext): Boolean = {
    val config = KubernetesConfig.theConfig(ctx)
    ctx.request.domain.contentEquals(s"${config.otoroshiServiceName}.${config.otoroshiNamespace}.svc.${config.clusterDomain}") &&
      ctx.request.path.contentEquals("/apis/webhooks/inject") &&
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
      val json = Json.parse(bodyRaw.utf8String)
      val operation = (json \ "request" \ "operation").as[String]
      val obj = (json \ "request" \ "object").as[JsObject]
      val uid = (json \ "request" \ "uid").as[String]
      val version = (obj \ "apiVersion").as[String]
      Results.Ok(Json.obj(
        "apiVersion" -> "admission.k8s.io/v1",
        "kind" -> "AdmissionReview",
        "response" -> Json.obj(
          "uid" -> uid,
          "allowed" -> true,
          "patchType" -> "JSONPatch",
          "patch" -> "[]".base64 // Base64 jsonpath
        )
      )).future
    }
  }
}