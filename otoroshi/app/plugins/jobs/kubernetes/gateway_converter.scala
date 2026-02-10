package otoroshi.plugins.jobs.kubernetes

import otoroshi.env.Env
import otoroshi.models.{EntityLocation, HttpProtocols, RoundRobin}
import otoroshi.next.models._
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._

import scala.concurrent.ExecutionContext

object GatewayApiConverter {

  private val logger = Logger("otoroshi-plugins-kubernetes-gateway-api-converter")

  // ─── HTTPRoute conversion ──────────────────────────────────────────────────

  /**
   * Converts an HTTPRoute into one or more NgRoute Otoroshi routes.
   *
   * Each HTTPRouteRule generates a distinct NgRoute because each rule can have
   * different matches and backends.
   *
   * The ID of each generated route follows the pattern:
   *   "kubernetes-gateway-api-{namespace}-{httproute-name}-rule-{ruleIndex}"
   *
   * @param referenceGrants passed through for future cross-namespace enforcement (MVP: not enforced)
   */
  def httpRouteToNgRoutes(
      httpRoute: KubernetesHTTPRoute,
      gateways: Seq[KubernetesGateway],
      services: Seq[KubernetesService],
      endpoints: Seq[KubernetesEndpoint],
      referenceGrants: Seq[KubernetesReferenceGrant],
      conf: KubernetesConfig
  )(implicit env: Env, ec: ExecutionContext): Seq[NgRoute] = {

    val matchingGateways = resolveParentRefs(httpRoute.parentRefs, httpRoute.namespace, gateways, conf)
    if (matchingGateways.isEmpty) {
      logger.warn(s"HTTPRoute ${httpRoute.path} has no matching gateways")
      return Seq.empty
    }

    val effectiveHostnames = resolveEffectiveHostnames(httpRoute.hostnames, matchingGateways)

    httpRoute.rules.zipWithIndex.flatMap { case (rule, ruleIdx) =>
      ruleToNgRoute(httpRoute, rule, ruleIdx, effectiveHostnames, services, endpoints, referenceGrants, conf)
    }
  }

  // ─── GRPCRoute conversion ────────────────────────────────────────────────

  /**
   * Converts a GRPCRoute into one or more NgRoute Otoroshi routes.
   *
   * Similar to HTTPRoute conversion, but:
   * - gRPC method matching is mapped to HTTP/2 path: /{service}/{method}
   * - Backend targets use HTTP/2 protocol
   */
  def grpcRouteToNgRoutes(
      grpcRoute: KubernetesGRPCRoute,
      gateways: Seq[KubernetesGateway],
      services: Seq[KubernetesService],
      endpoints: Seq[KubernetesEndpoint],
      referenceGrants: Seq[KubernetesReferenceGrant],
      conf: KubernetesConfig
  )(implicit env: Env, ec: ExecutionContext): Seq[NgRoute] = {

    val matchingGateways = resolveParentRefs(grpcRoute.parentRefs, grpcRoute.namespace, gateways, conf)
    if (matchingGateways.isEmpty) {
      logger.warn(s"GRPCRoute ${grpcRoute.path} has no matching gateways")
      return Seq.empty
    }

    val effectiveHostnames = resolveEffectiveHostnames(grpcRoute.hostnames, matchingGateways)

    grpcRoute.rules.zipWithIndex.flatMap { case (rule, ruleIdx) =>
      grpcRuleToNgRoute(grpcRoute, rule, ruleIdx, effectiveHostnames, services, endpoints, referenceGrants, conf)
    }
  }

  // ─── Shared parent ref / hostname resolution ─────────────────────────────

  /**
   * Resolves parentRefs to matching Gateways+Listeners.
   * Shared by HTTPRoute and GRPCRoute conversion.
   */
  private def resolveParentRefs(
      parentRefs: Seq[HTTPRouteParentRef],
      routeNamespace: String,
      gateways: Seq[KubernetesGateway],
      conf: KubernetesConfig
  ): Seq[(KubernetesGateway, GatewayListener)] = {
    parentRefs.flatMap { parentRef =>
      val gwNamespace = parentRef.namespace.getOrElse(routeNamespace)
      val gateway     = gateways.find(gw => gw.name == parentRef.name && gw.namespace == gwNamespace)
      gateway.toSeq.flatMap { gw =>
        val listeners = parentRef.sectionName match {
          case Some(sn) => gw.listeners.filter(_.name == sn)
          case None     => gw.listeners
        }
        listeners
          .filter(l => isListenerAcceptingRoute(l, routeNamespace, gw))
          .map(l => (gw, l))
      }
    }
  }

