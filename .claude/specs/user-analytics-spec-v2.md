# Spec v2 — Dashboarding User Analytics pour Otoroshi

## 1. Objectif

Section **User Analytics** native, basée sur un nouvel exporter PostgreSQL dédié, sans dépendance externe, avec dashboards personnalisables et catalogue de requêtes extensible.

Coexistence avec l'analytics legacy (Elastic). Deux entrées dans le menu Analytics :
- `Global Analytics` / `Service Analytics` (legacy, inchangées)
- **`User Analytics`** (nouveau, sur PG)

---

## 2. Architecture

```
┌────────────────────────┐
│ Otoroshi (all nodes)   │
│  GatewayEvent stream   │
└──────────┬─────────────┘
           │
           ▼ (filtré + stripé)
┌────────────────────────────────────┐
│ PostgresAnalyticsExporter          │
│  → table otoroshi_analytics_events │
│    (colonnes dénormalisées + GIN)  │
└──────────┬─────────────────────────┘
           │
           ▼ (lecture, leaders only)
┌────────────────────────────────────┐
│ AnalyticsQueryService              │
│  - catalogue de requêtes           │
│  - bucketing adaptatif             │
│  - cache LRU + statement_timeout   │
└──────────┬─────────────────────────┘
           │
           ▼
┌────────────────────────────────────┐
│ AnalyticsController (POST)         │
│  + DashboardController (CRUD)      │
└──────────┬─────────────────────────┘
           │
           ▼
┌────────────────────────────────────┐
│ Frontend `pages/analytics/`        │
│  /bo/dashboard/user-dashboards     │
└────────────────────────────────────┘
```

**Localisation du code** :
- Backend : `otoroshi/app/next/analytics/{models,controllers,queries,exporter,migration}/`
- Frontend : `otoroshi/javascript/src/pages/analytics/`
- Registration : `app/api.scala`, `app/OtoroshiLoader.scala`, `conf/routes`, `app/api/api.scala`

---

## 3. Nouvel exporter `UserAnalyticsExporter`

### 3.1 Configuration (case class)

```scala
UserAnalyticsExporterSettings(
  uri, host, port, database, user, password,
  schema = "public",
  table  = "otoroshi_analytics_events",
  poolSize = 10,
  ssl,
  retentionDays = 30,
  statementTimeoutMs = 30000,
  rollupEnabled = false   // phase 2
)
```

Exporter type distinct (`user-analytics`) — detection via une metadata distincte. Quand métadata présent, on affiche un label dans la table et sur l'exporter. Ajouter un bouton de shortcut qui place la métadata sur un exporter et l'enleve sur tous les autres. ca aidera les potentielles migrations.

### 3.2 Filtrage à l'entrée

Seuls les `GatewayEvent` sont insérés. Tout le reste (`AlertEvent`, `AuditEvent`, etc.) ignoré.

### 3.3 Stripping des events

**Champs supprimés** (gros, peu utiles pour analytics) :
- `headers`, `headersOut`, `otoroshiHeadersIn`, `otoroshiHeadersOut`
- `route.backend` (targets, creds)
- `route.plugins` (full plugin chain)
- `route.frontend` détaillé (on garde juste les domains)
- `clientCertChain`
- Stripping appliqué via une fonction `stripGatewayEvent(event: JsValue): JsValue` dédiée et **éditable manuellement** par le mainteneur otoroshi.

**Champs conservés** :
- `@id`, `@timestamp`, `@env`, `@type`, `@service`, `@serviceId`, `@product`
- `err`, `gwError`, `method`, `status`, `url`, `protocol`, `responseChunked`
- `from`, `to.host`, `target.host`
- Timings : `duration`, `backendDuration`, `overhead`, `cbDuration`, `requestStreamingDuration`, `responseStreamingDuration`
- `data.dataIn`, `data.dataOut`
- `identity` (type + id + label + metadata + tags)
- `userAgentInfo`, `geolocationInfo` (pour analytics géo/UA)
- `route` réduit : `{id, name, _loc, groups, metadata.Otoroshi-Api-Ref, frontend.domains, metadata, tags }`
- `reqId`, `parentReqId`, `callAttempts`

### 3.4 Schéma SQL

