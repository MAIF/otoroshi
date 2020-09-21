package otoroshi.plugins.envoy

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import akka.http.scaladsl.model.Uri
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import env.Env
import models.ServiceDescriptor
import otoroshi.script.{AfterRequestContext, BeforeRequestContext, HttpRequest, RequestTransformer, TransformerRequestBodyContext, TransformerRequestContext}
import play.api.libs.json.{JsArray, JsNull, JsObject, JsString, JsValue, Json}
import play.api.mvc.{Result, Results}
import otoroshi.utils.syntax.implicits._
import ssl.Cert

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

  def certificateToJson(certificate: Cert): JsObject = {
    Json.obj(
      "certificate_chain" -> Json.obj("inline_string" -> certificate.chain)
    ).applyOnIf(certificate.privateKey.trim.nonEmpty) { obj =>
      obj ++ Json.obj("private_key" -> Json.obj("inline_string" -> certificate.privateKey))
    }.applyOnIf(certificate.password.isDefined) { obj =>
      obj ++ Json.obj("password" -> Json.obj("inline_string" -> certificate.password.get))
    }
  }

  def handleClusterDiscovery(body: JsValue)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Result] = {

    def serviceToCluster(service: ServiceDescriptor): JsObject = {
      Json.obj(
        "@type" -> "type.googleapis.com/envoy.config.cluster.v3.Cluster",
        "name" -> s"target_${service.id}",
        "connect_timeout" -> "10s", // TODO: tune it according to desc
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

        val mtls = service.targets.exists(_.mtlsConfig.mtls)
        val clientCert = service.targets.flatMap(_.mtlsConfig.actualCerts).headOption
        val trustedCert = service.targets.flatMap(_.mtlsConfig.actualTrustedCerts.filter(_.ca)).headOption
        val trustAll = service.targets.exists(_.mtlsConfig.trustAll)
        obj ++ Json.obj(
          "transport_socket" -> Json.obj(
            "name" -> "envoy.transport_sockets.tls",
            "typed_config" -> Json.obj(
              // TODO: plug mtls support
              "@type" -> "type.googleapis.com/envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext",
              "sni" -> service.targets.find(_.scheme.toLowerCase() == "https").map(_.theHost).getOrElse(service.targets.head.theHost).asInstanceOf[String],
            ).applyOnIf(mtls && clientCert.isDefined) { obj =>
              obj ++ Json.obj(
                "common_tls_context" -> Json.obj(
                  "tls_certificates" -> JsArray(Seq(certificateToJson(clientCert.get)))
                ),
                "validation_context" -> Json.obj(
                  "trust_chain_verification" -> (if (trustAll) "ACCEPT_UNTRUSTED" else "VERIFY_TRUST_CHAIN").asInstanceOf[String]
                ).applyOnIf(trustedCert.isDefined) { obj =>
                  obj ++ Json.obj("trusted_ca" -> Json.obj("inline_string" -> trustedCert.get.chain))
                }
              )
            }
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

    def extractFilters(service: ServiceDescriptor): JsArray = {
      var arr = Json.arr();
      if (service.buildMode) {
        arr :+ Json.obj(
          "name" -> "envoy.filters.http.lua",
          "typed_config" -> Json.obj(
            "@type" -> "type.googleapis.com/envoy.extensions.filters.http.lua.v3.Lua",
            "inline_code" ->
              """function envoy_on_request(request_handle)
                |  request_handle:respond(
                |    {[":status"] = "403",
                |     ["upstream_foo"] = "foo"},
                |    "nope")
                |end
                |function envoy_on_response(response_handle)
                |  response_handle:respond(
                |    {[":status"] = "403",
                |     ["upstream_foo"] = "foo"},
                |    "nope")
                |end
                |""".stripMargin
          )
        )
      }
      arr
    }

    def serviceToJson(service: ServiceDescriptor): JsObject = {
      // https://www.envoyproxy.io/docs/envoy/latest/api-v3/config/route/v3/route_components.proto#envoy-v3-api-msg-config-route-v3-route
      Json.obj(
        "name" -> s"service_${service.id}",
        "domains" -> JsArray(service.allHosts.distinct.map(JsString.apply)),
        "routes" -> Json.arr(
          Json.obj(
            "match" -> Json.obj(
              "prefix" -> service.matchingRoot.getOrElse("/").asInstanceOf[String]
            )
          ).applyOnIf(service.additionalHeaders.nonEmpty) { obj =>
            obj ++ Json.obj("request_headers_to_add" -> JsArray(service.additionalHeaders.toSeq.map {
              case (key, value) => Json.obj("header" -> Json.obj("key" -> key, "value" -> value), "append" -> true)
            }))
          }.applyOnIf(service.removeHeadersIn.nonEmpty) { obj =>
            obj ++ Json.obj("request_headers_to_remove" -> JsArray(service.removeHeadersIn.map(JsString.apply)))
          }.applyOnIf(service.additionalHeadersOut.nonEmpty) { obj =>
            obj ++ Json.obj("response_headers_to_add" -> JsArray(service.additionalHeadersOut.toSeq.map {
              case (key, value) => Json.obj("header" -> Json.obj("key" -> key, "value" -> value), "append" -> true)
            }))
          }.applyOnIf(service.removeHeadersOut.nonEmpty) { obj =>
            obj ++ Json.obj("response_headers_to_remove" -> JsArray(service.removeHeadersOut.map(JsString.apply)))
          }.applyOnIf(!service.redirection.enabled && !service.buildMode && !service.maintenanceMode) { obj =>
            obj ++ Json.obj(
              "route" -> Json.obj(
                "auto_host_rewrite" -> service.overrideHost,
                "cluster" -> s"target_${service.id}"
              )
            )
          }.applyOnIf(!service.redirection.enabled && service.buildMode && !service.maintenanceMode) { obj =>
            obj ++ Json.obj(
              "direct_response" -> Json.obj(
                "status" -> 503,
                "body" -> Json.obj(
                  "inline_string" -> "<h1>Service under construction</h1>"
                )
              )
            )
          }.applyOnIf(!service.redirection.enabled && !service.buildMode && service.maintenanceMode) { obj =>
            obj ++ Json.obj(
              "direct_response" -> Json.obj(
                "status" -> 503,
                "body" -> Json.obj(
                  "inline_string" -> "<h1>Service in maintenance</h1>"
                )
              )
            )
          }.applyOnIf(service.redirection.enabled && !service.buildMode && !service.maintenanceMode) { obj =>
            val uri = Uri(service.redirection.to)
            obj ++ Json.obj(
              "redirect" -> Json.obj(
                "response_code" -> (service.redirection.code match {
                  case 301 => "MOVED_PERMANENTLY"
                  case 302 => "FOUND"
                  case 303 => "SEE_OTHER"
                  case 307 => "TEMPORARY_REDIRECT"
                  case 308 => "PERMANENT_REDIRECT"
                  case _ => "SEE_OTHER"
                }),
                "scheme_redirect" -> uri.scheme,
                "host_redirect" -> uri.authority.host.toString(),
                "port_redirect" -> uri.authority.port,
                "path_redirect" -> uri.path.toString(),
              )
            )
          }
        )
      )
    }

    def httpsListener(id: String, port: Int, services: Seq[ServiceDescriptor], certificates: Seq[Cert]): JsObject = {

      // TODO: try to group with wildcard certificates
      val chains = services.flatMap(s => s.allHosts.map(h => (h, s))).groupBy(_._1).mapValues(_.map(_._2)).map {
        case (host, servs) =>
          val certs = certificates.filter(_.matchesDomain(host)).sortWith((c1, c2) => c1.allDomains.exists(_.contains("*")))
          (host, (certs, servs))
      }

      def filterChain(t: (String, (Seq[Cert], Seq[ServiceDescriptor]))): Option[JsObject] = t match {
        case (host, (certs, servs)) => certs.headOption.map { cert =>
          Json.obj(
            "filter_chain_match" -> Json.obj(
              "server_names" -> Json.arr(host)
            ),
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
                      servs.map(serviceToJson)
                    )
                  ),
                  "http_filters" -> Json.arr(
                    Json.obj(
                      "name" -> "envoy.filters.http.router"
                    )
                  )
                )
              )
            ),
            "transport_socket" -> Json.obj(
              "name" -> "envoy.transport_sockets.tls",
              "typed_config" -> Json.obj(
                "require_client_certificate" -> true, // TODO: plug mtls support
                "@type" -> "type.googleapis.com/envoy.extensions.transport_sockets.tls.v3.DownstreamTlsContext",
                "common_tls_context" -> Json.obj(
                  "tls_certificates" -> JsArray(Seq(certificateToJson(cert)))
                )
              )
            )
          )
        }
      }

      val jsChains: Seq[JsValue] = chains.toSeq.flatMap(filterChain)

      Json.obj(
        "@type" -> "type.googleapis.com/envoy.config.listener.v3.Listener",
        "name" -> s"listener_$id",
        "address" -> Json.obj(
          "socket_address" -> Json.obj(
            "address" -> "0.0.0.0",
            "port_value" -> port
          )
        ),
        "listener_filters" -> Json.arr(
          Json.obj(
            "name" -> "envoy.filters.listener.tls_inspector",
            "typed_config" -> Json.obj()
          )
        ),
        "filter_chains" -> JsArray(jsChains)
      )
    }

    def httpListener(id: String, port: Int, services: Seq[ServiceDescriptor]): JsObject = {
      Json.obj(
        "@type" -> "type.googleapis.com/envoy.config.listener.v3.Listener",
        "name" -> s"listener_$id",
        "address" -> Json.obj(
          "socket_address" -> Json.obj(
            "address" -> "0.0.0.0",
            "port_value" -> port
          )
        ),
        "filter_chains" -> Json.obj(
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
                    services.map(serviceToJson)
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
    }

    env.datastores.certificatesDataStore.findAll().flatMap { __certs =>
      env.datastores.serviceDescriptorDataStore.findAll().map { _services =>

        val services = _services.filter(_.enabled)

        val _certs = __certs.sortWith((a, b) => a.id.compareTo(b.id) > 0).map(_.enrich())

        val certs = _certs
          .filterNot(_.keypair)
          .filterNot(_.ca)
          .filterNot(_.privateKey.trim.isEmpty)

        val listeners = Seq(httpListener("http", 10080/*env.port*/, services), httpsListener("https", 10443/*env.httpsPort*/, services, certs))
        Results.Ok(Json.obj(
          "version_info" -> "1.0",
          "type_url" -> "type.googleapis.com/envoy.config.listener.v3.Listener",
          "resources" -> JsArray(listeners)
        ))
      }
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
