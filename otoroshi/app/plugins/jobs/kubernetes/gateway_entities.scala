package otoroshi.plugins.jobs.kubernetes

import play.api.libs.json._
import otoroshi.utils.syntax.implicits._

// ─── GatewayClass (cluster-scoped) ──────────────────────────────────────────

case class KubernetesGatewayClass(raw: JsValue) extends KubernetesEntity {
  lazy val controllerName: String            = spec.select("controllerName").as[String]
  lazy val parametersRef: Option[JsObject]   = spec.select("parametersRef").asOpt[JsObject]
  // GatewayClass is cluster-scoped, namespace defaults to ""
  override lazy val namespace: String        = (raw \ "metadata" \ "namespace").asOpt[String].getOrElse("")
  override lazy val path: String             = name
}

// ─── Gateway ────────────────────────────────────────────────────────────────

case class GatewayListener(raw: JsValue) {
  lazy val name: String                                   = (raw \ "name").as[String]
  lazy val hostname: Option[String]                       = (raw \ "hostname").asOpt[String]
  lazy val port: Int                                      = (raw \ "port").as[Int]
  lazy val protocol: String                               = (raw \ "protocol").as[String] // HTTP, HTTPS, TLS, TCP, UDP
  lazy val tls: Option[JsObject]                          = (raw \ "tls").asOpt[JsObject]
  lazy val allowedRoutes: Option[JsObject]                = (raw \ "allowedRoutes").asOpt[JsObject]

  lazy val certificateRefs: Seq[JsObject] = tls
    .flatMap(t => (t \ "certificateRefs").asOpt[Seq[JsObject]])
    .getOrElse(Seq.empty)

  lazy val tlsMode: Option[String] = tls.flatMap(t => (t \ "mode").asOpt[String])

  lazy val allowedRoutesNamespacesFrom: String = allowedRoutes
    .flatMap(ar => (ar \ "namespaces" \ "from").asOpt[String])
    .getOrElse("Same") // Default per spec

  lazy val allowedRoutesNamespacesSelector: Option[JsObject] = allowedRoutes
    .flatMap(ar => (ar \ "namespaces" \ "selector").asOpt[JsObject])

  lazy val allowedRoutesKinds: Seq[JsObject] = allowedRoutes
    .flatMap(ar => (ar \ "kinds").asOpt[Seq[JsObject]])
    .getOrElse(Seq.empty)
}

case class KubernetesGateway(raw: JsValue) extends KubernetesEntity {
  lazy val gatewayClassName: String    = spec.select("gatewayClassName").as[String]
  lazy val listeners: Seq[GatewayListener] = spec.select("listeners")
    .asOpt[Seq[JsValue]]
    .getOrElse(Seq.empty)
    .map(GatewayListener.apply)
  lazy val addresses: Seq[JsObject]    = spec.select("addresses")
    .asOpt[Seq[JsObject]]
    .getOrElse(Seq.empty)
}

// ─── HTTPRoute ──────────────────────────────────────────────────────────────

case class HTTPRouteParentRef(raw: JsValue) {
  lazy val group: Option[String]       = (raw \ "group").asOpt[String]
  lazy val kind: Option[String]        = (raw \ "kind").asOpt[String]        // default "Gateway"
  lazy val namespace: Option[String]   = (raw \ "namespace").asOpt[String]
  lazy val name: String                = (raw \ "name").as[String]
  lazy val sectionName: Option[String] = (raw \ "sectionName").asOpt[String] // listener name
  lazy val port: Option[Int]           = (raw \ "port").asOpt[Int]
}

case class HTTPRouteMatch(raw: JsValue) {
  lazy val path: Option[JsObject]          = (raw \ "path").asOpt[JsObject]
  lazy val headers: Seq[JsObject]          = (raw \ "headers").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
  lazy val queryParams: Seq[JsObject]      = (raw \ "queryParams").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
  lazy val method: Option[String]          = (raw \ "method").asOpt[String]

  lazy val pathType: String  = path.flatMap(p => (p \ "type").asOpt[String]).getOrElse("PathPrefix")
  lazy val pathValue: String = path.flatMap(p => (p \ "value").asOpt[String]).getOrElse("/")
}

