---
title: Backends
sidebar_position: 5
---
# Backends

In Otoroshi, every [route](./routes.md) is built from three building blocks: a **frontend** (what to match), a **backend** (where to forward), and a **plugin chain** (what processing to apply). The backend is the part that answers the question: *"once a request has been matched, where should it go?"*

A backend encapsulates the full downstream configuration: the list of target servers, how to distribute traffic across them, how to connect to them, and how to react when they fail. By grouping all of this into a single entity, Otoroshi cleanly separates routing decisions from forwarding decisions -- you can change your target servers, adjust timeouts, or switch load balancing strategies without touching any routing rule.

### Inline vs stored backends

Backends can be used in two ways:

- **Inline** -- the backend configuration is embedded directly inside a route definition. This is the simplest approach when a backend is used by a single route.
- **Stored (global)** -- the backend is saved as a standalone entity with its own identifier. Multiple routes and [APIs](./apis.mdx) can then reference the same backend via `backend_ref`. When you update the stored backend, every route that references it picks up the change automatically. This is the recommended approach when several routes share the same set of targets.

### Key capabilities

- **Multiple targets with weights** -- define several downstream servers and assign each a weight to control how traffic is distributed. Mark targets as `backup` so they only receive traffic when all primary targets are down.
- **Load balancing strategies** -- choose from RoundRobin, Random, Sticky, IpAddressHash, BestResponseTime, WeightedBestResponseTime, LeastConnections, PowerOfTwoRandomChoices, Failover, or hash-based strategies on cookies, headers, and query parameters.
- **Health checks** -- periodically probe your targets so that unhealthy servers are automatically removed from the load balancing pool.
- **TLS and mTLS to backends** -- call targets over HTTPS, present client certificates, pin trusted CA certificates, or trust all certificates for development environments.
- **Path rewriting** -- prepend a root path to every forwarded request, or completely rewrite the request path using named path parameters and the [expression language](../topics/expression-language.mdx).
- **Client configuration** -- fine-tune how Otoroshi connects to your targets with retries (with exponential backoff), connection/idle/call timeouts, circuit breaker thresholds, connection pooling, path-specific timeout overrides, and HTTP proxy support.

## UI page

