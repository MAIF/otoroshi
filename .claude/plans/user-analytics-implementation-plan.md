# User Analytics — Plan d'implémentation détaillé

> Compagnon de `user-analytics-spec-v2.md`. Donne l'arborescence des fichiers à créer/modifier et l'ordre d'exécution.

## Phases & dépendances

```
A  Exporter & storage     ─┐
B  Query engine            ├──> C  Controllers/routes ──> G  Frontend ──> H  Onboarding
D  Dashboard entity       ─┘                                                   │
E  Default dashboards (dépend de A+D+B)                                        │
F  Migration legacy (dépend de A)                                              │
I  Tests + doc (transverse)                                              ──────┘
```

---

## Phase A — Exporter & storage foundation

### A.1 Nouveaux fichiers

| Fichier | Contenu |
|---|---|
| `app/next/analytics/exporter/EventStripper.scala` | **Fichier ÉDITABLE manuellement.** Expose `def stripGatewayEvent(event: JsValue): JsValue`. Implémentation par défaut documentée bloc par bloc (headers/route/plugins retirés). C'est ici que tu raffines la liste à la main. |
| `app/next/analytics/exporter/UserAnalyticsExporter.scala` | Tout-en-un : `UserAnalyticsExporterSettings` (case class + Format), `AnalyticsSchema` (DDL strings + `migrate(pool)` idempotent), `EventDenormalizer` (extract des 23 colonnes, lit `route.metadata['Otoroshi-Api-Ref']`), et le sink lui-même (filtre `@type == "GatewayEvent"`, applique stripping + denorm, batch INSERT via Vert.x `PgPool`, gère pool lifecycle). |
| `app/next/analytics/exporter/AnalyticsRetentionJob.scala` | Job cron horaire, leader-only. `DELETE FROM ... WHERE ts < NOW() - INTERVAL '$retentionDays days'`. |

### A.2 Détection de l'exporter actif

Pattern : **type distinct + metadata flag pour activer**.

- Type d'exporter : `user-analytics`
- Plusieurs exporters `user-analytics` peuvent coexister
- Metadata flag `otoroshi:user-analytics:active = "true"` désigne lequel est lu par les queries
- **Mutex** : un seul exporter peut porter le flag à un instant T
- Helper `def findActiveAnalyticsExporter(env): Option[DataExporterConfig]` dans `UserAnalyticsExporter.scala`
- Si aucun exporter actif → l'API `/_query` répond `412 Precondition Failed` avec message clair

### A.3 Fichiers existants à modifier

| Fichier | Modification |
|---|---|
| `app/models/dataExporter.scala` | Ajouter `UserAnalyticsExporterSettings` au sealed trait + factory + JSON Reads (discriminé par `type: "user-analytics"`). |
| `app/events/OtoroshiEventsActor.scala` | Ajouter le branch d'exécution dans le dispatcher d'events (à côté du PG existant lignes ~2350-2502). |
| `app/OtoroshiLoader.scala` | Enregistrer `AnalyticsRetentionJob`. |

### A.4 Ordre

1. `EventStripper.scala`
2. `UserAnalyticsExporter.scala` (incluant settings + schema + denormalizer + sink + helper de détection)
3. `AnalyticsRetentionJob.scala`
4. Wiring dans `dataExporter.scala` + `OtoroshiEventsActor.scala` + `OtoroshiLoader.scala`
5. **Test manuel** : créer 2 exporters `user-analytics`, vérifier que la table + indexes apparaissent dans PG, que tous les events arrivent dans les deux, et que le helper de détection retourne celui qui a le flag.

---

## Phase B — Query engine

### B.1 Nouveaux fichiers

