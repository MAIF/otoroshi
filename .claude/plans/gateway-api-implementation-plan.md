# Plan d'implémentation : Kubernetes Gateway API dans Otoroshi

## Contexte

Otoroshi dispose déjà d'une intégration Kubernetes complète dans `otoroshi/app/plugins/job/kubernetes/` avec :
- `config.scala` — `KubernetesConfig` case class + parsing depuis la global config
- `client.scala` — `KubernetesClient` qui fait les appels HTTP vers l'API k8s (fetch, watch, patch, create)
- `crds.scala` — `KubernetesOtoroshiCRDsControllerJob` (le Job) + `KubernetesCRDsJob` (le companion object avec `syncCRDs`)
- `entities.scala` — Les case classes wrappant les JsValue k8s (`KubernetesEntity`, `KubernetesService`, etc.)

L'objectif est d'implémenter le support de la **Kubernetes Gateway API** (spec `gateway.networking.k8s.io/v1`) en suivant **l'approche 2** (proxy existant, pas de provisioning dynamique). On se calque sur le pattern du job CRD existant.

liens vers la doc

- https://gateway-api.sigs.k8s.io/
- https://gateway-api.sigs.k8s.io/concepts/api-overview/
- https://gateway-api.sigs.k8s.io/concepts/roles-and-personas/
- https://gateway-api.sigs.k8s.io/concepts/security/
- https://gateway-api.sigs.k8s.io/guides/implementers/
- https://gateway-api.sigs.k8s.io/api-types/gateway/

spec 1.4 complete, à LIRE imperativement

- https://github.com/kubernetes-sigs/gateway-api/tree/v1.4.1/config/crd/standard
  - https://github.com/kubernetes-sigs/gateway-api/blob/v1.4.1/config/crd/standard/gateway.networking.k8s.io_backendtlspolicies.yaml
  - https://github.com/kubernetes-sigs/gateway-api/blob/v1.4.1/config/crd/standard/gateway.networking.k8s.io_gatewayclasses.yaml
  - https://github.com/kubernetes-sigs/gateway-api/blob/v1.4.1/config/crd/standard/gateway.networking.k8s.io_gateways.yaml
  - https://github.com/kubernetes-sigs/gateway-api/blob/v1.4.1/config/crd/standard/gateway.networking.k8s.io_grpcroutes.yaml
  - https://github.com/kubernetes-sigs/gateway-api/blob/v1.4.1/config/crd/standard/gateway.networking.k8s.io_httproutes.yaml
  - https://github.com/kubernetes-sigs/gateway-api/blob/v1.4.1/config/crd/standard/gateway.networking.k8s.io_referencegrants.yaml
- https://gateway-api.sigs.k8s.io/reference/1.4/spec/ 

### Ce que dit la spec

La spec Gateway API est **déclarative et intentionnelle** : quand tu déclares un listener sur le port 80, tu exprimes une **intention** ("je veux que du trafic arrive sur le port 80 avec ce hostname"). La spec ne prescrit absolument pas **comment** l'implémentation réalise ça physiquement. C'est explicitement laissé au contrôleur.

La spec dit même que le Gateway "defines a request for a way to translate traffic" — c'est une demande, pas une instruction technique directe.

### Comment font les autres implémentations

En pratique, il y a deux grandes approches :

**Approche 1 — Provisioning dynamique de listeners (Envoy Gateway, Istio, Kong/KGO)**

Le contrôleur crée/reconfigure dynamiquement des pods proxy qui écoutent réellement sur les ports déclarés. Quand tu crées un Gateway avec un listener port 8080, le contrôleur :
- Déploie ou reconfigure un pod Envoy/Nginx/Kong
- Ce pod bind effectivement le port 8080
- Un Service k8s de type LoadBalancer ou NodePort est créé pour exposer ce port

C'est le mode "managed gateway" — le contrôleur provisionne l'infra.

**Approche 2 — Mapping sur un proxy existant (Traefik, Nginx Gateway Fabric, Kong/KIC)**

Le proxy tourne déjà et écoute sur un ensemble fixe de ports (typiquement 80 et 443). Le contrôleur traduit les Gateway/HTTPRoute en config de routage interne, et le port déclaré dans le listener est utilisé comme **critère de matching logique**, pas comme un bind physique supplémentaire. Si tu déclares un listener sur le port 9999 mais que ton proxy n'écoute que sur 80/443, ça ne marchera tout simplement pas (et le status du listener doit refléter ça).

### Pour Otoroshi

Otoroshi correspond clairement à l'**approche 2**. Il tourne déjà, écoute sur ses ports configurés (typiquement 8080 HTTP, 8443 HTTPS, ou 80/443 derrière un LB). Du coup ta stratégie serait :

**Option pragmatique (recommandée pour le MVP)** : Otoroshi écoute déjà sur ses ports. Le contrôleur Gateway API vérifie que le port déclaré dans le listener correspond à un port sur lequel Otoroshi écoute réellement. Si oui → status `Accepted=true`. Si non → status `Accepted=false` avec reason `PortUnavailable`. Le hostname et le protocol du listener deviennent des critères de routage dans la config Otoroshi.

Concrètement, si Otoroshi écoute sur 8080 :

```yaml
listeners:
- name: http
  port: 8080        # ← doit matcher un port réel d'Otoroshi
  protocol: HTTP
  hostname: "*.example.com"
```

Le contrôleur ne crée pas de nouveau listener physique — il crée une route Otoroshi qui matche le hostname `*.example.com` sur le trafic qui arrive déjà sur le port 8080.

**Option avancée (plus tard)** : tu pourrais envisager qu'Otoroshi expose une API pour ajouter dynamiquement des listeners sur de nouveaux ports, mais c'est un gros chantier et la plupart des implémentations "self-hosted proxy" ne le font pas.

### Le routage du trafic physique

Le trafic arrive sur le container Otoroshi via la stack k8s classique :

```
Client externe
  → Service k8s (type LoadBalancer ou NodePort) exposant les ports d'Otoroshi
    → Pod Otoroshi (écoute sur 8080/8443)
      → Matching interne : hostname + path + headers (configuré par le contrôleur Gateway API)
        → Backend (Service k8s résolu en endpoints)
```

Le Service k8s devant Otoroshi est soit pré-existant (déployé avec le Helm chart Otoroshi), soit géré par ton contrôleur qui pourrait le mettre à jour pour exposer les bons ports. Mais dans tous les cas, le bind physique c'est Otoroshi qui le fait au démarrage, pas dynamiquement par listener Gateway API.

Donc en résumé : non, tu n'as pas besoin d'un listener physique par listener Gateway API. Tu valides juste que le port déclaré correspond à un port qu'Otoroshi écoute déjà, et tu utilises le reste (hostname, protocol) comme critères de routage logique.

---

## Architecture générale

```
┌──────────────────────────────────────────────────────┐
│  Kubernetes API Server                                │
│  CRDs: GatewayClass, Gateway, HTTPRoute               │
│  Resources: Services, Endpoints, Secrets              │
└───────────────┬──────────────────────────────────────┘
                │ watch + fetch
                ▼
┌──────────────────────────────────────────────────────┐
│  KubernetesGatewayApiJob (nouveau Job)                │
│  - Réconcilie GatewayClass → accepte si controllerName│
│  - Réconcilie Gateway → valide listeners              │
│  - Réconcilie HTTPRoute → crée/maj NgRoute Otoroshi   │
│  - Met à jour les status k8s                          │
│  - Supprime les entités orphelines                    │
└───────────────┬──────────────────────────────────────┘
                │ save/delete NgRoute, StoredNgBackend
                ▼
┌──────────────────────────────────────────────────────┐
│  Otoroshi datastore (routes, backends, certs)         │
│  Le moteur de routage utilise ces entités normalement  │
└──────────────────────────────────────────────────────┘
```

