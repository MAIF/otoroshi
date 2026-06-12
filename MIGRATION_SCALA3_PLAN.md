# Plan de migration Otoroshi → Scala 3 / Play 3 / Pekko

> Objectif : migrer le module `otoroshi/` de **Scala 2.12 / Play 2.8 / Akka** vers
> **Scala 3 / Play 3 / Apache Pekko**, de la façon **la plus minimaliste possible**.
> Aucun changement de code non strictement nécessaire à la migration. On reprend la
> *combinaison de versions* de la PR [MAIF/otoroshi#2218](https://github.com/MAIF/otoroshi/pull/2218)
> (branche `JulienSt:major-upgrade`) comme **référence de versions**, mais **pas** ses
> changements de code/refactors.

---

## 1. Contraintes (rappel)

- **Minimalisme** : on ne touche au code que pour le faire compiler/tourner en Scala 3 + Play 3 + Pekko. Pas de refactor opportuniste, pas de nettoyage, pas de changement de style.
- **Pas de syntaxe Scala 3 « python-like »** : on garde les accolades `{ }`, `import x._`, la syntaxe de contrôle classique. Migration syntaxique a minima.
- **Pas de version patchée d'Akka** : Pekko 1.6 upstream suffit (le fix TLS 1.3 handshake de MAIF/akka est intégré dans Pekko depuis 1.1.0). On supprime le jar patché de `lib/`.
- **Garder les libs qui marchent** : une lib Java pure marche en Scala 3 sans changement ; une lib Scala 2.13 **non-macro** peut être consommée via `.cross(CrossVersion.for3Use2_13)`. On ne réécrit le code **que** quand aucune option n'existe.

---

## 2. État des lieux (mesuré)

| Élément | Valeur |
|---|---|
| Fichiers Scala `app/` | 415 (~196 000 LOC) |
| Fichiers Scala `test/` | 196 |
| Fichiers `app/` référençant `akka` | 261 |
| Fichiers `test/` référençant `akka` | 54 |
| Lignes `import akka...` | 712 |
| Templates Twirl (`.scala.html`) | 21 |
| `conf/routes` | 696 lignes |
| Appels DI macwire `wire[...]` | 128 |
| JDK | 17 (mise.toml) |

### Comparatif de versions (actuel → cible, d'après la réf. Julien)

| Brique | Actuel | Cible |
|---|---|---|
| Scala | 2.12.16 | **3.8.3** (réf. Julien — décidé) |
| sbt | 1.7.2 | 1.12.5 |
| sbt-plugin | `com.typesafe.play` 2.8.19 | `org.playframework` 3.0.10 |
| play-json | `com.typesafe.play` 2.9.3 | `org.playframework` 3.0.6 |
| Acteurs/stream | Akka 2.6 (+ akka-http 10.2.10) | **Pekko 1.6.0** + Pekko HTTP 1.3.0 |
| Connectors | alpakka-s3 2.0.2 / akka-stream-kafka 2.0.7 | pekko-connectors-s3 1.3.0 / pekko-connectors-kafka 1.1.0 |
| Netty | 4.1.119 | 4.2.13 |
| Jackson | 2.13.4 | 2.21.3 |
| sbt-assembly | 0.14.6 | 2.3.1 |

(Les bumps secondaires — metrics, acme4j, bouncycastle, lettuce, vertx-pg, opentelemetry, tink, etc. — suivent la référence Julien.)

---

## 3. Cartographie des changements

### A. Build (mécanique, faible risque)
1. `project/build.properties` : `sbt.version=1.12.5`.
2. `project/plugins.sbt` : `org.playframework % sbt-plugin % 3.0.10`, + bumps (scalafmt 2.5.4, revolver 0.10.0, bloop 2.0.17, sonatype 3.12.2, sbt-pgp `com.github.sbt` 2.3.0, ci-release 1.11.0, **sbt-assembly 2.3.1** explicite). Supprimer `project/assembly.sbt` (ancien assembly 0.14.6) et `project/metals.sbt` (bloop auto-généré, à régénérer).
3. `build.sbt` :
   - `scalaVersion`, group rename Play, deps Pekko, play-json 3.0.6, `dependencyOverrides` (Pekko/Netty/Jackson/okhttp/okio/scram/slf4j/guava — repris de Julien), `assemblyMergeStrategy` adapté (`akka/stream`→`pekko/stream`, etc.).
   - `enablePlugins(PlayScala)` **sans** `PlayAkkaHttp2Support` (n'existe pas en Play 3 ; Otoroshi a sa propre pile Netty pour le data plane). `disablePlugins(PlayFilters)` conservé.
   - `scalacOptions` : `-feature`, `-language:...` conservés. **Pas** d'options activant la nouvelle syntaxe. Ajouter au besoin `-Wconf` ciblés (cf. quirk Twirl ci-dessous).
   - Hook Twirl (`import scala.language.adhocExtensions` retiré du code généré) — repris de Julien, nécessaire avec Twirl 2.x + Scala 3.8.
4. `lib/` :
   - **Supprimer** `akka-stream_2.12-...SNAPSHOT.jar` (plus de patch).
   - **Supprimer** `scala-schema-2.34.0_2.12.jar` (inutilisé — uniquement en commentaire dans `api.scala`).
   - **Garder** `java-jq-1.3.1-...jar` (Java pur, OK Scala 3).

### B. Rename mécanique `akka` → `pekko` (volumineux, faible risque unitaire)
- `akka.` → `org.apache.pekko.` dans `app/` + `test/` (~315 fichiers, 712 imports). Scriptable (sed) puis revue.
- Renommages non triviaux à traiter à part :
  - `akka.stream.alpakka.s3` → `org.apache.pekko.stream.connectors.s3` (5 fichiers : files, sources, cluster, persistence, OtoroshiEventsActor).
  - `akka.kafka` → `org.apache.pekko.kafka` (2 fichiers : tryit, kafka).
- `conf/application.conf` + `conf/base.conf` : blocs `akka { … }` → `pekko { … }`, `${akka.version}` → `${pekko.version}`, `${akka.http.parsing.*}` → `${pekko.http.parsing.*}`. (Vérifier aussi `conf/old/` si encore chargé — sinon ignorer.)
  - ⚠️ La clé otoroshi-spécifique `enforce-akka` (netty client) n'est PAS une clé Akka : à laisser telle quelle (ou renommer côté code+conf de façon cohérente — décision mineure).

### C. Réécritures forcées par les libs (cœur du travail « intelligent »)
| Sujet | Fichiers | Pourquoi | Approche minimale |
|---|---|---|---|
| **tcp patch** | `app/tcp/tcputils.scala`, `app/tcp/tcp.scala` (`package akka`) | utilise les internes akka-stream + ancien jar patché | `package org.apache.pekko`, s'appuyer sur `Tcp().bindWithTls(createSSLEngine, verifySession, …)` de Pekko upstream (API présente). Retirer toute dépendance au patch. |
| **kaleidoscope** | 8 fichiers (`el.scala`, `workflow.scala`, `script.scala`, `body.scala`, `discovery.scala` ×2, `eureka.scala`, `response.scala`) | interpolateur `r"…$x@(.*)"` = **macro Scala 2.12**, non consommable depuis Scala 3 (`for3Use2_13` impossible pour les macros) | Réécrire chaque `case r"…"` en regex stdlib (`val R = "…".r ; case R(x) => …`). Mécanique mais à faire à la main (captures nommées → groupes positionnels). |
| **pulsar4s** | `app/events/pulsar.scala`, `app/statefulclients/pulsar.scala` (+ `OtoroshiEventsActor`) | `pulsar4s-akka-streams` n'a pas de build Pekko/Scala 3 | 1er choix : vérifier `pulsar4s-pekko-streams` (clever-cloud). Sinon réécrire producteur/consumer sur `pulsar-client` brut (comme Julien). |
| **swagger (Scala)** | `app/api/api.scala`, `app/script/script.scala` | `SwaggerScalaModelConverter` (swagger-scala-module) est Scala | Garder `swagger-core-jakarta` (Java, OK). Pour le converter Scala : vérifier un build Scala 3 ; sinon enregistrer seulement le converter Java (perte mineure d'introspection case class) ou `for3Use2_13` si non-macro. |

> Note : `commons-lang` 2.6 (StringUtils/StringEscapeUtils, 2 fichiers) et `swagger-core-jakarta` sont **Java** → on les **garde** (Julien les avait remplacés inutilement). `censorinus` → `.cross(CrossVersion.for3Use2_13)`. `rediscala` etaty 1.9.0 → fork `io.github.rediscala` 2.0.2 (API `redis.*` compatible, 8 fichiers, drop-in à vérifier).

### D. play-json 3.0 & Play 3.0 — API (risque moyen, découvert à la compilation)
- play-json : le **package reste** `play.api.libs.json` (347 fichiers, pas de rename d'import). Seuls quelques points d'API peuvent casser (implicits Joda déplacés vers `play-json-joda`, certains `Reads`/`Writes`/`JsLookup`). À corriger au fil de la compilation.
- Play : `WSClient`, `play.api.mvc.*`, body parsers, filters — globalement stables. Points à surveiller : helpers `play.api.libs.concurrent` (Akka→Pekko), `ApplicationLoader`/`BuiltInComponentsFromContext` (DI macwire ×128), compilation routes + Twirl.

### E. Correctifs de compilation Scala 3 (itératif, dispersé)
À traiter en bouclant `compile` jusqu'au vert. Sans changer le style (accolades conservées). Attendus : littéraux Symbol (`'foo`) supprimés, résolution d'implicites plus stricte, eta-expansion/SAM, inférence/variance plus strictes, `@nowarn`. `scala.collection.JavaConverters` (14 fichiers) reste toléré (déprécié, compile) — **on ne le change pas** (minimalisme).

### F. Tests (après que `app/` compile et boote)
- `test/` (196 fichiers) : même rename akka→pekko, scalatestplus-play 7.0.2, testcontainers-scala 0.44.1, playwright 1.59.0. Correctifs Scala 3 analogues.

---

## 4. Séquencement proposé

0. **Baseline** : nouvelle branche `scala3-migration`. Confirmer build actuel OK (compile) sous JDK 17 comme point de référence.
1. **Build & plugins** (A) : passer build.sbt/plugins/properties à la cible ; nettoyer `lib/`. À ce stade ça ne compile pas — normal.
2. **Rename mécanique** (B) : akka→pekko (app+test+conf), alpakka, kafka.
3. **Réécritures forcées** (C) : tcp, kaleidoscope, pulsar4s, swagger. C'est le gros morceau « manuel ».
4. **Boucle de compilation `app/`** (D+E) : `sbt compile` en boucle, corriger play-json/Play/Scala 3 jusqu'au vert.
5. **Boot & smoke test** : `sbt ~reStart` (config dev déjà dans build.sbt), vérifier démarrage + UI + un appel proxy + endpoints admin.
6. **Tests** (F) : faire compiler `test/`, puis lancer les suites ciblées (`OtoroshiTests`, `functional.PluginsTestSpec`).
7. **Assembly** : `sbt assembly` (merge strategy), vérifier le jar boote.

Chaque phase = commits séparés et atomiques pour faciliter la revue (rename mécanique isolé des changements « intelligents »).

---

## 5. Risques principaux
- **macwire 2.6.7 / Scala 3** (128 `wire[]`) : le portage macro Scala 3 existe mais peut buter sur des cas tordus → risque moyen, à valider tôt (phase 4).
- **kaleidoscope** : volume de `case r"…"` à retranscrire sans régression de comportement (regex). Tests utiles.
- **pulsar4s** : si pas de build Pekko, réécriture non triviale du flux events/stateful clients.
- **Pile Netty maison** (`app/netty`, ~3900 LOC) + HTTP/3 : dépend de Netty 4.2 (API parfois changée vs 4.1) → à compiler tôt.
- **Twirl 2.x + Scala 3.8** : quirk `adhocExtensions` (hook prévu) ; surveiller d'autres incompatibilités de templates.
- **diffson-play-json 4.7** sur play-json 3 (2 fichiers).

---

## 6. Décisions (actées le 12 juin 2026)
1. **Version de Scala 3** : **3.8.3** (la version validée par la réf. Julien → moins d'inconnues sur la compat des libs).
2. **Périmètre du « terminé »** : **boote en dev + UI/proxy OK** (`app/` compile, `sbt ~reStart` démarre, back-office accessible, une route proxy fonctionne). Les tests et l'`assembly` sont des **étapes ultérieures hors du périmètre initial**.
3. **pulsar4s** : **essayer d'abord un `pulsar4s-pekko-streams`** (clever-cloud) pour minimiser le code ; **repli** sur réécriture directe `pulsar-client` seulement si aucun build Pekko n'existe.
4. **Stratégie git** : branche dédiée + **commits par phase** (rename mécanique isolé des changements « intelligents ») pour faciliter la revue.

---

## 7. Critères de validation (périmètre décidé : « boote en dev »)
- [ ] `sbt compile` vert (app/)
- [ ] `sbt ~reStart` : démarrage, login back-office, 1 route proxy fonctionnelle
- [ ] Diff = uniquement des changements liés à la migration (revue)

> Étapes ultérieures (hors périmètre initial, à planifier ensuite) :
> - [ ] `sbt test:compile` vert + suites `OtoroshiTests` / `functional.PluginsTestSpec`
> - [ ] `sbt assembly` → `otoroshi.jar` qui boote
