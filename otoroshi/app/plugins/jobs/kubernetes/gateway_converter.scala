package otoroshi.plugins.jobs.kubernetes

import otoroshi.env.Env
import otoroshi.models.{EntityLocation, HttpProtocols, RoundRobin}
import otoroshi.next.models._
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._

import scala.concurrent.ExecutionContext

/**
 * Result of converting a Gateway API route (HTTPRoute or GRPCRoute) to NgRoutes.
 *
 * In addition to the generated routes, this result carries information about
 * backend reference resolution failures. This allows the reconciliation loop
 * in gateway.scala to set precise status conditions on the route:
 *   - ResolvedRefs=True when all backend refs are resolved
 *   - ResolvedRefs=False, reason=RefNotPermitted when a cross-namespace ref is denied
 *   - ResolvedRefs=False, reason=BackendNotFound when a Service doesn't exist
 *
 * @param routes           The NgRoute entities generated from the route's rules
 * @param refNotPermitted  true if at least one cross-namespace backendRef was denied
 *                         because no matching ReferenceGrant exists in the target namespace
 */
case class RouteConversionResult(
  routes: Seq[NgRoute],
  refNotPermitted: Boolean
)

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
   * Cross-namespace backendRefs are subject to ReferenceGrant enforcement:
   * any backendRef targeting a Service in a different namespace requires a
   * matching ReferenceGrant in the target namespace. Denied refs are excluded
   * from the generated targets and flagged in the result for status reporting.
   */
  def httpRouteToNgRoutes(
      httpRoute: KubernetesHTTPRoute,
      gateways: Seq[KubernetesGateway],
      services: Seq[KubernetesService],
      endpoints: Seq[KubernetesEndpoint],
      referenceGrants: Seq[KubernetesReferenceGrant],
      namespaces: Seq[KubernetesNamespace],
      conf: KubernetesConfig
  )(implicit env: Env, ec: ExecutionContext): RouteConversionResult = {

    val matchingGateways = resolveParentRefs(httpRoute.parentRefs, httpRoute.namespace, gateways, namespaces, conf)
    if (matchingGateways.isEmpty) {
      logger.warn(s"HTTPRoute ${httpRoute.path} has no matching gateways")
      return RouteConversionResult(Seq.empty, refNotPermitted = false)
    }

    val effectiveHostnames = resolveEffectiveHostnames(httpRoute.hostnames, matchingGateways)

    val routes = httpRoute.rules.zipWithIndex.flatMap { case (rule, ruleIdx) =>
      ruleToNgRoute(httpRoute, rule, ruleIdx, effectiveHostnames, services, endpoints, referenceGrants, conf)
    }

    // Check if any cross-namespace backendRef was denied due to missing ReferenceGrant.
    // This is checked separately (without logging) to provide accurate status reporting
    // without duplicating the warnings already emitted by isBackendRefAllowed in buildTargets.
    val allBackendRefs = httpRoute.rules.flatMap(_.backendRefs)
    val refDenied = hasDeniedCrossNamespaceRefs(allBackendRefs, httpRoute.namespace, "HTTPRoute", referenceGrants)

    RouteConversionResult(routes, refNotPermitted = refDenied)
  }

  // ─── GRPCRoute conversion ────────────────────────────────────────────────

  /**
   * Converts a GRPCRoute into one or more NgRoute Otoroshi routes.
   *
   * Similar to HTTPRoute conversion, but:
   * - gRPC method matching is mapped to HTTP/2 path: /{service}/{method}
   * - Backend targets use HTTP/2 protocol
   *
   * Cross-namespace backendRefs are subject to ReferenceGrant enforcement.
   */
  def grpcRouteToNgRoutes(
      grpcRoute: KubernetesGRPCRoute,
      gateways: Seq[KubernetesGateway],
      services: Seq[KubernetesService],
      endpoints: Seq[KubernetesEndpoint],
      referenceGrants: Seq[KubernetesReferenceGrant],
      namespaces: Seq[KubernetesNamespace],
      conf: KubernetesConfig
  )(implicit env: Env, ec: ExecutionContext): RouteConversionResult = {

    val matchingGateways = resolveParentRefs(grpcRoute.parentRefs, grpcRoute.namespace, gateways, namespaces, conf)
    if (matchingGateways.isEmpty) {
      logger.warn(s"GRPCRoute ${grpcRoute.path} has no matching gateways")
      return RouteConversionResult(Seq.empty, refNotPermitted = false)
    }

    val effectiveHostnames = resolveEffectiveHostnames(grpcRoute.hostnames, matchingGateways)

    val routes = grpcRoute.rules.zipWithIndex.flatMap { case (rule, ruleIdx) =>
      grpcRuleToNgRoute(grpcRoute, rule, ruleIdx, effectiveHostnames, services, endpoints, referenceGrants, conf)
    }

    val allBackendRefs = grpcRoute.rules.flatMap(_.backendRefs)
    val refDenied = hasDeniedCrossNamespaceRefs(allBackendRefs, grpcRoute.namespace, "GRPCRoute", referenceGrants)

    RouteConversionResult(routes, refNotPermitted = refDenied)
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
      namespaces: Seq[KubernetesNamespace],
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
          .filter(l => isListenerAcceptingRoute(l, routeNamespace, gw, namespaces))
          .map(l => (gw, l))
      }
    }
  }

  /**
   * Checks if a listener accepts routes from the given namespace.
   * Implements allowedRoutes.namespaces.from:
   * - "Same" (default): same namespace as the Gateway
   * - "All": all namespaces
   * - "Selector": by label selector (matchLabels + matchExpressions)
   */
  private def isListenerAcceptingRoute(
      listener: GatewayListener,
      routeNamespace: String,
      gateway: KubernetesGateway,
      namespaces: Seq[KubernetesNamespace]
  ): Boolean = {
    listener.allowedRoutesNamespacesFrom match {
      case "All"      => true
      case "Same"     => routeNamespace == gateway.namespace
      case "Selector" =>
        listener.allowedRoutesNamespacesSelector match {
          case None =>
            // No selector means match all namespaces (per K8s convention: empty selector matches everything)
            true
          case Some(selector) =>
            namespaces.find(_.name == routeNamespace) match {
              case None =>
                logger.warn(s"Namespace $routeNamespace not found, rejecting route for listener ${listener.name}")
                false
              case Some(ns) =>
                matchesLabelSelector(ns.labels, selector)
            }
        }
      case _ => false
    }
  }

  /**
   * Evaluates a Kubernetes label selector against a set of labels.
   * Supports both matchLabels and matchExpressions.
   *
   * matchLabels: simple key=value equality (all must match)
   * matchExpressions: operators In, NotIn, Exists, DoesNotExist
   */
  private def matchesLabelSelector(labels: Map[String, String], selector: JsObject): Boolean = {
    val matchLabels = (selector \ "matchLabels").asOpt[Map[String, String]].getOrElse(Map.empty)
    val matchExpressions = (selector \ "matchExpressions").asOpt[Seq[JsObject]].getOrElse(Seq.empty)

    val labelsMatch = matchLabels.forall { case (key, value) =>
      labels.get(key).contains(value)
    }

    val expressionsMatch = matchExpressions.forall { expr =>
      val key      = (expr \ "key").as[String]
      val operator = (expr \ "operator").as[String]
      val values   = (expr \ "values").asOpt[Seq[String]].getOrElse(Seq.empty)
      operator match {
        case "In"            => labels.get(key).exists(values.contains)
        case "NotIn"         => labels.get(key).forall(v => !values.contains(v))
        case "Exists"        => labels.contains(key)
        case "DoesNotExist"  => !labels.contains(key)
        case other =>
          logger.warn(s"Unknown label selector operator: $other")
          false
      }
    }

    labelsMatch && expressionsMatch
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
            // logger.warn(
            //   s"HTTPRoute ${httpRoute.path} rule $ruleIdx uses ReplaceFullPath which is not fully supported, " +
            //     "using path as backend root"
            // )
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
        headers = rule.matches.flatMap(_.headers.map(o => (o.select("name").asString, o.select("value").asString))).toMap,
        query = rule.matches.flatMap(_.queryParams.map(o => (o.select("name").asString, o.select("value").asString))).toMap,
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
   * Cross-namespace references are validated against ReferenceGrants.
   * If a cross-namespace backendRef is denied (no matching ReferenceGrant),
   * it is excluded from the returned targets.
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

  // ─── ReferenceGrant enforcement ─────────────────────────────────────────────
  //
  // The Gateway API specification (GEP-709) defines ReferenceGrant as a security
  // mechanism for cross-namespace references. When a route (HTTPRoute or GRPCRoute)
  // references a backend Service in a different namespace, a ReferenceGrant resource
  // must exist in the **target** namespace (where the Service lives) to explicitly
  // allow this reference.
  //
  // Without a matching ReferenceGrant, the cross-namespace reference is denied and
  // the backend is excluded from the generated NgRoute targets.
  //
  // ReferenceGrant matching rules:
  //   1. The ReferenceGrant must reside in the same namespace as the referenced Service
  //   2. Its `from` list must contain an entry matching:
  //      - group: "gateway.networking.k8s.io"
  //      - kind: the route kind ("HTTPRoute" or "GRPCRoute")
  //      - namespace: the namespace of the route making the reference
  //   3. Its `to` list must contain an entry matching:
  //      - group: "" (core API group, for Services)
  //      - kind: "Service"
  //      - name: either the specific service name, or empty/absent (wildcard)
  //
  // Example: An HTTPRoute in namespace "frontend" wants to reference a Service
  // "api-svc" in namespace "backend". The following ReferenceGrant in namespace
  // "backend" would allow this:
  //
  //   apiVersion: gateway.networking.k8s.io/v1beta1
  //   kind: ReferenceGrant
  //   metadata:
  //     name: allow-frontend-to-backend
  //     namespace: backend
  //   spec:
  //     from:
  //     - group: gateway.networking.k8s.io
  //       kind: HTTPRoute
  //       namespace: frontend
  //     to:
  //     - group: ""
  //       kind: Service
  //       name: api-svc
  // ────────────────────────────────────────────────────────────────────────────

  /**
   * Core matching logic for ReferenceGrant validation (no logging).
   *
   * Checks whether a ReferenceGrant exists in the target namespace that permits
   * the given route kind and namespace to reference the given backend Service.
   *
   * @param backendRef       The backend reference to validate
   * @param routeNamespace   The namespace of the route making the reference
   * @param routeKind        The kind of the route ("HTTPRoute" or "GRPCRoute")
   * @param referenceGrants  All ReferenceGrant resources from the cluster
   * @return true if a matching grant exists, false otherwise
   */
  private def hasMatchingReferenceGrant(
      backendRef: HTTPRouteBackendRef,
      routeNamespace: String,
      routeKind: String,
      referenceGrants: Seq[KubernetesReferenceGrant]
  ): Boolean = {
    val svcNamespace = backendRef.namespace.getOrElse(routeNamespace)
    referenceGrants.exists { grant =>
      // The grant must be in the Service's namespace (the "target" namespace)
      grant.namespace == svcNamespace &&
      // The "from" list must explicitly allow the route's kind and source namespace
      grant.from.exists(f =>
        f.group == "gateway.networking.k8s.io" &&
        f.kind == routeKind &&
        f.namespace == routeNamespace
      ) &&
      // The "to" list must explicitly allow referencing Services.
      // If `name` is absent/empty in the grant, it acts as a wildcard (all Services allowed).
      // If `name` is specified, it must match the backendRef's service name exactly.
      grant.to.exists(t =>
        t.group == "" &&
        t.kind == "Service" &&
        t.name.forall(_ == backendRef.name)
      )
    }
  }

  /**
   * Checks if a cross-namespace backendRef is allowed, with logging.
   *
   * Same-namespace references are always allowed without any grant.
   * Cross-namespace references require a matching ReferenceGrant in the target namespace.
   *
   * Called from buildTargets/buildGrpcTargets to filter out denied backends
   * and log appropriate warnings.
   */
  private def isBackendRefAllowed(
      backendRef: HTTPRouteBackendRef,
      routeNamespace: String,
      routePath: String,
      routeKind: String,
      referenceGrants: Seq[KubernetesReferenceGrant]
  ): Boolean = {
    val svcNamespace = backendRef.namespace.getOrElse(routeNamespace)
    // Same-namespace references are always allowed (no ReferenceGrant needed)
    if (svcNamespace == routeNamespace) {
      true
    } else {
      // Cross-namespace: check for a matching ReferenceGrant
      val allowed = hasMatchingReferenceGrant(backendRef, routeNamespace, routeKind, referenceGrants)
      if (!allowed) {
        logger.warn(
          s"$routeKind $routePath references Service ${svcNamespace}/${backendRef.name} " +
            s"across namespaces but no matching ReferenceGrant was found in namespace $svcNamespace. " +
            s"The backend reference is DENIED. To allow this, create a ReferenceGrant in " +
            s"namespace $svcNamespace that permits $routeKind from namespace $routeNamespace."
        )
      } else {
        logger.debug(
          s"$routeKind $routePath cross-namespace reference to Service ${svcNamespace}/${backendRef.name} " +
            s"is allowed by a ReferenceGrant in namespace $svcNamespace."
        )
      }
      allowed
    }
  }

  /**
   * Checks whether any backendRef in the given list is a denied cross-namespace reference.
   *
   * This method is used (without logging) to determine the appropriate status condition
   * for the route: if any ref is denied, the status should be ResolvedRefs=False
   * with reason RefNotPermitted.
   *
   * It uses the same matching logic as isBackendRefAllowed (via hasMatchingReferenceGrant)
   * but does not emit log messages to avoid duplicate warnings.
   */
  private def hasDeniedCrossNamespaceRefs(
      backendRefs: Seq[HTTPRouteBackendRef],
      routeNamespace: String,
      routeKind: String,
      referenceGrants: Seq[KubernetesReferenceGrant]
  ): Boolean = {
    backendRefs.exists { ref =>
      val backendKind = ref.kind.getOrElse("Service")
      val svcNamespace = ref.namespace.getOrElse(routeNamespace)
      // Only check Service-kind refs that are actually cross-namespace
      backendKind == "Service" &&
      svcNamespace != routeNamespace &&
      !hasMatchingReferenceGrant(ref, routeNamespace, routeKind, referenceGrants)
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
