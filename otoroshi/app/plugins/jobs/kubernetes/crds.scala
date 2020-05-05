package otoroshi.plugins.jobs.kubernetes

import java.util.concurrent.atomic.AtomicBoolean

import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.{Sink, Source}
import auth.AuthModuleConfig
import env.Env
import models._
import otoroshi.script._
import otoroshi.ssl.pki.models.GenCsrQuery
import otoroshi.tcp.TcpService
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
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

class ClientSupport(client: KubernetesClient)(implicit ec: ExecutionContext, env: Env) {

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

  private def customizeServiceDescriptor(spec: JsValue, res: KubernetesOtoroshiResource): JsValue = {
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
              (target \ "url").asOpt[String] match {
                case None => target
                case Some(tv) =>
                  val uri = Uri(tv)
                  target.as[JsObject] ++ Json.obj(
                    "host" -> uri.authority.host.toString(),
                    "scheme" -> uri.scheme
                  )
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

  private def customizeApikey(spec: JsValue, res: KubernetesOtoroshiResource): JsValue = {
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
    )
  }

  private def customizeCert(spec: JsValue, res: KubernetesOtoroshiResource): JsValue = {
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
        "entityMetadata" -> ((s \ "entityMetadata").asOpt[JsObject].getOrElse(Json.obj()) ++ Json.obj(
          "otoroshi-provider" -> "kubernetes-crds",
          "kubernetes-name" -> res.name,
          "kubernetes-namespace" -> res.namespace,
          "kubernetes-path" -> res.path,
          "kubernetes-uid" -> res.uid
        ))
      )
    ).applyOn(s =>
      s.as[JsObject] ++ Json.obj("id" -> s"kubernetes-crd-import-${res.namespace}-${res.name}".slugifyWithSlash)
    ).applyOn { s =>
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
                  s.as[JsObject] ++ Json.obj(
                    "caRef" -> caOpt.map(_._2.id).map(JsString.apply).getOrElse(JsNull).as[JsValue],
                    "chain" -> c.chain,
                    "privateKey" -> c.privateKey,
                    "entityMetadata" -> ((s \ "entityMetadata").asOpt[JsObject].getOrElse(Json.obj()) ++ Json.obj(
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

  def crdsFetchServiceGroups(): Future[Seq[OtoResHolder[ServiceGroup]]] = client.fetchOtoroshiResources[ServiceGroup]("service-groups", ServiceGroup._fmt, customizeIdAndName)
  def crdsFetchServiceDescriptors(): Future[Seq[OtoResHolder[ServiceDescriptor]]] =  client.fetchOtoroshiResources[ServiceDescriptor]("service-descriptors", ServiceDescriptor._fmt, customizeServiceDescriptor)
  def crdsFetchApiKeys(): Future[Seq[OtoResHolder[ApiKey]]] = client.fetchOtoroshiResources[ApiKey]("apikeys", ApiKey._fmt, customizeApikey)
  def crdsFetchCertificates(): Future[Seq[OtoResHolder[Cert]]] = client.fetchOtoroshiResources[Cert]("certificates", Cert._fmt, customizeCert)
  def crdsFetchGlobalConfig(): Future[Seq[OtoResHolder[GlobalConfig]]] = client.fetchOtoroshiResources[GlobalConfig]("global-configs", GlobalConfig._fmt)
  def crdsFetchJwtVerifiers(): Future[Seq[OtoResHolder[JwtVerifier]]] = client.fetchOtoroshiResources[JwtVerifier]("jwt-verifiers", JwtVerifier.fmt, customizeIdAndName)
  def crdsFetchAuthModules(): Future[Seq[OtoResHolder[AuthModuleConfig]]] = client.fetchOtoroshiResources[AuthModuleConfig]("auth-modules", AuthModuleConfig._fmt, customizeIdAndName)
  def crdsFetchScripts(): Future[Seq[OtoResHolder[Script]]] = client.fetchOtoroshiResources[Script]("scripts", Script._fmt, customizeIdAndName)
  def crdsFetchTcpServices(): Future[Seq[OtoResHolder[TcpService]]] = client.fetchOtoroshiResources[TcpService]("tcp-services", TcpService.fmt, customizeIdAndName)
  def crdsFetchSimpleAdmins(): Future[Seq[OtoResHolder[JsValue]]] = client.fetchOtoroshiResources[JsValue]("admins", v => JsSuccess(v))
}

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

  def syncCRDs(conf: KubernetesConfig, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    implicit val mat = env.otoroshiMaterializer
    val client = new KubernetesClient(conf, env)
    if (running.compareAndSet(false, true)) {
      shouldRunNext.set(false)
      logger.info("Sync. otoroshi CRDs")
      KubernetesCertSyncJob.syncKubernetesSecretsToOtoroshiCerts(client).flatMap { _ =>
        // TODO: support secret name for
        // - apikey secret
        // - certificate payload
        val clientSupport = new ClientSupport(client)
        for {
          serviceGroups <- clientSupport.crdsFetchServiceGroups()
          serviceDescriptors <- clientSupport.crdsFetchServiceDescriptors()
          apiKeys <- clientSupport.crdsFetchApiKeys()
          certificates <- clientSupport.crdsFetchCertificates()
          globalConfigs <- clientSupport.crdsFetchGlobalConfig()
          jwtVerifiers <- clientSupport.crdsFetchJwtVerifiers()
          authModules <- clientSupport.crdsFetchAuthModules()
          scripts <- clientSupport.crdsFetchScripts()
          tcpServices <- clientSupport.crdsFetchTcpServices()
          simpleAdmins <- clientSupport.crdsFetchSimpleAdmins()

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
        } yield {
          if (globalConfigs.size > 1) {
            Future.failed(new RuntimeException("There can only be one GlobalConfig entity !"))
          } else {
            val entities = (
              compareAndSave(globalConfigs)(otoglobalConfigs, _ => "global", _.save()) ++
                compareAndSave(simpleAdmins)(otosimpleAdmins, v => (v \ "username").as[String], v => env.datastores.simpleAdminDataStore.registerUser(v)) ++ // useful ?
                compareAndSave(serviceGroups)(otoserviceGroups, _.id, _.save()) ++
                compareAndSave(certificates)(otocertificates, _.id, _.save()) ++ // TODO: match also on csr
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
            }.runWith(Sink.ignore)

            otoserviceGroups
              .filter(sg => sg.metadata.get("otoroshi-provider").contains("kubernetes-crds"))
              .filterNot(sg => serviceGroups.exists(ssg => sg.metadata.get("kubernetes-path").contains(ssg.path)))
              .map(_.id)
              .debug(seq => logger.info(s"Will delete ${seq.size} out of date service-group entities"))
              .applyOn(env.datastores.serviceGroupDataStore.deleteByIds)

            otoserviceDescriptors
              .filter(sg => sg.metadata.get("otoroshi-provider").contains("kubernetes-crds"))
              .filterNot(sg => serviceDescriptors.exists(ssg => sg.metadata.get("kubernetes-path").contains(ssg.path)))
              .map(_.id)
              .debug(seq => logger.info(s"Will delete ${seq.size} out of date service-descriptor entities"))
              .applyOn(env.datastores.serviceDescriptorDataStore.deleteByIds)

            otoapiKeys
              .filter(sg => sg.metadata.get("otoroshi-provider").contains("kubernetes-crds"))
              .filterNot(sg => apiKeys.exists(ssg => sg.metadata.get("kubernetes-path").contains(ssg.path)))
              .map(_.clientId)
              .debug(seq => logger.info(s"Will delete ${seq.size} out of date apikey entities"))
              .applyOn(env.datastores.apiKeyDataStore.deleteByIds)

            otocertificates
              .filter(sg => sg.entityMetadata.get("otoroshi-provider").contains("kubernetes-crds"))
              .filterNot(sg => certificates.exists(ssg => sg.entityMetadata.get("kubernetes-path").contains(ssg.path)))
              .map(_.id)
              .debug(seq => logger.info(s"Will delete ${seq.size} out of date certificate entities"))
              .applyOn(env.datastores.certificatesDataStore.deleteByIds)

            otojwtVerifiers
              .filter(sg => sg.metadata.get("otoroshi-provider").contains("kubernetes-crds"))
              .filterNot(sg => jwtVerifiers.exists(ssg => sg.metadata.get("kubernetes-path").contains(ssg.path)))
              .map(_.id)
              .debug(seq => logger.info(s"Will delete ${seq.size} out of date jwt-verifier entities"))
              .applyOn(env.datastores.globalJwtVerifierDataStore.deleteByIds)

            otoauthModules
              .filter(sg => sg.metadata.get("otoroshi-provider").contains("kubernetes-crds"))
              .filterNot(sg => authModules.exists(ssg => sg.metadata.get("kubernetes-path").contains(ssg.path)))
              .map(_.id)
              .debug(seq => logger.info(s"Will delete ${seq.size} out of date auth-module entities"))
              .applyOn(env.datastores.authConfigsDataStore.deleteByIds)

            otoscripts
              .filter(sg => sg.metadata.get("otoroshi-provider").contains("kubernetes-crds"))
              .filterNot(sg => scripts.exists(ssg => sg.metadata.get("kubernetes-path").contains(ssg.path)))
              .map(_.id)
              .debug(seq => logger.info(s"Will delete ${seq.size} out of date script entities"))
              .applyOn(env.datastores.scriptDataStore.deleteByIds)

            ototcpServices
              .filter(sg => sg.metadata.get("otoroshi-provider").contains("kubernetes-crds"))
              .filterNot(sg => tcpServices.exists(ssg => sg.metadata.get("kubernetes-path").contains(ssg.path)))
              .map(_.id)
              .debug(seq => logger.info(s"Will delete ${seq.size} out of date script entities"))
              .applyOn(env.datastores.tcpServiceDataStore.deleteByIds)

            otosimpleAdmins
              .filter(sg => (sg \ "metadata" \ "otoroshi-provider").asOpt[String].contains("kubernetes-crds"))
              .filterNot(sg => simpleAdmins.exists(ssg => (sg \ "metadata" \ "kubernetes-path").asOpt[String].contains(ssg.path)))
              .map(sg => (sg \ "username").as[String])
              .debug(seq => logger.info(s"Will delete ${seq.size} out of date script entities"))
              .applyOn(env.datastores.simpleAdminDataStore.deleteUsers)

          }
        }
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