You can find all backends [here](http://otoroshi.oto.tools:8080/bo/dashboard/backends)

## Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `targets` | array of [Target](#targets) | `[]` | List of target servers |
| `root` | string | `"/"` | Path prefix added to each request sent to the downstream service |
| `rewrite` | boolean | `false` | When enabled, the request path is completely replaced by `root`. Supports [expression language](../topics/expression-language.mdx) for dynamic rewriting |
| `load_balancing` | object | `{"type": "RoundRobin"}` | Load balancing algorithm (see [below](#load-balancing)) |
| `health_check` | object | `null` | Optional health check configuration (see [below](#health-check)) |
| `client` | object |     | HTTP client settings (see [below](#client-settings)) |

### Full path rewrite

When `rewrite` is enabled, the original request path is stripped and replaced by the `root` value. Combined with named path parameters, this enables powerful URL rewriting:

* **Input**: `subdomain.domain.tld/api/users/$id<[0-9]+>/bills`
* **Output**: `target.domain.tld/apis/v1/basic_users/${req.pathparams.id}/all_bills`

## Targets

Each target represents a downstream server that Otoroshi can forward requests to.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `id` | string |     | Unique identifier of the target |
| `hostname` | string |     | Hostname of the target (without scheme) |
| `port` | number |     | Port of the target |
| `tls` | boolean |     | Call the target via HTTPS |
| `weight` | number | `1` | Weight used by the load balancing strategy to distribute traffic |
| `protocol` | string | `HTTP/1.1` | Protocol: `HTTP/1.0`, `HTTP/1.1`, `HTTP/2.0`, or `HTTP/3.0` |
| `predicate` | object | `AlwaysMatch` | Predicate function to filter this target based on request properties |
| `ip_address` | string | `null` | IP address of the target (optional, for DNS bypass) |
| `tls_config` | object |     | TLS settings for this specific target (see below) |
| `backup` | boolean | `false` | If `true`, this target is only used when all primary (non-backup) targets are unavailable |

### Target TLS settings

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | boolean | `false` | Enable custom TLS configuration for this target |
| `loose` | boolean | `false` | If enabled, will block all untrusted SSL configurations |
| `trust_all` | boolean | `false` | Accept any server certificate, including self-signed ones |
| `certs` | array of string | `[]` | Client certificate IDs used to communicate with the target |
| `trusted_certs` | array of string | `[]` | Trusted certificate IDs expected from the target |

### Target JSON example

```json
{
  "id": "target_1",
  "hostname": "api.backend.internal",
  "port": 8443,
  "tls": true,
  "weight": 1,
  "protocol": "HTTP/1.1",
  "predicate": { "type": "AlwaysMatch" },
  "ip_address": null,
  "tls_config": {
    "enabled": true,
    "loose": false,
    "trust_all": false,
    "certs": [],
    "trusted_certs": ["cert_backend_ca"]
  },
  "backup": false
}
```

## Health check

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | boolean | `false` | Enable periodic health checks |
| `url` | string |     | The URL to call for health checks |

When enabled, Otoroshi periodically calls the health check URL. Unhealthy targets can be temporarily removed from the load balancing pool.

## Load balancing

The `load_balancing` object selects which target receives the next request when several are available. Every strategy is a JSON object with at least a `type` field; some strategies accept additional parameters.

| Type | Extra parameters | Description |
|------|------------------|-------------|
| `RoundRobin` | -- | Distributes requests evenly across all targets, in declaration order. Default strategy. |
| `Random` | -- | Picks a target uniformly at random for each request. |
| `Sticky` | -- | Routes requests carrying the same Otoroshi tracking id to the same target, using consistent hashing. Requires the tracking cookie to be enabled (Otoroshi sets it automatically). |
| `IpAddressHash` | -- | Picks the target by consistent hashing on the client IP address. Same client IP always lands on the same target as long as the target pool is unchanged. |
| `CookieHash` | `cookie_name` (string, default `"session-id"`) | Picks the target by consistent hashing on the value of the named request cookie. Falls back to round-robin when the cookie is absent. |
| `QueryHash` | `query_name` (string, default `"session-id"`) | Picks the target by consistent hashing on the value of the named query string parameter. Falls back to round-robin when the parameter is absent. |
| `HeaderHash` | `header_name` (string, default `"session-id"`) | Picks the target by consistent hashing on the value of the named request header. Falls back to round-robin when the header is absent. |
| `BestResponseTime` | -- | Routes to the target with the lowest observed average response time. Targets with no recorded samples are tried first so every target gets measured. |
| `WeightedBestResponseTime` | `ratio` (number, default `0.5`, clamped to `[0.0, 0.99]`) | Mixes `BestResponseTime` with `Random`. The `ratio` controls how often the fastest target is preferred -- `0.0` is equivalent to `Random`, values close to `0.99` always pick the fastest target. |
| `LeastConnections` | -- | Routes to the target with the fewest in-flight requests handled by this Otoroshi instance. Ties are broken in round-robin order. |
| `PowerOfTwoRandomChoices` | -- | Picks two targets at random and forwards to the one with fewer in-flight requests. Cheap approximation of `LeastConnections` that scales well with large target pools. |

:::note
`Sticky`, `BestResponseTime`, `WeightedBestResponseTime`, `LeastConnections` and `PowerOfTwoRandomChoices` keep their state in memory on each Otoroshi instance. In a cluster, every worker maintains its own counters, so the distribution converges per-instance rather than globally.
:::

:::tip
Targets flagged with `"backup": true` are excluded from load balancing as long as at least one non-backup target is available. They only enter the pool once all primary targets are marked unhealthy or removed.
:::

### Examples

Round robin (default):

```json
{ "type": "RoundRobin" }
```

Hash on a header value:

```json
{ "type": "HeaderHash", "header_name": "x-tenant-id" }
```

Weighted best response time biased 80% toward the fastest target:

```json
{ "type": "WeightedBestResponseTime", "ratio": 0.8 }
```

## Client settings

Client settings control how Otoroshi connects to backend targets.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `retries` | number | `1` | Number of retry attempts after a failed request |
| `max_errors` | number | `20` | Number of errors before opening the circuit breaker |
| `retry_initial_delay` | number | `50` | Delay (ms) before the first retry |
| `backoff_factor` | number | `2` | Multiplier applied to the delay between each retry |
| `connection_timeout` | number | `10000` | Maximum duration (ms) for establishing a connection |
| `idle_timeout` | number | `60000` | Maximum duration (ms) a connection can stay idle |
| `call_and_stream_timeout` | number | `120000` | Maximum duration (ms) for handling the request and streaming the response |
| `call_timeout` | number | `30000` | Maximum duration (ms) for a single call |
| `global_timeout` | number | `30000` | Maximum duration (ms) for the entire call including retries |
| `sample_interval` | number | `2000` | Delay (ms) between retries. Multiplied by `backoff_factor` at each retry |
| `custom_timeouts` | array of object | `[]` | Path-specific timeout overrides (see below) |
| `cache_connection_settings` | object |     | Connection caching settings (see below) |
| `proxy` | object | `null` | HTTP proxy settings for reaching the backend |

### Custom timeouts

Custom timeouts allow overriding timeout values for specific paths:

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `path` | string | `/*` | Path pattern to match |
| `connection_timeout` | number | `10000` | Connection timeout (ms) |
| `idle_timeout` | number | `60000` | Idle timeout (ms) |
| `call_and_stream_timeout` | number | `3600000` | Call and stream timeout (ms) |
| `call_timeout` | number | `30000` | Call timeout (ms) |
| `global_timeout` | number | `30000` | Global timeout (ms) |

### Connection cache settings

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | boolean | `false` | Try to keep TCP connections alive between requests |
| `queue_size` | number | `2048` | Queue size for open TCP connections |

### Proxy settings

| Property | Type | Description |
|----------|------|-------------|
| `host` | string | Proxy hostname |
| `port` | number | Proxy port |
| `protocol` | string | Proxy protocol (`http` or `https`) |
| `principal` | string | Proxy username |
| `password` | string | Proxy password |

## Complete backend JSON example

```json
{
  "targets": [
    {
      "id": "target_1",
      "hostname": "api-1.backend.internal",
      "port": 8080,
      "tls": false,
      "weight": 1,
      "protocol": "HTTP/1.1",
      "predicate": { "type": "AlwaysMatch" },
      "backup": false
    },
    {
      "id": "target_2",
      "hostname": "api-2.backend.internal",
      "port": 8080,
      "tls": false,
      "weight": 1,
      "protocol": "HTTP/1.1",
      "predicate": { "type": "AlwaysMatch" },
      "backup": true
    }
  ],
  "root": "/api/v1",
  "rewrite": false,
  "load_balancing": { "type": "RoundRobin" },
  "health_check": {
    "enabled": true,
    "url": "http://api-1.backend.internal:8080/health"
  },
  "client": {
    "retries": 1,
    "max_errors": 20,
    "retry_initial_delay": 50,
    "backoff_factor": 2,
    "connection_timeout": 10000,
    "idle_timeout": 60000,
    "call_and_stream_timeout": 120000,
    "call_timeout": 30000,
    "global_timeout": 30000,
    "sample_interval": 2000,
    "cache_connection_settings": {
      "enabled": false,
      "queue_size": 2048
    },
    "custom_timeouts": []
  }
}
```

## Admin API

```
GET    /api/backends           # List all stored backends
POST   /api/backends           # Create a stored backend
GET    /api/backends/:id       # Get a stored backend
PUT    /api/backends/:id       # Update a stored backend
DELETE /api/backends/:id       # Delete a stored backend
PATCH  /api/backends/:id       # Partially update a stored backend
```

## Related entities

* [Routes](./routes.md) - Routes reference backends inline or via `backend_ref`
* [APIs](./apis.mdx) - APIs define backends that are shared across API routes
