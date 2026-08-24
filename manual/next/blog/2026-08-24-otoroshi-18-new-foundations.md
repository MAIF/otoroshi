---
slug: otoroshi-18-new-foundations
title: Otoroshi 18 - new foundations, same gateway
authors: [otoroshi-team]
tags: [otoroshi, release, scala3, play, pekko, migration]
---

Otoroshi `18.0.0` is the largest release we have shipped in years: 1,299 files changed, 37,009 lines added and 120,949 removed since `17.17.0`. The whole runtime moved to Scala 3, Play 3 and Apache Pekko, and Service Descriptors -- deprecated for four years -- are finally gone.

It is also, by design, meant to be one of the most boring upgrades you will ever do. Your routes, plugins, API keys, certificates and exporters are untouched. Your traffic does not care.

<!-- truncate -->

## The ship, and why it needed new planks

There is an old puzzle about a ship whose planks are replaced one by one until none of the originals remain: is it still the same ship? Philosophers have argued about it for two thousand years. The crew, meanwhile, never stopped sailing.

That is roughly what `18.0.0` is. Underneath, almost every foundational plank has been swapped. On deck, nothing moved.

The planks needed swapping for reasons that had been accumulating for a while:

- **Scala 2.12** first shipped in 2016. Staying on it meant an ever-shrinking set of libraries willing to publish for it, and no access to anything the language has learned since.
- **Akka** was relicensed under the BSL in September 2023. The Apache Software Foundation forked the last Apache-2.0 release as **Apache Pekko**, and the ecosystem followed. Otoroshi did not, yet.
- **Play 2.8** went out of support, and **Play 3.0** -- the Pekko-based line -- landed in late 2023. Issue [#1755](https://github.com/MAIF/otoroshi/issues/1755) was opened on November 7th, 2023 and stayed open for almost three years.
- We were shipping a **patched snapshot build of akka-stream** in the repository (`akka-stream_2.12-2.6.21+5-a72bf6ba-SNAPSHOT.jar`) to carry a TLS 1.3 handshake fix that upstream had not released.

Individually, each of those is survivable. Together they meant every security update, every dependency bump and every new feature had to be negotiated against a foundation that was quietly drifting away from the rest of the JVM ecosystem. Thirteen issues in this milestone carry a `waiting-for-scala3-port` label: real bugs and real feature requests that were parked because fixing them on the old foundation was not worth the effort.

So in July we tagged `scala2-freeze`, stopped adding features, and did the port.

## What actually changed

**Scala 2.12.16 → 3.8.4.** The entire ~180k line backend, plus the test suite. [PR #2595](https://github.com/MAIF/otoroshi/pull/2595) alone is 636 files and 100 commits.

**Play 2.8.19 → 3.0.11, Akka 2.6 → Apache Pekko 1.6.** Same actor model, same streams, same HTTP stack -- Pekko is a direct fork of the Akka we were already running. The package names changed, the semantics did not.

**The vendored jars are gone.** The patched akka-stream snapshot was dropped: the TLS 1.3 fix it carried has been part of Pekko since 1.1.0, so we now run straight upstream Pekko with no local patches. A second vendored jar (`scala-schema`) went with it.

**Service Descriptors have been removed.** They were deprecated in `v1.5.3` (February 2022) when the new proxy engine landed, `v17.0.0` shipped the migration tooling, and `v18.0.0` removes the entity, its admin API, its UI pages and its Kubernetes CRD. Routes are now the only way to configure HTTP proxying -- one entity, one mental model, one code path. This is the plank we had been carrying the longest, and removing it accounts for most of those 120,949 deleted lines.

Six weeks from freeze to `18.0.0-preview1`.

## What this means for you

For the vast majority of installations, the answer is: run the new version.

**Your Service Descriptors migrate themselves.** On first startup, a job runs once per cluster, writes a backup of every descriptor to `./service-descriptors-backup.json`, then converts each one to a Route *keeping the same id*. A descriptor is only deleted once its Route has been written and read back successfully -- for any given id there is always either a Route or a Descriptor in the datastore, never neither. Anything that fails or conflicts is left alone, logged, and retried at the next startup, and keeps serving traffic in the meantime. API keys pointing at `service_<id>` still work, old exports still import, and the analytics endpoints are unchanged. The full story is in [Sunsetting Service Descriptors](/docs/topics/deprecating-sd).

Four things are worth checking before you upgrade:

| If you... | then... |
| --- | --- |
| tuned `akka.*` settings in your config | rename them to `pekko.*` (`akka.http.parsing.max-uri-length` → `pekko.http.parsing.max-uri-length`, and so on) |
| depend on Otoroshi as a library | the Maven artifact moves from `fr.maif:otoroshi_2.12` to `fr.maif:otoroshi_3` |
| ship **custom Scala plugins** as JARs | they must be recompiled against Scala 3 and the new artifact. WASM plugins are unaffected |
| deploy on **Kubernetes** | apply the updated CRD manifests -- the `ServiceDescriptor` CRD is gone, and the `ingress.otoroshi.io/is-route=true` annotation is no longer read (the Ingress controller always creates Routes now) |

One small UI removal: the `Use circuit breakers` global toggle in the Danger Zone is gone. It only ever existed to switch circuit breakers off for Service Descriptors; circuit breaking is now configured per Route, in the client settings of its backend.

If you still have `v17` descriptors living outside the datastore -- in Git, in a CI pipeline, in Kubernetes manifests -- `POST /api/routes/_from_service_descriptor` converts them to Routes without storing anything, one at a time or a whole array at once. `POST /api/new/resources` does the same for `ServiceDescriptor` manifests, including multi-document YAML.

## Still the same ship

Here is the part that matters, and the reason the paradox is a comfort rather than a warning: **the identity of Otoroshi was never in its build tooling.** It is in your routes, your plugins, your API keys, your TLS material, your exporters, your admin API calls, your GitOps pipelines. None of that changed. The proxy engine that handles your requests is the same engine, with the same plugin pipeline, on the same entities.

We did not take the opportunity to redesign anything. That was deliberate. A migration this size is dangerous exactly when it becomes a rewrite, so the rule for the whole port was: change the foundation, change nothing else. The only user-visible removal is the one we had announced four years in advance.

## What to expect next

The point of all this work is what comes after it. Being back on supported upstreams means security patches flow again without archaeology; it means the JVM libraries we depend on publish for us; it means the thirteen issues that were parked behind the port are already fixed and shipping in the previews -- OpenSearch support, ACME chain selection, WebSocket ordering and chunking fixes, Redis TLS material from config, Elastic exporters that actually report their failures, and more.

It also means we can start using Scala 3 properly rather than merely compiling with it. You will see that show up gradually, in internals first.

In the shorter term, expect the `18.0.0` final release imminently, and a `17.x` line that stays available for anyone who needs more time.

## Try it

`18.0.0-preview2` is out now, on [Docker Hub](https://hub.docker.com/r/maif/otoroshi) and in the [GitHub releases](https://github.com/MAIF/otoroshi/releases). The full milestone is [here](https://github.com/MAIF/otoroshi/milestone/141?closed=1).

If you run Otoroshi in production, this is a genuinely useful moment to test an upgrade on a copy of your datastore and tell us what you find -- especially the Service Descriptor migration, which is the one part of this release that touches your data. Open an [issue](https://github.com/MAIF/otoroshi/issues) or come talk to us on [Discord](https://discord.gg/dmbwZrfpcQ).

The planks are new. It is still the same ship, and it never stopped sailing.
