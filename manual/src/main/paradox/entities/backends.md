# Backends

A backend represents a list of target servers to forward requests to, along with its client settings, load balancing, and health check configuration.

Backends can be defined inline on a route or on their dedicated page to be reusable across multiple routes and APIs.

## UI page

You can find all backends [here](http://otoroshi.oto.tools:8080/bo/dashboard/backends)

## Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `targets` | array of [Target](#targets) | `[]` | List of target servers |
| `root` | string | `"/"` | Path prefix added to each request sent to the downstream service |
| `rewrite` | boolean | `false` | When enabled, the request path is completely replaced by `root`. Supports @ref[expression language](../topics/expression-language.md) for dynamic rewriting |
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

| Type | Description |
|------|-------------|
| `RoundRobin` | Distributes requests evenly across all targets in order |
| `Random` | Randomly selects a target for each request |
| `Sticky` | Routes requests from the same client to the same target |
| `IpAddressHash` | Selects target based on a hash of the client IP address |
| `BestResponseTime` | Routes to the target with the lowest response time |
| `WeightedBestResponseTime` | Combines weight and response time for target selection |

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

* @ref:[Routes](./routes.md) - Routes reference backends inline or via `backend_ref`
* @ref:[APIs](./apis.md) - APIs define backends that are shared across API routes