---

## Fichiers à créer / modifier

### Nouveaux fichiers (tous dans `otoroshi/app/plugins/job/kubernetes/`)

| Fichier | Rôle |
|---|---|
| `gateway.scala` | Le Job principal + companion object avec `syncGatewayApi` |
| `gateway_entities.scala` | Case classes pour GatewayClass, Gateway, HTTPRoute, ReferenceGrant |
| `gateway_converter.scala` | Logique de conversion HTTPRoute → NgRoute Otoroshi |

### Fichiers à modifier

| Fichier | Modification |
|---|---|
| `config.scala` | Ajouter les champs de config gateway API dans `KubernetesConfig` |
| `client.scala` | Ajouter les méthodes fetch/watch/updateStatus pour les ressources Gateway API |
| `entities.scala` | Rien à modifier (les nouvelles entités vont dans `gateway_entities.scala`) |

---

## Fichier 1 : `gateway_entities.scala`

### But
Modéliser les ressources Gateway API en case classes Scala, en suivant le pattern de `entities.scala` (wrapper sur `JsValue` avec des lazy val pour extraire les champs).

### Entités à créer

```scala
package otoroshi.plugins.jobs.kubernetes

import play.api.libs.json._

// ─── GatewayClass ───────────────────────────────────────────────────────

case class KubernetesGatewayClass(raw: JsValue) extends KubernetesEntity {
  lazy val controllerName: String = spec.select("controllerName").as[String]
  lazy val parametersRef: Option[JsObject] = spec.select("parametersRef").asOpt[JsObject]
}

// ─── Gateway ────────────────────────────────────────────────────────────

case class GatewayListener(raw: JsValue) {
  lazy val name: String = (raw \ "name").as[String]
  lazy val hostname: Option[String] = (raw \ "hostname").asOpt[String]
  lazy val port: Int = (raw \ "port").as[Int]
  lazy val protocol: String = (raw \ "protocol").as[String]  // HTTP, HTTPS, TLS, TCP, UDP
  lazy val tls: Option[JsObject] = (raw \ "tls").asOpt[JsObject]
  lazy val allowedRoutes: Option[JsObject] = (raw \ "allowedRoutes").asOpt[JsObject]

  // Extraire les certificateRefs depuis tls.certificateRefs
  lazy val certificateRefs: Seq[JsObject] = tls
    .flatMap(t => (t \ "certificateRefs").asOpt[Seq[JsObject]])
    .getOrElse(Seq.empty)

  // Extraire le mode TLS (Terminate, Passthrough)
  lazy val tlsMode: Option[String] = tls.flatMap(t => (t \ "mode").asOpt[String])

  // Extraire les namespaces autorisés pour l'attachement de routes
  lazy val allowedRoutesNamespacesFrom: String = allowedRoutes
    .flatMap(ar => (ar \ "namespaces" \ "from").asOpt[String])
    .getOrElse("Same")  // Default per spec

  lazy val allowedRoutesNamespacesSelector: Option[JsObject] = allowedRoutes
    .flatMap(ar => (ar \ "namespaces" \ "selector").asOpt[JsObject])

  lazy val allowedRoutesKinds: Seq[JsObject] = allowedRoutes
    .flatMap(ar => (ar \ "kinds").asOpt[Seq[JsObject]])
    .getOrElse(Seq.empty)
}

case class KubernetesGateway(raw: JsValue) extends KubernetesEntity {
  lazy val gatewayClassName: String = spec.select("gatewayClassName").as[String]
  lazy val listeners: Seq[GatewayListener] = spec.select("listeners")
    .asOpt[Seq[JsValue]]
    .getOrElse(Seq.empty)
    .map(GatewayListener.apply)
  lazy val addresses: Seq[JsObject] = spec.select("addresses")
    .asOpt[Seq[JsObject]]
    .getOrElse(Seq.empty)
}

// ─── HTTPRoute ──────────────────────────────────────────────────────────

case class HTTPRouteParentRef(raw: JsValue) {
  lazy val group: Option[String] = (raw \ "group").asOpt[String]
  lazy val kind: Option[String] = (raw \ "kind").asOpt[String]  // default "Gateway"
  lazy val namespace: Option[String] = (raw \ "namespace").asOpt[String]
  lazy val name: String = (raw \ "name").as[String]
  lazy val sectionName: Option[String] = (raw \ "sectionName").asOpt[String]  // listener name
  lazy val port: Option[Int] = (raw \ "port").asOpt[Int]
}

case class HTTPRouteMatch(raw: JsValue) {
  lazy val path: Option[JsObject] = (raw \ "path").asOpt[JsObject]
  lazy val headers: Seq[JsObject] = (raw \ "headers").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
  lazy val queryParams: Seq[JsObject] = (raw \ "queryParams").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
  lazy val method: Option[String] = (raw \ "method").asOpt[String]

  // Helpers pour le path matching
  lazy val pathType: String = path.flatMap(p => (p \ "type").asOpt[String]).getOrElse("PathPrefix")
  lazy val pathValue: String = path.flatMap(p => (p \ "value").asOpt[String]).getOrElse("/")
}

case class HTTPRouteBackendRef(raw: JsValue) {
  lazy val group: Option[String] = (raw \ "group").asOpt[String]
  lazy val kind: Option[String] = (raw \ "kind").asOpt[String]  // default "Service"
  lazy val name: String = (raw \ "name").as[String]
  lazy val namespace: Option[String] = (raw \ "namespace").asOpt[String]
  lazy val port: Option[Int] = (raw \ "port").asOpt[Int]
  lazy val weight: Int = (raw \ "weight").asOpt[Int].getOrElse(1)
}

case class HTTPRouteFilter(raw: JsValue) {
  lazy val filterType: String = (raw \ "type").as[String]
  // Types: RequestHeaderModifier, ResponseHeaderModifier, RequestRedirect,
  //        URLRewrite, RequestMirror, ExtensionRef
  lazy val requestHeaderModifier: Option[JsObject] = (raw \ "requestHeaderModifier").asOpt[JsObject]
  lazy val responseHeaderModifier: Option[JsObject] = (raw \ "responseHeaderModifier").asOpt[JsObject]
  lazy val requestRedirect: Option[JsObject] = (raw \ "requestRedirect").asOpt[JsObject]
  lazy val urlRewrite: Option[JsObject] = (raw \ "urlRewrite").asOpt[JsObject]
}

case class HTTPRouteRule(raw: JsValue) {
  lazy val matches: Seq[HTTPRouteMatch] = (raw \ "matches")
    .asOpt[Seq[JsValue]]
    .getOrElse(Seq(Json.obj()))  // default: match all
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

// ─── ReferenceGrant (optionnel pour le MVP mais structuré) ──────────────

case class KubernetesReferenceGrant(raw: JsValue) extends KubernetesEntity {
  lazy val from: Seq[JsObject] = spec.select("from").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
  lazy val to: Seq[JsObject] = spec.select("to").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
}
```

### Notes d'implémentation
- Suivre exactement le pattern de `entities.scala` : `case class Xxx(raw: JsValue) extends KubernetesEntity`
- Les lazy val extraient les champs depuis le JsValue brut
- Les sous-objets (listener, rule, match, backendRef) ne sont pas des KubernetesEntity car ils n'ont pas de metadata propre

