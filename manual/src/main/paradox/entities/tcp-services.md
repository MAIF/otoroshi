# TCP Services

TCP services are a special kind of Otoroshi service designed to proxy pure TCP connections (SSH, databases, HTTP, etc.) without HTTP-level processing.

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
