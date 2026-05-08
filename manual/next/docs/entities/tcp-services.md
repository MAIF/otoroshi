---
title: TCP Services
sidebar_position: 19
---
# TCP Services

## Overview

Otoroshi is primarily an HTTP reverse proxy and API gateway, but not all traffic in a modern infrastructure speaks HTTP. Databases (PostgreSQL, MySQL, MongoDB), message brokers (MQTT, AMQP), remote shells (SSH), custom binary protocols, and many other services communicate over raw TCP. Without TCP proxying support, you would need a separate tool to manage this non-HTTP traffic, fragmenting your infrastructure and multiplying operational overhead.

TCP services solve this problem by letting you consolidate both HTTP and non-HTTP traffic through a single platform. A TCP service listens on a dedicated port and forwards raw TCP byte streams to one or more backend targets, without any HTTP-level processing. There are no headers, no request/response semantics, no plugins, and no content transformation -- just transparent byte forwarding between the client and the backend.

### How TCP services differ from HTTP routes

HTTP routes in Otoroshi operate at the application layer (Layer 7): they parse HTTP requests, apply plugins, transform headers, enforce rate limits, and more. TCP services operate at the transport layer (Layer 4): they accept a TCP connection and pipe bytes to a backend, with no awareness of the application protocol flowing through them. This makes TCP services suitable for any protocol that runs over TCP, but it also means that HTTP-specific features (authentication plugins, header manipulation, traffic policies) are not available on TCP services.

### SNI-based routing

A key feature of TCP services is the ability to route connections using TLS Server Name Indication (SNI). During the TLS handshake, the client sends the hostname it is trying to reach. Otoroshi can inspect this hostname and use it to select different backend targets, even when multiple services share the same listening port. This works in two modes:

- **TLS termination** (`Enabled`): Otoroshi terminates TLS, reads the SNI hostname, selects the appropriate certificate from its dynamic certificate store, and opens a new connection (optionally with TLS) to the matched backend.
- **TLS passthrough** (`PassThrough`): Otoroshi extracts the SNI hostname from the initial TLS ClientHello packet without terminating TLS, then forwards the entire encrypted connection to the matched backend. The backend handles TLS termination. This is useful when backends must manage their own certificates or when end-to-end encryption is required.

When SNI is not enabled, each TCP service must bind to a unique port. When SNI is enabled, multiple routing rules can coexist on the same port, each matching a different domain pattern via regex.

### Key features

- **Multiple targets with round-robin load balancing**: each routing rule can define several backend targets; Otoroshi distributes connections across them.
- **Flexible TLS modes**: disabled (plain TCP), enabled (Otoroshi terminates TLS), or passthrough (TLS forwarded to the backend).
- **Mutual TLS (mTLS)**: optionally require or request client certificates for TCP connections.
- **SNI-based routing**: route connections to different backends based on the domain name in the TLS handshake, allowing multiple logical services on a single port.
- **Default forwarding**: when SNI is enabled but no rule matches, optionally forward to a default target instead of rejecting the connection.
- **Analytics**: TCP connections generate events that flow through the same analytics pipeline as HTTP traffic.

### When to use TCP services vs HTTP routes

Use **HTTP routes** when your traffic is HTTP or HTTPS and you need application-level features such as authentication, rate limiting, header transformation, or plugin processing. Use **TCP services** when you need to proxy non-HTTP protocols (databases, SSH, MQTT, custom TCP protocols) or when you need transparent TLS passthrough without Otoroshi inspecting the application-layer content.

## UI page

You can find all TCP services [here](http://otoroshi.oto.tools:8080/bo/dashboard/tcp/services)

## Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `id` | string |     | Unique identifier |
| `name` | string |     | Display name of the TCP service |
| `description` | string |     | Description |
| `enabled` | boolean | `true` | Whether the service is active |
| `port` | number |     | The listening port for incoming TCP connections |
| `interface` | string | `0.0.0.0` | Network interface the service listens on |
| `tags` | array of string | `[]` | Tags |
| `metadata` | object | `{}` | Key/value metadata |
| `tls` | string | `Disabled` | TLS mode (see below) |
| `clientAuth` | string | `None` | mTLS client authentication mode (see below) |
| `sni` | object |     | SNI configuration (see below) |
| `rules` | array of object | `[]` | Routing rules (see below) |

## TLS modes

| Mode | Description |
|------|-------------|
| `Disabled` | No TLS. TCP traffic is proxied as-is |
| `PassThrough` | The target exposes TLS. Traffic passes through Otoroshi without termination |
| `Enabled` | Otoroshi terminates TLS and selects the certificate based on SNI |

## Client authentication

| Mode | Description |
|------|-------------|
| `None` | No client certificate required |
| `Want` | Client certificate is requested but not required |
| `Need` | Valid client certificate is mandatory |

## SNI (Server Name Indication)

SNI allows Otoroshi to select different targets based on the hostname in the TLS handshake.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | boolean | `false` | Enable SNI-based routing |
| `forwardIfNoMatch` | boolean | `false` | Forward to a default target if no SNI match is found |
| `forwardsTo.host` | string |     | Default target hostname (when no SNI match) |
| `forwardsTo.ip` | string |     | Default target IP address |
| `forwardsTo.port` | number |     | Default target port |
| `forwardsTo.tls` | boolean | `false` | Use TLS to connect to the default target |

## Rules

For any listening TCP proxy, you can define multiple routing rules based on SNI or extracted HTTP host (when proxying HTTP).

| Property | Type | Description |
|----------|------|-------------|
| `domain` | string | Regex pattern to match against the domain name |
| `targets` | array of object | List of targets for this rule |
| `targets[].host` | string | Target hostname |
| `targets[].ip` | string | Target IP address |
| `targets[].port` | number | Target port |
| `targets[].tls` | boolean | Use TLS to connect to this target |

## JSON example

```json
{
  "id": "tcp_service_postgres",
  "name": "PostgreSQL proxy",
  "description": "TCP proxy for the production PostgreSQL cluster",
  "enabled": true,
  "port": 5432,
  "interface": "0.0.0.0",
  "tls": "Enabled",
  "clientAuth": "None",
  "sni": {
    "enabled": true,
    "forwardIfNoMatch": true,
    "forwardsTo": {
      "host": "postgres-primary.internal",
      "ip": null,
      "port": 5432,
      "tls": false
    }
  },
  "rules": [
    {
      "domain": ".*read.*",
      "targets": [
        {
          "host": "postgres-replica.internal",
          "ip": null,
          "port": 5432,
          "tls": false
        }
      ]
    }
  ],
  "tags": ["database", "production"],
  "metadata": {}
}
```

## Admin API

```
GET    /api/tcp/services           # List all TCP services
POST   /api/tcp/services           # Create a TCP service
GET    /api/tcp/services/:id       # Get a TCP service
PUT    /api/tcp/services/:id       # Update a TCP service
DELETE /api/tcp/services/:id       # Delete a TCP service
PATCH  /api/tcp/services/:id       # Partially update a TCP service
```
