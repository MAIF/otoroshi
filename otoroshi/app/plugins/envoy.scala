package otoroshi.plugins.envoy

import java.util.concurrent.atomic.AtomicBoolean

import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import env.Env
import models.ServiceDescriptor
import otoroshi.script.{AfterRequestContext, BeforeRequestContext, HttpRequest, RequestTransformer, TransformerRequestBodyContext, TransformerRequestContext}
import play.api.libs.json.{JsArray, JsObject, JsString, JsValue, Json}
import play.api.mvc.{Result, Results}
import otoroshi.utils.syntax.implicits._

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future, Promise}

class EnvoyControlPlane extends RequestTransformer {

  private val awaitingRequests = new TrieMap[String, Promise[Source[ByteString, _]]]()

  override def name: String = "Envoy Control Plane (experimental)"

  override def defaultConfig: Option[JsObject] = Json.obj(
    "EnvoyControlPlane" -> Json.obj(
      "enabled" -> true,
    )
  ).some


  override def description: Option[String] = """This plugin will expose the otoroshi state to envoy instances using the xDS V3 API`.
    |
    |Right now, all the features of otoroshi cannot be exposed as is through Envoy.
    |
    |This plugin can accept the following configuration
    |
    |```json
    |{
    |  "EnvoyControlPlane": {
    |    "enabled": true
    |  }
    |}
    |```
  """.stripMargin.some

  def handleClusterDiscovery(body: JsValue)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Result] = {

    def serviceToCluster(service: ServiceDescriptor): JsObject = {
      Json.obj(
        "@type" -> "type.googleapis.com/envoy.config.cluster.v3.Cluster",
        "name" -> s"target_${service.id}",
        "connect_timeout" -> "0.25s", // TODO: tune it according to desc
        "type" -> "STRICT_DNS",
        // "dns_lookup_family": "V4_ONLY",
        "lb_policy" -> "ROUND_ROBIN", // TODO: tune it according to desc
        "load_assignment" -> Json.obj(
          "cluster_name" -> s"target_${service.id}",
          "endpoints" -> Json.arr(
            Json.obj(
              "lb_endpoints" -> JsArray(
                service.targets.map { target =>
                  Json.obj(
                    "load_balancing_weight" -> target.weight,
                    "endpoint" -> Json.obj(
                      "address" -> Json.obj(
                        "socket_address" -> Json.obj(
                          "address" -> target.ipAddress.getOrElse(target.theHost).asInstanceOf[String],
                          "port_value" -> target.thePort
                        )
                      )
                    )
                  )
                }
              )
            )
          )
        )
      ).applyOnIf(service.targets.exists(_.scheme.toLowerCase() == "https")) { obj =>
        obj ++ Json.obj(
          "transport_socket" -> Json.obj(
            "name" -> "envoy.transport_sockets.tls",
            "typed_config" -> Json.obj(
              "@type" -> "type.googleapis.com/envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext",
              "sni" -> service.targets.find(_.scheme.toLowerCase() == "https").map(_.theHost).getOrElse(service.targets.head.theHost).asInstanceOf[String]
            )
          )
        )
      }
    }

    env.datastores.serviceDescriptorDataStore.findAll().map { services =>
      val clusters = services.map(serviceToCluster)
      Results.Ok(Json.obj(
        "version_info" -> "1.0",
        "type_url" -> "type.googleapis.com/envoy.config.cluster.v3.Cluster",
        "resources" -> JsArray(clusters)
      ))
    }
  }

  def handleListenerDiscovery(body: JsValue)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Result] = {

    def service2(service: ServiceDescriptor): JsObject = {
      Json.obj(
        "name" -> s"service_${service.id}_${service.name}",
        "domains" -> JsArray(service.allHosts.distinct.map(JsString.apply)),
        "routes" -> Json.arr(
          Json.obj(
            "match" -> Json.obj(
              "prefix" -> service.matchingRoot.getOrElse("/").asInstanceOf[String]
            ),
            "route" -> Json.obj(
              "auto_host_rewrite" -> service.overrideHost,
              "cluster" -> s"target_${service.id}"
            )
          )
        )
      )
    }

    def listener2(id: String, port: Int, services: Seq[ServiceDescriptor]): JsObject = {
      Json.obj(
        "@type" -> "type.googleapis.com/envoy.config.listener.v3.Listener",
        "name" -> s"listener_$id",
        "address" -> Json.obj(
          "socket_address" -> Json.obj(
            "address" -> "0.0.0.0",
            "port_value" -> port
          )
        ),
        "filter_chains" -> Json.arr(
          Json.obj(
            "filters" -> Json.arr(
              Json.obj(
                "name" -> "envoy.filters.network.http_connection_manager",
                "typed_config" -> Json.obj(
                  "@type" -> "type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager",
                  "stat_prefix" -> "ingress_http",
                  "codec_type" -> "AUTO",
                  "route_config" -> Json.obj(
                    "name" -> s"otoroshi",
                    "virtual_hosts" -> JsArray(
                      services.map(service2)
                    )
                  ),
                  "http_filters" -> Json.arr(
                    Json.obj(
                      "name" -> "envoy.filters.http.router"
                    )
                  )
                )
              )
            )
          )
        )
      )
    }

    env.datastores.serviceDescriptorDataStore.findAll().map { services =>
      val listeners = Seq(listener2("http", env.port + 2, services), listener2("https", env.httpsPort + 2, services))
      Results.Ok(Json.obj(
        "version_info" -> "1.0",
        "type_url" -> "type.googleapis.com/envoy.config.listener.v3.Listener",
        "resources" -> JsArray(listeners)
      ))
    }
  }

  override def beforeRequest(
    ctx: BeforeRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    awaitingRequests.putIfAbsent(ctx.snowflake, Promise[Source[ByteString, _]])
    funit
  }

  override def afterRequest(
    ctx: AfterRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    awaitingRequests.remove(ctx.snowflake)
    funit
  }

  override def transformRequestBodyWithCtx(
    ctx: TransformerRequestBodyContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, _] = {
    awaitingRequests.get(ctx.snowflake).map(_.trySuccess(ctx.body))
    ctx.body
  }

  def withBody(ctx: TransformerRequestContext)(f: JsValue => Future[Result])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    awaitingRequests.get(ctx.snowflake).map { promise =>
      val bodySource: Source[ByteString, _] = Source
        .future(promise.future)
        .flatMapConcat(s => s)
      bodySource.runFold(ByteString.empty)(_ ++ _).flatMap { body =>
        f(Json.parse(body.utf8String))
      }
    } getOrElse {
      Results.BadRequest(Json.obj("error" -> "no body provided")).future
    } map(r => Left(r))
  }

  override def transformRequestWithCtx(ctx: TransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    (ctx.request.method, ctx.request.path) match {
      case ("POST", "/v3/discovery:clusters") => withBody(ctx)(handleClusterDiscovery)
      case ("POST", "/v3/discovery:listeners") => withBody(ctx)(handleListenerDiscovery)
      case _ => Left(Results.NotFound(Json.obj("error" -> "resource not found !"))).future
    }
  }
}