  /**
   * Checks if a listener accepts routes from the given namespace.
   * Implements allowedRoutes.namespaces.from:
   * - "Same" (default): same namespace as the Gateway
   * - "All": all namespaces
   * - "Selector": by label selector (TODO: implement label matching)
   */
  private def isListenerAcceptingRoute(
      listener: GatewayListener,
      routeNamespace: String,
      gateway: KubernetesGateway
  ): Boolean = {
    listener.allowedRoutesNamespacesFrom match {
      case "All"      => true
      case "Same"     => routeNamespace == gateway.namespace
      case "Selector" =>
        // TODO: implement label selector matching for allowedRoutes.namespaces.from: Selector
        logger.warn(
          s"Gateway ${gateway.path} listener ${listener.name} uses namespace selector, " +
            "defaulting to allow all (not yet implemented)"
        )
        true
      case _ => false
    }
  }

  /**
   * Computes effective hostnames by intersecting:
   * - Route hostnames (HTTPRoute or GRPCRoute)
   * - Listener hostnames from attached gateways
   */
  private def resolveEffectiveHostnames(
      routeHostnames: Seq[String],
      matchingGateways: Seq[(KubernetesGateway, GatewayListener)]
  ): Seq[String] = {
    val listenerHostnames = matchingGateways.flatMap(_._2.hostname).distinct
    if (routeHostnames.isEmpty && listenerHostnames.isEmpty) {
      Seq("*")
    } else if (routeHostnames.isEmpty) {
      listenerHostnames
    } else if (listenerHostnames.isEmpty) {
      routeHostnames
    } else {
      routeHostnames.filter { rh =>
        listenerHostnames.exists(lh => hostnameMatches(lh, rh))
      }
    }
  }

  /**
   * Checks compatibility between a listener hostname and a route hostname.
   * Supports wildcard matching (*.example.com matches foo.example.com).
   */
  private def hostnameMatches(listenerHostname: String, routeHostname: String): Boolean = {
    if (listenerHostname.startsWith("*.")) {
      routeHostname.endsWith(listenerHostname.drop(1)) || routeHostname == listenerHostname.drop(2)
    } else if (routeHostname.startsWith("*.")) {
      listenerHostname.endsWith(routeHostname.drop(1)) || listenerHostname == routeHostname.drop(2)
    } else {
      listenerHostname == routeHostname
    }
  }