---

## Fichier 2 : Modifications de `config.scala`

### Champs à ajouter dans `KubernetesConfig`

```scala
// Dans le case class KubernetesConfig, ajouter :
gatewayApi: Boolean,                                        // active le job Gateway API
gatewayApiControllerName: String,                           // ex: "otoroshi.io/gateway-controller"  
gatewayApiHttpListenerPort: Int,                            // port HTTP qu'Otoroshi écoute réellement (ex: 8080)
gatewayApiHttpsListenerPort: Int,                           // port HTTPS qu'Otoroshi écoute réellement (ex: 8443)
gatewayApiSyncIntervalSeconds: Long,                        // intervalle de sync (peut reprendre syncIntervalSeconds)
```

### Parsing dans `theConfig(conf: JsValue)`

```scala
// Ajouter dans le bloc de parsing :
gatewayApi = (conf \ "gatewayApi").asOpt[Boolean].getOrElse(false),
gatewayApiControllerName = (conf \ "gatewayApiControllerName").asOpt[String]
  .getOrElse("otoroshi.io/gateway-controller"),
gatewayApiHttpListenerPort = (conf \ "gatewayApiHttpListenerPort").asOpt[Int].getOrElse(8080),
gatewayApiHttpsListenerPort = (conf \ "gatewayApiHttpsListenerPort").asOpt[Int].getOrElse(8443),
gatewayApiSyncIntervalSeconds = (conf \ "gatewayApiSyncIntervalSeconds").asOpt[Long].getOrElse(60L),
```

### Config JSON par défaut

Ajouter dans `defaultConfig` :
```json
{
  "gatewayApi": false,
  "gatewayApiControllerName": "otoroshi.io/gateway-controller",
  "gatewayApiHttpListenerPort": 8080,
  "gatewayApiHttpsListenerPort": 8443,
  "gatewayApiSyncIntervalSeconds": 60
}
```

---

## Fichier 3 : Modifications de `client.scala`

### Méthodes à ajouter dans `KubernetesClient`

Le pattern est identique aux méthodes existantes (`fetchServices`, `fetchIngresses`, etc.). Les ressources Gateway API sont dans l'API group `gateway.networking.k8s.io/v1`.

```scala
// ─── Fetch GatewayClasses (cluster-scoped, pas de namespace) ────────────

def fetchGatewayClasses(): Future[Seq[KubernetesGatewayClass]] = {
  // ATTENTION: GatewayClass est cluster-scoped, pas de namespace dans le path
  val cli: WSRequest = client(s"/apis/gateway.networking.k8s.io/v1/gatewayclasses", false)
  cli.addHttpHeaders("Accept" -> "application/json")
    .get()
    .map { resp =>
      if (resp.status == 200) {
        (resp.json \ "items").as[JsArray].value.map(item => KubernetesGatewayClass(item))
      } else if (resp.status == 403) {
        KubernetesClientNotifications.registerForbiddenEntities("gateway.networking.k8s.io/gatewayclasses")
        resp.ignore(); Seq.empty
      } else if (resp.status == 404) {
        // CRDs Gateway API pas installées
        KubernetesClientNotifications.registerMissionCustomResourceDefinition(
          "gateway.networking.k8s.io/gatewayclasses"
        )
        resp.ignore(); Seq.empty
      } else { resp.ignore(); Seq.empty }
    }
}

// ─── Fetch Gateways (namespace-scoped) ──────────────────────────────────

def fetchGateways(): Future[Seq[KubernetesGateway]] = {
  asyncSequence(config.namespaces.map { namespace =>
    val path = if (namespace == "*")
      s"/apis/gateway.networking.k8s.io/v1/gateways"
    else
      s"/apis/gateway.networking.k8s.io/v1/namespaces/$namespace/gateways"
    val cli: WSRequest = client(path)
    () => cli.addHttpHeaders("Accept" -> "application/json")
      .get()
      .map { resp =>
        if (resp.status == 200) {
          filterLabels((resp.json \ "items").as[JsArray].value.map(item => KubernetesGateway(item)))
        } else if (resp.status == 403) {
          KubernetesClientNotifications.registerForbiddenEntities("gateway.networking.k8s.io/gateways")
          resp.ignore(); Seq.empty
        } else if (resp.status == 404) {
          KubernetesClientNotifications.registerMissionCustomResourceDefinition(
            "gateway.networking.k8s.io/gateways"
          )
          resp.ignore(); Seq.empty
        } else { resp.ignore(); Seq.empty }
      }
  }).map(_.flatten)
}

// ─── Fetch HTTPRoutes (namespace-scoped) ────────────────────────────────

def fetchHTTPRoutes(): Future[Seq[KubernetesHTTPRoute]] = {
  asyncSequence(config.namespaces.map { namespace =>
    val path = if (namespace == "*")
      s"/apis/gateway.networking.k8s.io/v1/httproutes"
    else
      s"/apis/gateway.networking.k8s.io/v1/namespaces/$namespace/httproutes"
    val cli: WSRequest = client(path)
    () => cli.addHttpHeaders("Accept" -> "application/json")
      .get()
      .map { resp =>
        if (resp.status == 200) {
          filterLabels((resp.json \ "items").as[JsArray].value.map(item => KubernetesHTTPRoute(item)))
        } else if (resp.status == 403) {
          KubernetesClientNotifications.registerForbiddenEntities("gateway.networking.k8s.io/httproutes")
          resp.ignore(); Seq.empty
        } else if (resp.status == 404) {
          KubernetesClientNotifications.registerMissionCustomResourceDefinition(
            "gateway.networking.k8s.io/httproutes"
          )
          resp.ignore(); Seq.empty
        } else { resp.ignore(); Seq.empty }
      }
  }).map(_.flatten)
}

// ─── Update Status (sous-ressource /status) ─────────────────────────────

// GatewayClass status (cluster-scoped)
def updateGatewayClassStatus(name: String, status: JsObject): Future[Option[JsValue]] = {
  val cli: WSRequest = client(
    s"/apis/gateway.networking.k8s.io/v1/gatewayclasses/$name/status", false
  )
  val req = cli.addHttpHeaders(
    "Accept" -> "application/json",
    "Content-Type" -> "application/merge-patch+json"
  )
  req.patch(Json.obj("status" -> status)).map { resp =>
    if (resp.status == 200 || resp.status == 201) resp.json.some
    else { resp.ignore(); None }
  }
}

// Gateway status (namespace-scoped)
def updateGatewayStatus(namespace: String, name: String, status: JsObject): Future[Option[JsValue]] = {
  val cli: WSRequest = client(
    s"/apis/gateway.networking.k8s.io/v1/namespaces/$namespace/gateways/$name/status", false
  )
  val req = cli.addHttpHeaders(
    "Accept" -> "application/json",
    "Content-Type" -> "application/merge-patch+json"
  )
  req.patch(Json.obj("status" -> status)).map { resp =>
    if (resp.status == 200 || resp.status == 201) resp.json.some
    else { resp.ignore(); None }
  }
}

// HTTPRoute status (namespace-scoped)
def updateHTTPRouteStatus(namespace: String, name: String, status: JsObject): Future[Option[JsValue]] = {
  val cli: WSRequest = client(
    s"/apis/gateway.networking.k8s.io/v1/namespaces/$namespace/httproutes/$name/status", false
  )
  val req = cli.addHttpHeaders(
    "Accept" -> "application/json",
    "Content-Type" -> "application/merge-patch+json"
  )
  req.patch(Json.obj("status" -> status)).map { resp =>
    if (resp.status == 200 || resp.status == 201) resp.json.some
    else { resp.ignore(); None }
  }
}

// ─── Watch Gateway API resources ────────────────────────────────────────

def watchGatewayApiResources(
    namespaces: Seq[String],
    timeout: Int,
    stop: => Boolean
): Source[Seq[ByteString], _] = {
  watchResources(
    namespaces,
    Seq("gateways", "httproutes"),
    "gateway.networking.k8s.io/v1",
    timeout,
    stop
  )
  // Note : GatewayClass est cluster-scoped, il faudra un watch séparé
  // avec le path /apis/gateway.networking.k8s.io/v1/gatewayclasses?watch=1
}
```

