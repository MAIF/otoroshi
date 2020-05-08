package otoroshi.plugins.jobs.kubernetes

import java.util.Base64
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}
import java.util.regex.Pattern

import akka.{Done, NotUsed}
import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.{Framing, Sink, Source}
import akka.util.ByteString
import env.Env
import models._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.libs.ws.WSRequest
import ssl.{Cert, DynamicSSLEngineProvider}
import utils.UrlSanitizer
import utils.http.MtlsConfig

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

// TODO: watch res to trigger sync
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
    ).applyOn(req => config.token match {
      case None => req
      case Some(token) => req.withHttpHeaders(
        "Authorization" -> s"Bearer ${token}"
      )
    }).applyOn(req => config.userPassword match {
      case None => req
      case Some(token) => req.withHttpHeaders(
        "Authorization" -> s"Basic ${Base64.getEncoder.encodeToString(token.getBytes)}"
      )
    })
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
  def fetchSecret(namespace: String, name: String): Future[Option[KubernetesSecret]] = {
    val cli: WSRequest = client(s"/api/v1/namespaces/$namespace/secrets/$name", false)
    cli.addHttpHeaders(
      "Accept" -> "application/json"
    ).get().map { resp =>
      if (resp.status == 200) {
        KubernetesSecret(resp.json).some
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

  def fetchOtoroshiResources[T](pluralName: String, reader: Reads[T], customize: (JsValue, KubernetesOtoroshiResource) => JsValue = (a, b) => a): Future[Seq[OtoResHolder[T]]] = {
    Future.sequence(config.namespaces.map { namespace =>
      val cli: WSRequest = client(s"/apis/proxy.otoroshi.io/v1alpha1/namespaces/$namespace/$pluralName")
      cli.addHttpHeaders(
        "Accept" -> "application/json"
      ).get().map { resp =>
        Try {
          if (resp.status == 200) {
            filterLabels((resp.json \ "items").as[JsArray].value.map(v => KubernetesOtoroshiResource(v))).map { item =>
              val spec = (item.raw \ "spec").as[JsValue]
              val customSpec = customize(spec, item)
              (reader.reads(customSpec), item.raw)
            }.collect {
              case (JsSuccess(item, _), raw) => OtoResHolder(raw, item)
            }
          } else {
            Seq.empty
          }
        } match {
          case Success(r) => r
          case Failure(e) => Seq.empty
        }
      }
    }).map(_.flatten)
  }

  def createSecret(namespace: String, name: String, typ: String, data: JsValue): Future[Option[KubernetesSecret]] = {
    val cli: WSRequest = client(s"/api/v1/namespaces/$namespace/secrets", false)
    cli.addHttpHeaders(
      "Accept" -> "application/json",
      "Content-Type" -> "application/json"
    ).post(Json.obj(
      "apiVersion" -> "v1",
      "kind" -> "Secret",
      "metadata" -> Json.obj(
        "name" -> name
        // TODO: add otoroshi label on it ???
      ),
      "type" -> typ,
      "data" -> data
     )
    ).map { resp =>
      Try {
        if (resp.status == 200 || resp.status == 201) {
          KubernetesSecret(resp.json).some
        } else {
          None
        }
      } match {
        case Success(r) => r
        case Failure(e) => None
      }
    }
  }

  def updateSecret(namespace: String, name: String, typ: String, data: JsObject): Future[Option[KubernetesSecret]] = {
    val cli: WSRequest = client(s"/api/v1/namespaces/$namespace/secrets/$name", false)
    cli.addHttpHeaders(
      "Accept" -> "application/json",
      "Content-Type" -> "application/json"
    ).put(Json.obj(
      "apiVersion" -> "v1",
      "kind" -> "Secret",
      "metadata" -> Json.obj(
        "name" -> name
        // TODO: add otoroshi label on it ???
      ),
      "type" -> typ,
      "data" -> data
    )
    ).map { resp =>
      Try {
        if (resp.status == 200 || resp.status == 201) {
          KubernetesSecret(resp.json).some
        } else {
          None
        }
      } match {
        case Success(r) => r
        case Failure(e) => None
      }
    }
  }

  def patchSecret(namespace: String, name: String, typ: String, data: JsObject): Future[Option[KubernetesSecret]] = {
    val cli: WSRequest = client(s"/api/v1/namespaces/$namespace/secrets/$name", false)
    cli.addHttpHeaders(
      "Accept" -> "application/json",
      "Content-Type" -> "application/json"
    ).patch(Json.obj(
    "data" -> data
    )).map { resp =>
      Try {
        if (resp.status == 200 || resp.status == 201) {
          KubernetesSecret(resp.json).some
        } else {
          None
        }
      } match {
        case Success(r) => r
        case Failure(e) => None
      }
    }
  }

  def watchOtoResource(namespace: String, resource: String, timeout: Int, stop: AtomicBoolean, labelSelector: Option[String] = None):Source[Seq[ByteString], _] = {
    watchApi(namespace, resource, "proxy.otoroshi.io/v1alpha1", timeout, stop, labelSelector)
  }

  def watchNetResource(namespace: String, resource: String, timeout: Int, stop: AtomicBoolean, labelSelector: Option[String] = None):Source[Seq[ByteString], _] = {
    watchApi(namespace, resource, "networking.k8s.io/v1beta1", timeout, stop, labelSelector)
  }

  def watchKubeResource(namespace: String, resource: String, timeout: Int, stop: AtomicBoolean, labelSelector: Option[String] = None):Source[Seq[ByteString], _] = {
    watchApi(namespace, resource, "v1", timeout, stop, labelSelector)
  }

  def watchApi(namespace: String, resource: String, api: String, timeout: Int, stop: AtomicBoolean, labelSelector: Option[String] = None): Source[Seq[ByteString], _] = {
    val pattern = Pattern.compile(""""resourceVersion"="([0-9]*)"""")
    val last = new AtomicReference[String]("0")
    Source.repeat(())
      .flatMapConcat { _ =>
        val lbl = labelSelector.map(s => s"&labelSelector=$s").getOrElse("")
        val cli: WSRequest = client(s"/apis/$api/namespaces/$namespace/$resource?watch=true&resourceVersion=${last.get()}&timeoutSeconds=$timeout$lbl")
        val f: Future[Source[Seq[ByteString], _]] = cli.addHttpHeaders(
          "Accept" -> "application/json"
        ).withMethod("GET").stream().map { resp =>
          resp.bodyAsSource
            // .alsoTo(Sink.foreach(v => println(v.utf8String)))
            .via(Framing.delimiter("\n".byteString, Int.MaxValue, true))
            .groupedWithin(1000, 2.seconds)
            .alsoTo(Sink.foreach { v =>
              val payload = v.map(_.utf8String).mkString("")
              // println(payload)
              val m = pattern.matcher(payload)
              while(m.find()) {
                last.set(m.toMatchResult.group())
              }
              ()
            })

        }
        Source.future(f).flatMapConcat(v => v)
      }
      .takeWhile(_ => !stop.get())
  }
}
