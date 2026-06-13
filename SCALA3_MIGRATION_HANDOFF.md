# Otoroshi → Scala 3 / Play 3 / Pekko — Migration Handoff

Status: **`app/` and `test/` both compile on Scala 3; the app boots in dev and proxies traffic end‑to‑end.**
Branch: `scala3-play3-pekko-migration`. JDK 17.

This document lists everything that was done, the **points of attention / behavior changes** to review, what to **test**, and what **remains**.

---

## 1. Result summary

| Item | Before | After |
|---|---|---|
| Scala | 2.12.16 | **3.8.3** |
| Play | `com.typesafe.play` 2.8.19 | `org.playframework` **3.0.10** |
| Actors / HTTP | Akka 2.6 / akka-http 10.2.10 (+ patched akka-stream jar) | **Pekko 1.6.0 / pekko-http 1.3.0** (no patched jar) |
| play-json | 2.9.3 | **3.0.6** (package `play.api.libs.json` unchanged) |
| sbt | 1.7.2 | 1.12.5 |
| `sbt compile` (app/) | — | **0 errors** (was 1874 mid‑migration) |
| `sbt Test/compile` (test/) | — | **0 errors** (was 2581 mid‑migration) |
| Boot (`sbt ~reStart`) | — | **OK** — PekkoHttpServer on :9999/:9998 + experimental Netty server (HTTP/1.1/2/3) |

Smoke test performed: back‑office login page renders, admin API works with auth (200 + JSON), route creation (201), **end‑to‑end proxy of a real upstream (200)**, unknown route → otoroshi 404. Tests were **not executed** (you will run them).

---

## 2. How it was kept minimal (decisions you may want to know)

- **No more patched akka‑stream jar.** Pekko 1.6 upstream has the TLS‑1.3 handshake fix; `otoroshi/lib/akka-stream_2.12-*.jar` was removed. `otoroshi/lib/scala-schema-*.jar` was removed too (it was unused, only referenced in a comment). `java-jq-*.jar` kept (Java).
- **Pure‑Java libraries were kept at their current versions** (Netty 4.1.x + reactor-netty 1.1.x, kubernetes-client 16.0.1, lettuce, vertx-pg 4.5.22, bouncycastle, opentelemetry, geoip, jsoup, json-path, …) to avoid unrelated code changes. Only libraries that *need* a Scala 3 build were bumped (Pekko, play-json, macwire 2.6.7, rediscala fork 2.0.2, scaffeine 5.3.0, sangria 4.2.18, diffson 4.7.0, wasm4s 5.0.3, pulsar4s‑pekko‑streams 2.10.0, jackson 2.21.3, scalatestplus-play 7.0.2, testcontainers 0.44.1).
- **`-source:3.0-migration`** was added to `scalacOptions`. This makes the compiler accept Scala‑2.13 syntax (e.g. un‑parenthesized typed lambda params) as **warnings** instead of rewriting hundreds of call sites. Consequence: ~250 migration warnings in `app/`, ~70 in `test/`. It is **not "pure" Scala 3**.
- **No Scala 3 "python‑like" (significant‑indentation) syntax** was introduced; braces and `import x._` kept.

---

## 3. ⚠️ Points of attention (review these — possible behavior changes)

> These are the spots where an external API forced a real change, or where a stub/heuristic was used. They compile, but verify behavior.

1. **SSL ciphers / protocols — `app/ssl/dyn.scala`** ⚠️ **(security‑relevant)**
   The `ssl-config` library pulled by Pekko **removed** `Ciphers` and `SSLLooseConfig.allowWeakProtocols/allowWeakCiphers`. Adaptation made:
   - `allowWeakProtocols` / `allowWeakCiphers` are now hard‑coded `false` (secure default).
   - When **no** ciphers are configured, it now falls back to the **JVM‑supported ciphers** instead of `ssl-config`'s "recommended" list.
   - The deprecated‑cipher rejection loop is now a no‑op (no list available).
   → Net effect: the cipher/protocol *filtering* is looser/different than before. Review if you rely on otoroshi rejecting weak ciphers from a configured list. (Protocols still use `Protocols.recommendedProtocols` + the deprecated‑protocol check, which survived.)