| Fichier | Contenu |
|---|---|
| `app/next/analytics/queries/AnalyticsCore.scala` | Tout l'infra : `AnalyticsShape` (sealed trait), `AnalyticsQuery` (trait), `QueryParam`, `QueryResult`, `Filters`, `Bucket`, `Bucketing.autoBucket`, `FilterSql.whereClause` (avec hook tenant), `QueryCache` (LRU 1000 entrées TTL 30s), `QueryExecutor` (cache + statement_timeout + compare), `AnalyticsQueryRegistry` (core + extensions). |
| `app/next/analytics/queries/CoreQueries.scala` | Les 20 requêtes core, groupées en 5 objets : `ScalarQueries`, `PieQueries`, `TopNQueries`, `TimeseriesQueries`, `HeatmapQueries`. Toutes utilisent `generate_series` + `date_trunc` UTC. |

### B.2 Hook extension

Modifier `app/next/extensions/extension.scala` (ou équivalent — confirmer en début de phase) :

```scala
trait AdminExtension {
  def analyticsQueries(): Seq[AnalyticsQuery] = Seq.empty
}
```

### B.3 Ordre

1. `AnalyticsCore.scala` (infra + registry vide)
2. `CoreQueries.scala` (commencer par Scalar/Pie qui sont triviales, puis TopN, puis Timeseries, puis Heatmap)
3. Brancher les queries dans le Registry
4. Hook `AdminExtension.analyticsQueries()`

---

## Phase C — Controllers + routes

### C.1 Nouveaux fichiers