  /**
   * Converts a single HTTPRouteRule into NgRoute(s).
   */
  private def ruleToNgRoute(
      httpRoute: KubernetesHTTPRoute,
      rule: HTTPRouteRule,
      ruleIdx: Int,
      effectiveHostnames: Seq[String],
      services: Seq[KubernetesService],
      endpoints: Seq[KubernetesEndpoint],
      referenceGrants: Seq[KubernetesReferenceGrant],
      conf: KubernetesConfig
  )(implicit env: Env): Seq[NgRoute] = {

    val routeId = s"kubernetes-gateway-api-${httpRoute.namespace}-${httpRoute.name}-rule-$ruleIdx"
      .replace("/", "-")
      .replace(".", "-")
    val routeName = s"${httpRoute.namespace}/${httpRoute.name} rule $ruleIdx"

    val domains = buildDomains(effectiveHostnames, rule)
    val targets = buildTargets(httpRoute, rule, services, endpoints, referenceGrants)
    val plugins = buildPlugins(rule.filters)

    if (targets.isEmpty) {
      logger.warn(s"HTTPRoute ${httpRoute.path} rule $ruleIdx has no resolvable backends")
    }

    val isExact  = rule.matches.exists(_.pathType == "Exact")
    val methods  = rule.matches.flatMap(_.method).distinct

    // Check for URLRewrite path rewriting
    val urlRewriteFilter = rule.filters.find(_.filterType == "URLRewrite")
    val (stripPath, backendRoot) = urlRewriteFilter.flatMap(_.urlRewrite) match {
      case Some(rewrite) =>
        val pathRewrite = (rewrite \ "path").asOpt[JsObject]
        pathRewrite match {
          case Some(pr) if (pr \ "type").asOpt[String].contains("ReplacePrefixMatch") =>
            val replacement = (pr \ "replacePrefixMatch").asOpt[String].getOrElse("/")
            (true, replacement)
          case Some(pr) if (pr \ "type").asOpt[String].contains("ReplaceFullPath") =>
            logger.warn(
              s"HTTPRoute ${httpRoute.path} rule $ruleIdx uses ReplaceFullPath which is not fully supported, " +
                "using path as backend root"
            )
            val replacement = (pr \ "replaceFullPath").asOpt[String].getOrElse("/")
            (false, replacement)
          case _ => (false, "/")
        }
      case None => (false, "/")
    }

    val route = NgRoute(
      location = EntityLocation(),
      id = routeId,
      name = routeName,
      description = s"Generated from Gateway API HTTPRoute ${httpRoute.path}",
      tags = Seq.empty,
      metadata = Map(
        "otoroshi-provider"    -> "kubernetes-gateway-api",
        "kubernetes-name"      -> httpRoute.name,
        "kubernetes-namespace" -> httpRoute.namespace,
        "kubernetes-path"      -> httpRoute.path,
        "kubernetes-uid"       -> httpRoute.uid,
        "gateway-api-kind"     -> "HTTPRoute"
      ),
      enabled = true,
      debugFlow = false,
      capture = false,
      exportReporting = false,
      groups = Seq("default"),
      boundListeners = Seq.empty,
      frontend = NgFrontend(
        domains = domains,
        headers = Map.empty,
        query = Map.empty,
        cookies = Map.empty,
        methods = methods,
        stripPath = stripPath,
        exact = isExact
      ),
      backend = NgBackend(
        targets = targets,
        root = backendRoot,
        rewrite = false,
        loadBalancing = RoundRobin,
        healthCheck = None,
        client = NgClientConfig()
      ),
      backendRef = None,
      plugins = plugins
    )

    Seq(route)
  }

  // ─── GRPCRoute rule conversion ─────────────────────────────────────────────

  /**
   * Converts a single GRPCRouteRule into NgRoute(s).
   * gRPC method matching is mapped to path: /{service}/{method}
   * Targets use HTTP/2 protocol.
   */
  private def grpcRuleToNgRoute(
      grpcRoute: KubernetesGRPCRoute,
      rule: GRPCRouteRule,
      ruleIdx: Int,
      effectiveHostnames: Seq[String],
      services: Seq[KubernetesService],
      endpoints: Seq[KubernetesEndpoint],
      referenceGrants: Seq[KubernetesReferenceGrant],
      conf: KubernetesConfig
  )(implicit env: Env): Seq[NgRoute] = {

    val routeId = s"kubernetes-gateway-api-${grpcRoute.namespace}-${grpcRoute.name}-grpc-rule-$ruleIdx"
      .replace("/", "-")
      .replace(".", "-")
    val routeName = s"${grpcRoute.namespace}/${grpcRoute.name} grpc rule $ruleIdx"

    val domains = buildGrpcDomains(effectiveHostnames, rule)
    val targets = buildGrpcTargets(grpcRoute, rule, services, endpoints, referenceGrants)
    val plugins = buildPlugins(rule.filters)

    if (targets.isEmpty) {
      logger.warn(s"GRPCRoute ${grpcRoute.path} rule $ruleIdx has no resolvable backends")
    }

    // Determine if path is exact based on gRPC method matching
    val isExact = rule.matches.exists { m =>
      m.method.exists(mm => mm.service.isDefined && mm.method.isDefined && mm.matchType == "Exact")
    }

    val route = NgRoute(
      location = EntityLocation(),
      id = routeId,
      name = routeName,
      description = s"Generated from Gateway API GRPCRoute ${grpcRoute.path}",
      tags = Seq.empty,
      metadata = Map(
        "otoroshi-provider"    -> "kubernetes-gateway-api",
        "kubernetes-name"      -> grpcRoute.name,
        "kubernetes-namespace" -> grpcRoute.namespace,
        "kubernetes-path"      -> grpcRoute.path,
        "kubernetes-uid"       -> grpcRoute.uid,
        "gateway-api-kind"     -> "GRPCRoute"
      ),
      enabled = true,
      debugFlow = false,
      capture = false,
      exportReporting = false,
      groups = Seq("default"),
      boundListeners = Seq.empty,
      frontend = NgFrontend(
        domains = domains,
        headers = Map.empty,
        query = Map.empty,
        cookies = Map.empty,
        methods = Seq("POST"), // gRPC always uses POST
        stripPath = false,
        exact = isExact
      ),
      backend = NgBackend(
        targets = targets,
        root = "/",
        rewrite = false,
        loadBalancing = RoundRobin,
        healthCheck = None,
        client = NgClientConfig()
      ),
      backendRef = None,
      plugins = plugins
    )

    Seq(route)
  }