2. **rediscala fork (`io.github.rediscala` 2.0.2) — `SentinelMonitoredRedisClient`** ⚠️
   The fork added a `username: Option[String]` param (Redis 6 ACL) between `password` and `db`. I passed `None` (= previous behavior, default user). If you use **Redis Sentinel with an ACL username**, wire it from config. Other rediscala constructors needed `(using actorSystem)` (Scala 3 explicit‑implicit syntax) — `RedisClientPool`, `RedisClientMasterSlaves`.

3. **EL engine + kaleidoscope shim — `app/utils/KaleidoscopeShim.scala`** ⚠️ **(test this)**
   `kaleidoscope` (macro, Scala 2.12 only) was replaced by a small non‑macro shim that re‑implements the old `r"...$name@(regex)"` pattern interpolator with `String` captures. The **Expression Language** (`app/el/el.scala`, ~106 patterns) and a few plugins use it. **The EL engine has thin test coverage** → functionally test expressions like `${date(...)}`, `${req.headers.X}`, `${req.pathparams...}`, `${env...}`, `${config...}`, etc.

4. **Open‑charset JSON media type hack — `app/utils/akka.scala`** **⚠️ IMPORTANT** check if there is a better way no in pekko http (I remember there is)
   This reflectively patches Pekko‑HTTP's internal media‑type registry. The mangled field name was updated to the Pekko package (`org$apache$pekko$http$impl$util$ObjectRegistry$$_registry`). It is **fragile** and only runs when `otoroshi.options.enable-json-media-type-with-open-charset=true` (which **is set in the dev `reStart` options**). If the internal field name is wrong, it throws at startup with that option on. Verify, or consider replacing with the proper Pekko `ParserSettings` API later.

5. **TCP / TLS termination + SNI — `app/tcp/tcp.scala`, `app/tcp/tcputils.scala`** ⚠️ **(test this)**
   Moved from `package akka` to `package org.apache.pekko`, now using upstream Pekko `Tcp().bindWithTls(...)`. The manual SNI extraction / BiDi‑TLS path (`bindTlsWithSSLEngineAndSNI`) compiles but should be **functionally tested** (TCP services with TLS, SNI routing, mTLS).

6. **`swagger-scala-module` dropped** (no Scala 3 build, pulled Akka). **⚠️ IMPORTANT**
   - `Env.scala`: the `SwaggerScalaModelConverter` registration was removed → admin‑API JSON schemas are **less precise** (a generic fallback already existed in `api.scala`).
   - `TemplatesController.templateSpec` used `scala.reflect.runtime.universe` (no runtime universe in Scala 3) → **rewritten with Java reflection**. The dotted event‑field list it returns may differ slightly from before. Verify the "event type fields" endpoint if you use it.

7. **GraphQL plugin (sangria 3 → 4)** ⚠️ **(test this)** — `app/next/plugins/graphql.scala`, `app/next/models/graphql.scala`
   sangria 4 API changes were applied: `InterfaceTypeDefinition(... interfaces = Vector.empty)`, `QueryValidator.validateQuery(schema, queryAst, Map.empty, None)`, the dynamic resolve returns a `JsValue` consistently, `c.arg(urlArg)` was un‑tagged (`: String`). Functionally test the GraphQL plugin.

8. **Custom WS clients — no‑op stubs** — `app/netty/h1h2client.scala`, `app/netty/h3client.scala`, `app/utils/httpclient.scala`
   Play 3's `WSRequest` adds `withDisableUrlEncoding(Boolean)` and `addCookies(WSCookie*)`. On the custom Netty H1/H2/H3 clients and `AkkaWsClientRequest` these are implemented as **no‑ops returning `this`**. Likely unused, but if any caller relies on them on these specific clients, they do nothing now.

9. **`MutableList` → `ListBuffer`** (`scala.collection.mutable.MutableList` was removed in 2.13) — `app/next/models/treerouter.scala`, `app/cluster/cluster.scala`, etc. API‑compatible; verify the tree router / cluster paths.

10. **Pulsar exporter** — swapped to `pulsar4s-pekko-streams`. Verify the Pulsar event exporter.

11. **prometheus `CustomCollector.collect()`** — `app/utils/CustomCollector.scala` now declares `override def collect()` (parens) to match the Java method; verify `/metrics` (prometheus format) export.

12. **Pervasive collection conversions.** play-json 3 makes `JsArray.value` a `collection.IndexedSeq`, and Scala 2.13 made `Seq` = `immutable.Seq`; many `.toSeq` / `.toMap` were added across the codebase. They compile (so types are right), but they are numerous — keep an eye out for any semantic surprise in hot paths.

