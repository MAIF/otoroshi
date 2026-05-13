---
title: HTTP Listeners
sidebar_position: 10
---
# HTTP Listeners

By default, Otoroshi listens on a single HTTP port and a single HTTPS port. This works well for many deployments, but real-world infrastructure often demands more flexibility. You may need to isolate internal APIs from public traffic, enforce mutual TLS on a dedicated port, expose HTTP/3 alongside HTTP/2, or bind an admin interface to a private network interface. HTTP Listeners solve this problem by letting you open additional ports, each with its own independent configuration, without running multiple Otoroshi instances.

## How it works

Each HTTP Listener creates a new [Netty](../topics/netty-server.md) server binding on the port and host you specify. The listener runs its own protocol stack, so you can mix and match settings per port:

- **TLS configuration** -- enable or disable TLS independently, choose a client authentication mode (`None`, `Want`, or `Need` for mTLS), all separate from the main Otoroshi ports.
- **Protocol support** -- enable HTTP/1.1, HTTP/2, H2C (HTTP/2 cleartext, useful for internal gRPC without TLS), or [HTTP/3](../topics/http3.md) (QUIC) on a per-listener basis.
- **Network binding** -- bind to `0.0.0.0` for all interfaces, or restrict to a specific address like `127.0.0.1` or an internal network interface.

Listeners can be **static** (defined in the Otoroshi configuration file and started at boot) or **dynamic** (created and managed at runtime through the admin API or UI). Dynamic listeners are synchronized automatically: when you create, update, or delete a listener entity, Otoroshi starts, restarts, or stops the corresponding Netty server without requiring a full restart.

## Relationship with routes

Routes can be scoped to specific listeners through the `bound_listeners` field on the [route](./routes.md) entity. When a listener is marked as `exclusive`, it will only serve routes that explicitly reference it -- all other routes are ignored on that port. This creates fully isolated traffic channels: for example, internal management routes accessible only on a private port, with no risk of accidental exposure on the public port.

Individual plugins within a route can also be scoped to specific listeners, giving even finer control over which processing logic runs depending on the port that received the request.

## Typical use cases

- **Public / internal separation** -- run a public-facing listener on port 443 and an internal listener on port 9443 bound to a private interface, with `exclusive` mode so internal APIs are never reachable from the outside.
- **Dedicated admin port** -- expose the Otoroshi admin API and backoffice on a separate port restricted to a management network.
- **Per-port TLS policies** -- require client certificates (`clientAuth: Need`) on a specific port for services that need mTLS, while keeping the main port open for standard HTTPS.
- **Protocol-specific listeners** -- dedicate a port to HTTP/3 traffic, or run H2C on an internal port for gRPC services that do not need TLS.
- **Multi-port deployment** -- serve webhooks, health checks, or monitoring endpoints on dedicated ports with their own access logging settings.

## UI page

You can find all HTTP listeners [here](http://otoroshi.oto.tools:8080/bo/dashboard/extensions/cloud-apim/http-listeners)

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `id` | string | Unique identifier of the HTTP listener |
| `name` | string | Display name of the listener |
| `description` | string | Description of the listener |
| `tags` | array of string | Tags associated to the listener |
| `metadata` | object | Key/value metadata associated to the listener |
| `config` | object | The listener configuration (see below) |

## Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | boolean | `true` | Whether the listener is active and accepting connections |
| `exclusive` | boolean | `false` | If enabled, this listener only handles routes explicitly bound to it. Routes not bound will not be served on its port |
| `tls` | boolean | `true` | Enable TLS/HTTPS on this listener |
| `http1` | boolean | `true` | Enable HTTP/1.1 protocol support |
| `http2` | boolean | `true` | Enable HTTP/2 protocol support |
| `h2c` | boolean | `false` | Enable H2C (HTTP/2 Cleartext, without TLS) protocol support |
| `http3` | boolean | `false` | Enable HTTP/3 (QUIC) protocol support |
| `port` | number | `7890` | The port on which the listener will accept connections |
| `exposedPort` | number | `7890` | The externally exposed port (useful behind a load balancer or in a container) |
| `host` | string | `0.0.0.0` | The host address to bind to |
| `accessLog` | boolean | `false` | Enable access logging for this listener |
| `clientAuth` | string | `None` | mTLS client authentication mode: `None`, `Want`, or `Need` |

### Client authentication modes

| Mode | Description |
|------|-------------|
| `None` | No client certificate is requested |
| `Want` | A client certificate is requested but not required. If provided, it will be validated |
| `Need` | A valid client certificate is required. Connections without one will be rejected |

## Binding routes to listeners

Routes can be bound to specific listeners using the `bound_listeners` field on the [route](./routes.md) entity. When a listener is configured as `exclusive`, only routes that explicitly reference this listener in their `bound_listeners` array will be served on its port.

This allows you to create isolated traffic channels where specific routes are only accessible on specific ports.

## JSON example

A basic HTTPS listener on a custom port:

```json
{
  "id": "http-listener_internal_api",
  "name": "Internal API listener",
  "description": "HTTPS listener for internal APIs on port 9443",
  "tags": ["internal"],
  "metadata": {},
  "config": {
    "enabled": true,
    "exclusive": true,
    "tls": true,
    "http1": true,
    "http2": true,
    "h2c": false,
    "http3": false,
    "port": 9443,
    "exposedPort": 9443,
    "host": "0.0.0.0",
    "accessLog": true,
    "clientAuth": "None"
  }
}
```

An HTTP/3 listener with mTLS:

```json
{
  "id": "http-listener_secure_h3",
  "name": "Secure HTTP/3 listener",
  "description": "HTTP/3 listener with mutual TLS on port 8443",
  "tags": ["secure", "http3"],
  "metadata": {},
  "config": {
    "enabled": true,
    "exclusive": false,
    "tls": true,
    "http1": true,
    "http2": true,
    "h2c": false,
    "http3": true,
    "port": 8443,
    "exposedPort": 8443,
    "host": "0.0.0.0",
    "accessLog": false,
    "clientAuth": "Need"
  }
}
```

## Use cases

* **Internal/external separation**: Run a public listener on port 443 and an internal listener on port 9443 with `exclusive: true` so internal APIs are never exposed publicly
* **Protocol-specific listeners**: Dedicate a listener for HTTP/3 traffic or H2C (useful for gRPC without TLS in internal networks)
* **mTLS endpoints**: Create a listener with `clientAuth: Need` for services that require mutual TLS authentication
* **Multi-port deployment**: Expose the same Otoroshi instance on multiple ports for different purposes (admin, public API, webhooks)
* **Access logging**: Enable access logging on specific listeners for auditing without adding overhead to all traffic

## Admin API

```
GET    /apis/http-listeners.otoroshi.io/v1/http-listeners           # List all listeners
POST   /apis/http-listeners.otoroshi.io/v1/http-listeners           # Create a listener
GET    /apis/http-listeners.otoroshi.io/v1/http-listeners/:id       # Get a listener
PUT    /apis/http-listeners.otoroshi.io/v1/http-listeners/:id       # Update a listener
DELETE /apis/http-listeners.otoroshi.io/v1/http-listeners/:id       # Delete a listener
```

## Learn more

For detailed information about setting up HTTP listeners, binding routes, and using static vs dynamic listeners, see [Custom HTTP Listeners](../topics/http-listeners.mdx).

You can also check:

* [HTTP3 support](../topics/http3.md) for HTTP/3 protocol configuration
* [Alternative HTTP server](../topics/netty-server.md) for information about the Netty-based HTTP server