  /**
   * Builds domains for GRPCRoute from hostnames and gRPC method matching.
   * gRPC uses HTTP/2 paths: /{service}/{method}
   */
  private def buildGrpcDomains(hostnames: Seq[String], rule: GRPCRouteRule): Seq[NgDomainAndPath] = {
    val paths = rule.matches.flatMap { m =>
      m.method match {
        case Some(mm) =>
          val svc    = mm.service.getOrElse("*")
          val method = mm.method.getOrElse("*")
          if (svc == "*" && method == "*") Seq("/")
          else if (method == "*") Seq(s"/$svc/")
          else Seq(s"/$svc/$method")
        case None => Seq("/")
      }
    }.distinct

    if (paths.isEmpty || paths == Seq("/")) {
      hostnames.map(NgDomainAndPath.apply)
    } else {
      for {
        hostname <- hostnames
        path     <- paths
      } yield {
        if (path == "/") NgDomainAndPath(hostname)
        else NgDomainAndPath(s"$hostname$path")
      }
    }
  }

  /**
   * Resolves GRPCRoute backendRefs to NgTarget with HTTP/2 protocol.
   */
  private def buildGrpcTargets(
      grpcRoute: KubernetesGRPCRoute,
      rule: GRPCRouteRule,
      services: Seq[KubernetesService],
      endpoints: Seq[KubernetesEndpoint],
      referenceGrants: Seq[KubernetesReferenceGrant]
  ): Seq[NgTarget] = {
    rule.backendRefs.flatMap { backendRef =>
      val backendKind = backendRef.kind.getOrElse("Service")
      if (backendKind != "Service") {
        logger.warn(s"Unsupported backendRef kind: $backendKind in GRPCRoute ${grpcRoute.path}")
        Seq.empty
      } else {
        val svcNamespace = backendRef.namespace.getOrElse(grpcRoute.namespace)

        if (!isBackendRefAllowed(backendRef, grpcRoute.namespace, grpcRoute.path, "GRPCRoute", referenceGrants)) {
          Seq.empty
        } else {
          val svcPath = s"$svcNamespace/${backendRef.name}"
          val service = services.find(_.path == svcPath)
          service match {
            case Some(svc) =>
              val port = backendRef.port.getOrElse(50051)
              Seq(NgTarget(
                id = s"${svcPath}:$port",
                hostname = svc.clusterIP,
                port = port,
                tls = false,
                weight = backendRef.weight,
                protocol = HttpProtocols.HTTP_2_0,
                predicate = otoroshi.models.AlwaysMatch,
                ipAddress = None
              ))
            case None =>
              logger.warn(s"Service $svcPath not found for backendRef in GRPCRoute ${grpcRoute.path}")
              Seq.empty
          }
        }
      }
    }
  }

  // ─── HTTPRoute rule conversion ────────────────────────────────────────────

  /**
   * Builds domains (NgDomainAndPath) from hostnames and matches.
   *
   * For PathPrefix "/api" with hostname "app.example.com" -> "app.example.com/api"
   * For Exact "/api/v1" with hostname "app.example.com" -> "app.example.com/api/v1"
   */
  private def buildDomains(hostnames: Seq[String], rule: HTTPRouteRule): Seq[NgDomainAndPath] = {
    if (rule.matches.isEmpty || rule.matches.forall(m => m.pathValue == "/")) {
      hostnames.map(NgDomainAndPath.apply)
    } else {
      for {
        hostname <- hostnames
        m        <- rule.matches
      } yield {
        val path = m.pathValue
        if (path == "/") NgDomainAndPath(hostname)
        else NgDomainAndPath(s"$hostname$path")
      }
    }
  }