### Point d'attention : le client HTTP

La méthode `client(path)` existante dans `KubernetesClient` construit un `WSRequest` avec le endpoint + path + token. Vérifier que le 2e paramètre `filterByNamespace` (boolean) est bien passé à `false` pour les ressources cluster-scoped (GatewayClass).

---

## Fichier 4 : `gateway_converter.scala`

### But
Convertir une `KubernetesHTTPRoute` + son contexte (Gateway, Services, Endpoints) en une ou plusieurs `NgRoute` Otoroshi.

### Logique de conversion

```scala
package otoroshi.plugins.jobs.kubernetes

import otoroshi.env.Env
import otoroshi.next.models._
import play.api.Logger
import play.api.libs.json._
import scala.concurrent.{ExecutionContext, Future}

object GatewayApiConverter {

  private val logger = Logger("otoroshi-plugins-kubernetes-gateway-api-converter")

  /**
   * Convertit une HTTPRoute en une ou plusieurs NgRoute Otoroshi.
   * 
   * Chaque HTTPRouteRule génère une NgRoute distincte car chaque rule peut avoir
   * des matches et des backends différents.
   * 
   * L'ID de chaque route générée suit le pattern :
   *   "kubernetes-gateway-api-{namespace}-{httproute-name}-rule-{ruleIndex}"
   */
  def httpRouteToNgRoutes(
    httpRoute: KubernetesHTTPRoute,
    gateways: Seq[KubernetesGateway],
    services: Seq[KubernetesService],
    endpoints: Seq[KubernetesEndpoint],
    conf: KubernetesConfig
  )(implicit env: Env, ec: ExecutionContext): Seq[NgRoute] = {

    // 1. Résoudre les parentRefs → trouver les Gateways attachées
    val matchingGateways = resolveParentRefs(httpRoute, gateways, conf)
    if (matchingGateways.isEmpty) {
      logger.warn(s"HTTPRoute ${httpRoute.path} has no matching gateways")
      return Seq.empty
    }

    // 2. Collecter les hostnames effectifs
    //    = intersection des hostnames de la route avec ceux des listeners attachés
    val effectiveHostnames = resolveEffectiveHostnames(httpRoute, matchingGateways)

    // 3. Convertir chaque rule en NgRoute
    httpRoute.rules.zipWithIndex.flatMap { case (rule, ruleIdx) =>
      ruleToNgRoute(httpRoute, rule, ruleIdx, effectiveHostnames, services, endpoints, conf)
    }
  }

  /**
   * Résout les parentRefs d'une HTTPRoute vers les Gateways qui matchent.
   * Vérifie que :
   * - Le Gateway existe
   * - Le Gateway est géré par notre controllerName
   * - Le listener autorise les routes depuis le namespace de la HTTPRoute
   */
  private def resolveParentRefs(
    httpRoute: KubernetesHTTPRoute,
    gateways: Seq[KubernetesGateway],
    conf: KubernetesConfig
  ): Seq[(KubernetesGateway, GatewayListener)] = {
    httpRoute.parentRefs.flatMap { parentRef =>
      val gwNamespace = parentRef.namespace.getOrElse(httpRoute.namespace)
      val gateway = gateways.find(gw => gw.name == parentRef.name && gw.namespace == gwNamespace)
      gateway.toSeq.flatMap { gw =>
        // Si sectionName est spécifié, ne matcher que ce listener
        val listeners = parentRef.sectionName match {
          case Some(sn) => gw.listeners.filter(_.name == sn)
          case None     => gw.listeners
        }
        // Filtrer les listeners qui acceptent les HTTPRoute depuis ce namespace
        listeners
          .filter(l => isListenerAcceptingRoute(l, httpRoute, gw))
          .map(l => (gw, l))
      }
    }
  }

  /**
   * Vérifie si un listener accepte les routes depuis le namespace donné.
   * Implémente la logique `allowedRoutes.namespaces.from` :
   * - "Same" (default) : même namespace que le Gateway
   * - "All" : tous les namespaces
   * - "Selector" : par label selector (simplifié pour le MVP)
   */
  private def isListenerAcceptingRoute(
    listener: GatewayListener,
    route: KubernetesHTTPRoute,
    gateway: KubernetesGateway
  ): Boolean = {
    listener.allowedRoutesNamespacesFrom match {
      case "All"      => true
      case "Same"     => route.namespace == gateway.namespace
      case "Selector" => true // Simplifié pour MVP, implémenter le label matching plus tard
      case _          => false
    }
  }

  /**
   * Calcule les hostnames effectifs en intersectant :
   * - Les hostnames de la HTTPRoute
   * - Les hostnames des listeners attachés
   * 
   * Si la HTTPRoute n'a pas de hostnames, utiliser ceux des listeners.
   * Si un listener n'a pas de hostname, il accepte tout.
   */
  private def resolveEffectiveHostnames(
    httpRoute: KubernetesHTTPRoute,
    matchingGateways: Seq[(KubernetesGateway, GatewayListener)]
  ): Seq[String] = {
    val listenerHostnames = matchingGateways.flatMap(_._2.hostname).distinct
    if (httpRoute.hostnames.isEmpty && listenerHostnames.isEmpty) {
      Seq("*")  // catch-all
    } else if (httpRoute.hostnames.isEmpty) {
      listenerHostnames
    } else if (listenerHostnames.isEmpty) {
      httpRoute.hostnames
    } else {
      // Intersection : pour chaque hostname de la route, vérifier qu'il est
      // compatible avec au moins un hostname de listener (wildcard matching)
      httpRoute.hostnames.filter { rh =>
        listenerHostnames.exists(lh => hostnameMatches(lh, rh))
      }
    }
  }

  /**
   * Vérifie la compatibilité entre un hostname listener et un hostname route.
   * Supporte le wildcard matching (*.example.com matche foo.example.com).
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
   * Convertit une HTTPRouteRule en NgRoute Otoroshi.
   */
  private def ruleToNgRoute(
    httpRoute: KubernetesHTTPRoute,
    rule: HTTPRouteRule,
    ruleIdx: Int,
    effectiveHostnames: Seq[String],
    services: Seq[KubernetesService],
    endpoints: Seq[KubernetesEndpoint],
    conf: KubernetesConfig
  )(implicit env: Env): Seq[NgRoute] = {

    val routeId = s"kubernetes-gateway-api-${httpRoute.namespace}-${httpRoute.name}-rule-$ruleIdx"
      .replace("/", "-").replace(".", "-")
    val routeName = s"${httpRoute.namespace}/${httpRoute.name} rule $ruleIdx"

    // Construire les domains (frontend) depuis les hostnames + path matching
    val domains = buildDomains(effectiveHostnames, rule)

    // Construire les targets (backend) depuis les backendRefs
    val targets = buildTargets(httpRoute, rule, services, endpoints)

    // Construire les plugins depuis les filters
    val plugins = buildPlugins(rule)

    // Construire le JSON de la NgRoute
    val routeJson = Json.obj(
      "id"          -> routeId,
      "name"        -> routeName,
      "description" -> s"Generated from Gateway API HTTPRoute ${httpRoute.path}",
      "enabled"     -> true,
      "capture"     -> false,
      "export_reporting" -> false,
      "groups"      -> Json.arr("default"),
      "bound_listeners" -> Json.arr(),
      "frontend"    -> Json.obj(
        "domains" -> JsArray(domains.map(JsString.apply)),
        "strip_path" -> false,
        "exact"   -> rule.matches.exists(_.pathType == "Exact"),
        "headers" -> Json.obj(),
        "query"   -> Json.obj(),
        "methods" -> JsArray(
          rule.matches.flatMap(_.method).distinct.map(JsString.apply)
        )
      ),
      "backend"     -> Json.obj(
        "targets" -> JsArray(targets),
        "root"    -> "",
        "rewrite" -> false,
        "load_balancing" -> Json.obj(
          "type" -> "WeightedBestResponseTime"
        )
      ),
      "plugins"     -> plugins,
      "metadata"    -> Json.obj(
        "otoroshi-provider"    -> "kubernetes-gateway-api",
        "kubernetes-name"      -> httpRoute.name,
        "kubernetes-namespace" -> httpRoute.namespace,
        "kubernetes-path"      -> httpRoute.path,
        "kubernetes-uid"       -> httpRoute.uid,
        "gateway-api-kind"     -> "HTTPRoute"
      )
    )

    NgRoute.fmt.reads(routeJson) match {
      case JsSuccess(route, _) => Seq(route)
      case JsError(errors) =>
        logger.error(s"Failed to parse generated NgRoute for ${httpRoute.path} rule $ruleIdx: $errors")
        Seq.empty
    }
  }

  /**
   * Construit les domains Otoroshi depuis les hostnames et les matches.
   * 
   * Pour PathPrefix "/api" avec hostname "app.example.com" → "app.example.com/api"
   * Pour Exact "/api/v1" avec hostname "app.example.com" → "app.example.com/api/v1"
   */
  private def buildDomains(hostnames: Seq[String], rule: HTTPRouteRule): Seq[String] = {
    if (rule.matches.isEmpty) {
      hostnames
    } else {
      for {
        hostname <- hostnames
        m <- rule.matches
      } yield {
        val path = m.pathValue
        if (path == "/") hostname
        else s"$hostname$path"
      }
    }
  }

  /**
   * Résout les backendRefs en targets NgTarget Otoroshi.
   * 
   * Pour chaque backendRef qui pointe vers un Service k8s :
   * 1. Trouver le Service k8s correspondant
   * 2. Résoudre ses endpoints
   * 3. Construire les targets avec le bon port
   * 
   * Le weight de chaque backendRef est converti en metadata sur le target
   * pour le load balancing pondéré.
   */
  private def buildTargets(
    httpRoute: KubernetesHTTPRoute,
    rule: HTTPRouteRule,
    services: Seq[KubernetesService],
    endpoints: Seq[KubernetesEndpoint]
  ): Seq[JsObject] = {
    val totalWeight = rule.backendRefs.map(_.weight).sum
    rule.backendRefs.flatMap { backendRef =>
      val backendKind = backendRef.kind.getOrElse("Service")
      if (backendKind != "Service") {
        logger.warn(s"Unsupported backendRef kind: $backendKind in HTTPRoute ${httpRoute.path}")
        Seq.empty
      } else {
        val svcNamespace = backendRef.namespace.getOrElse(httpRoute.namespace)
        val svcPath = s"$svcNamespace/${backendRef.name}"
        val service = services.find(_.path == svcPath)
        val endpoint = endpoints.find(_.path == svcPath)
        
        service match {
          case Some(svc) =>
            val port = backendRef.port.getOrElse(80)
            val clusterIp = svc.clusterIP
            // Construire un target avec le clusterIP du service
            // Note : pour le MVP, utiliser le clusterIP directement
            // Plus tard, on pourra résoudre les endpoints individuels
            val weight = if (totalWeight > 0) (backendRef.weight.toDouble / totalWeight * 100).toInt else 100
            Seq(Json.obj(
              "id"       -> s"${svcPath}:$port",
              "hostname" -> clusterIp,
              "port"     -> port,
              "weight"   -> weight,
              "tls"      -> false,
              "protocol" -> "HTTP/1.1",
              "predicate" -> Json.obj("type" -> "AlwaysMatch"),
              "ip_address" -> JsNull
            ))
          case None =>
            logger.warn(s"Service $svcPath not found for backendRef in HTTPRoute ${httpRoute.path}")
            Seq.empty
        }
      }
    }
  }

  /**
   * Convertit les HTTPRoute filters en plugins Otoroshi.
   * 
   * Mapping :
   * - RequestHeaderModifier → plugin AdditionalHeadersIn (pour set et add), RemoveHeadersIn pour le remove 
   * - ResponseHeaderModifier → plugin AdditionalHeadersOut (pour set et add), RemoveHeadersOut pour le remove 
   * - RequestRedirect → plugin Redirection
   * - URLRewrite → plugin OverrideHost + rewrite path via frontend config
   * - CORS → plugin cors
   * - RequestMirror → plugin mirror
   * - ExternalAuth → NgExternalValidator
   * - ExtensionRef → 
   */
  private def buildPlugins(rule: HTTPRouteRule): JsArray = {
    val plugins = rule.filters.flatMap { filter =>
      filter.filterType match {
        case "RequestHeaderModifier" =>
          filter.requestHeaderModifier.map { mod =>
            val setHeaders = (mod \ "set").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
              .map(h => (h \ "name").as[String] -> (h \ "value").as[String]).toMap
            val addHeaders = (mod \ "add").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
              .map(h => (h \ "name").as[String] -> (h \ "value").as[String]).toMap
            val removeHeaders = (mod \ "remove").asOpt[Seq[String]].getOrElse(Seq.empty) //. TODO: use RemoveHeadersIn for that
            Json.obj(
              "enabled" -> true,
              "plugin" -> "cp:otoroshi.next.plugins.AdditionalHeadersIn",
              "config" -> Json.obj(
                "headers" -> (setHeaders ++ addHeaders).map { case (k, v) => Json.obj("key" -> k, "value" -> v) },
                "remove"  -> removeHeaders
              )
            )
          }

        case "RequestRedirect" =>
          filter.requestRedirect.map { redir =>
            val scheme = (redir \ "scheme").asOpt[String]
            val hostname = (redir \ "hostname").asOpt[String]
            val port = (redir \ "port").asOpt[Int]
            val path = (redir \ "path").asOpt[JsObject]
            val statusCode = (redir \ "statusCode").asOpt[Int].getOrElse(302)
            // Construire l'URL de redirection
            val baseUrl = s"${scheme.getOrElse("https")}://${hostname.getOrElse("$${req.host}")}${port.map(p => s":$p").getOrElse("")}"
            Json.obj(
              "enabled" -> true,
              "plugin" -> "cp:otoroshi.next.plugins.Redirection",
              "config" -> Json.obj(
                "code" -> statusCode,
                "to"   -> baseUrl
              )
            )
          }

        case "URLRewrite" =>
          filter.urlRewrite.map { rewrite =>
            val hostname = (rewrite \ "hostname").asOpt[String]
            val pathRewrite = (rewrite \ "path").asOpt[JsObject]
            Json.obj(
              "enabled" -> true,
              "plugin" -> "cp:otoroshi.next.plugins.OverrideHost",
              "config" -> Json.obj(
                "host" -> hostname.getOrElse("")
              )
            )
          }
        // TODO: supporter tous les autres
        case other =>
          logger.warn(s"Unsupported HTTPRoute filter type: $other")
          None
      }
    }
    JsArray(plugins)
  }
}
```

