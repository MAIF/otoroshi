package otoroshi.plugins.jobs.kubernetes

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.{Sink, Source}
import auth.AuthModuleConfig
import env.Env
import models._
import otoroshi.plugins.jobs.kubernetes.IngressSupport.IntOrString
import otoroshi.script._
import otoroshi.ssl.pki.models.GenCsrQuery
import otoroshi.tcp.TcpService
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import security.IdGenerator
import ssl.{Cert, DynamicSSLEngineProvider}
import utils.TypedMap

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

class KubernetesOtoroshiCRDsControllerJob extends Job {

  private val logger = Logger("otoroshi-plugins-kubernetes-crds-controller-job")

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

  override def instantiation: JobInstantiation = JobInstantiation.OneInstancePerOtoroshiCluster

  override def initialDelay: Option[FiniteDuration] = 2.seconds.some

  override def interval: Option[FiniteDuration] = 5.seconds.some

  override def jobStart(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = super.jobStart(ctx)

  override def jobStop(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = super.jobStop(ctx)

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val conf = KubernetesConfig.theConfig(ctx)
    if (conf.enabled) {
      if (conf.crds) {
        KubernetesCRDsJob.syncCRDs(conf, ctx.attrs)
      } else {
        ().future
      }
    } else {
      ().future
    }
  }
}

class ClientSupport(val client: KubernetesClient, logger: Logger)(implicit ec: ExecutionContext, env: Env) {

  private def customizeIdAndName(spec: JsValue, res: KubernetesOtoroshiResource): JsValue = {
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
      s.as[JsObject] ++ Json.obj("id" -> s"kubernetes-crd-import-${res.namespace}-${res.name}".slugifyWithSlash)
    )
  }