case class HTTPRouteBackendRef(raw: JsValue) {
  lazy val group: Option[String]     = (raw \ "group").asOpt[String]
  lazy val kind: Option[String]      = (raw \ "kind").asOpt[String]      // default "Service"
  lazy val name: String              = (raw \ "name").as[String]
  lazy val namespace: Option[String] = (raw \ "namespace").asOpt[String]
  lazy val port: Option[Int]         = (raw \ "port").asOpt[Int]
  lazy val weight: Int               = (raw \ "weight").asOpt[Int].getOrElse(1)
}

case class HTTPRouteFilter(raw: JsValue) {
  lazy val filterType: String                            = (raw \ "type").as[String]
  // Types: RequestHeaderModifier, ResponseHeaderModifier, RequestRedirect,
  //        URLRewrite, RequestMirror, ExtensionRef
  lazy val requestHeaderModifier: Option[JsObject]       = (raw \ "requestHeaderModifier").asOpt[JsObject]
  lazy val responseHeaderModifier: Option[JsObject]      = (raw \ "responseHeaderModifier").asOpt[JsObject]
  lazy val requestRedirect: Option[JsObject]             = (raw \ "requestRedirect").asOpt[JsObject]
  lazy val urlRewrite: Option[JsObject]                  = (raw \ "urlRewrite").asOpt[JsObject]
  lazy val requestMirror: Option[JsObject]               = (raw \ "requestMirror").asOpt[JsObject]
  lazy val extensionRef: Option[JsObject]                = (raw \ "extensionRef").asOpt[JsObject]
}

case class HTTPRouteRule(raw: JsValue) {
  lazy val matches: Seq[HTTPRouteMatch] = (raw \ "matches")
    .asOpt[Seq[JsValue]]
    .getOrElse(Seq(Json.obj())) // default: match all
    .map(HTTPRouteMatch.apply)
  lazy val filters: Seq[HTTPRouteFilter] = (raw \ "filters")
    .asOpt[Seq[JsValue]]
    .getOrElse(Seq.empty)
    .map(HTTPRouteFilter.apply)
  lazy val backendRefs: Seq[HTTPRouteBackendRef] = (raw \ "backendRefs")
    .asOpt[Seq[JsValue]]
    .getOrElse(Seq.empty)
    .map(HTTPRouteBackendRef.apply)
  lazy val timeouts: Option[JsObject] = (raw \ "timeouts").asOpt[JsObject]
}

case class KubernetesHTTPRoute(raw: JsValue) extends KubernetesEntity {
  lazy val parentRefs: Seq[HTTPRouteParentRef] = spec.select("parentRefs")
    .asOpt[Seq[JsValue]]
    .getOrElse(Seq.empty)
    .map(HTTPRouteParentRef.apply)
  lazy val hostnames: Seq[String] = spec.select("hostnames")
    .asOpt[Seq[String]]
    .getOrElse(Seq.empty)
  lazy val rules: Seq[HTTPRouteRule] = spec.select("rules")
    .asOpt[Seq[JsValue]]
    .getOrElse(Seq.empty)
    .map(HTTPRouteRule.apply)
}

// ─── GRPCRoute ──────────────────────────────────────────────────────────────

case class GRPCRouteMethodMatch(raw: JsValue) {
  lazy val service: Option[String] = (raw \ "service").asOpt[String]
  lazy val method: Option[String]  = (raw \ "method").asOpt[String]
  lazy val matchType: String       = (raw \ "type").asOpt[String].getOrElse("Exact")
}

case class GRPCRouteMatch(raw: JsValue) {
  lazy val method: Option[GRPCRouteMethodMatch] =
    (raw \ "method").asOpt[JsValue].map(GRPCRouteMethodMatch.apply)
  lazy val headers: Seq[JsObject] =
    (raw \ "headers").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
}