### Notes d'implémentation
- **IMPORTANT** : La conversion des filters en plugins Otoroshi est approximative ci-dessus. Il faudra adapter aux vrais noms et configs des plugins NgRoute d'Otoroshi. Le développeur devra consulter les plugins existants dans `otoroshi.next.plugins.*` pour mapper correctement. Les noms exacts (OverrideHost, Redirection, etc.) et leur format de config devront être validés contre le code source Otoroshi.
- Le weight-based routing est géré nativement par NgRoute via le champ `weight` sur les targets.
- Pour le MVP, on résout vers le `clusterIP` du Service. L'étape d'après sera de résoudre vers les Endpoints individuels (comme le fait déjà `KubernetesIngressToDescriptor.serviceToTargetsSync` dans le code existant — **réutiliser cette logique**).

---

## Fichier 5 : `gateway.scala` — Le Job principal

### Structure

Ce fichier suit exactement le pattern de `crds.scala` :
1. Une classe `KubernetesGatewayApiControllerJob extends Job` (le job schedulé)
2. Un `object KubernetesGatewayApiJob` avec la méthode `syncGatewayApi` (la logique de sync)

### Le Job (la classe)

```scala
class KubernetesGatewayApiControllerJob extends Job {

  private val logger      = Logger("otoroshi-plugins-kubernetes-gateway-api-job")
  private val shouldRun   = new AtomicBoolean(false)
  private val running     = new AtomicBoolean(false)
  private val stopCommand = new AtomicBoolean(false)
  // ... mêmes atomics que KubernetesOtoroshiCRDsControllerJob

  override def uniqueId: JobId = JobId("io.otoroshi.plugins.jobs.kubernetes.KubernetesGatewayApiControllerJob")
  override def name: String = "Kubernetes Gateway API Controller"
  override def defaultConfig: Option[JsObject] = KubernetesConfig.defaultConfig.some
  override def configFlow: Seq[String] = KubernetesConfig.configFlow
  override def configSchema: Option[JsObject] = KubernetesConfig.configSchema
  override def jobVisibility: JobVisibility = JobVisibility.UserLand
  override def kind: JobKind = JobKind.ScheduledEvery
  override def starting: JobStarting = JobStarting.FromConfiguration
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Integrations)

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation = {
    // Même logique que KubernetesOtoroshiCRDsControllerJob
    // Basé sur kubeLeader et clusterMode
  }

  override def predicate(ctx: JobContext, env: Env): Option[Boolean] = {
    Try(KubernetesConfig.theConfig(ctx)(env, env.otoroshiExecutionContext)) match {
      case Failure(_) => Some(false)
      case Success(conf) => if (conf.gatewayApi) None else Some(false)
    }
  }

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 10.seconds.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = {
    val conf = KubernetesConfig.theConfig(ctx)(env, env.otoroshiExecutionContext)
    conf.gatewayApiSyncIntervalSeconds.seconds.some
  }

  override def jobStart(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    logger.info("Starting Kubernetes Gateway API controller job")
    stopCommand.set(false)
    // Optionnel: démarrer le watch sur les ressources Gateway API
    // handleWatch(config, ctx) — même pattern que crds.scala
    ().future
  }

  override def jobStop(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    logger.info("Stopping Kubernetes Gateway API controller job")
    stopCommand.set(true)
    running.set(false)
    ().future
  }

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val conf = KubernetesConfig.theConfig(ctx)
    if (conf.gatewayApi) {
      KubernetesGatewayApiJob.syncGatewayApi(conf, ctx.attrs, !stopCommand.get())
        .map(_ => ())
    } else {
      ().future
    }
  }
}
```