| Fichier | Contenu |
|---|---|
| `app/next/analytics/controllers/AnalyticsController.scala` | `POST /_query`, `GET /_schema`, `POST /_migrate`, `POST /_test-connection`, `POST /_set-active-exporter/:id`. Tous super admin. `_query` est leader-only. |
| `app/next/analytics/controllers/UserDashboardController.scala` | CRUD dashboards (peut s'appuyer sur `GenericResourceAccessApiWithState`) + `POST /dashboards/_restore-defaults`. |

### C.2 Fichiers existants à modifier

| Fichier | Modification |
|---|---|
| `conf/routes` | Routes pour `/api/analytics/*` + UI `GET /bo/dashboard/user-dashboards*`. |
| `app/OtoroshiLoader.scala` | DI des controllers. |
| `app/api.scala` | Si pattern existant le requiert (à confirmer). |

### C.3 Garde-fous

- `if (!ctx.user.superAdmin) Forbidden` partout
- `if (!env.clusterConfig.mode.isLeader) NotFound` sur `_query` et `_migrate`
- `_query` retourne `412` si pas d'exporter actif

---

## Phase D — Entité Dashboard

### D.1 Nouveaux fichiers

| Fichier | Contenu |
|---|---|
| `app/next/analytics/models/UserDashboard.scala` | `case class Widget` + `case class UserDashboard extends EntityLocationSupport` + Format JSON + `trait UserDashboardDataStore extends BasicStore[UserDashboard]` + `class KvUserDashboardDataStore extends UserDashboardDataStore with RedisLikeStore[UserDashboard]`. Clé de stockage : `s"${env.storageRoot}:analytics:userdashboards:$id"`. |

### D.2 Fichiers existants à modifier

| Fichier | Modification |
|---|---|
| `app/api/api.scala` | Déclarer `Resource("UserDashboard", ...)` avec `GenericResourceAccessApiWithState`. |
| `app/api.scala` | Brancher dans le router générique. |
| `app/storage/stores/...` | Ajouter le datastore au trait `DataStores` + impls (in-memory, redis, postgres, cassandra) — pattern habituel. |
| `conf/schemas/dashboard.json` | JSON Schema statique (les ids dynamiques de queries viennent de `/api/analytics/_schema`). |

---

## Phase E — Default dashboards

### E.1 Nouveau fichier

| Fichier | Contenu |
|---|---|
| `app/next/analytics/defaults/DefaultDashboards.scala` | Définit les 5 dashboards par défaut (constantes JSON), méthodes `seedIfMissing(env)` (ne crée que les manquants par `metadata.otoroshi-default-id`) et `restoreAll(env)`. Inclut aussi le **migrator legacy** (Phase F) car les deux concernent le seeding initial. |

### E.2 Hooks

- `seedIfMissing` appelé quand un exporter `user-analytics` devient actif (au start si déjà actif au boot, ou à la promotion via le bouton)
- Endpoint `POST /api/analytics/dashboards/_restore-defaults` → `restoreAll`

### E.3 Liste

```
analytics-default-overview      Global Overview
analytics-default-performance   Performance
analytics-default-errors        Errors
analytics-default-apis-routes   APIs & Routes
analytics-default-consumers     Consumers
```

---

## Phase F — Migration script (legacy PG → user-analytics)

Inclus dans `DefaultDashboards.scala` (Phase E) ou dans son propre fichier si ça grossit. Logique :

- Stream `SELECT id, event FROM <legacy_table> ORDER BY timestamp` avec curseur
- Applique `stripGatewayEvent` + extract columns
- Batch INSERT dans la nouvelle table avec `ON CONFLICT (id) DO NOTHING`
- Mode `dryRun` : juste `COUNT(*)`
- Endpoint : `POST /api/analytics/_migrate` (cf Phase C)
- Réponse streamée avec progress

---

## Phase G — Frontend

### G.1 Nouveaux fichiers

| Fichier | Contenu |
|---|---|
| `pages/analytics/UserDashboardsPage.js` | Liste (réutilise `Table`) + bouton "Restore defaults" + onboarding inline si pas d'exporter actif. |
| `pages/analytics/UserDashboardEditPage.js` | Éditeur JSON (`JsonObjectAsCodeInput` + JSON Schema chargé depuis `/api/analytics/_schema`). |
| `pages/analytics/UserDashboardViewPage.js` | Visualisation : assemble `Filters` + `Grid` + widgets, gère URL ↔ state. |
| `pages/analytics/Filters.js` | Time range picker (presets + custom + relative `now-1h`) + selectors route/api/apikey/group + auto-refresh + compare toggle. |
| `pages/analytics/Grid.js` | Layout 4 colonnes auto-flow + `WidgetWrapper` (loading/error/empty/ok + AbortController + auto-refresh + pause sur tab inactif). |
| `pages/analytics/widgets.js` | Tous les types : Line, Area, Bar, Pie, Donut, Scalar, Metric, Table, Heatmap. Chacun ~30 lignes en réutilisant Recharts. Map `type → component`. |
| `pages/analytics/service.js` | `runQuery(query, filters, params, bucket, compare, signal)`, parsing time relatif, sérialisation URL. |

### G.2 Fichiers existants à modifier

| Fichier | Modification |
|---|---|
| `javascript/src/backoffice.js` | Routes `/bo/dashboard/user-dashboards*` + entrée menu "User Analytics" sous Analytics. |
| **Page Data Exporters existante** | Ajouter le bouton **"Set as active analytics exporter"** (visible uniquement sur les exporters de type `user-analytics`) qui appelle `POST /_set-active-exporter/:id`. Badge "Active" sur celui qui porte le flag. |

### G.3 Ordre

1. `service.js` (utilitaires)
2. `widgets.js` (commence par Metric/Scalar/Line, les plus simples)
3. `Grid.js` (incluant WidgetWrapper)
4. `Filters.js`
5. `UserDashboardViewPage.js`
6. `UserDashboardsPage.js`
7. `UserDashboardEditPage.js`
8. Modifs `backoffice.js` + page Data Exporters

---

## Phase H — Onboarding

Inclus dans `UserDashboardsPage.js` (Phase G). Logique :

1. Au mount, fetch `/api/data-exporters?type=user-analytics`
2. Aucun exporter `user-analytics` → écran "Configure user analytics" avec wizard inline
3. Wizard : champs PG requis (host, port, db, user, pwd, retentionDays). Bouton "Test connection" → `POST /_test-connection`. Sur succès, "Create" → POST exporter + active flag automatique + déclenche seed des dashboards par défaut.
4. Au moins un exporter `user-analytics` mais aucun actif → bandeau "An analytics exporter is configured but not active" + bouton "Set as active".

---

## Phase I — Tests + doc

| Fichier | Contenu |
|---|---|
| `test/functional/UserAnalyticsSpec.scala` | Un spec qui couvre : exporter écrit + dénormalise correctement, queries clés exécutées sur fixtures (timeseries + topN + scalar), bucketing, compare, dashboard CRUD, restore defaults. |
| `manual/next/docs/topics/user-analytics.mdx` | Doc utilisateur : création de l'exporter via onboarding, dashboards par défaut, ajouter un widget, query params URL, écrire ses requêtes via extension, migration depuis l'ancien exporter PG, promotion d'exporter pour zéro-downtime. |

---

## Récap fichiers

### Nouveaux : 18

**Backend (9)**
1. `app/next/analytics/exporter/EventStripper.scala`
2. `app/next/analytics/exporter/UserAnalyticsExporter.scala`
3. `app/next/analytics/exporter/AnalyticsRetentionJob.scala`
4. `app/next/analytics/queries/AnalyticsCore.scala`
5. `app/next/analytics/queries/CoreQueries.scala`
6. `app/next/analytics/controllers/AnalyticsController.scala`
7. `app/next/analytics/controllers/UserDashboardController.scala`
8. `app/next/analytics/models/UserDashboard.scala`
9. `app/next/analytics/defaults/DefaultDashboards.scala`

**Frontend (7)**
10. `javascript/src/pages/analytics/UserDashboardsPage.js`
11. `javascript/src/pages/analytics/UserDashboardEditPage.js`
12. `javascript/src/pages/analytics/UserDashboardViewPage.js`
13. `javascript/src/pages/analytics/Filters.js`
14. `javascript/src/pages/analytics/Grid.js`
15. `javascript/src/pages/analytics/widgets.js`
16. `javascript/src/pages/analytics/service.js`

**Tests + doc (2)**
17. `test/functional/UserAnalyticsSpec.scala`
18. `manual/next/docs/topics/user-analytics.mdx`

### Modifs sur l'existant : 8

1. `app/models/dataExporter.scala` (déclaration exporter)
2. `app/events/OtoroshiEventsActor.scala` (dispatch)
3. `app/OtoroshiLoader.scala` (job + DI controllers)
4. `app/api.scala` (controllers)
5. `app/api/api.scala` (resource)
6. `conf/routes` (toutes les routes)
7. `conf/schemas/dashboard.json` (JSON Schema)
8. `javascript/src/backoffice.js` (routes + menu) + page Data Exporters (bouton promote)

**Total : 26 touches** (18 créations + 8 modifs).

---

## Estimations grossières

| Phase | Charge |
|---|---|
| A — Exporter | 1.5 j |
| B — Query engine | 2.5 j |
| C — Controllers | 0.5 j |
| D — Entité | 0.5 j |
| E — Defaults + F — Migration | 0.5 j |
| G — Frontend | 4 j |
| H — Onboarding | 0.5 j |
| I — Tests + doc | 1 j |
| **Total phase 1** | **~11 j** |

---

## Points à vérifier en cours de route

1. Le Vert.x `PgPool` se partage-t-il proprement entre l'exporter (write) et le query service (read) ? Sinon → 2 pools sur les mêmes paramètres.
2. Pattern exact de l'`AdminExtension` pour exposer `analyticsQueries()` (à confirmer en début de phase B).
3. Otoroshi a-t-il déjà un mécanisme de JSON Schema dynamique injecté dans `JsonObjectAsCodeInput` ? Sinon → patch léger.
4. Format de l'entité `Api` next-gen et son lien avec les routes via `Otoroshi-Api-Ref` (à confirmer en début de phase B).
5. La page Data Exporters existante : où ajouter le bouton "Set as active" sans casser l'UI (modal d'action, dropdown, ou ligne dédiée) ?

---

## Premier palier visible

Fin de **Phase A** → un exporter `user-analytics` est configurable depuis la page Data Exporters, écrit dans une table PG dénormalisée et indexée, le flag d'activation est lisible, et les events arrivent. Aucune feature visible côté utilisateur final mais l'infra est en place.