  /**
   * Resolves backendRefs to NgTarget Otoroshi targets.
   *
   * @param referenceGrants passed for future cross-namespace validation. Currently logs warnings only.
   */
  private def buildTargets(
      httpRoute: KubernetesHTTPRoute,
      rule: HTTPRouteRule,
      services: Seq[KubernetesService],
      endpoints: Seq[KubernetesEndpoint],
      referenceGrants: Seq[KubernetesReferenceGrant]
  ): Seq[NgTarget] = {
    rule.backendRefs.flatMap { backendRef =>
      val backendKind = backendRef.kind.getOrElse("Service")
      if (backendKind != "Service") {
        logger.warn(s"Unsupported backendRef kind: $backendKind in HTTPRoute ${httpRoute.path}")
        Seq.empty
      } else {
        val svcNamespace = backendRef.namespace.getOrElse(httpRoute.namespace)

        if (!isBackendRefAllowed(backendRef, httpRoute.namespace, httpRoute.path, "HTTPRoute", referenceGrants)) {
          Seq.empty
        } else {
          val svcPath = s"$svcNamespace/${backendRef.name}"
          val service = services.find(_.path == svcPath)
          service match {
            case Some(svc) =>
              val port = backendRef.port.getOrElse(80)
              Seq(NgTarget(
                id = s"${svcPath}:$port",
                hostname = svc.clusterIP,
                port = port,
                tls = false,
                weight = backendRef.weight,
                protocol = HttpProtocols.HTTP_1_1,
                predicate = otoroshi.models.AlwaysMatch,
                ipAddress = None
              ))
            case None      =>
              logger.warn(s"Service $svcPath not found for backendRef in HTTPRoute ${httpRoute.path}")
              Seq.empty
          }
        }
      }
    }
  }

  /**
   * Checks if a cross-namespace backendRef is allowed by ReferenceGrants.
   *
   * MVP: Always returns true. Logs a warning if cross-namespace reference is detected
   * without a matching ReferenceGrant.
   *
   * TODO (CRITICAL): Implement real ReferenceGrant enforcement.
   * When implementing, check:
   *   - backendRef.namespace != routeNamespace (cross-namespace)
   *   - Look for a ReferenceGrant in backendRef.namespace that allows
   *     from: [{group: gateway.networking.k8s.io, kind: routeKind, namespace: routeNamespace}]
   *     to: [{group: "", kind: Service}]
   *   - If no matching grant found, return false and set status ResolvedRefs=False, reason=RefNotPermitted
   */
  private def isBackendRefAllowed(
      backendRef: HTTPRouteBackendRef,
      routeNamespace: String,
      routePath: String,
      routeKind: String,
      referenceGrants: Seq[KubernetesReferenceGrant]
  ): Boolean = {
    val svcNamespace = backendRef.namespace.getOrElse(routeNamespace)
    if (svcNamespace != routeNamespace) {
      // Cross-namespace reference detected
      val hasGrant = referenceGrants.exists { grant =>
        grant.namespace == svcNamespace &&
        grant.from.exists(f =>
          f.group == "gateway.networking.k8s.io" &&
          f.kind == routeKind &&
          f.namespace == routeNamespace
        ) &&
        grant.to.exists(t =>
          t.group == "" &&
          t.kind == "Service" &&
          t.name.forall(_ == backendRef.name)
        )
      }
      if (!hasGrant) {
        logger.warn(
          s"$routeKind $routePath references Service ${svcNamespace}/${backendRef.name} " +
            s"across namespaces without a matching ReferenceGrant. " +
            s"Allowing for now (MVP), but this should be enforced."
        )
      }
      // MVP: always allow, enforcement will come later
      true
    } else {
      true
    }
  }

