---
title: WASM Plugins
sidebar_position: 21
---
# WASM Plugins

WASM Plugins are reusable WebAssembly plugin configurations that can be referenced by multiple routes. Instead of configuring WASM settings inline on each route, you can create a WASM plugin entity and reference it by ID, making it easier to manage and share WASM logic across your infrastructure.

## UI page

You can find all WASM plugins [here](http://otoroshi.oto.tools:8080/bo/dashboard/wasm-plugins)

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `id` | string | Unique identifier of the WASM plugin |
| `name` | string | Display name of the plugin |
| `description` | string | Description of the plugin |
| `tags` | array of string | Tags associated to the plugin |
| `metadata` | object | Key/value metadata associated to the plugin |
| `steps` | array of string | The list of proxy engine steps where this plugin can be used |
| `config` | object | The WASM plugin configuration (see below) |

## Steps

The `steps` field defines at which points in the proxy engine pipeline this WASM plugin can be used:

| Step | Description |
|------|-------------|
| `MatchRoute` | Custom route matching logic |
| `Router` | Custom routing decision |
| `Sink` | Handle requests that don't match any route |
| `PreRoute` | Request enrichment before routing |
| `ValidateAccess` | Access control validation |
| `TransformRequest` | Modify the request before forwarding to backend |
| `TransformResponse` | Modify the response before sending to client |
| `HandlesRequest` | Handle the complete request/response lifecycle (acts as a backend) |
| `CallBackend` | Custom backend call logic |
| `HandlesTunnel` | Handle tunnel connections |
| `Job` | Periodic job execution |

## Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `source` | object |     | The WASM source definition (see source types below) |
| `memoryPages` | number | `50` | Number of memory pages allocated to the WASM VM |
| `functionName` | string |     | Name of the function to invoke (optional, defaults to step-specific name) |
| `config` | object | `{}` | Key-value configuration passed to the WASM plugin at runtime |
| `instances` | number | `1` | Number of concurrent WASM VM instances |
| `wasi` | boolean | `false` | Enable WebAssembly System Interface support |
| `opa` | boolean | `false` | Enable Open Policy Agent mode for policy evaluation |
| `httpWasm` | boolean | `false` | Enable HTTP WASM mode |
| `allowedHosts` | array of string | `[]` | Hostnames the WASM plugin is allowed to call via HTTP |
| `allowedPaths` | object | `{}` | File paths the WASM plugin is allowed to access (path -> permissions) |
| `killOptions` | object |     | VM termination options for resource management |
| `authorizations` | object |     | Fine-grained access control for the WASM plugin (see below) |

## Source types

The `source` field defines where the WASM module is loaded from:

| Type | Description |
|------|-------------|
| `base64` | The WASM binary encoded as a base64 string |
| `file` | A local file path pointing to a `.wasm` file |
| `http` | An HTTP/HTTPS URL to download the WASM module from |
| `wasmo` | A reference to a WASM module compiled and managed by a Wasmo instance |
| `local` | A reference to another WASM plugin entity by ID |

## Authorizations

The `authorizations` field controls what the WASM plugin is allowed to access at runtime:

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `httpAccess` | boolean | `false` | Allow the plugin to make outbound HTTP calls |
| `proxyHttpCallTimeout` | number | `5000` | Timeout in milliseconds for HTTP calls made by the plugin |
| `globalDataStoreAccess` | object | `{"read": false, "write": false}` | Read/write access to the global persistent key-value store |
| `pluginDataStoreAccess` | object | `{"read": false, "write": false}` | Read/write access to the plugin-scoped persistent key-value store |
| `globalMapAccess` | object | `{"read": false, "write": false}` | Read/write access to the global in-memory store |
| `pluginMapAccess` | object | `{"read": false, "write": false}` | Read/write access to the plugin-scoped in-memory store |
| `proxyStateAccess` | boolean | `false` | Allow reading the current Otoroshi proxy state |
| `configurationAccess` | boolean | `false` | Allow reading the Otoroshi configuration |

## JSON example

A WASM plugin loaded from an HTTP URL with access to the proxy state:

```json
{
  "id": "wasm-plugin_request_validator",
  "name": "Request validator",
  "description": "Validates incoming requests using custom WASM logic",
  "tags": ["security"],
  "metadata": {},
  "steps": ["ValidateAccess", "TransformRequest"],
  "config": {
    "source": {
      "kind": "http",
      "path": "https://wasm-registry.internal/plugins/request-validator.wasm"
    },
    "memoryPages": 50,
    "functionName": null,
    "config": {
      "max_body_size": "1048576",
      "allowed_content_types": "application/json,text/plain"
    },
    "instances": 2,
    "wasi": false,
    "opa": false,
    "httpWasm": false,
    "allowedHosts": ["api.internal"],
    "allowedPaths": {},
    "authorizations": {
      "httpAccess": true,
      "proxyHttpCallTimeout": 5000,
      "globalDataStoreAccess": { "read": true, "write": false },
      "pluginDataStoreAccess": { "read": true, "write": true },
      "globalMapAccess": { "read": true, "write": false },
      "pluginMapAccess": { "read": true, "write": true },
      "proxyStateAccess": true,
      "configurationAccess": false
    }
  }
}
```

An OPA (Open Policy Agent) plugin for policy evaluation:

```json
{
  "id": "wasm-plugin_opa_policy",
  "name": "OPA access policy",
  "description": "Evaluates access policies using OPA WASM module",
  "tags": ["policy", "opa"],
  "metadata": {},
  "steps": ["ValidateAccess"],
  "config": {
    "source": {
      "kind": "file",
      "path": "/etc/otoroshi/policies/access-policy.wasm"
    },
    "memoryPages": 100,
    "instances": 4,
    "opa": true,
    "wasi": false,
    "config": {},
    "allowedHosts": [],
    "allowedPaths": {},
    "authorizations": {
      "httpAccess": false,
      "proxyStateAccess": true,
      "configurationAccess": true
    }
  }
}
```

## Admin API

```
GET    /api/wasm-plugins           # List all WASM plugins
POST   /api/wasm-plugins           # Create a WASM plugin
GET    /api/wasm-plugins/:id       # Get a WASM plugin
PUT    /api/wasm-plugins/:id       # Update a WASM plugin
DELETE /api/wasm-plugins/:id       # Delete a WASM plugin
PATCH  /api/wasm-plugins/:id       # Partially update a WASM plugin
```

## Learn more

For a detailed overview of WASM in Otoroshi, available plugin types, and how they integrate with the proxy engine, see [Otoroshi and WASM](../topics/wasm-usage.md).

For practical guides:

* [Install a Wasmo](../how-to-s/wasmo-installation.md) to set up a Wasmo instance for compiling WASM plugins
* [Use a WASM plugin](../how-to-s/wasm-usage.md) for a step-by-step tutorial on creating and using WASM plugins