### Le companion object — `syncGatewayApi`

```scala
object KubernetesGatewayApiJob {

  private val logger  = Logger("otoroshi-plugins-kubernetes-gateway-api-sync")
  private val running = new AtomicBoolean(false)

  // Identifiant du provider pour le metadata tracking (suppression des orphelins)
  val PROVIDER = "kubernetes-gateway-api"

  /**
   * Méthode principale de synchronisation.
   * 
   * Flow :
   * 1. Fetch toutes les ressources Gateway API du cluster
   * 2. Fetch les ressources k8s associées (Services, Endpoints, Secrets)  
   * 3. Fetch les entités Otoroshi existantes (NgRoute, StoredNgBackend)
   * 4. Réconcilier GatewayClass → mettre à jour les status
   * 5. Réconcilier Gateway → valider les listeners, mettre à jour les status
   * 6. Réconcilier HTTPRoute → convertir en NgRoute, sauvegarder
   * 7. Supprimer les entités Otoroshi orphelines
   */
  def syncGatewayApi(
    conf: KubernetesConfig,
    attrs: TypedMap,
    jobRunning: => Boolean
  )(implicit env: Env, ec: ExecutionContext): Future[Unit] = {

    if (!jobRunning) { running.set(false) }
    if (jobRunning && running.compareAndSet(false, true)) {
      logger.info("Starting Gateway API sync")
      val client = new KubernetesClient(conf, env)

      val result = for {
        // ─── Phase 1 : Fetch ────────────────────────────────────
        gatewayClasses <- client.fetchGatewayClasses()
        gateways       <- client.fetchGateways()
        httpRoutes     <- client.fetchHTTPRoutes()
        services       <- client.fetchServices()
        endpoints      <- client.fetchEndpoints()
        secrets        <- client.fetchSecrets()

        // Fetch entités Otoroshi existantes gérées par ce provider
        existingRoutes <- if (conf.useProxyState) env.proxyState.allRoutes().vfuture
                          else env.datastores.routeDataStore.findAll()
        managedRoutes = existingRoutes.filter(
          _.metadata.get("otoroshi-provider").contains(PROVIDER)
        )

        // ─── Phase 2 : Réconcilier GatewayClasses ──────────────
        _ <- reconcileGatewayClasses(client, gatewayClasses, conf)

        // ─── Phase 3 : Réconcilier Gateways ────────────────────
        acceptedGateways <- reconcileGateways(client, gateways, gatewayClasses, conf)

        // ─── Phase 4 : Réconcilier HTTPRoutes ──────────────────
        generatedRoutes <- reconcileHTTPRoutes(
          client, httpRoutes, acceptedGateways, services, endpoints, conf
        )

        // ─── Phase 5 : Sauvegarder les NgRoutes ────────────────
        _ <- saveGeneratedRoutes(generatedRoutes, managedRoutes)

        // ─── Phase 6 : Supprimer les orphelins ─────────────────
        _ <- deleteOrphanedRoutes(generatedRoutes, managedRoutes)

        _ = logger.info(s"Gateway API sync done: ${generatedRoutes.size} routes generated")
      } yield ()

      result.andThen {
        case Failure(e) =>
          logger.error(s"Gateway API sync failed: ${e.getMessage}", e)
          running.set(false)
        case Success(_) =>
          running.set(false)
      }
    } else {
      logger.info("Gateway API sync already running, skipping")
      ().future
    }
  }

  // ─── Réconciliation GatewayClass ────────────────────────────────────────

  /**
   * Pour chaque GatewayClass qui matche notre controllerName :
   * - Set status.conditions[0] = Accepted: true
   * Pour les autres : ne rien faire (un autre contrôleur les gère)
   */
  private def reconcileGatewayClasses(
    client: KubernetesClient,
    gatewayClasses: Seq[KubernetesGatewayClass],
    conf: KubernetesConfig
  )(implicit ec: ExecutionContext): Future[Unit] = {
    val ours = gatewayClasses.filter(_.controllerName == conf.gatewayApiControllerName)
    Future.sequence(ours.map { gc =>
      val status = Json.obj(
        "conditions" -> Json.arr(Json.obj(
          "type"               -> "Accepted",
          "status"             -> "True",
          "reason"             -> "Accepted",
          "message"            -> "GatewayClass accepted by Otoroshi",
          "lastTransitionTime" -> org.joda.time.DateTime.now().toString(),
          "observedGeneration" -> gc.raw.select("metadata").select("generation").asOpt[Long].getOrElse(0L)
        ))
      )
      client.updateGatewayClassStatus(gc.name, status)
    }).map(_ => ())
  }

  // ─── Réconciliation Gateway ─────────────────────────────────────────────

  /**
   * Pour chaque Gateway dont la gatewayClassName matche une GatewayClass acceptée :
   * - Valider chaque listener (port compatible avec les ports Otoroshi)
   * - Set status Accepted + Programmed
   * - Retourner la liste des Gateways acceptées
   */
  private def reconcileGateways(
    client: KubernetesClient,
    gateways: Seq[KubernetesGateway],
    gatewayClasses: Seq[KubernetesGatewayClass],
    conf: KubernetesConfig
  )(implicit ec: ExecutionContext): Future[Seq[KubernetesGateway]] = {
    val acceptedClassNames = gatewayClasses
      .filter(_.controllerName == conf.gatewayApiControllerName)
      .map(_.name).toSet
    val ourGateways = gateways.filter(gw => acceptedClassNames.contains(gw.gatewayClassName))

    Future.sequence(ourGateways.map { gw =>
      // Valider les listeners : vérifier que les ports sont compatibles
      val listenerStatuses = gw.listeners.map { listener =>
        val portOk = listener.protocol match {
          case "HTTP"  => listener.port == conf.gatewayApiHttpListenerPort
          case "HTTPS" => listener.port == conf.gatewayApiHttpsListenerPort
          case _       => false // TCP, UDP, TLS pas supportés pour le MVP
        }
        val protocolOk = Seq("HTTP", "HTTPS").contains(listener.protocol)

        val conditions = if (portOk && protocolOk) {
          Json.arr(
            conditionJson("Accepted", "True", "Accepted", "Listener accepted"),
            conditionJson("Programmed", "True", "Programmed", "Listener programmed"),
            conditionJson("ResolvedRefs", "True", "ResolvedRefs", "References resolved")
          )
        } else if (!protocolOk) {
          Json.arr(
            conditionJson("Accepted", "False", "UnsupportedProtocol",
              s"Protocol ${listener.protocol} not supported"),
            conditionJson("Programmed", "False", "Invalid", "Listener not programmed")
          )
        } else {
          Json.arr(
            conditionJson("Accepted", "False", "PortUnavailable",
              s"Port ${listener.port} does not match Otoroshi listener ports " +
              s"(HTTP:${conf.gatewayApiHttpListenerPort}, HTTPS:${conf.gatewayApiHttpsListenerPort})"),
            conditionJson("Programmed", "False", "Invalid", "Listener not programmed")
          )
        }

        Json.obj(
          "name"       -> listener.name,
          "conditions" -> conditions,
          "attachedRoutes" -> 0,  // Sera calculé après la réconciliation des routes
          "supportedKinds" -> Json.arr(
            Json.obj("group" -> "gateway.networking.k8s.io", "kind" -> "HTTPRoute")
          )
        )
      }

      val gatewayAccepted = listenerStatuses.nonEmpty // Au moins un listener
      val gatewayStatus = Json.obj(
        "conditions" -> Json.arr(
          conditionJson("Accepted",
            if (gatewayAccepted) "True" else "False",
            if (gatewayAccepted) "Accepted" else "NotReconciled",
            if (gatewayAccepted) "Gateway accepted by Otoroshi" else "No valid listeners"
          ),
          conditionJson("Programmed",
            if (gatewayAccepted) "True" else "False",
            if (gatewayAccepted) "Programmed" else "NotReconciled",
            if (gatewayAccepted) "Gateway programmed" else "Gateway not programmed"
          )
        ),
        "listeners" -> JsArray(listenerStatuses)
      )

      client.updateGatewayStatus(gw.namespace, gw.name, gatewayStatus).map(_ => gw)
    })
  }

  // ─── Réconciliation HTTPRoute ────────────────────────────────────────────

  /**
   * Pour chaque HTTPRoute :
   * - Vérifier les parentRefs (doivent pointer vers un Gateway accepté)
   * - Convertir en NgRoute(s) Otoroshi via GatewayApiConverter
   * - Mettre à jour le status de la HTTPRoute
   */
  private def reconcileHTTPRoutes(
    client: KubernetesClient,
    httpRoutes: Seq[KubernetesHTTPRoute],
    acceptedGateways: Seq[KubernetesGateway],
    services: Seq[KubernetesService],
    endpoints: Seq[KubernetesEndpoint],
    conf: KubernetesConfig
  )(implicit env: Env, ec: ExecutionContext): Future[Seq[NgRoute]] = {
    implicit val mat = env.otoroshiMaterializer

    Source(httpRoutes.toList)
      .mapAsync(1) { httpRoute =>
        val generatedRoutes = GatewayApiConverter.httpRouteToNgRoutes(
          httpRoute, acceptedGateways, services, endpoints, conf
        )

        // Construire le status pour chaque parentRef
        val parentStatuses = httpRoute.parentRefs.map { parentRef =>
          val gwNamespace = parentRef.namespace.getOrElse(httpRoute.namespace)
          val gatewayFound = acceptedGateways.exists(gw =>
            gw.name == parentRef.name && gw.namespace == gwNamespace
          )
          Json.obj(
            "parentRef"  -> Json.obj(
              "group"     -> "gateway.networking.k8s.io",
              "kind"      -> "Gateway",
              "namespace" -> gwNamespace,
              "name"      -> parentRef.name
            ),
            "controllerName" -> conf.gatewayApiControllerName,
            "conditions" -> Json.arr(
              conditionJson("Accepted",
                if (gatewayFound) "True" else "False",
                if (gatewayFound) "Accepted" else "NoMatchingParent",
                if (gatewayFound) "Route accepted" else s"Gateway ${gwNamespace}/${parentRef.name} not found"
              ),
              conditionJson("ResolvedRefs",
                if (generatedRoutes.nonEmpty) "True" else "False",
                if (generatedRoutes.nonEmpty) "ResolvedRefs" else "BackendNotFound",
                if (generatedRoutes.nonEmpty) "All references resolved" else "Some backend references could not be resolved"
              )
            )
          )
        }

        val routeStatus = Json.obj("parents" -> JsArray(parentStatuses))
        client.updateHTTPRouteStatus(httpRoute.namespace, httpRoute.name, routeStatus)
          .map(_ => generatedRoutes)
      }
      .runWith(akka.stream.scaladsl.Sink.seq)
      .map(_.flatten)
  }

  // ─── Sauvegarde et nettoyage ────────────────────────────────────────────

  /**
   * Sauvegarde les NgRoutes générées dans le datastore Otoroshi.
   * Utilise le même pattern compareAndSave que KubernetesCRDsJob.
   */
  private def saveGeneratedRoutes(
    generatedRoutes: Seq[NgRoute],
    existingManagedRoutes: Seq[NgRoute]
  )(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    implicit val mat = env.otoroshiMaterializer
    val existingMap = existingManagedRoutes.map(r => (r.id, r)).toMap
    val toSave = generatedRoutes.filter { route =>
      existingMap.get(route.id) match {
        case None           => true  // nouvelle route
        case Some(existing) => existing != route  // route modifiée
      }
    }
    if (toSave.nonEmpty) {
      logger.info(s"Saving ${toSave.size} Gateway API routes")
    }
    Source(toSave.toList)
      .mapAsync(1)(route => route.save().recover { case e =>
        logger.error(s"Failed to save route ${route.id}: ${e.getMessage}")
        false
      })
      .runWith(akka.stream.scaladsl.Sink.ignore)
      .map(_ => ())
  }

  /**
   * Supprime les NgRoutes qui étaient gérées par le Gateway API controller
   * mais qui n'ont plus de correspondance dans les ressources k8s.
   */
  private def deleteOrphanedRoutes(
    generatedRoutes: Seq[NgRoute],
    existingManagedRoutes: Seq[NgRoute]
  )(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val generatedIds = generatedRoutes.map(_.id).toSet
    val toDelete = existingManagedRoutes
      .filter(r => r.metadata.get("otoroshi-provider").contains(PROVIDER))
      .filterNot(r => generatedIds.contains(r.id))
    if (toDelete.nonEmpty) {
      logger.info(s"Deleting ${toDelete.size} orphaned Gateway API routes")
      env.datastores.routeDataStore.deleteByIds(toDelete.map(_.id))
    } else {
      ().future
    }
  }

  // ─── Helpers ────────────────────────────────────────────────────────────

  private def conditionJson(
    condType: String, status: String, reason: String, message: String
  ): JsObject = Json.obj(
    "type"               -> condType,
    "status"             -> status,
    "reason"             -> reason,
    "message"            -> message,
    "lastTransitionTime" -> org.joda.time.DateTime.now().toString()
  )
}
```

