package otoroshi.plugins.jobs.kubernetes

import java.util.Base64
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}
import java.util.regex.Pattern
import akka.{Done, NotUsed}
import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.{Concat, Framing, Sink, Source}
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.models._
import org.joda.time.DateTime
import otoroshi.security.IdGenerator
import otoroshi.utils.UrlSanitizer
import otoroshi.utils.http.MtlsConfig
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.libs.ws.WSRequest
import otoroshi.ssl.{Cert, DynamicSSLEngineProvider}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import otoroshi.utils.http.Implicits._

// TODO: watch res to trigger sync
class KubernetesClient(val config: KubernetesConfig, env: Env) {

  private val logger = Logger("otoroshi-plugins-kubernetes-client")

  implicit val ec  = env.otoroshiExecutionContext
  implicit val mat = env.otoroshiMaterializer

  config.caCert.foreach { cert =>
    val caCert = Cert.apply("kubernetes-ca-cert", cert, "").copy(id = "kubernetes-ca-cert")
    DynamicSSLEngineProvider.certificates.find { case (k, c) =>
      c.id == "kubernetes-ca-cert"
    } match {
      case None                                                => caCert.enrich().save()(ec, env)
      case Some((k, c)) if c.contentHash == caCert.contentHash => ()
      case Some((k, c)) if c.contentHash != caCert.contentHash => caCert.enrich().save()(ec, env)
    }
  }

  private def asyncSequence[T](seq: Seq[() => Future[T]], par: Int = 1): Future[Seq[T]] = {
    Source(seq.toList)
      .mapAsync(par) { f => f() }
      .runWith(Sink.seq[T])
  }

