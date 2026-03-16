---
title: Routes
sidebar_position: 16
---
# Routes

A route is a unique routing rule based on hostname, path, method and headers that will execute a chain of plugins and eventually forward the request to the backend application.

## UI page

You can find all routes [here](http://otoroshi.oto.tools:8080/bo/dashboard/routes)

## Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `id`     | string |    | Unique identifier of the route |
| `name`   | string |    | Display name of the route |
| `description` | string |    | Description of the route |
| `tags` | array of string | `[]` | Tags for categorization and API automation |
| `metadata` | object | `{}` | Key/value metadata. Some keys are [reserved](#reserved-metadata) |
| `enabled` | boolean | `true` | Whether the route is active. Disabled routes are ignored by the router |
| `debug_flow` | boolean | `false` | Enable debug flow. Execution reports will contain all input/output values. See [engine docs](../topics/engine.md#reporting) |
| `capture` | boolean | `false` | Enable request/response capture. Generates events with full request content. Use with caution! See [engine docs](../topics/engine.md#http-traffic-capture) |
| `export_reporting` | boolean | `false` | Export execution reports for each request via [data exporters](./data-exporters.mdx). See [engine docs](../topics/engine.md#reporting) |
| `groups` | array of string | `["default"]` | Service groups this route belongs to. Used for API key authorization |
| `bound_listeners` | array of string | `[]` | List of [HTTP listener](./http-listeners.md) IDs this route is bound to. When a listener is exclusive, only bound routes are served on its port |
| `frontend` | object |    | Frontend configuration (how the router matches this route). See [below](#frontend-configuration) |
| `backend` | object |    | Backend configuration (where to forward requests). See [backends](./backends.md) |
| `backend_ref` | string | `null` | Reference to a global [stored backend](./backends.md) by ID. If set, takes precedence over inline `backend` |
| `plugins` | object |    | Plugin chain configuration. See [below](#plugins) |

### Reserved metadata

Some metadata keys are reserved for Otoroshi internal use:

| Key | Description |
|-----|-------------|
| `otoroshi-core-user-facing` | Is this a user-facing app for the Snow Monkey |
| `otoroshi-core-use-akka-http-client` | Use the pure Akka HTTP client |
| `otoroshi-core-use-netty-http-client` | Use the pure Netty HTTP client |
| `otoroshi-core-use-akka-http-ws-client` | Use the modern WebSocket client |
| `otoroshi-core-issue-lets-encrypt-certificate` | Enable Let's Encrypt certificate issuance for this route (`true`/`false`) |
| `otoroshi-core-issue-certificate` | Enable certificate issuance for this route (`true`/`false`) |
| `otoroshi-core-issue-certificate-ca` | ID of the CA cert to generate the certificate |
| `otoroshi-core-openapi-url` | OpenAPI spec URL for this route |
| `otoroshi-core-env` | Environment for this route (legacy) |
| `otoroshi-deployment-providers` | Relay routing: infrastructure providers |
| `otoroshi-deployment-regions` | Relay routing: network regions |
| `otoroshi-deployment-zones` | Relay routing: network zones |
| `otoroshi-deployment-dcs` | Relay routing: datacenters |
| `otoroshi-deployment-racks` | Relay routing: racks |

## Frontend configuration

The frontend defines how the Otoroshi router matches incoming requests to this route.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `domains` | array of string | `[]` | Matched domains and paths. Supports wildcards in domain and path, and named path params (e.g., `api.oto.tools/users/$id<[0-9]+>`) |
| `strip_path` | boolean | `true` | Strip the matched path from the forwarded request |
| `exact` | boolean | `false` | Perform exact path matching. If `false`, matches on `/path*` |
| `headers` | object | `{}` | Required HTTP headers to match. If empty, any headers match |
| `query` | object | `{}` | Required query parameters to match. If empty, any query params match |
| `cookies` | object | `{}` | Required cookies to match. If empty, any cookies match |
| `methods` | array of string | `[]` | Allowed HTTP methods. If empty, any method matches |

For more information about routing, check the [engine documentation](../topics/engine.md#routing).

### Frontend JSON example

```json
{
  "domains": [
    "api.oto.tools/users"
  ],
  "strip_path": true,
  "exact": false,
  "headers": {},
  "query": {},
  "cookies": {},
  "methods": ["GET", "POST"]
}
```

## Backend configuration

The `backend` field defines the target servers to forward requests to. For detailed documentation, see [backends](./backends.md).

Alternatively, use `backend_ref` to reference a global stored backend by ID, making the backend reusable across multiple routes.

## Plugins

The `plugins` field contains the list of plugins applied on this route. Each plugin definition has the following structure:

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | boolean | `true` | Whether the plugin is active |
| `debug` | boolean | `false` | Enable debug output for this specific plugin |
| `plugin` | string |     | The plugin identifier (e.g., `cp:otoroshi.next.plugins.Redirection`) |
| `include` | array of string | `[]` | Path patterns to include. If empty, all paths are included |
| `exclude` | array of string | `[]` | Path patterns to exclude. If empty, no paths are excluded |
| `config` | object | `{}` | Plugin-specific configuration |
| `plugin_index` | object | `null` | Explicit plugin execution order. If not provided, array order is used |
| `bound_listeners` | array of string | `[]` | Listener-specific plugin binding |

### Plugin JSON example

```json
{
  "enabled": true,
  "debug": false,
  "plugin": "cp:otoroshi.next.plugins.Redirection",
  "include": [],
  "exclude": [],
  "config": {
    "code": 303,
    "to": "https://www.otoroshi.io"
  },
  "plugin_index": {
    "pre_route": 0
  }
}
```

For the full list of available plugins, see [built-in plugins](../plugins/built-in-plugins.mdx).

## Complete route JSON example

```json
{
  "id": "route_users_api",
  "name": "Users API",
  "description": "Route for the users microservice",
  "tags": ["users", "api"],
  "metadata": {},
  "enabled": true,
  "debug_flow": false,
  "capture": false,
  "export_reporting": false,
  "groups": ["default"],
  "bound_listeners": [],
  "frontend": {
    "domains": ["api.oto.tools/users"],
    "strip_path": true,
    "exact": false,
    "headers": {},
    "query": {},
    "cookies": {},
    "methods": []
  },
  "backend": {
    "targets": [
      {
        "id": "target_1",
        "hostname": "users-service.internal",
        "port": 8080,
        "tls": false,
        "weight": 1,
        "protocol": "HTTP/1.1"
      }
    ],
    "root": "/",
    "rewrite": false,
    "load_balancing": { "type": "RoundRobin" }
  },
  "backend_ref": null,
  "plugins": {
    "slots": [
      {
        "plugin": "cp:otoroshi.next.plugins.OverrideHost",
        "enabled": true,
        "include": [],
        "exclude": [],
        "config": {}
      }
    ]
  }
}
```

## Admin API

```
GET    /api/routes           # List all routes
POST   /api/routes           # Create a route
GET    /api/routes/:id       # Get a route
PUT    /api/routes/:id       # Update a route
DELETE /api/routes/:id       # Delete a route
PATCH  /api/routes/:id       # Partially update a route
```

## Related entities

* [Backends](./backends.md) - Reusable backend configurations
* [APIs](./apis.md) - Group multiple routes into a managed API
* [HTTP Listeners](./http-listeners.md) - Custom listeners that routes can be bound to
* [Route Templates](./route-templates.md) - Reusable route blueprints
* [Service Groups](./service-groups.md) - Group routes for API key authorization