---

## Ordre d'implémentation recommandé

### Phase 1 — Fondations (estimé : 1-2 jours)

1. **`gateway_entities.scala`** — Créer toutes les case classes. Testable immédiatement avec des JsValue mockés.
2. **`config.scala`** — Ajouter les nouveaux champs. Changement minimal, pas de risque de régression.
3. **`client.scala`** — Ajouter les méthodes fetch. Testable en pointant sur un cluster avec les CRDs Gateway API installées.

### Phase 2 — Conversion (estimé : 2-3 jours)

4. **`gateway_converter.scala`** — La partie la plus complexe. Implémenter et tester unitairement :
   - `resolveParentRefs` — résolution des Gateways
   - `resolveEffectiveHostnames` — intersection hostnames
   - `buildDomains` — construction des domains Otoroshi
   - `buildTargets` — résolution des backends (commencer par clusterIP, puis endpoints)
   - `buildPlugins` — commencer avec RequestHeaderModifier et RequestRedirect seulement

### Phase 3 — Job et réconciliation (estimé : 1-2 jours)

5. **`gateway.scala`** — Le Job et la logique de sync. S'appuie sur tout le reste.
   - Commencer SANS le watch (sync périodique uniquement)
   - Ajouter le watch ensuite (même pattern que `handleWatch` dans `crds.scala`)