13. **vertx `Pool.close()`** — `app/next/analytics/exporter/AnalyticsRetentionJob.scala` now calls `close((_: AsyncResult[Void]) => ())` (callback form) because the no‑arg overload was ambiguous in Scala 3.

14. **Tests: `.par` removed** — 1–2 test loops (`BasicSpec`) are now sequential (parallel collections are a separate module in 2.13). Functionally equivalent, possibly slower.

15. **Tests: plugin stub members** — test plugin classes (`Transformer1/2/3`, `Validator1` in `Version1413Spec`) had to implement the now‑strictly‑abstract `categories`/`steps`/`visibility` with `Seq.empty` / `NgInternal` stubs.

16. **Tests: `\`Content-Type\`` header** — `ResponseBodyXmlToJsonTests` builds the header via `RawHeader("Content-Type", "text/xml; charset=UTF-8")` (the pekko‑http modeled constructor is package‑private and its companion `apply` no longer resolves in Scala 3). Functionally equivalent for the test.

---

## 4. What to test (functional areas most affected)

- [ ] **Boot** in a prod‑like config (dev boot already verified).
- [ ] **Admin API** CRUD on the main entities (routes, apikeys, services, certs, auth modules…).
- [ ] **Proxy data plane**: routing, headers, body streaming, gzip/brotli, chunked/streamed responses.
- [ ] **TLS termination + SNI + mTLS** (and TCP services) — see #5.
- [ ] **HTTP/3** experimental Netty server.
- [ ] **Auth modules**: LDAP, OAuth1/2, OIDC, basic, SAML (lots of JsArray/collection code was touched).
- [ ] **Expression Language** — see #3 (kaleidoscope shim).
- [ ] **GraphQL plugin** — see #7.
- [ ] **Event exporters**: Pulsar (#10), Kafka, file/S3, custom.
- [ ] **Kubernetes plugin** (CRDs sync, ingress) — k8s client kept at 16.0.1.
- [ ] **WASM plugins** (wasm4s 5.0.3 — major bump) + Coraza WAF.
- [ ] **Prometheus / metrics** export — see #11.
- [ ] **Redis Sentinel** (ACL username) — see #2.
- [ ] **SSL cipher/protocol config** — see #1.
- [ ] Open‑charset JSON media type if enabled — see #4.
- [ ] **Run the test suites** (`OtoroshiTests`, `functional.PluginsTestSpec`, etc.).

---

## 5. What remains to do

- [ ] **Run the test suites** (compile is green; execution not done).
- [ ] **`sbt assembly`** → `otoroshi.jar` was **not** attempted. The `assemblyMergeStrategy` was only minimally adapted (`akka/stream` → `pekko/stream`); new dependency conflicts may need extra merge rules.
- [ ] **(Optional) Clean migration warnings**: drop `-source:3.0-migration` and fix the Scala‑2 syntax properly (mostly lambda parens) to get "pure" Scala 3. ~250 warnings in app, ~70 in test.
- [ ] rewrite doc about JDK17, versions, etc
- [ ] upgrade remaining libraries to the most recent version
---

## 6. Reference

- Build files: `otoroshi/build.sbt`, `otoroshi/project/{plugins.sbt,build.properties}`.
- Original plan: `MIGRATION_SCALA3_PLAN.md` (repo root).
- Dev run: `cd otoroshi && sbt ~reStart` (port 9999/9998; uses the experimental Netty server; admin password `password`, domain `oto.tools`, file storage — see `reStart / javaOptions` in `build.sbt`).
- akka→pekko rename rules applied to code: `akka.` → `org.apache.pekko.`, alpakka → `org.apache.pekko.stream.connectors`, akka.kafka → `org.apache.pekko.kafka`; config `akka { }` → `pekko { }`, `${akka.*}` → `${pekko.*}`, `play.server.akka` → `play.server.pekko`. Otoroshi's own `*Akka*` identifiers, `enforce-akka` config key, `OTOROSHI_AKKA_*` env vars and the `Otoroshi-akka` user‑agent string were **intentionally left unchanged**.

## 7. Local publish to test ecosystem

```sh
sbt ';set Compile / packageDoc / publishArtifact := false ;publishLocal;publishM2'
```