case class GRPCRouteRule(raw: JsValue) {
  lazy val matches: Seq[GRPCRouteMatch] = (raw \ "matches")
    .asOpt[Seq[JsValue]]
    .getOrElse(Seq(Json.obj())) // default: match all
    .map(GRPCRouteMatch.apply)
  lazy val filters: Seq[HTTPRouteFilter] = (raw \ "filters")
    .asOpt[Seq[JsValue]]
    .getOrElse(Seq.empty)
    .map(HTTPRouteFilter.apply)
  lazy val backendRefs: Seq[HTTPRouteBackendRef] = (raw \ "backendRefs")
    .asOpt[Seq[JsValue]]
    .getOrElse(Seq.empty)
    .map(HTTPRouteBackendRef.apply)
}

case class KubernetesGRPCRoute(raw: JsValue) extends KubernetesEntity {
  lazy val parentRefs: Seq[HTTPRouteParentRef] = spec.select("parentRefs")
    .asOpt[Seq[JsValue]]
    .getOrElse(Seq.empty)
    .map(HTTPRouteParentRef.apply)
  lazy val hostnames: Seq[String] = spec.select("hostnames")
    .asOpt[Seq[String]]
    .getOrElse(Seq.empty)
  lazy val rules: Seq[GRPCRouteRule] = spec.select("rules")
    .asOpt[Seq[JsValue]]
    .getOrElse(Seq.empty)
    .map(GRPCRouteRule.apply)
}

// ─── ReferenceGrant ─────────────────────────────────────────────────────────
// Fetched for future enforcement of cross-namespace references (CRITICAL TODO).
// See gateway-api-implementation-plan-claude.md "ReferenceGrant — Future enforcement design"

case class ReferenceGrantFrom(raw: JsValue) {
  lazy val group: String     = (raw \ "group").as[String]
  lazy val kind: String      = (raw \ "kind").as[String]
  lazy val namespace: String = (raw \ "namespace").as[String]
}

case class ReferenceGrantTo(raw: JsValue) {
  lazy val group: String         = (raw \ "group").as[String]
  lazy val kind: String          = (raw \ "kind").as[String]
  lazy val name: Option[String]  = (raw \ "name").asOpt[String]
}

case class KubernetesReferenceGrant(raw: JsValue) extends KubernetesEntity {
  lazy val from: Seq[ReferenceGrantFrom] = spec.select("from")
    .asOpt[Seq[JsValue]]
    .getOrElse(Seq.empty)
    .map(ReferenceGrantFrom.apply)
  lazy val to: Seq[ReferenceGrantTo] = spec.select("to")
    .asOpt[Seq[JsValue]]
    .getOrElse(Seq.empty)
    .map(ReferenceGrantTo.apply)
}

// ─── BackendTLSPolicy ──────────────────────────────────────────────────────
// Defines TLS validation parameters for connections from the gateway to backend
// services. See https://gateway-api.sigs.k8s.io/api-types/backendtlspolicy/

case class BackendTLSPolicyTargetRef(raw: JsValue) {
  lazy val group: String = (raw \ "group").asOpt[String].getOrElse("")
  lazy val kind: String  = (raw \ "kind").asOpt[String].getOrElse("Service")
  lazy val name: String  = (raw \ "name").as[String]
}

case class BackendTLSPolicyValidation(raw: JsValue) {
  lazy val hostname: String                        = (raw \ "hostname").as[String]
  lazy val caCertificateRefs: Seq[JsObject]        = (raw \ "caCertificateRefs").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
  lazy val wellKnownCACertificates: Option[String]  = (raw \ "wellKnownCACertificates").asOpt[String]
  lazy val subjectAltNames: Seq[JsObject]          = (raw \ "subjectAltNames").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
}

case class KubernetesBackendTLSPolicy(raw: JsValue) extends KubernetesEntity {
  lazy val targetRefs: Seq[BackendTLSPolicyTargetRef] = spec.select("targetRefs")
    .asOpt[Seq[JsValue]]
    .getOrElse(Seq.empty)
    .map(BackendTLSPolicyTargetRef.apply)
  lazy val validation: Option[BackendTLSPolicyValidation] = spec.select("validation")
    .asOpt[JsValue]
    .map(BackendTLSPolicyValidation.apply)
}