  private def client(url: String, wildcard: Boolean = true): WSRequest = {
    val _uri = UrlSanitizer.sanitize(config.endpoint + url)
    val uri  = if (wildcard) Uri(_uri.replace("/namespaces/*", "")) else Uri(_uri)
    if (logger.isDebugEnabled) logger.debug(s"built uri: $uri")
    env.Ws
      .akkaUrlWithTarget(
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
        ClientConfig(
          connectionTimeout = config.connectionTimeout,
          idleTimeout = config.idleTimeout,
          callAndStreamTimeout = config.callAndStreamTimeout
        )
      )
      .applyOn(req =>
        config.token match {
          case None        => req
          case Some(token) =>
            req.withHttpHeaders(
              "Authorization" -> s"Bearer ${token}"
            )
        }
      )
      .applyOn(req =>
        config.userPassword match {
          case None        => req
          case Some(token) =>
            req.withHttpHeaders(
              "Authorization" -> s"Basic ${Base64.getEncoder.encodeToString(token.getBytes)}"
            )
        }
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

  private def filterNamespaceLabels[A <: KubernetesEntity](items: Seq[A]): Seq[A] = {
    // TODO: handle kubernetes label expressions
    if (config.namespacesLabels.isEmpty) {
      items
    } else {
      items.filter(i => config.namespacesLabels.forall(t => i.labels.get(t._1) == t._2.some))
    }
  }

  def fetchNamespacesAndFilterLabels(): Future[Seq[KubernetesNamespace]] = {
    val cli: WSRequest = client(s"/api/v1/namespaces")
    cli
      .addHttpHeaders(
        "Accept" -> "application/json"
      )
      .get()
      .map { resp =>
        if (resp.status == 200) {
          filterLabels((resp.json \ "items").as[JsArray].value.map { item =>
            KubernetesNamespace(item)
          })
        } else {
          resp.ignore()
          if (logger.isDebugEnabled) logger.debug(s"fetchNamespacesAndFilterLabels: bad status ${resp.status}")
          Seq.empty
        }
      }
  }
  def fetchServices(): Future[Seq[KubernetesService]] = {
    asyncSequence(config.namespaces.map { namespace =>
      val cli: WSRequest = client(s"/api/v1/namespaces/$namespace/services")
      () =>
        cli
          .addHttpHeaders(
            "Accept" -> "application/json"
          )
          .get()
          .map { resp =>
            if (resp.status == 200) {
              (resp.json \ "items").as[JsArray].value.map { item =>
                KubernetesService(item)
              }
            } else {
              resp.ignore()
              if (logger.isDebugEnabled) logger.debug(s"fetchServices: bad status ${resp.status}")
              Seq.empty
            }
          }
    }).map(_.flatten)
  }
  def fetchService(namespace: String, name: String): Future[Option[KubernetesService]] = {
    val cli: WSRequest = client(s"/api/v1/namespaces/$namespace/services/$name", false)
    cli
      .addHttpHeaders(
        "Accept" -> "application/json"
      )
      .get()
      .map { resp =>
        if (resp.status == 200) {
          KubernetesService(resp.json).some
        } else {
          resp.ignore()
          if (logger.isDebugEnabled) logger.debug(s"fetchService: bad status ${resp.status}")
          None
        }
      }
  }
  def fetchSecret(namespace: String, name: String): Future[Option[KubernetesSecret]] = {
    val cli: WSRequest = client(s"/api/v1/namespaces/$namespace/secrets/$name", false)
    cli
      .addHttpHeaders(
        "Accept" -> "application/json"
      )
      .get()
      .map { resp =>
        if (resp.status == 200) {
          KubernetesSecret(resp.json).some
        } else {
          resp.ignore()
          if (logger.isDebugEnabled) logger.debug(s"fetchSecret: bad status ${resp.status}")
          None
        }
      }
  }
  def fetchEndpoints(): Future[Seq[KubernetesEndpoint]] = {
    asyncSequence(config.namespaces.map { namespace =>
      val cli: WSRequest = client(s"/api/v1/namespaces/$namespace/endpoints")
      () =>
        cli
          .addHttpHeaders(
            "Accept" -> "application/json"
          )
          .get()
          .map { resp =>
            if (resp.status == 200) {
              (resp.json \ "items").as[JsArray].value.map { item =>
                KubernetesEndpoint(item)
              }
            } else {
              resp.ignore()
              if (logger.isDebugEnabled) logger.debug(s"fetchEndpoints: bad status ${resp.status}")
              Seq.empty
            }
          }
    }).map(_.flatten)
  }
  def fetchEndpoint(namespace: String, name: String): Future[Option[KubernetesEndpoint]] = {
    val cli: WSRequest = client(s"/api/v1/namespaces/$namespace/endpoints/$name", false)
    cli
      .addHttpHeaders(
        "Accept" -> "application/json"
      )
      .get()
      .map { resp =>
        if (resp.status == 200) {
          KubernetesEndpoint(resp.json).some
        } else {
          resp.ignore()
          if (logger.isDebugEnabled) logger.debug(s"fetchEndpoint: bad status ${resp.status}")
          None
        }
      }
  }
  def fetchIngressesAndFilterLabels(): Future[Seq[KubernetesIngress]] = {
    asyncSequence(config.namespaces.map { namespace =>
      val cli: WSRequest = client(s"/apis/networking.k8s.io/v1beta1/namespaces/$namespace/ingresses")
      () =>
        cli
          .addHttpHeaders(
            "Accept" -> "application/json"
          )
          .get()
          .map { resp =>
            if (resp.status == 200) {
              filterLabels((resp.json \ "items").as[JsArray].value.map { item =>
                KubernetesIngress(item)
              })
            } else {
              resp.ignore()
              logger.error(s"bad http status while fetching ingresses: ${resp.status}")
              Seq.empty
            }
          }
    }).map(_.flatten)
  }
  def fetchIngresses(): Future[Seq[KubernetesIngress]] = {
    asyncSequence(config.namespaces.map { namespace =>
      val cli: WSRequest = client(s"/apis/networking.k8s.io/v1beta1/namespaces/$namespace/ingresses")
      () =>
        cli
          .addHttpHeaders(
            "Accept" -> "application/json"
          )
          .get()
          .map { resp =>
            if (resp.status == 200) {
              (resp.json \ "items").as[JsArray].value.map { item =>
                KubernetesIngress(item)
              }
            } else {
              resp.ignore()
              logger.error(s"bad http status while fetching ingresses: ${resp.status}")
              Seq.empty
            }
          }
    }).map(_.flatten)
  }
  def fetchIngressClasses(): Future[Seq[KubernetesIngressClass]] = {
    asyncSequence(config.namespaces.map { namespace =>
      val cli: WSRequest = client(s"/apis/networking.k8s.io/v1beta1/namespaces/$namespace/ingressclasses")
      () =>
        cli
          .addHttpHeaders(
            "Accept" -> "application/json"
          )
          .get()
          .map { resp =>
            if (resp.status == 200) {
              (resp.json \ "items").as[JsArray].value.map { item =>
                KubernetesIngressClass(item)
              }
            } else {
              resp.ignore()
              logger.error(s"bad http status while fetching ingresses-classes: ${resp.status}")
              Seq.empty
            }
          }
    }).map(_.flatten)
  }
  def fetchDeployments(): Future[Seq[KubernetesDeployment]] = {
    asyncSequence(config.namespaces.map { namespace =>
      val cli: WSRequest = client(s"/apis/apps/v1/namespaces/$namespace/deployments")
      () =>
        cli
          .addHttpHeaders(
            "Accept" -> "application/json"
          )
          .get()
          .map { resp =>
            if (resp.status == 200) {
              (resp.json \ "items").as[JsArray].value.map { item =>
                KubernetesDeployment(item)
              }
            } else {
              resp.ignore()
              if (logger.isDebugEnabled) logger.debug(s"fetchDeployments: bad status ${resp.status}")
              Seq.empty
            }
          }
    }).map(_.flatten)
  }
  def fetchPods(): Future[Seq[KubernetesPod]] = {
    asyncSequence(config.namespaces.map { namespace =>
      val cli: WSRequest = client(s"/api/v1/namespaces/$namespace/pods")
      () =>
        cli
          .addHttpHeaders(
            "Accept" -> "application/json"
          )
          .get()
          .map { resp =>
            if (resp.status == 200) {
              (resp.json \ "items").as[JsArray].value.map { item =>
                KubernetesPod(item)
              }
            } else {
              resp.ignore()
              if (logger.isDebugEnabled) logger.debug(s"fetchPods: bad status ${resp.status}")
              Seq.empty
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
    asyncSequence(config.namespaces.map { namespace =>
      val cli: WSRequest = client(s"/api/v1/namespaces/$namespace/secrets")
      () =>
        cli
          .addHttpHeaders(
            "Accept" -> "application/json"
          )
          .get()
          .map { resp =>
            if (resp.status == 200) {
              (resp.json \ "items").as[JsArray].value.map { item =>
                KubernetesSecret(item)
              }
            } else {
              resp.ignore()
              if (logger.isDebugEnabled) logger.debug(s"fetchSecrets: bad status ${resp.status}")
              Seq.empty
            }
          }
    }).map(_.flatten)
  }
  def fetchSecretsAndFilterLabels(): Future[Seq[KubernetesSecret]] = {
    asyncSequence(config.namespaces.map { namespace =>
      val cli: WSRequest = client(s"/api/v1/namespaces/$namespace/secrets")
      () =>
        cli
          .addHttpHeaders(
            "Accept" -> "application/json"
          )
          .get()
          .map { resp =>
            if (resp.status == 200) {
              filterLabels((resp.json \ "items").as[JsArray].value.map { item =>
                KubernetesSecret(item)
              })
            } else {
              resp.ignore()
              if (logger.isDebugEnabled) logger.debug(s"fetchSecretsAndFilterLabels: bad status ${resp.status}")
              Seq.empty
            }
          }
    }).map(_.flatten)
  }

  def fetchDeployment(namespace: String, name: String): Future[Option[KubernetesDeployment]] = {
    val cli: WSRequest = client(s"/apis/apps/v1/namespaces/$namespace/deployments/$name", false)
    cli
      .addHttpHeaders(
        "Accept" -> "application/json"
      )
      .get()
      .map { resp =>
        if (resp.status == 200) {
          KubernetesDeployment(resp.json).some
        } else {
          resp.ignore()
          if (logger.isDebugEnabled) logger.debug(s"fetchDeployment: bad status ${resp.status}")
          None
        }
      }
  }

  def fetchConfigMap(namespace: String, name: String): Future[Option[KubernetesConfigMap]] = {
    val cli: WSRequest = client(s"/api/v1/namespaces/$namespace/configmaps/$name", false)
    cli
      .addHttpHeaders(
        "Accept" -> "application/json"
      )
      .get()
      .map { resp =>
        if (resp.status == 200) {
          KubernetesConfigMap(resp.json).some
        } else {
          resp.ignore()
          if (logger.isDebugEnabled) logger.debug(s"fetchConfigMap: bad status ${resp.status}")
          None
        }
      }
  }

  def updateConfigMap(
      namespace: String,
      name: String,
      newValue: KubernetesConfigMap
  ): Future[Either[(Int, String), KubernetesConfigMap]] = {
    val cli: WSRequest = client(s"/api/v1/namespaces/$namespace/configmaps/$name", false)
    val req            = cli
      .addHttpHeaders(
        "Accept"       -> "application/json",
        "Content-Type" -> "application/json"
      )
    req
      .put(newValue.raw)
      .map { resp =>
        Try {
          if (resp.status == 200 || resp.status == 201) {
            KubernetesConfigMap(resp.json).right
          } else {
            Left((resp.status, resp.body))
          }
        } match {
          case Success(r) => r
          case Failure(e) =>
            resp.ignore()
            Left((0, e.getMessage))
        }
      }
      .andThen { case Failure(exception) =>
        req.ignore()
      }
  }

  def fetchOtoroshiResources[T](
      pluralName: String,
      reader: Reads[T],
      customize: (JsValue, KubernetesOtoroshiResource) => JsValue = (a, b) => a
  ): Future[Seq[OtoResHolder[T]]] = {
    asyncSequence(config.namespaces.flatMap { namespace =>
      Seq(
        fetchOtoroshiResourcesForNamespaceAndVersion[T](pluralName, namespace, "v1", reader, customize),
        fetchOtoroshiResourcesForNamespaceAndVersion[T](pluralName, namespace, "v1alpha1", reader, customize)
      )
    }).map(_.flatten)
  }

  def fetchOtoroshiResourcesForNamespaceAndVersion[T](
      pluralName: String,
      namespace: String,
      version: String,
      reader: Reads[T],
      customize: (JsValue, KubernetesOtoroshiResource) => JsValue = (a, b) => a
  ): () => Future[Seq[OtoResHolder[T]]] = {
    val cli: WSRequest = client(s"/apis/proxy.otoroshi.io/$version/namespaces/$namespace/$pluralName")
    () => {
      cli
        .addHttpHeaders(
          "Accept" -> "application/json"
        )
        .get()
        .map { resp =>
          Try {
            if (resp.status == 200) {
              filterLabels((resp.json \ "items").as[JsArray].value.map(v => KubernetesOtoroshiResource(v)))
                .map { item =>
                  val spec                      = (item.raw \ "spec").as[JsValue]
                  val (failed, err, customSpec) = Try(customize(spec, item)) match {
                    case Success(value) => (false, None, value)
                    case Failure(e)     => (true, e.some, spec)
                  }
                  Try {
                    (reader.reads(customSpec), item.raw)
                  }.debug {
                    case Success(_) if failed => {
                      logger.error(s"error while customizing spec entity of type $pluralName", err.get)
                      FailedCrdParsing(
                        `@id` = env.snowflakeGenerator.nextIdStr(),
                        `@env` = env.env,
                        namespace = namespace,
                        pluralName = pluralName,
                        crd = item.raw,
                        customizedSpec = customSpec,
                        error = err.map(_.getMessage).getOrElse("--")
                      ).toAnalytics()(env)
                    }
                    case Success(_)           => ()
                    case Failure(e)           =>
                      logger.error(s"error while reading entity of type $pluralName", e)
                      FailedCrdParsing(
                        `@id` = env.snowflakeGenerator.nextIdStr(),
                        `@env` = env.env,
                        namespace = namespace,
                        pluralName = pluralName,
                        crd = item.raw,
                        customizedSpec = customSpec,
                        error = e.getMessage
                      ).toAnalytics()(env)
                  }
                }
                .collect { case Success((JsSuccess(item, _), raw)) =>
                  OtoResHolder(raw, item)
                }
            } else {
              resp.ignore()
              if (logger.isDebugEnabled)
                logger.debug(s"fetchOtoroshiResources ${pluralName}: bad status ${resp.status}")
              Seq.empty
            }
          } match {
            case Success(r) => r
            case Failure(e) => Seq.empty
          }
        }
    }
  }

  def createSecret(
      namespace: String,
      name: String,
      typ: String,
      data: JsValue,
      kind: String,
      id: String
  ): Future[Option[KubernetesSecret]] = {
    val cli: WSRequest = client(s"/api/v1/namespaces/$namespace/secrets", false)
    val req            = cli
      .addHttpHeaders(
        "Accept"       -> "application/json",
        "Content-Type" -> "application/json"
      )
    req
      .post(
        Json.obj(
          "apiVersion" -> "v1",
          "kind"       -> "Secret",
          "metadata"   -> Json.obj(
            "name"        -> name,
            "annotations" -> Json.obj(
              "otoroshi.io/kind" -> kind,
              "otoroshi.io/id"   -> id
            )
          ),
          "type"       -> typ,
          "data"       -> data
        )
      )
      .map { resp =>
        Try {
          if (resp.status == 200 || resp.status == 201) {
            KubernetesSecret(resp.json).some
          } else {
            resp.ignore()
            // logger.error(s"error create cert: ${resp.status} - ${resp.body}")
            None
          }
        } match {
          case Success(r) => r
          case Failure(e) =>
            // logger.error(s"error create cert", e)
            resp.ignore()
            None
        }
      }
      .andThen { case Failure(_) =>
        req.ignore()
      }
  }

  def updateSecret(
      namespace: String,
      name: String,
      typ: String,
      data: JsObject,
      kind: String,
      id: String
  ): Future[Option[KubernetesSecret]] = {
    val cli: WSRequest = client(s"/api/v1/namespaces/$namespace/secrets/$name", false)
    val req            = cli
      .addHttpHeaders(
        "Accept"       -> "application/json",
        "Content-Type" -> "application/json"
      )
    req
      .put(
        Json.obj(
          "apiVersion" -> "v1",
          "kind"       -> "Secret",
          "metadata"   -> Json.obj(
            "name"        -> name,
            "annotations" -> Json.obj(
              "otoroshi.io/kind" -> kind,
              "otoroshi.io/id"   -> id
            )
          ),
          "type"       -> typ,
          "data"       -> data
        )
      )
      .map { resp =>
        Try {
          if (resp.status == 200 || resp.status == 201) {
            KubernetesSecret(resp.json).some
          } else {
            resp.ignore()
            // logger.error(s"error update cert: ${resp.status} - ${resp.body}")
            None
          }
        } match {
          case Success(r) => r
          case Failure(e) =>
            resp.ignore()
            // logger.error(s"error update cert", e)
            None
        }
      }
      .andThen { case Failure(_) =>
        req.ignore()
      }
  }

  def deleteSecret(namespace: String, name: String): Future[Either[String, Unit]] = {
    val cli: WSRequest = client(s"/api/v1/namespaces/$namespace/secrets/$name", false)
    val req            = cli
      .addHttpHeaders(
        "Accept" -> "application/json"
      )
    req
      .delete()
      .map { resp =>
        Try {
          if (resp.status == 200) {
            resp.ignore()
            ().right
          } else {
            resp.body.left
          }
        } match {
          case Success(r) => r
          case Failure(e) =>
            resp.ignore()
            e.getMessage.left
        }
      }
  }

  def patchDeployment(namespace: String, name: String, body: JsValue): Future[Option[KubernetesDeployment]] = {
    val cli: WSRequest = client(s"/apis/apps/v1/namespaces/$namespace/deployments/$name", false)
    val req            = cli
      .addHttpHeaders(
        "Accept"       -> "application/json",
        "Content-Type" -> "application/json-patch+json"
      )
    req
      .patch(body)
      .map { resp =>
        Try {
          if (resp.status == 200 || resp.status == 201) {
            KubernetesDeployment(resp.json).some
          } else {
            resp.ignore()
            if (logger.isDebugEnabled) logger.debug(s"patchDeployment: bad status ${resp.status}")
            None
          }
        } match {
          case Success(r) => r
          case Failure(e) =>
            resp.ignore()
            None
        }
      }
      .andThen { case Failure(_) =>
        req.ignore()
      }
  }

  def fetchOpenshiftDnsOperator(): Future[Option[KubernetesOpenshiftDnsOperator]] = {
    val cli: WSRequest = client(s"/apis/operator.openshift.io/v1/dnses/default", false)
    cli
      .addHttpHeaders(
        "Accept" -> "application/json"
      )
      .get()
      .map { resp =>
        if (resp.status == 200) {
          KubernetesOpenshiftDnsOperator(resp.json).some
        } else {
          resp.ignore()
          if (logger.isDebugEnabled) logger.debug(s"fetchOpenshiftDnsOperator: bad status ${resp.status}")
          None
        }
      }
  }

  def updateOpenshiftDnsOperator(
      source: KubernetesOpenshiftDnsOperator
  ): Future[Option[KubernetesOpenshiftDnsOperator]] = {
    fetchOpenshiftDnsOperator().flatMap {
      case None      => None.future
      case Some(dns) => {
        val cli: WSRequest = client(s"/apis/operator.openshift.io/v1/dnses/default", false)
        val req            = cli
          .addHttpHeaders(
            "Accept" -> "application/json"
          )
        req
          .put(dns.raw.as[JsObject] ++ Json.obj("spec" -> source.spec))
          .map { resp =>
            if (resp.status == 200) {
              KubernetesOpenshiftDnsOperator(resp.json).some
            } else {
              resp.ignore()
              if (logger.isDebugEnabled) logger.debug(s"updateOpenshiftDnsOperator: bad status ${resp.status}")
              None
            }
          }
          .andThen { case Failure(_) =>
            req.ignore()
          }
      }
    }
  }

  def fetchMutatingWebhookConfiguration(name: String): Future[Option[KubernetesMutatingWebhookConfiguration]] = {
    val cli: WSRequest = client(s"/apis/admissionregistration.k8s.io/v1/mutatingwebhookconfigurations/$name", false)
    cli
      .addHttpHeaders(
        "Accept" -> "application/json"
      )
      .get()
      .map { resp =>
        if (resp.status == 200) {
          KubernetesMutatingWebhookConfiguration(resp.json).some
        } else {
          resp.ignore()
          if (logger.isDebugEnabled) logger.debug(s"fetchMutatingWebhookConfiguration: bad status ${resp.status}")
          None
        }
      }
  }

  def patchMutatingWebhookConfiguration(
      name: String,
      body: JsValue
  ): Future[Option[KubernetesMutatingWebhookConfiguration]] = {
    val cli: WSRequest = client(s"/apis/admissionregistration.k8s.io/v1/mutatingwebhookconfigurations/$name", false)
    val req            = cli
      .addHttpHeaders(
        "Accept"       -> "application/json",
        "Content-Type" -> "application/json-patch+json"
      )
    req
      .patch(body)
      .map { resp =>
        Try {
          if (resp.status == 200 || resp.status == 201) {
            KubernetesMutatingWebhookConfiguration(resp.json).some
          } else {
            resp.ignore()
            if (logger.isDebugEnabled) logger.debug(s"patchMutatingWebhookConfiguration: bad status ${resp.status}")
            None
          }
        } match {
          case Success(r) => r
          case Failure(e) =>
            resp.ignore()
            None
        }
      }
      .andThen { case Failure(_) =>
        req.ignore()
      }
  }

  def fetchValidatingWebhookConfiguration(name: String): Future[Option[KubernetesValidatingWebhookConfiguration]] = {
    val cli: WSRequest = client(s"/apis/admissionregistration.k8s.io/v1/validatingwebhookconfigurations/$name", false)
    cli
      .addHttpHeaders(
        "Accept" -> "application/json"
      )
      .get()
      .map { resp =>
        if (resp.status == 200) {
          KubernetesValidatingWebhookConfiguration(resp.json).some
        } else {
          resp.ignore()
          if (logger.isDebugEnabled) logger.debug(s"fetchValidatingWebhookConfiguration: bad status ${resp.status}")
          None
        }
      }
  }

  def patchValidatingWebhookConfiguration(
      name: String,
      body: JsValue
  ): Future[Option[KubernetesValidatingWebhookConfiguration]] = {
    val cli: WSRequest = client(s"/apis/admissionregistration.k8s.io/v1/validatingwebhookconfigurations/$name", false)
    val req            = cli
      .addHttpHeaders(
        "Accept"       -> "application/json",
        "Content-Type" -> "application/json-patch+json"
      )
    req
      .patch(body)
      .map { resp =>
        Try {
          if (resp.status == 200 || resp.status == 201) {
            KubernetesValidatingWebhookConfiguration(resp.json).some
          } else {
            resp.ignore()
            if (logger.isDebugEnabled) logger.debug(s"patchValidatingWebhookConfiguration: bad status ${resp.status}")
            None
          }
        } match {
          case Success(r) => r
          case Failure(e) =>
            resp.ignore()
            None
        }
      }
      .andThen { case Failure(_) =>
        req.ignore()
      }
  }

  def watchOtoResources(
      namespaces: Seq[String],
      resources: Seq[String],
      timeout: Int,
      stop: => Boolean,
      labelSelector: Option[String] = None
  ): Source[Seq[ByteString], _] = {
    Source.combine(
      watchResources(namespaces, resources, "proxy.otoroshi.io/v1", timeout, stop, labelSelector),
      watchResources(namespaces, resources, "proxy.otoroshi.io/v1alpha1", timeout, stop, labelSelector)
    )(Concat(_))
  }

  def watchNetResources(
      namespaces: Seq[String],
      resources: Seq[String],
      timeout: Int,
      stop: => Boolean,
      labelSelector: Option[String] = None
  ): Source[Seq[ByteString], _] = {
    watchResources(namespaces, resources, "networking.k8s.io/v1beta1", timeout, stop, labelSelector)
  }

  def watchKubeResources(
      namespaces: Seq[String],
      resources: Seq[String],
      timeout: Int,
      stop: => Boolean,
      labelSelector: Option[String] = None
  ): Source[Seq[ByteString], _] = {
    watchResources(namespaces, resources, "v1", timeout, stop, labelSelector, "/api")
  }

  def watchResources(
      namespaces: Seq[String],
      resources: Seq[String],
      api: String,
      timeout: Int,
      stop: => Boolean,
      labelSelector: Option[String] = None,
      root: String = "/apis"
  ): Source[Seq[ByteString], _] = {
    if (namespaces.contains("*")) {
      resources
        .map(r => watchResource("*", r, api, timeout, stop, labelSelector, root))
        .foldLeft(Source.empty[Seq[ByteString]])((s1, s2) => s1.merge(s2))
    } else {
      resources
        .flatMap(r => namespaces.map(n => watchResource(n, r, api, timeout, stop, labelSelector, root)))
        .foldLeft(Source.empty[Seq[ByteString]])((s1, s2) => s1.merge(s2))
    }
  }

  def watchResource(
      namespace: String,
      resource: String,
      api: String,
      timeout: Int,
      stop: => Boolean,
      labelSelector: Option[String] = None,
      root: String = "/apis"
  ): Source[Seq[ByteString], _] = {

    import otoroshi.utils.http.Implicits._

    val lastTime = new AtomicLong(0L)
    val last     = new AtomicReference[String]("0")
    Source
      .repeat(())
      .flatMapConcat { _ =>
        val now = System.currentTimeMillis()
        if ((lastTime.get() + 5000) > now) {
          if (logger.isDebugEnabled) logger.debug("call too close, waiting for 5 secs")
          Source.single(Source.empty).delay(5.seconds).flatMapConcat(v => v)
        } else {
          lastTime.set(now)
          if (logger.isDebugEnabled)
            logger.debug(s"watch on ${api} / ${namespace} / ${resource} for ${timeout} seconds ! ")
          val lblStart                              = labelSelector.map(s => s"?labelSelector=$s").getOrElse("")
          val cliStart: WSRequest                   = client(s"${root}/$api/namespaces/$namespace/$resource$lblStart")
          val f: Future[Source[Seq[ByteString], _]] = cliStart
            .addHttpHeaders(
              "Accept" -> "application/json"
            )
            .withMethod("GET")
            .withRequestTimeout(timeout.seconds)
            .get()
            .flatMap { list =>
              if (list.status == 200) {
                val resourceVersionStart = (list.json \ "metadata" \ "resourceVersion").asOpt[String].getOrElse("0")
                last.set(resourceVersionStart)
                val lbl                  = labelSelector.map(s => s"&labelSelector=$s").getOrElse("")
                val cli: WSRequest       = client(
                  s"${root}/$api/namespaces/$namespace/$resource?watch=1&resourceVersion=${last.get()}&timeoutSeconds=$timeout$lbl"
                )
                cli
                  .addHttpHeaders(
                    "Accept" -> "application/json"
                  )
                  .withMethod("GET")
                  .withRequestTimeout(timeout.seconds)
                  .stream()
                  .map { resp =>
                    if (resp.status == 200) {
                      resp.bodyAsSource
                        .via(Framing.delimiter("\n".byteString, Int.MaxValue, true))
                        .map(_.utf8String)
                        .filterNot(_.trim.isEmpty)
                        .map { line =>
                          val json            = Json.parse(line)
                          val typ             = (json \ "type").asOpt[String]
                          val name            = (json \ "object" \ "metadata" \ "name").asOpt[String]
                          val ns              = (json \ "object" \ "metadata" \ "namespace").asOpt[String]
                          val resourceVersion = (json \ "object" \ "metadata" \ "resourceVersion").asOpt[String]
                          if (logger.isDebugEnabled)
                            logger.debug(
                              s"received event for ${api}/${namespace}/${resource} - $typ - $ns/$name($resourceVersion)"
                            )
                          resourceVersion.foreach(v => last.set(v))
                          ByteString(line)
                        }
                        .groupedWithin(1000, 2.seconds)
                    } else {
                      resp.ignore()
                      Source.empty
                    }
                  }
                  .recover { case e =>
                    logger.error(s"error while watching ${api}/${namespace}/${resource}", e)
                    Source.empty
                  }
              } else if (list.status == 404) {
                list.ignore()
                logger.error(s"resource ${resource} of api ${api} does not exists on namespace ${namespace}")
                Source.empty.future
              } else {
                list.ignore()
                logger.error(
                  s"error while trying to get ${resource} of api ${api} on namespace ${namespace}: ${list.status} - ${list.body}"
                )
                Source.empty.future
              }
            }
            .recover { case e =>
              logger.error(s"error while fetching latest version of ${api}/${namespace}/${resource}", e)
              Source.empty
            }
          Source.future(f).flatMapConcat(v => v)
        }
      }
      .filterNot(_.isEmpty)
      .takeWhile(_ => !stop)
  }
}
