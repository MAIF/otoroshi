---
title: Production checklist
sidebar_position: 8
---

# Production checklist

Otoroshi sits on the edge of your infrastructure and hands you sharp tools: it can trust any TLS
certificate, believe every header a client sends, or hand full administrative power to a key that
declares no rights at all. Every one of those behaviours exists for a reason, and every one of them
is a decision you own.

A few of Otoroshi's defaults favour compatibility over strictness, so that an upgrade never breaks a
running platform. That is a deliberate trade: it means a freshly installed Otoroshi and an Otoroshi
upgraded from an older version may not be configured the same way, even though they run the same
code. This page lists the settings where that matters.

Nothing here is a bug report. It is the list of things nobody else can decide for you.

## The short version

- [ ] `otoroshi.secret` is not the shipped default, and neither are the admin API credentials
- [ ] Every admin API key carries an explicit `otoroshi-access-rights` metadata
- [ ] `otoroshi.bypassUserRightsCheck` is off
- [ ] `otoroshi.ssl.trust.all` is off
- [ ] `strictBackendServerValidation` is enabled in your global config, not just assumed
- [ ] `trustXForwarded` matches your topology: on only if a trusted proxy rewrites those headers
- [ ] Your reverse proxies are declared as trusted proxies, including the last hop in front of Otoroshi,
      and the `trusted proxies:` line of the logs says so
- [ ] The client address header is one every proxy in front of Otoroshi builds or overwrites
- [ ] `useLegacyClientIpAddress` is off
- [ ] No route runs the apikey plugin with both *validate* and *mandatory* turned off
- [ ] API keys authenticating with keypair-signed JWTs pin their keypair
- [ ] Your event exporters are treated as secret material

## Identity and administrative access

### Default secrets are a warning, not a failure

Otoroshi logs a warning at startup when `otoroshi.secret` still holds its shipped value
(`verysecretvaluethatyoumustoverwrite`), or when the admin API key still uses the default
`admin-api-apikey-id` / `admin-api-apikey-secret`. It logs a warning — it does not refuse to start,
because refusing to start would break every evaluation setup and every CI pipeline.

`otoroshi.secret` is not only used for session cookies. It signs the token that carries the identity
of a backoffice user to the admin API. Leaving it at its default means the value protecting your
administrative identity is published in this documentation and in the source tree.

```sh
export OTOROSHI_SECRET="$(openssl rand -base64 48)"
export OTOROSHI_ADMIN_API_CLIENT_ID="$(openssl rand -hex 16)"
export OTOROSHI_ADMIN_API_CLIENT_SECRET="$(openssl rand -base64 48)"
```

:::warning Cluster workers skip the warning
The startup warning is only printed by leaders. A worker with a default secret says nothing at all.
Check the value, do not rely on reading the logs of one node.
:::

### An admin API key with no rights metadata is a superadmin

Rights for an API key calling the admin API are read from its `otoroshi-access-rights` metadata. When
that metadata is **absent**, the key is treated as unrestricted — not as having no rights.

This is backward compatibility: admin API keys predate the tenant and team model, and existing keys
had to keep working. The consequence is that creating an admin API key and forgetting to describe its
rights grants it everything, silently.

```json
{
  "clientId": "...",
  "metadata": {
    "otoroshi-access-rights": "[{\"tenant\":\"my-tenant:rw\",\"teams\":[\"my-team:rw\"]}]"
  }
}
```

Audit your existing keys: any key authorized on the admin API group without that metadata is a
superadmin, whatever its name suggests.

### `otoroshi.bypassUserRightsCheck` disables RBAC entirely

This flag short-circuits every rights check in the admin API — tenants, teams, superadmin, all of it.
It exists for local development and for recovering from a configuration that locked you out.

It has no legitimate use in production. Its default is `false`; make sure nothing in your deployment
turns it on.

### API key secrets are stored and returned in clear text

`clientSecret` is not hashed. It is stored as-is, returned in full by the admin API on read and list,
and included in the audit events emitted for API key operations — which means it reaches every
configured event exporter: Elasticsearch, Kafka, webhooks, files.

This is inherent to what an API key is: unlike a password, Otoroshi has to present the secret to
compare it, and operators legitimately need to read it back to hand it to a consumer. There is no fix
to apply here, only a consequence to accept:

- Treat your analytics and audit pipeline as a secret store. Anyone who can read your Elasticsearch
  index can authenticate as any API key created while that exporter was running.
- Restrict who can read API keys through tenant and team rights, not only who can write them.
- Prefer [secrets management](./secrets.md) references for the secrets *you* inject into Otoroshi;
  it does not apply to API key secrets Otoroshi generates.

## TLS

### Backend certificate validation on upgraded installations

Otoroshi can validate the certificate presented by your backends, or accept anything. The switch is
`strictBackendServerValidation` in the global config's TLS settings.

A **fresh install** defaults to `true`. But when the field is missing from a persisted configuration —
which is the case for every installation upgraded from a version that predates it — reading that
configuration yields `false`. Same code, same version, two different postures depending on your
history.

The permissive trust manager accepts self-signed certificates and hostname mismatches on outgoing
connections. Worse, Otoroshi installs its SSL context as the JVM default, so the same permissiveness
extends to anything else running in that JVM, including LDAPS connections made by your authentication
modules.

Check the effective value rather than assuming it:

```sh
curl -u "$CLIENT_ID:$CLIENT_SECRET" \
  https://otoroshi-api.your.domain/api/globalconfig | jq '.tlsSettings.strictBackendServerValidation'
```

:::note mTLS on incoming connections
The same trust manager serves client certificate validation. Note that the built-in
`NgHasClientCertValidator` plugin only checks that a client certificate is *present*. If you need the
certificate to be issued by a specific authority, or to match a specific API key, use the dedicated
validators instead.
:::

### `otoroshi.ssl.trust.all` disables TLS validation completely

When this flag is on, Otoroshi trusts every certificate, everywhere, unconditionally. It is meant for
local development against self-signed backends.

Treat it as development-only. There is no production scenario where it is the right answer; if you
need to trust a private authority, add that authority to Otoroshi's certificate store instead.

## Network trust

### `X-Forwarded-*` headers are trusted by default

`trustXForwarded` defaults to `true`. When no trusted proxy is declared, the client IP address is then
read from the first value of the client address header, `X-Forwarded-For` by default.

That default assumes Otoroshi runs behind a load balancer that **overwrites** those headers. If a
client can reach Otoroshi directly, it chooses its own identity, which means it can:

- pick an arbitrary source IP, bypassing per-IP throttling and IP allow/block lists
- claim `X-Forwarded-Proto: https` and satisfy the "force HTTPS" plugin over plain HTTP
- set `X-Forwarded-Host` and influence domain-based routing

Three valid configurations, and you must pick the one matching your topology:

- **Behind known reverse proxies**: keep `trustXForwarded` on and declare them as trusted proxies
  (see below). This is the only configuration where a proxy chain is read without letting the client
  choose its own address.
- **Behind a proxy that overwrites the headers**: keep `trustXForwarded` on, and pick the client
  address header it overwrites (see below).
- **Directly exposed**: turn it off. The client address is then the connection address: no forwarded
  header is used to resolve it, whatever the trusted proxies say. The
  `${req.ip_from_xff}` expression remains available: it returns the first non-empty normalised
  `X-Forwarded-For` value, or the connection address when none is available.

There is no way for Otoroshi to tell those topologies apart on its own. This is the setting on this
page most likely to be wrong without anyone noticing, because nothing misbehaves until someone tries.

The IP address rewriting of Play itself is disabled (`play.http.forwarded.trustedProxies = []`):
Otoroshi resolves the client address on its own, the same way on every HTTP server it can run.

### Declare your reverse proxies as trusted proxies

A trusted proxy is a reverse proxy allowed to tell Otoroshi who the client is, through the client
address header described in the next section. When the connection comes from one of them, Otoroshi
walks the proxy chain of that header from the closest hop to the farthest and stops at the first
address that is not a trusted proxy. If every address of the chain is a trusted proxy, the leftmost
one is used; if the chain is empty, the connection address is used. When the connection does not
come from a trusted proxy, the headers are ignored and the connection address is used. A hop that is
not an address, like the `unknown` or obfuscated identifiers of `Forwarded`, also stops the walk and
the connection address is used: going further would reach what the client wrote.

The list accepts IP addresses, CIDR ranges and wildcard patterns. It is the combination of:

- the startup list, read once at startup, so changing it requires a restart. It is made of
  `otoroshi.options.trustedProxies`, set by `OTOROSHI_OPTIONS_TRUSTED_PROXIES`, and of every entry of
  `otoroshi.options.trustedProxiesSources`, which ships with `CC_REVERSE_PROXY_IPS` and
  `OTOROSHI_TRUSTED_PROXIES`. Each source is a comma separated list
- `trustedProxies` in the global config, editable at runtime from the danger zone. An entry can hold
  a comma separated list, so it can be a vault reference to an env var publishing the proxies of a
  platform, like `${vault://env/CC_REVERSE_PROXY_IPS}`

Every source **adds** its entries to the others, and duplicates are dropped: declaring a proxy of your
own never hides the ones a platform publishes. On Clever Cloud, setting
`OTOROSHI_OPTIONS_TRUSTED_PROXIES` for a CDN in front of the platform keeps the proxies of
`CC_REVERSE_PROXY_IPS` in the list. An empty or undefined source contributes nothing. Another
variable can be declared as a source from your own configuration file:

```
otoroshi.options.trustedProxiesSources.MY_CDN_IPS = ${?MY_CDN_IPS}
```

As soon as the list is not empty, the loopback (`127.0.0.1` and `::1`) is trusted too, as Play did by
default: a last hop running on the same host as Otoroshi does not have to be declared. IPv6 entries
are compared as addresses, not as text, so any spelling of the same address matches, and a wildcard
matches both the compressed and the full spelling of an address. The IP allow and block lists, the IP
filtering of the global config, the endless responses and the fail2ban rules, when the fail2ban
identifier is the client address, match addresses the same way.

The list only applies while `trustXForwarded` is on, which stays the master switch. As soon as the
list is not empty, the client address header is never trusted blindly anymore.

:::warning Declare every hop with the address the next one sees
Each proxy must be declared with the address the **next** hop sees, not with the one it receives
requests on: the `by=` parameter of a `Forwarded` element is not what belongs in the list. The
address that matters most is the one of the peer actually connected to Otoroshi: when it is not
trusted, the headers are ignored and every client resolves to that peer, sharing the same per-IP
quotas, bans and allow lists. On Clever Cloud, `CC_REVERSE_PROXY_IPS` is filled by the platform,
which means upgrading Otoroshi turns the trusted proxy resolution on by itself.
:::

The `${req.ip_safe}`, `${req.ip_from_trusted_proxy}`, `${req.ip_from_xff}` and
`${req.ip_from_socket}` [expressions](./expression-language.mdx) expose each resolution separately.

### Read the client address from the header your proxies build

Otoroshi reads the client address from a single header, the client address header, `X-Forwarded-For`
by default. It must be one that **every** proxy in front of Otoroshi builds or overwrites: a header a
proxy lets through untouched is written by the client. A proxy that only appends to
`X-Forwarded-For` passes a `Forwarded` header sent by the client along, which is why Otoroshi never
falls back to another header.

It is set by `clientAddressHeader` in the global config, from the danger zone, or by
`OTOROSHI_OPTIONS_CLIENT_ADDRESS_HEADER`, which wins when it is set. It accepts:

- `X-Forwarded-For`, built by nginx (`$proxy_add_x_forwarded_for`), HAProxy (`option forwardfor`),
  AWS ALB, Traefik or Envoy
- `Forwarded`, the RFC 7239 header, built by HAProxy with `option forwarded` for instance. Sozu, on
  Clever Cloud, sends both
- any header carrying the client address, like `X-Real-IP` (nginx) or `CF-Connecting-IP`
  (Cloudflare), read as a single address or as a comma separated list

The protocol and the host of the original request come from the same family of headers: from the
element of the client in `Forwarded` when it is chosen, from `X-Forwarded-Proto` and
`X-Forwarded-Host` otherwise. As soon as trusted proxies are declared, they are only read when the
connection comes from one of them: a client reaching Otoroshi directly can no longer claim
`X-Forwarded-Proto: https` or pick the host it is routed with. The `Forwarded header` and
`X-Forwarded-* headers` plugins forward the chain of the client address header to the backend, and
only when Otoroshi trusts it itself.

### Check the trusted proxies in use

Otoroshi logs once, at startup, how it resolves the client address. The line gives the number of
entries of each startup source, of the global config and of the list in use, loopback included, then
what `trustXForwarded`, `useLegacyClientIpAddress` and the client address header make of them:

```
trusted proxies: 12 entries at startup (CC_REVERSE_PROXY_IPS: 12), 0 in the global config, 14 in use including the loopback. trustXForwarded is enabled, the client address is read from X-Forwarded-For when the connection comes from a trusted proxy
```

The entries themselves are only logged at debug level on the `otoroshi-env` logger
(`OTOROSHI_LOGGERS_OTOROSHI_ENV=DEBUG`), as a platform can publish several hundred proxies. Every node
logs its own line: the startup list of a worker comes from the environment of that worker. The later
changes of the global config are not logged there, they are traced by its audit events.

### Block lists can look at the whole proxy chain

The IP block list plugin (`match_forwarded_chain`) and the IP blocklist of the global config
(`ipFiltering.blacklistMatchesForwardedChain`) can also refuse a request when a blocked address
appears anywhere in the chain of the client address header, not only when it is the resolved client
address. It is off by default. The client writes part of that chain, so it only catches the
intermediaries that disclose the address they forward, like an open proxy, never a client that hides
its own: treat it as defense in depth. Allow lists only ever look at the resolved client address, as
anything else would let the client pick an allowed one.

### A request keeps the address it came in with

The client address is resolved once, when the request enters the proxy engine, against the global
config the request is handled with. The IP filtering, the allow and block lists, the quotas, the load
balancing on the client address, the expressions, the access logs, the events and the alerts of that
request all read that address, and the proxy chain it was resolved from. A change of
`trustXForwarded`, of the trusted proxies, of the client address header or of
`useLegacyClientIpAddress` applies to the requests that come in after it, never halfway through one:
an event always reports the address its request was handled with.

### `useLegacyClientIpAddress` is a way back, not a setting

The client address used everywhere Otoroshi does not ask for a specific source, including
`${req.ip}`, is the safe resolution described above. `useLegacyClientIpAddress` restores the
previous one: the raw leftmost `X-Forwarded-For` entry, with the trusted proxies ignored. It exists
only to roll back quickly if the new resolution misbehaves after an upgrade, and it will be removed.

It is enabled either by `OTOROSHI_OPTIONS_USE_LEGACY_CLIENT_IP_ADDRESS=true` or by the
`useLegacyClientIpAddress` field of the global config. The environment variable wins: while it is
set to `true`, the danger zone cannot turn the option off. Otoroshi logs a warning every time it becomes
active.

It does not bring back the rewriting Play used to do for proxies connected through the loopback. A
complete rollback of that part also needs
`-Dplay.http.forwarded.trustedProxies=["127.0.0.1","::1"]`, which only takes effect with the default
HTTP server and after a restart.

## API key plugin configuration

### `validate` and `mandatory` both off swallows every rejection

The apikey plugin has two toggles that look independent but combine into a surprising state. With
both *validate* and *mandatory* disabled, the plugin still extracts and resolves the API key, but
every rejection it produces is turned into an allow — including a bad secret, a disabled key, and a
`429` from an exhausted quota.

That combination means "an API key is optional here, and I do not want it enforced". If what you want
is "an API key is optional, but a *wrong* one must be rejected", that is a different configuration:
keep validation on and mandatory off.

Review any route where both are off, especially if you also rely on quotas: quotas are counted but
never enforced there.

### Pin the keypair of API keys that authenticate with JWTs

An API key can authenticate by presenting a JWT signed either with its `clientSecret` (`HS*`) or with
a keypair held in Otoroshi's certificate store (`RS*`, `ES*`).

For the keypair case, the certificate is selected by the `kid` header of the token being verified —
a value chosen by whoever signed it — and the lookup spans the whole certificate store, without
tenant scoping. If the API key does not declare which keypair it signs with, anyone able to get a
certificate of their own into that store can designate it and authenticate as that key.

Pin the keypair on the API key itself:

```json
{
  "metadata": {
    "jwt-sign-keypair": "the-certificate-id"
  }
}
```

When the metadata is present, the `kid` of the token must match it — the pin already wins. To enforce
the pin everywhere and ignore the `kid` header entirely, set:

```sh
export OTOROSHI_OPTIONS_APIKEYJWTPINNEDKEYPAIRONLY=true
```

With that option on, an API key that pins no keypair can no longer authenticate with a keypair-signed
JWT at all. This is the recommended posture; it defaults to off so that existing deployments keep
working.