  /**
   * Converts HTTPRoute filters to Otoroshi plugin instances.
   *
   * Mapping:
   * - RequestHeaderModifier (set/add) -> AdditionalHeadersIn
   * - RequestHeaderModifier (remove) -> RemoveHeadersIn
   * - ResponseHeaderModifier (set/add) -> AdditionalHeadersOut
   * - ResponseHeaderModifier (remove) -> RemoveHeadersOut
   * - RequestRedirect -> Redirection
   * - URLRewrite (hostname) -> AdditionalHeadersIn with Host header
   */
  private def buildPlugins(filters: Seq[HTTPRouteFilter]): NgPlugins = {
    val plugins = filters.flatMap { filter =>
      filter.filterType match {

        case "RequestHeaderModifier" =>
          filter.requestHeaderModifier.toSeq.flatMap { mod =>
            val setHeaders = (mod \ "set").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
              .map(h => (h \ "name").as[String] -> (h \ "value").as[String]).toMap
            val addHeaders = (mod \ "add").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
              .map(h => (h \ "name").as[String] -> (h \ "value").as[String]).toMap
            val removeHeaders = (mod \ "remove").asOpt[Seq[String]].getOrElse(Seq.empty)

            val allHeaders = setHeaders ++ addHeaders
            val addPlugin  = if (allHeaders.nonEmpty) {
              Seq(NgPluginInstance(
                plugin = "cp:otoroshi.next.plugins.AdditionalHeadersIn",
                enabled = true,
                config = NgPluginInstanceConfig(Json.obj("headers" -> allHeaders))
              ))
            } else Seq.empty

            val removePlugin = if (removeHeaders.nonEmpty) {
              Seq(NgPluginInstance(
                plugin = "cp:otoroshi.next.plugins.RemoveHeadersIn",
                enabled = true,
                config = NgPluginInstanceConfig(Json.obj("header_names" -> removeHeaders))
              ))
            } else Seq.empty

            addPlugin ++ removePlugin
          }

        case "ResponseHeaderModifier" =>
          filter.responseHeaderModifier.toSeq.flatMap { mod =>
            val setHeaders = (mod \ "set").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
              .map(h => (h \ "name").as[String] -> (h \ "value").as[String]).toMap
            val addHeaders = (mod \ "add").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
              .map(h => (h \ "name").as[String] -> (h \ "value").as[String]).toMap
            val removeHeaders = (mod \ "remove").asOpt[Seq[String]].getOrElse(Seq.empty)

            val allHeaders = setHeaders ++ addHeaders
            val addPlugin  = if (allHeaders.nonEmpty) {
              Seq(NgPluginInstance(
                plugin = "cp:otoroshi.next.plugins.AdditionalHeadersOut",
                enabled = true,
                config = NgPluginInstanceConfig(Json.obj("headers" -> allHeaders))
              ))
            } else Seq.empty

            val removePlugin = if (removeHeaders.nonEmpty) {
              Seq(NgPluginInstance(
                plugin = "cp:otoroshi.next.plugins.RemoveHeadersOut",
                enabled = true,
                config = NgPluginInstanceConfig(Json.obj("header_names" -> removeHeaders))
              ))
            } else Seq.empty

            addPlugin ++ removePlugin
          }

        case "RequestRedirect" =>
          filter.requestRedirect.toSeq.flatMap { redir =>
            val scheme     = (redir \ "scheme").asOpt[String]
            val hostname   = (redir \ "hostname").asOpt[String]
            val port       = (redir \ "port").asOpt[Int]
            val statusCode = (redir \ "statusCode").asOpt[Int].getOrElse(302)
            val portSuffix = port.map(p => s":$p").getOrElse("")
            val to         = s"${scheme.getOrElse("https")}://${hostname.getOrElse("$${req.host}")}$portSuffix$${req.uri}"
            Seq(NgPluginInstance(
              plugin = "cp:otoroshi.next.plugins.Redirection",
              enabled = true,
              config = NgPluginInstanceConfig(Json.obj("code" -> statusCode, "to" -> to))
            ))
          }

        case "URLRewrite" =>
          filter.urlRewrite.toSeq.flatMap { rewrite =>
            val hostname = (rewrite \ "hostname").asOpt[String]
            // Hostname rewrite via AdditionalHeadersIn with Host header
            hostname.toSeq.map { host =>
              NgPluginInstance(
                plugin = "cp:otoroshi.next.plugins.AdditionalHeadersIn",
                enabled = true,
                config = NgPluginInstanceConfig(Json.obj("headers" -> Json.obj("Host" -> host)))
              )
            }
            // Path rewriting is handled in ruleToNgRoute via stripPath + backend.root
          }

        case other =>
          logger.warn(s"Unsupported HTTPRoute filter type: $other")
          Seq.empty
      }
    }
    NgPlugins(plugins)
  }
}
