---
slug: otoroshi-18-new-foundations
title: Otoroshi 18 - new foundations, same gateway
authors: [otoroshi-team]
tags: [otoroshi, release, scala3, play, pekko, migration]
---

Otoroshi `18.0.0` is the largest release we have shipped in years. The whole runtime moved to Scala 3, Play 3 and Apache Pekko, and Service Descriptors -- deprecated for four years -- are finally gone.

It is also, by design, meant to be one of the most boring upgrades you will ever do. Your routes, plugins, API keys, certificates and exporters are untouched. Your traffic does not care.

And once the new foundations were in, we started building on them: every caller of an API is now an API key, consumers can carry their own plugin chain, any plugin can run conditionally, and the whole gateway went through a security pass.

<!-- truncate -->

## The ship, and why it needed new planks

There is an old puzzle about a ship whose planks are replaced one by one until none of the originals remain: is it still the same ship? Philosophers have argued about it for two thousand years. The crew, meanwhile, never stopped sailing.

That is roughly what `18.0.0` is. Underneath, almost every foundational plank has been swapped. On deck, nothing moved.

The planks needed swapping for reasons that had been accumulating for a while:

- **Scala 2.12** first shipped in 2016. Staying on it meant an ever-shrinking set of libraries willing to publish for it, and no access to anything the language has learned since.
- **Akka** was relicensed under the BSL in September 2023. The Apache Software Foundation forked the last Apache-2.0 release as **Apache Pekko**, and the ecosystem followed. Otoroshi did not, yet.
- **Play 2.8** went out of support, and **Play 3.0** -- the Pekko-based line -- landed in late 2023. Issue [#1755](https://github.com/MAIF/otoroshi/issues/1755) was opened on November 7th, 2023 and stayed open for almost three years.
- We were shipping a **patched snapshot build of akka-stream** in the repository (`akka-stream_2.12-2.6.21+5-a72bf6ba-SNAPSHOT.jar`) to carry a TLS 1.3 handshake fix that upstream had not released.

Individually, each of those is survivable. Together they meant the foundation was quietly drifting away from the rest of the JVM ecosystem, and the gap was only going to widen.

So in July we tagged `scala2-freeze`, stopped adding features, and did the port.

## What actually changed

**Scala 2.12.16 → 3.8.4.** The entire backend, plus the test suite, ported in [PR #2595](https://github.com/MAIF/otoroshi/pull/2595).

**Play 2.8.19 → 3.0.11, Akka 2.6 → Apache Pekko 1.6.** Same actor model, same streams, same HTTP stack -- Pekko is a direct fork of the Akka we were already running. The package names changed, the semantics did not.

**The vendored third-party jars are gone.** The patched akka-stream snapshot was dropped: the TLS 1.3 fix it carried has been part of Pekko since 1.1.0, so we now run straight upstream Pekko with no local patches. `scala-schema` went with it, and so did our local build of `java-jq`, which we had been carrying for Apple Silicon support and which now comes from its upstream release.

**The admin UI build moved from webpack to Vite**, and jQuery left the frontend for good. The documentation toolchain was updated along the way. You will not see any of it, which is the point: faster builds and fewer ancient dependencies for the people working on Otoroshi.

**Service Descriptors have been removed.** They were deprecated in `v1.5.3` (February 2022) when the new proxy engine landed, `v17.0.0` shipped the migration tooling, and `v18.0.0` removes the entity, its admin API, its UI pages and its Kubernetes CRD. Routes are now the only way to configure HTTP proxying -- one entity, one mental model, one code path. This is the plank we had been carrying the longest, and by far the biggest thing this release takes away.

Six weeks from freeze to the first preview.

## Then we started building on deck

The rule for the port was strict: change nothing but the foundation. Once it had shipped as a preview and the test suites were green on the new stack, we lifted the rule, and the following previews added a few things on top.

**Every API consumer is an API key.** Until now, only `apikey` and `oauth2-local` plans of the [API](/docs/entities/apis) entity really knew who was calling. In `18.0.0`, every published plan turns its caller into an API key, whatever it presents: client credentials, a JWT, a client certificate, an OIDC token, or nothing at all -- a `keyless` plan identifies its callers with an expression, their IP address by default. Everything an API key carries then applies to the call. A public plan can rate limit its callers one by one, exactly like a plan asking for credentials, and a single `Api consumer enforcer` at the end of access validation is the only place where calls are counted. The details are in [API consumers and plugin flows](/docs/topics/api-consumers).

**Plugin chains per consumer.** A plan, or a single API key, can now bring its own plugins, applied on top of the chain of the route it calls -- or replacing it. Two consumers of the same route no longer have to go through the same pipeline: the gold plan can get a response cache, a partner key can get an extra header, a trial plan can run a stricter validator. Both chains are edited in a designer drawer, right from the plan or the API key.

**Conditional plugins.** The new [Conditional plugin](/docs/plugins/conditional-plugin) wraps any other plugin and runs it only when a set of JSONPath predicates matches -- on the API key, the authenticated user, the route metadata, the request, the response or the request attributes. "Only apply this rate limiter to the free tier" is now configuration, not code. JSONPath evaluation itself got a lot faster in the process, which also benefits the plugins that already relied on it.

**A security pass.** Admin API rights are checked more strictly for users and API keys scoped to a tenant or a team. SAML response validation was fixed and is now secure by default. An LDAP filter injection was closed. The token the admin UI hands to the admin API is now short-lived and signed with the Otoroshi secret. Random ids and digest comparisons were hardened, and the keypair verifying an API key JWT can be pinned so that a token cannot pick it through its `kid` header. We also wrote down everything we would check before putting a gateway in front of real traffic: the new [production checklist](/docs/topics/production-checklist) is worth ten minutes of your time, whether you install Otoroshi or upgrade it.

A few smaller things came along:

- WebAuthn works again, for the admin UI and for authentication modules.
- Let's Encrypt certificates can be renewed with a fixed margin in days, old copies can be deleted after renewal, and an endpoint collapses the duplicates an instance may have accumulated.
- Remote catalogs can sync from Bitbucket Server / Data Center, and from any self-hosted forge exposing a compatible API.
- User Analytics lets you choose which families of events are stored, and admin extensions can contribute their own queries and projections.
- Info tokens signed with a keypair now carry a `kid` header, so backends can pick the right key from a JWKS.
- The test suite grew along the way, with dedicated coverage for clustering, mTLS, WebAuthn, SAML validation and admin API rights.

## What this means for you

For the vast majority of installations, the answer is: run the new version.

**Your Service Descriptors migrate themselves.** On first startup, a job runs once per cluster, writes a backup of every descriptor to `./service-descriptors-backup.json`, then converts each one to a Route *keeping the same id*. A descriptor is only deleted once its Route has been written and read back successfully -- for any given id there is always either a Route or a Descriptor in the datastore, never neither. Anything that fails or conflicts is left alone, logged, and retried at the next startup, and keeps serving traffic in the meantime. API keys pointing at `service_<id>` still work, old exports still import, and the analytics endpoints are unchanged. The full story is in [Sunsetting Service Descriptors](/docs/topics/deprecating-sd).

A few things are worth checking before you upgrade:

| If you... | then... |
| --- | --- |
| tuned `akka.*` settings in your config | rename them to `pekko.*` (`akka.http.parsing.max-uri-length` → `pekko.http.parsing.max-uri-length`, and so on). The `OTOROSHI_AKKA_*` environment variables keep working, and now have `OTOROSHI_PEKKO_*` equivalents |
| depend on Otoroshi as a library | the Maven artifact moves from `fr.maif:otoroshi_2.12` to `fr.maif:otoroshi_3` |
| ship **custom Scala plugins** as JARs | they must be recompiled against Scala 3 and the new artifact. WASM plugins are unaffected |
| deploy on **Kubernetes** | apply the updated CRD manifests -- the `ServiceDescriptor` CRD is gone, and the `ingress.otoroshi.io/is-route=true` annotation is no longer read (the Ingress controller always creates Routes now) |
| publish **APIs** with `keyless`, `jwt`, `mtls` or `oauth2-remote` plans | their callers now become API keys, rate limited by the plan and persisted by default. On a public API with many distinct callers, set `create_if_missing` to `false` on the plan so the datastore does not get one API key per caller |
| use **SAML** authentication modules | responses are now really checked against the configured issuer, must carry exactly one assertion, and must be signed when signature validation is on. Existing modules keep their settings; new ones validate signatures and assertions by default |
| rely on the status code of `NgServiceQuotas`, `NgCustomQuotas` or `NgCustomThrottling` | they now answer `429 Too Many Requests` instead of `403` when a limit is reached |
| delegate admin rights to **tenant or team-scoped** users or API keys | a patch can no longer move an entity outside of the caller's scope, nobody can grant rights they do not hold, and writing the global config from a template requires superadmin rights |

One small UI removal: the `Use circuit breakers` global toggle in the Danger Zone is gone. It only ever existed to switch circuit breakers off for Service Descriptors; circuit breaking is now configured per Route, in the client settings of its backend.

If you still have `v17` descriptors living outside the datastore -- in Git, in a CI pipeline, in Kubernetes manifests -- `POST /api/routes/_from_service_descriptor` converts them to Routes without storing anything, one at a time or a whole array at once. `POST /api/new/resources` does the same for `ServiceDescriptor` manifests, including multi-document YAML.

## Still the same ship

Here is the part that matters, and the reason the paradox is a comfort rather than a warning: **the identity of Otoroshi was never in its build tooling.** It is in your routes, your plugins, your API keys, your TLS material, your exporters, your admin API calls, your GitOps pipelines. None of that changed. The proxy engine that handles your requests is the same engine, with the same plugin pipeline, on the same entities.

We did not use the port as an excuse to redesign anything. That was deliberate. A migration this size is dangerous exactly when it becomes a rewrite, so the rule for the port was: change the foundation, change nothing else. What is new in this release came afterwards, one preview at a time, on a foundation that had already proven it could carry the old weight. The only removal is the one we had announced four years in advance.

## What to expect next

The point of all this work is what comes after it. Being back on supported upstreams means security patches flow again without archaeology; it means the JVM libraries we depend on publish for us; and it means the fixes we had been holding until the port landed are merged and shipping -- OpenSearch support, ACME chain selection, WebSocket ordering and chunking fixes, Redis TLS material from config, Elastic exporters that actually report their failures, and more.

It also means we can start using Scala 3 properly rather than merely compiling with it. You will see that show up gradually, in internals first.

In the shorter term, the `17.x` line stays available for anyone who needs more time.

## Try it

`18.0.0` is out now, on [Docker Hub](https://hub.docker.com/r/maif/otoroshi) and in the [GitHub releases](https://github.com/MAIF/otoroshi/releases). The full milestone is [here](https://github.com/MAIF/otoroshi/milestone/141?closed=1).

If you run Otoroshi in production, this is a genuinely useful moment to test an upgrade on a copy of your datastore and tell us what you find -- especially the Service Descriptor migration, which rewrites your data, and the new consumer model if you publish APIs. Open an [issue](https://github.com/MAIF/otoroshi/issues) or come talk to us on [Discord](https://discord.gg/dmbwZrfpcQ).

The planks are new. It is still the same ship, and it never stopped sailing.