```sql
CREATE TABLE otoroshi_analytics_events (
  id              TEXT        PRIMARY KEY,
  ts              TIMESTAMPTZ NOT NULL,
  env             TEXT,
  tenant          TEXT        NOT NULL,
  teams           TEXT[]      NOT NULL DEFAULT '{}',
  route_id        TEXT,
  route_name      TEXT,
  api_id          TEXT,                         -- route.metadata['Otoroshi-Api-Ref']
  group_ids       TEXT[]      NOT NULL DEFAULT '{}',
  apikey_id       TEXT,
  user_email      TEXT,                         -- identity.identity si PRIVATEAPP
  identity_type   TEXT,                         -- APIKEY | PRIVATEAPP | null
  domain          TEXT,                         -- to.host
  method          TEXT,
  status          SMALLINT,
  err             BOOLEAN     NOT NULL DEFAULT false,
  duration_ms     INTEGER,
  overhead_ms     INTEGER,
  backend_ms      INTEGER,
  data_in         BIGINT,
  data_out        BIGINT,
  from_ip         TEXT,
  country         TEXT,                         -- geolocationInfo.country
  user_agent      TEXT,                         -- userAgentInfo.ua
  protocol        TEXT,
  raw             JSONB       NOT NULL          -- event stripé complet
);

CREATE INDEX idx_oae_ts             ON otoroshi_analytics_events (ts DESC);
CREATE INDEX idx_oae_route_ts       ON otoroshi_analytics_events (route_id, ts DESC);
CREATE INDEX idx_oae_api_ts         ON otoroshi_analytics_events (api_id, ts DESC) WHERE api_id IS NOT NULL;
CREATE INDEX idx_oae_apikey_ts      ON otoroshi_analytics_events (apikey_id, ts DESC) WHERE apikey_id IS NOT NULL;
CREATE INDEX idx_oae_tenant_ts      ON otoroshi_analytics_events (tenant, ts DESC);
CREATE INDEX idx_oae_status_ts      ON otoroshi_analytics_events (status, ts DESC);
CREATE INDEX idx_oae_err_ts         ON otoroshi_analytics_events (ts DESC) WHERE err = true;
CREATE INDEX idx_oae_groups_gin     ON otoroshi_analytics_events USING GIN (group_ids);
CREATE INDEX idx_oae_teams_gin      ON otoroshi_analytics_events USING GIN (teams);
```

**Migration idempotente** : `CREATE TABLE IF NOT EXISTS` + `CREATE INDEX IF NOT EXISTS` au démarrage de l'exporter (par le leader uniquement). Versionnée : table `otoroshi_analytics_schema_version` pour évoluer le schéma proprement plus tard.

**Rollups** : pas en phase 1, mais le code prévoit un hook (job qui lit la table brute → écrit `otoroshi_analytics_rollup_1m/1h/1d`). Activé via `rollupEnabled`.

### 3.5 Rétention

Job `AnalyticsRetentionJob` (toutes les heures, leader only) :
```sql
DELETE FROM otoroshi_analytics_events WHERE ts < NOW() - INTERVAL 'X days';
```

### 3.6 Cluster

