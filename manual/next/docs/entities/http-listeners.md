---
title: HTTP Listeners
sidebar_position: 10
---
# HTTP Listeners

HTTP Listeners allow you to create custom HTTP listeners on specific ports, enabling Otoroshi to serve traffic on multiple ports with different protocol configurations. Each listener can be configured independently for TLS, HTTP/1.1, HTTP/2, H2C, and HTTP/3 support.

This is useful when you need to expose different services on different ports, separate public and internal traffic, or run specific protocols on dedicated ports.

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

For detailed information about setting up HTTP listeners, binding routes, and using static vs dynamic listeners, see [Custom HTTP Listeners](../topics/http-listeners.md).

You can also check:

* [HTTP3 support](../topics/http3.md) for HTTP/3 protocol configuration
* [Alternative HTTP server](../topics/netty-server.md) for information about the Netty-based HTTP server