  private def customizeServiceDescriptor(spec: JsValue, res: KubernetesOtoroshiResource, services: Seq[KubernetesService], endpoints: Seq[KubernetesEndpoint]): JsValue = {
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
    }.applyOn { s =>
      (s \ "targets").asOpt[JsArray] match {
        case Some(targets) => {
          if (targets.value.isEmpty) {
            s
          } else {
            s.as[JsObject] ++ Json.obj("targets" -> JsArray(targets.value.map(item => item.applyOn(target =>
              ((target \ "url").asOpt[String] match {
                case None => target
                case Some(tv) =>
                  val uri = Uri(tv)
                  target.as[JsObject] ++ Json.obj(
                    "host" -> uri.authority.host.toString(),
                    "scheme" -> uri.scheme
                  )
              }).applyOn { targetJson =>
                val serviceName = (target \ "serviceName").asOpt[String]
                val servicePort = (target \ "servicePort").asOpt[JsValue]
                (serviceName, servicePort) match {
                  case (Some(sn), Some(sp)) => {
                    val service = services.find(s => s.path == res.path)
                    val endpoint = endpoints.find(s => s.path == res.path)
                    (service, endpoint) match {
                      case (Some(s), p) => {
                        val port = IntOrString(sp.asOpt[Int], sp.asOpt[String])
                        val targets = KubernetesIngressToDescriptor.serviceToTargetsSync(s, p, port, client, logger)
                        JsArray(targets.map(_.toJson))
                      }
                      case _ => targetJson
                    }
                  }
                  case _ => targetJson
                }
                targetJson
              }
            ))))
          }
        }
        case None => s.as[JsObject] ++ Json.obj("targets" -> Json.arr())
      }
    }.applyOn(s =>
      (s \ "groupId").asOpt[String] match {
        case None => s
        case Some(v) => s.as[JsObject] - "group" ++ Json.obj("groupId" -> v)
      }
    )
  }

  private def customizeApiKey(spec: JsValue, res: KubernetesOtoroshiResource, secrets: Seq[KubernetesSecret], registerApkToExport: Function3[String, String, ApiKey, Unit]): JsValue = {
    spec.applyOn(s =>
      (s \ "clientName").asOpt[String] match {
        case None => s.as[JsObject] ++ Json.obj("clientName" -> res.name)
        case Some(_) => s
      }
    ).applyOn(s =>
      (s \ "group").asOpt[String] match {
        case None => s
        case Some(v) => s.as[JsObject] - "group" ++ Json.obj("authorizedGroup" -> v)
      }
    ).applyOn(s =>
      (s \ "authorizedGroup").asOpt[String] match {
        case None => s.as[JsObject] ++ Json.obj("authorizedGroup" -> "default")
        case Some(v) => s
      }
    ).applyOn { s =>
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
        case Some(v) if shouldExport && v.contains("/") =>
          val namespace :: name :: Nil = v.split("/").toList
          val clientId = secrets.find(_.path == v).map(s => (s.raw \ "data" \ "clientId").as[String].applyOn(_.fromBase64)).getOrElse(IdGenerator.token(64))
          val clientSecret = secrets.find(_.path == v).map(s => (s.raw \ "data" \ "clientSecret").as[String].applyOn(_.fromBase64)).getOrElse(IdGenerator.token(128))
          registerApkToExport(namespace, name, ApiKey(clientId, clientSecret, clientName = name, authorizedGroup = "default"))
          s.as[JsObject] ++ Json.obj(
            "clientId" -> clientId,
            "clientSecret" -> clientSecret
          )
        case Some(v) if shouldExport && !v.contains("/") =>
          val clientId = secrets.find(_.path == v).map(s => (s.raw \ "data" \ "clientId").as[String].applyOn(_.fromBase64)).getOrElse(IdGenerator.token(64))
          val clientSecret = secrets.find(_.path == v).map(s => (s.raw \ "data" \ "clientSecret").as[String].applyOn(_.fromBase64)).getOrElse(IdGenerator.token(128))
          registerApkToExport(res.namespace, v, ApiKey(clientId, clientSecret, clientName = v, authorizedGroup = "default"))
          s.as[JsObject] ++ Json.obj(
            "clientId" -> clientId,
            "clientSecret" -> clientSecret
          )
        case Some(v) if v.contains("/") =>
          secrets.find(_.path == v) match {
            case None => s.as[JsObject] ++ Json.obj("clientSecret" -> "secret-not-found")
            case Some(secret) => s.as[JsObject] ++ Json.obj(
              "clientId" -> (secret.raw \ "data" \ "clientId").as[String].applyOn(_.fromBase64),
                "clientSecret" -> (secret.raw \ "data" \ "clientSecret").as[String].applyOn(_.fromBase64)
            )
          }
        case Some(v) if !v.contains("/") =>
          secrets.find(_.name == v) match {
            case None => s.as[JsObject] ++ Json.obj("clientSecret" -> "secret-not-found")
            case Some(secret) => s.as[JsObject] ++ Json.obj(
              "clientId" -> (secret.raw \ "data" \ "clientId").as[String].applyOn(_.fromBase64),
              "clientSecret" -> (secret.raw \ "data" \ "secret").as[String].applyOn(_.fromBase64)
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

  private def foundACertWithSameIdAndCsr(id: String, csrJson: JsValue, caOpt: Option[Cert], certs: Seq[Cert]): Option[Cert] = {
    certs
      //.find(c => c.id == id)
      .filter(_.entityMetadata.get("otoroshi-provider").contains("kubernetes-crds"))
      .filter(_.entityMetadata.get("csr").contains(csrJson.stringify))
      .filter(c => caOpt.map(_.id) == c.caRef)
      .headOption
  }

  private def customizeCert(spec: JsValue, res: KubernetesOtoroshiResource, certs: Seq[Cert], registerCertToExport: Function3[String, String, Cert, Unit]): JsValue = {
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
      s.as[JsObject] ++ Json.obj("id" -> id)
    ).applyOn { s =>
      val shouldExport = (s \ "exportSecret").asOpt[Boolean].getOrElse(false)
      val secretName = (s \ "secretName").asOpt[String].getOrElse(res.name + "-secret")
      (s \ "csr").asOpt[JsValue] match {
        case None => s
        case Some(csrJson) => {
          val caOpt = (csrJson \ "caDN").asOpt[String] match {
            case None => None
            case Some(dn) =>
              DynamicSSLEngineProvider.certificates.find {
                case (_, cert) => cert.certificate.map(_.getIssuerDN.getName).contains(dn)
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
                  case Some(ca) => Await.result(env.pki.genCert(csr, ca._2.certificate.get, ca._2.cryptoKeyPair.getPrivate), 1.second)
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

  def crdsFetchServiceGroups(): Future[Seq[OtoResHolder[ServiceGroup]]] = client.fetchOtoroshiResources[ServiceGroup]("service-groups", ServiceGroup._fmt, customizeIdAndName)
  def crdsFetchServiceDescriptors(services: Seq[KubernetesService], endpoints: Seq[KubernetesEndpoint]): Future[Seq[OtoResHolder[ServiceDescriptor]]] = {
    client.fetchOtoroshiResources[ServiceDescriptor]("service-descriptors", ServiceDescriptor._fmt, (a, b) => customizeServiceDescriptor(a, b, services, endpoints))
  }
  def crdsFetchApiKeys(secrets: Seq[KubernetesSecret], registerApkToExport: Function3[String, String, ApiKey, Unit]): Future[Seq[OtoResHolder[ApiKey]]] = {
    val otoApikeySecrets = secrets.filter(_.theType == "otoroshi.io/apikey-secret")
    client.fetchOtoroshiResources[ApiKey]("apikeys", ApiKey._fmt, (a, b) => customizeApiKey(a, b, otoApikeySecrets, registerApkToExport))
  }
  def crdsFetchCertificates(certs: Seq[Cert], registerCertToExport: Function3[String, String, Cert, Unit]): Future[Seq[OtoResHolder[Cert]]] = {
    client.fetchOtoroshiResources[Cert]("certificates", Cert._fmt, (a, b) => customizeCert(a, b, certs, registerCertToExport))
  }
  def crdsFetchGlobalConfig(): Future[Seq[OtoResHolder[GlobalConfig]]] = client.fetchOtoroshiResources[GlobalConfig]("global-configs", GlobalConfig._fmt)
  def crdsFetchJwtVerifiers(): Future[Seq[OtoResHolder[JwtVerifier]]] = client.fetchOtoroshiResources[JwtVerifier]("jwt-verifiers", JwtVerifier.fmt, customizeIdAndName)
  def crdsFetchAuthModules(): Future[Seq[OtoResHolder[AuthModuleConfig]]] = client.fetchOtoroshiResources[AuthModuleConfig]("auth-modules", AuthModuleConfig._fmt, customizeIdAndName)
  def crdsFetchScripts(): Future[Seq[OtoResHolder[Script]]] = client.fetchOtoroshiResources[Script]("scripts", Script._fmt, customizeIdAndName)
  def crdsFetchTcpServices(): Future[Seq[OtoResHolder[TcpService]]] = client.fetchOtoroshiResources[TcpService]("tcp-services", TcpService.fmt, customizeIdAndName)
  def crdsFetchSimpleAdmins(): Future[Seq[OtoResHolder[JsValue]]] = client.fetchOtoroshiResources[JsValue]("admins", v => JsSuccess(v))
}

case class CRDContext(
  serviceGroups: Seq[OtoResHolder[ServiceGroup]],
  serviceDescriptors: Seq[OtoResHolder[ServiceDescriptor]],
  apiKeys: Seq[OtoResHolder[ApiKey]],
  certificates: Seq[OtoResHolder[Cert]],
  globalConfigs: Seq[OtoResHolder[GlobalConfig]],
  jwtVerifiers: Seq[OtoResHolder[JwtVerifier]],
  authModules: Seq[OtoResHolder[AuthModuleConfig]],
  scripts: Seq[OtoResHolder[Script]],
  tcpServices: Seq[OtoResHolder[TcpService]],
  simpleAdmins: Seq[OtoResHolder[JsValue]],
  otoserviceGroups: Seq[ServiceGroup],
  otoserviceDescriptors: Seq[ServiceDescriptor],
  otoapiKeys: Seq[ApiKey],
  otocertificates: Seq[Cert],
  otoglobalConfigs: Seq[GlobalConfig],
  otojwtVerifiers: Seq[JwtVerifier],
  otoauthModules: Seq[AuthModuleConfig],
  otoscripts: Seq[Script],
  ototcpServices: Seq[TcpService],
  otosimpleAdmins: Seq[JsValue],
)

object KubernetesCRDsJob {

  private val logger = Logger("otoroshi-plugins-kubernetes-crds-sync")
  private val running = new AtomicBoolean(false)
  private val shouldRunNext = new AtomicBoolean(false)

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

      services <- clientSupport.client.fetchServices()
      endpoints <- clientSupport.client.fetchEndpoints()
      secrets <- clientSupport.client.fetchSecrets()
      serviceGroups <- clientSupport.crdsFetchServiceGroups()
      serviceDescriptors <- clientSupport.crdsFetchServiceDescriptors(services, endpoints)
      apiKeys <- clientSupport.crdsFetchApiKeys(secrets, registerApkToExport)
      certificates <- clientSupport.crdsFetchCertificates(otocertificates, registerCertToExport)
      globalConfigs <- clientSupport.crdsFetchGlobalConfig()
      jwtVerifiers <- clientSupport.crdsFetchJwtVerifiers()
      authModules <- clientSupport.crdsFetchAuthModules()
      scripts <- clientSupport.crdsFetchScripts()
      tcpServices <- clientSupport.crdsFetchTcpServices()
      simpleAdmins <- clientSupport.crdsFetchSimpleAdmins()

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
        otosimpleAdmins = otosimpleAdmins
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
      otosimpleAdmins
    ) = ctx
    if (globalConfigs.size > 1) {
      Future.failed(new RuntimeException("There can only be one GlobalConfig entity !"))
    } else {
      val entities = (
        compareAndSave(globalConfigs)(otoglobalConfigs, _ => "global", _.save()) ++
          compareAndSave(simpleAdmins)(otosimpleAdmins, v => (v \ "username").as[String], v => env.datastores.simpleAdminDataStore.registerUser(v)) ++ // useful ?
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
    ) = ctx
    for {
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
      .debug(seq => logger.info(s"Will delete ${seq.size} out of date script entities"))
      .applyOn(env.datastores.tcpServiceDataStore.deleteByIds)

    _ <- otosimpleAdmins
      .filter(sg => (sg \ "metadata" \ "otoroshi-provider").asOpt[String].contains("kubernetes-crds"))
      .filterNot(sg => simpleAdmins.exists(ssg => (sg \ "metadata" \ "kubernetes-path").asOpt[String].contains(ssg.path)))
      .map(sg => (sg \ "username").as[String])
      .debug(seq => logger.info(s"Will delete ${seq.size} out of date script entities"))
      .applyOn(env.datastores.simpleAdminDataStore.deleteUsers)
    } yield ()
  }

  def exportApiKeys(conf: KubernetesConfig, attrs: TypedMap, clientSupport: ClientSupport, ctx: CRDContext, apikeys: Seq[(String, String, ApiKey)])(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    logger.info(s"will export ${apikeys.size} apikeys as secrets")
    implicit val mat = env.otoroshiMaterializer
    Source(apikeys.toList)
      .mapAsync(1) {
        case (namespace, name, apikey) => clientSupport.client.fetchSecret(namespace, name).flatMap {
          case None =>
            clientSupport.client.createSecret(namespace, name, "otoroshi.io/apikey-secret", Json.obj("clientId" -> apikey.clientId.base64, "clientSecret" -> apikey.clientSecret.base64))
          case Some(secret) =>
            val clientId = (secret.raw \ "data" \ "clientId").as[String].applyOn(s => s.fromBase64)
            val clientSecret = (secret.raw \ "data" \ "clientSecret").as[String].applyOn(s => s.fromBase64)
            if ((clientId != apikey.clientId) || (clientSecret != apikey.clientSecret)) {
              clientSupport.client.updateSecret(namespace, name, "otoroshi.io/apikey-secret", Json.obj("clientId" -> apikey.clientId.base64, "clientSecret" -> apikey.clientSecret.base64))
            } else {
              ().future
            }
          }
        }.runWith(Sink.ignore).map(_ => ())
  }

  def exportCerts(conf: KubernetesConfig, attrs: TypedMap, clientSupport: ClientSupport, ctx: CRDContext, certs: Seq[(String, String, Cert)])(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    logger.info(s"will export ${certs.size} certificates as secrets")
    implicit val mat = env.otoroshiMaterializer
    Source(certs.toList)
      .mapAsync(1) {
        case (namespace, name, cert) => clientSupport.client.fetchSecret(namespace, name).flatMap {
          case None =>
            clientSupport.client.createSecret(namespace, name, "kubernetes.io/tls", Json.obj("tls.crt" -> cert.chain.base64, "tls.key" -> cert.privateKey.base64))
          case Some(secret) =>
            val chain = (secret.raw \ "data" \ "clientId").as[String].applyOn(s => s.fromBase64)
            val privateKey = (secret.raw \ "data" \ "clientSecret").as[String].applyOn(s => s.fromBase64)
            if ((chain != cert.chain) || (privateKey != cert.privateKey)) {
              clientSupport.client.updateSecret(namespace, name, "kubernetes.io/tls", Json.obj("tls.crt" -> cert.chain.base64, "tls.key" -> cert.privateKey.base64))
            } else {
              ().future
            }
        }
      }.runWith(Sink.ignore).map(_ => ())
    ().future
  }

  def syncCRDs(conf: KubernetesConfig, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val client = new KubernetesClient(conf, env)
    if (running.compareAndSet(false, true)) {
      shouldRunNext.set(false)
      logger.info("Sync. otoroshi CRDs")
      KubernetesCertSyncJob.syncKubernetesSecretsToOtoroshiCerts(client).flatMap { _ =>
        val clientSupport = new ClientSupport(client, logger)
        val apiKeysToExport = new AtomicReference[Seq[(String, String, ApiKey)]](Seq.empty)
        val certsToExport = new AtomicReference[Seq[(String, String, Cert)]](Seq.empty)
        for {
          ctx <- context(conf, attrs, clientSupport, (ns, n, apk) => apiKeysToExport.getAndUpdate(s => s :+ (ns, n, apk)), (ns, n, cert) => certsToExport.getAndUpdate(c => c :+ (ns, n, cert)))
          _ <- importCRDEntities(conf, attrs, clientSupport, ctx)
          _ <- deleteOutDatedEntities(conf, attrs, ctx)
          _ <- exportApiKeys(conf, attrs, clientSupport, ctx, apiKeysToExport.get())
          _ <- exportCerts(conf, attrs, clientSupport, ctx, certsToExport.get())
        } yield ()
      }.flatMap { _ =>
        if (shouldRunNext.get()) {
          shouldRunNext.set(false)
          syncCRDs(conf, attrs)
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
}