- **Écriture** : tous les nodes (workers + leader) écrivent dans le même PG (déjà le cas pour l'exporter actuel).
- **Lecture** (API analytics) : leaders only → guard dans le controller (`if (!env.clusterConfig.mode.isLeader) NotFound`).
- **Création table / dashboards par défaut / rétention job** : leader only.

### 3.7 Script de migration depuis l'exporter PG existant

Endpoint admin (super admin only) ou job ponctuel CLI :

```
POST /api/analytics/_migrate
{
  "source": { "host": "...", "database": "...", "table": "events", ... },
  "batchSize": 5000,
  "dryRun": false
}
```

Lit l'ancienne table (`event JSONB` → GatewayEvent), strip + dénormalise, INSERT batch dans la nouvelle. Stream + progress logs. Idempotent (`ON CONFLICT (id) DO NOTHING`).

Mode `dryRun` : compte les lignes sans insérer.

---

## 4. API backend analytics

### 4.1 Endpoint unique

```
POST /api/analytics/_query
Authorization: super admin only (phase 1)
Content-Type: application/json

{
  "query": "requests_per_second",
  "params": { "top_n": 10 },              // params spécifiques à la requête
  "filters": {
    "from": "2026-04-27T00:00:00Z",        // ou "now-1h"
    "to":   "now",
    "route_id": "route_xxx",               // optionnel
    "api_id":   "api_xxx",                 // optionnel
    "apikey_id": "apikey_xxx",             // optionnel
    "group_id":  "group_xxx",              // optionnel
    "err":       null                      // null | true | false
  },
  "bucket": null,                          // null = auto, sinon "1m" | "5m" | "1h" | "6h" | "1d"
  "compare": false,                        // overlay période précédente
  "nocache": false
}
```

### 4.2 Bucketing adaptatif

Si `bucket = null`, dérivé de la range :
| Range            | Bucket |
|------------------|--------|
| ≤ 1h             | `1m`   |
| ≤ 24h            | `1h`   |
| ≤ 7d             | `6h`   |
| > 7d             | `1d`   |

Override possible via `bucket` dans le payload.

Bucket alignment : `date_trunc(bucket, ts)` (UTC). Buckets vides remplis à `0` côté serveur (`generate_series` JOIN).

### 4.3 Format de réponse

```json
{
  "query": "requests_per_second",
  "shape": "timeseries",          // timeseries | topN | pie | scalar | metric | table | heatmap
  "bucket": "1m",
  "from": "...",
  "to":   "...",
  "data": { /* shape-spécifique, voir §5 */ },
  "raw":  [ /* lignes brutes SQL, fallback */ ],
  "meta": {
    "executionMs": 124,
    "fromCache":   false,
    "rowCount":    60
  },
  "compare": { /* même format si compare=true */ }
}
```

### 4.4 Cache

- LRU bornée (1000 entrées) en RAM, TTL 30s (configurable globalement).
- Clé : `hash(query + params + filters + bucket + tenant)`.
- Invalidation : TTL only (pas d'invalidation event-based en phase 1).
- Bypass via `nocache: true`.

### 4.5 Timeout & cancellation

- `statement_timeout = 30s` posé à chaque session.
- Cancellation phase 1 = client-side AbortController. Côté serveur, on laisse le statement_timeout couper. Vrai cancel PG en phase 2.

### 4.6 RBAC phase 1

- **Super admin only** pour le controller (UI + API).
- Hook RBAC déjà en place pour étendre plus tard (filtre auto par `tenant` injecté avant exécution SQL).

---

## 5. Catalogue de requêtes

### 5.1 Format de déclaration

```scala
case class AnalyticsQuery(
  id: String,
  name: String,
  description: String,
  shape: AnalyticsShape,        // Timeseries | TopN | Pie | Scalar | Metric | Table | Heatmap
  defaultWidget: WidgetType,    // suggestion UI
  params: Seq[QueryParam],      // top_n, percentile, etc.
  supportsCompare: Boolean,
  execute: (Filters, Params, Bucket, PgPool) => Future[QueryResult]
)
```

### 5.2 Liste phase 1

| ID | Nom | Shape | Widget |
|---|---|---|---|
| `requests_total` | Total requests | scalar | metric/scalar |
| `requests_per_second` | Requests per second | timeseries | line |
| `requests_by_status` | Requests by status code | pie | pie |
| `requests_by_status_class_ts` | Status classes over time | timeseries (stacked) | area |
| `requests_by_method` | Requests by method | pie | pie |
| `requests_by_route` | Top routes | topN | bar |
| `requests_by_api` | Top APIs | topN | bar |
| `requests_by_apikey` | Top API keys | topN | bar |
| `requests_by_user` | Top users | topN | bar |
| `requests_by_domain` | Top domains | topN | bar |
| `requests_by_country` | Top countries | topN | bar |
| `error_rate_ts` | Error rate over time | timeseries | line |
| `errors_total` | Total errors | scalar | metric |
| `top_error_routes` | Top error routes | topN | bar |
| `duration_avg_ts` | Avg response time | timeseries | line |
| `duration_percentiles_ts` | Latency percentiles (p50/75/95/99) | timeseries (multi) | line |
| `overhead_avg_ts` | Avg overhead | timeseries | line |
| `traffic_in_out_ts` | Data in/out over time | timeseries (multi) | area |
| `response_time_heatmap` | Latency × time heatmap | heatmap | heatmap |
| `apikey_quota_usage` | API key quota usage | topN | bar |

### 5.3 Extensibilité par admin extensions

Trait à exposer :

```scala
trait AdminExtension {
  // ...existants...
  def analyticsQueries(): Seq[AnalyticsQuery] = Seq.empty
}
```

Le service `AnalyticsQueryRegistry` agrège : core + `env.adminExtensions.analyticsQueries()`. Les ids sont préfixés (`waf.blocked_per_route`) pour éviter les collisions.

---

## 6. Entité Dashboard

### 6.1 Modèle

```scala
case class UserDashboard(
  location: EntityLocation,
  id: String,
  name: String,
  description: String,
  tags: Seq[String],
  metadata: Map[String, String],   // "otoroshi-default" -> "true" pour les défauts
  enabled: Boolean,
  widgets: Seq[Widget]
) extends EntityLocationSupport
```

### 6.2 Format JSON

```json
{
  "id": "dashboard_xxx",
  "name": "Global overview",
  "description": "Default global traffic dashboard",
  "metadata": {},
  "tags": [],
  "enabled": true,
  "widgets": [
    {
      "id": "w1",
      "title": "Requests per second",
      "query": "requests_per_second",
      "params": {},
      "type": "line",
      "width": 4,
      "height": 2,
      "options": { "format": "rps", "yAxisLog": false, "legend": true }
    },
    {
      "id": "w2",
      "title": "Total requests",
      "query": "requests_total",
      "type": "metric",
      "width": 1,
      "height": 1,
      "options": { "format": "count", "thresholds": [] }
    }
  ]
}
```

### 6.3 JSON Schema

Schéma JSON publié à `/conf/schemas/dashboard.json`, chargé par l'éditeur (CodeMirror) pour autocomplete/validation live. Liste des `query` ids et `widget.type` injectées dynamiquement (via endpoint `GET /api/analytics/_schema`).

### 6.4 Datastore & registration

Suit le pattern habituel des nouvelles entités :
- `app/next/analytics/models/UserDashboard.scala`
- `app/next/analytics/datastore/UserDashboardDataStore.scala` (KV-based, comme les autres)
- Registration dans `app/api/api.scala` (Resource) + `app/api.scala` + `app/OtoroshiLoader.scala`
- CRUD générique via `GenericResourceAccessApiWithState`
- Routes admin auto-exposées + UI form auto-générable (mais ici on fera une page custom)

### 6.5 Permissions phase 1

Super admin only pour CRUD et lecture.

---

## 7. Widgets

### 7.1 Types supportés (phase 1)

| Type | Description | Shapes acceptées |
|------|-------------|------------------|
| `line` | Time series ligne | timeseries |
| `area` | Time series remplie | timeseries |
| `bar` | Top-N horizontal | topN, pie |
| `pie` | Camembert | pie |
| `donut` | Camembert ajouré | pie |
| `scalar` | Big number + sparkline | scalar |
| `metric` | Texte + valeur (label + valeur) | scalar, metric |
| `table` | Tableau | topN, table |
| `heatmap` | Matrice | heatmap |

### 7.2 Options communes

```json
{
  "title": "string (override query name)",
  "subtitle": "string",
  "format": "count | rps | ms | bytes | percent | custom",
  "formatTemplate": "${value} req/s",
  "colors": ["#..."],
  "thresholds": [
    { "value": 100, "color": "green" },
    { "value": 500, "color": "orange" },
    { "value": 1000, "color": "red" }
  ],
  "yAxisLog": false,
  "yAxisMin": null,
  "yAxisMax": null,
  "legend": true,
  "stacked": false,
  "decimals": 0,
  "compareWithPrevious": false
}
```

### 7.3 Refresh / loading / error / empty

Chaque widget gère ses 4 états visuellement :
- **loading** : skeleton + spinner discret
- **error** : message rouge + bouton retry
- **empty** : "No data for this range"
- **ok** : chart

Refresh : auto via AbortController + débounce 250ms quand les filtres globaux changent.

---

## 8. Layout

Grille 4 colonnes, hauteur illimitée. Layout = ordre du tableau `widgets[]` + `width` (1-4) + `height` (1-N rows). CSS flex/grid simple, pas de drag&drop en phase 1.

Si `width > colonnes restantes sur la ligne` → wrap automatique sur la ligne suivante. Pas de `x/y` explicites.

---

## 9. Filtres globaux + auto-refresh + compare

### 9.1 Filtres en haut du dashboard

- **Time range** : presets (`1h`, `6h`, `12h`, `24h`, `7d`, `30d`) + custom (date/time picker)
- **Route** : single-select (autocomplete sur les NgRoute)
- **API** : single-select (autocomplete sur l'entité Api). API id récupéré via `route.metadata['Otoroshi-Api-Ref']`
- **API key** : single-select
- **Group** : single-select
- (Pas de multi-select / autres filtres en phase 1)

### 9.2 Auto-refresh

Selector global : `Off | 10s | 30s | 1m | 5m | Custom...`. Custom = input numérique secondes (min 5s).

Pause auto-refresh quand l'onglet n'est pas visible (via `document.visibilityState`).

### 9.3 Compare period

Toggle global "Compare with previous period". Quand activé, chaque widget timeseries reçoit aussi la série précédente (range décalée de la durée actuelle), affichée en pointillés avec la même couleur opacité 50%.

Disponible uniquement sur les widgets dont la query déclare `supportsCompare = true`.

---

## 10. Query params URL

État du dashboard 100% sérialisé en URL :

```
/bo/dashboard/user-dashboards/{id}?from=now-1h&to=now&route=route_xxx&api=api_xxx&apikey=apikey_xxx&group=group_xxx&err=true&refresh=30s&compare=true
```

- Time range : supporte le format relatif (`now-1h`, `now-7d`) ET absolu ISO. Le relatif reste vivant sur les liens partagés.
- Back/forward navigation respectés (URL → state).
- "Copy link" button qui copie l'URL courante.

Sur la page liste, les filtres permettent aussi le deep-link mais juste pour ouvrir un dashboard donné.

---

## 11. Dashboards par défaut & restore

### 11.1 Création initiale

À la création réussie de l'exporter analytics (ou au démarrage si exporter déjà présent et table vide de dashboards défaut), le leader crée 5 dashboards :

1. **Global Overview** — RPS, total requests, avg duration, error rate, status pie
2. **Performance** — duration percentiles, overhead avg, slowest routes, latency heatmap
3. **Errors** — error rate ts, top error routes, errors by status class
4. **APIs & Routes** — top routes, top APIs, traffic per API
5. **Consumers** — top API keys, top users, top countries, quota usage

Marqués `metadata: { "otoroshi-default": "true", "otoroshi-default-version": "1" }`.

### 11.2 Bouton restore

Sur la page liste des dashboards, bouton **"Restore default dashboards"** :
- Recrée uniquement les défauts manquants (vérification par `metadata.otoroshi-default-id`).
- Confirmation modale avant exécution.
- Les défauts existants modifiés par l'utilisateur ne sont **pas** écrasés.

---

## 12. UI / pages frontend

### 12.1 Pages

- `/bo/dashboard/user-dashboards` — table de dashboards (réutilise `Table` Otoroshi)
- `/bo/dashboard/user-dashboards/new` — édition JSON (CodeMirror + JSON schema)
- `/bo/dashboard/user-dashboards/:id/edit` — idem
- `/bo/dashboard/user-dashboards/:id` — visualisation (filtres + grille de widgets)

### 12.2 Composants à réutiliser

- `Table` (existant)
- `JsonObjectAsCodeInput` (CodeMirror), voir si possible d'utiliser un editeur monaco (présent) à la place
- `Histogram`, `RoundChart` (Recharts) → étendus avec `Bar`, `Scalar`, `Metric`, `Table`, `Heatmap`
- Form components Otoroshi pour les filtres
- Bootstrap v4 + CSS Otoroshi natif

### 12.3 Onboarding

Si pas d'exporter analytics détecté à l'arrivée sur `/bo/dashboard/user-dashboards` :
- Écran "No analytics exporter configured"
- Bouton "Create one" → wizard pré-rempli (host/db/user requis) → crée l'exporter + table + dashboards défaut
- Lien "Configure manually" vers la page Data Exporters

---

## 13. Hors-scope phase 1 (notés pour plus tard)

- Système d'alertes (phase 2)
- Éditeur drag & drop (phase 2)
- Rollups / continuous aggregates (phase 2 si besoin perf)
- Vrai cancel PG côté serveur (phase 2)
- RBAC tenant-scoped (phase 2)
- Multi-route / multi-apikey filter (phase 2)
- Filtres : status code, method, domain, err tri-state, free-text path (phase 2)
- Dashboards "personnels vs partagés" (phase 2)
- Drill-down click (phase 2)
- Export CSV / PNG (phase 2)
- Dark mode polish, embedding iframe (phase 3)

---

## 14. Décisions finales

**Q1. Référence API dans la route.** Clé metadata : `Otoroshi-Api-Ref` → valeur = id de l'API. Lecture : `route.metadata['Otoroshi-Api-Ref']` à l'insertion → colonne `api_id`.

**Q2. Stripping.** Une fonction `stripGatewayEvent(event: JsValue): JsValue` dédiée, isolée dans son propre fichier. Implémentation par défaut fournie (voir §3.3) mais conçue pour être éditée à la main par l'utilisateur sans toucher au reste du code de l'exporter.

**Q3. Nom de l'exporter.** `UserAnalyticsExporter` (config = `UserAnalyticsExporterSettings`, type = `user-analytics`).