### Phase 4 — Status et polish (estimé : 1 jour)

6. **Status updates** — Les mises à jour de status dans le client.
7. **Cleanup/orphan deletion** — Suppression des routes orphelines.
8. Tests d'intégration avec un cluster réel.

---

## Points d'attention critiques

### 1. Le namespace `*` (wildcard)
Le client existant supporte `*` comme namespace (= tous les namespaces). Vérifier que les paths API sont corrects pour les ressources cluster-scoped (GatewayClass) vs namespace-scoped (Gateway, HTTPRoute).

### 2. Collision d'IDs de routes
L'ID de route généré doit être **déterministe et unique** : `kubernetes-gateway-api-{namespace}-{name}-rule-{idx}`. Cela garantit l'idempotence : deux syncs successifs sans changement ne créent pas de doublons.

### 3. Le metadata `otoroshi-provider`
C'est **critique** pour le garbage collection. Toute route créée par le Gateway API controller doit avoir `"otoroshi-provider" -> "kubernetes-gateway-api"` dans ses metadata. C'est ce que `deleteOrphanedRoutes` utilise pour identifier les routes à nettoyer. Le provider existant pour les CRDs est `"kubernetes-crds"` — ne pas confondre.

### 4. Les Status updates et les conflits
Les status updates utilisent des `merge-patch` sur la sous-ressource `/status`. En cas de conflit de version (409), il faudra retry. Pour le MVP, on peut ignorer les erreurs de status update (non bloquant).

### 5. TLS et certificats
Pour les listeners HTTPS, il faudra résoudre les `certificateRefs` vers des Secrets k8s de type `kubernetes.io/tls`, les importer en tant que `Cert` Otoroshi, et les associer. Le code existant dans `KubernetesCertSyncJob` fait déjà ce travail — **le réutiliser**.

### 6. Réutilisation du code existant
Réutiliser autant que possible le code existant d'Otoroshi :
- `KubernetesIngressToDescriptor.serviceToTargetsSync` pour la résolution Service → Endpoints → Targets
- `KubernetesCertSyncJob` pour la synchronisation des certificats TLS
- Le pattern `compareAndSave` de `KubernetesCRDsJob` pour la sauvegarde incrémentale
- Le pattern `filterLabels` de `KubernetesClient` pour le filtrage par labels

### 7. RBAC Kubernetes
Le ServiceAccount d'Otoroshi aura besoin de permissions supplémentaires sur les ressources Gateway API. Ajouter au ClusterRole :
```yaml
- apiGroups: ["gateway.networking.k8s.io"]
  resources: ["gatewayclasses", "gateways", "httproutes", "referencegrants"]
  verbs: ["get", "list", "watch"]
- apiGroups: ["gateway.networking.k8s.io"]
  resources: ["gatewayclasses/status", "gateways/status", "httproutes/status"]
  verbs: ["get", "update", "patch"]
```

---

## Tests

### Test minimal de validation
```yaml
# 1. Installer les CRDs Gateway API
kubectl apply -f https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.4.1/standard-install.yaml

# 2. Créer les ressources de test
---
apiVersion: gateway.networking.k8s.io/v1
kind: GatewayClass
metadata:
  name: otoroshi
spec:
  controllerName: otoroshi.io/gateway-controller
---
apiVersion: gateway.networking.k8s.io/v1
kind: Gateway
metadata:
  name: my-gateway
  namespace: default
spec:
  gatewayClassName: otoroshi
  listeners:
  - name: http
    port: 8080
    protocol: HTTP
    hostname: "*.example.com"
---
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata:
  name: my-route
  namespace: default
spec:
  parentRefs:
  - name: my-gateway
  hostnames:
  - "app.example.com"
  rules:
  - matches:
    - path:
        type: PathPrefix
        value: /api
    backendRefs:
    - name: my-backend-service
      port: 8080
```

### Vérifications attendues
1. `kubectl get gatewayclass otoroshi -o json | jq .status` → conditions Accepted: True
2. `kubectl get gateway my-gateway -o json | jq .status` → conditions Accepted: True, Programmed: True
3. `kubectl get httproute my-route -o json | jq .status` → parents[0].conditions Accepted: True
4. Dans l'admin Otoroshi : une NgRoute visible avec frontend domain `app.example.com/api` et backend pointant vers le service
