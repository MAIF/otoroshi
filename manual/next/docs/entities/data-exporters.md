---
title: Data Exporters
sidebar_position: 7
---
# Data Exporters

Data exporters are the way to export alerts, events, and analytics from Otoroshi to external storage and monitoring systems.

To try them out, you can follow [this tutorial](../how-to-s/export-alerts-using-mailgun.md).

## UI page

You can find all data exporters [here](http://otoroshi.oto.tools:8080/bo/dashboard/exporters)

## Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `id` | string |     | Unique identifier |
| `name` | string |     | Display name |
| `description` | string |     | Description |
| `enabled` | boolean | `true` | Whether the exporter is active |
| `typ` | string |     | The type of exporter (see [exporter types](#exporter-types)) |
| `tags` | array of string | `[]` | Tags |
| `metadata` | object | `{}` | Key/value metadata |
| `filtering` | object |     | Event filtering configuration (see [below](#matching-and-projections)) |
| `projection` | object | `{}` | Projection to export only specific fields |
| `bufferSize` | number | `5000` | Number of events to keep in memory buffer |
| `jsonWorkers` | number | `1` | Number of workers for JSON serialization |
| `sendWorkers` | number | `1` | Number of workers for sending events |
| `groupSize` | number | `100` | Number of events to batch together |
| `groupDuration` | number | `30000` | Maximum wait time (ms) before sending a batch |
| `config` | object |     | Exporter-specific configuration |

## Matching and projections

### Filtering

**Filtering** is used to **include** or **exclude** specific types of events and alerts. Each include/exclude entry is a key-value match.

Example: only keep Otoroshi alerts:

```json
{ "include": [{ "@type": "AlertEvent" }] }
```

Otoroshi provides rules to filter events based on their content. Given this example event:

```json
{
  "foo": "bar",
  "type": "AlertEvent",
  "alert": "big-alert",
  "status": 200,
  "codes": ["a", "b"],
  "inner": {
    "foo": "bar",
    "bar": "foo"
  }
}
```

@@@div { #filtering }
&nbsp;
:::
### Projection

**Projection** is a list of fields to export. If empty, all fields are exported. If specified, **only** the listed fields will be included in exported events.

@@@div { #projection }
&nbsp;
:::
## Exporter types

### Elastic

Sends events in batch to an Elasticsearch cluster. Can be used with the [elastic analytics dashboard](./global-config.md#analytics-elastic-dashboard-datasource-read-).

| Property | Type | Description |
|----------|------|-------------|
| `uris` | array of string | Elasticsearch cluster URIs |
| `index` | string | Index name |
| `type` | string | Event type (not needed for ES 6.x+) |
| `user` | string | Username (optional) |
| `password` | string | Password (optional) |
| `version` | string | Elasticsearch version (auto-detected if not set) |
| `applyTemplate` | boolean | Automatically apply index template |
| `indexSettings.clientSide` | boolean | Otoroshi manages index creation over time |
| `indexSettings.interval` | string | Index rotation interval: `Day`, `Week`, `Month`, or `Year` |
| `indexSettings.numberOfShards` | number | Number of shards |
| `indexSettings.numberOfReplicas` | number | Number of replicas |
| `mtlsConfig` | object | Custom TLS settings |

### Webhook

Sends events in batch to an HTTP endpoint using POST with a JSON array body.

| Property | Type | Description |
|----------|------|-------------|
| `url` | string | URL to post events to |
| `headers` | object | HTTP headers to include |
| `tlsConfig` | object | Custom TLS settings |

### Kafka

Sends events to an [Apache Kafka](https://kafka.apache.org/) topic. See [Kafka tutorials](../how-to-s/communicate-with-kafka.md).

| Property | Type | Description |
|----------|------|-------------|
| `servers` | array of string | Kafka broker addresses |
| `topic` | string | Kafka topic name |
| `sasl` | object | SASL authentication (username, password, mechanism: `PLAIN`/`SCRAM-SHA-256`/`SCRAM-SHA-512`) |
| `keypass` | string | Keystore password |
| `keystore` | string | Keystore path |
| `truststore` | string | Truststore path |
| `tlsConfig` | object | Custom TLS settings |

### Pulsar

Sends events to an [Apache Pulsar](https://pulsar.apache.org/) topic.

| Property | Type | Description |
|----------|------|-------------|
| `uri` | string | Pulsar server URI |
| `tenant` | string | Pulsar tenant |
| `namespace` | string | Pulsar namespace |
| `topic` | string | Pulsar topic |
| `tlsConfig` | object | Custom TLS settings |

### Splunk

Sends events to a Splunk HTTP Event Collector (HEC).

| Property | Type | Description |
|----------|------|-------------|
| `url` | string | Splunk HEC URL |
| `token` | string | HEC token |
| `index` | string | Splunk index (optional) |
| `type` | string | Event source type (optional) |
| `headers` | object | Additional HTTP headers |
| `tlsConfig` | object | Custom TLS settings |

### Datadog

Sends events to Datadog via the Datadog API.

| Property | Type | Description |
|----------|------|-------------|
| `host` | string | Datadog API host (e.g., `https://api.datadoghq.eu`) |
| `apiKey` | string | Datadog API key |
| `applicationKey` | string | Application key (optional) |
| `headers` | object | Additional HTTP headers |
| `tlsConfig` | object | Custom TLS settings |

### New Relic

Sends events to New Relic via their API.

| Property | Type | Description |
|----------|------|-------------|
| `url` | string | New Relic API URL |
| `licenseKey` | string | New Relic license key |
| `accountId` | string | New Relic account ID |
| `headers` | object | Additional HTTP headers |
| `tlsConfig` | object | Custom TLS settings |

### File

Writes events to local files.

| Property | Type | Description |
|----------|------|-------------|
| `path` | string | File path to write events |
| `maxFileSize` | number | Maximum file size in bytes. When reached, a new timestamped file is created |
| `maxNumberOfFile` | number | Maximum number of files to retain |

### S3

Writes events to an S3-compatible object store.

| Property | Type | Description |
|----------|------|-------------|
| `maxFileSize` | number | Maximum size per file |
| `maxNumberOfFile` | number | Maximum number of files |
| `config` | object | S3 configuration (bucket, region, credentials, endpoint) |

### GoReplay file

Writes events in `.gor` format compatible with [GoReplay](https://goreplay.org/).

:::warning
This exporter only catches `TrafficCaptureEvent`. These events are generated when a route has the `capture` flag enabled. See [engine docs](../topics/engine.md).
:::
| Property | Type | Description |
|----------|------|-------------|
| `path` | string | File path |
| `maxFileSize` | number | Maximum file size |
| `captureRequests` | boolean | Capture HTTP requests |
| `captureResponses` | boolean | Capture HTTP responses |

### GoReplay S3

Same as GoReplay file but writes to an S3-compatible store.

| Property | Type | Description |
|----------|------|-------------|
| `s3` | object | S3 configuration |
| `maxFileSize` | number | Maximum file size |
| `captureRequests` | boolean | Capture HTTP requests |
| `captureResponses` | boolean | Capture HTTP responses |
| `preferBackendRequest` | boolean | Prefer backend request over client request |
| `preferBackendResponse` | boolean | Prefer backend response over client response |
| `methods` | array of string | HTTP methods to capture |

### TCP

Sends events over raw TCP connections.

| Property | Type | Description |
|----------|------|-------------|
| `host` | string | Target host |
| `port` | number | Target port |
| `unixSocket` | string | Unix socket path (alternative to host/port) |
| `connectTimeout` | number | Connection timeout (ms) |
| `tls` | object | TLS configuration |

### UDP

Sends events over UDP.

| Property | Type | Description |
|----------|------|-------------|
| `host` | string | Target host |
| `port` | number | Target port |
| `unixSocket` | string | Unix socket path (alternative to host/port) |
| `connectTimeout` | number | Connection timeout (ms) |

### Syslog

Sends events to a syslog server.

| Property | Type | Description |
|----------|------|-------------|
| `host` | string | Syslog server host |
| `port` | number | Syslog server port |
| `protocol` | string | Protocol: `tcp` or `udp` |
| `unixSocket` | string | Unix socket path |
| `connectTimeout` | number | Connection timeout (ms) |
| `tls` | object | TLS configuration (for TCP) |

### JMS

Sends events to a JMS (Java Message Service) queue or topic.

| Property | Type | Description |
|----------|------|-------------|
| `url` | string | JMS connection URL |
| `name` | string | Queue/topic name |
| `topic` | boolean | If `true`, sends to a topic; otherwise to a queue |
| `username` | string | JMS username |
| `password` | string | JMS password |

### PostgreSQL

Writes events to a PostgreSQL database.

| Property | Type | Description |
|----------|------|-------------|
| `uri` | string | Full connection URI (alternative to individual fields) |
| `host` | string | Database host |
| `port` | number | Database port |
| `database` | string | Database name |
| `user` | string | Username |
| `password` | string | Password |
| `schema` | string | Schema name |
| `table` | string | Table name |
| `poolSize` | number | Connection pool size |
| `ssl` | boolean | Enable SSL |

### Workflow

Routes events to an Otoroshi [workflow](./workflows.md) for custom processing.

| Property | Type | Description |
|----------|------|-------------|
| `ref` | string | Workflow ID to invoke |

### Console

Writes events to the standard output. No additional configuration needed.

### WASM

Processes events through a WebAssembly plugin.

| Property | Type | Description |
|----------|------|-------------|
| `wasmRef` | string | Reference to a [WASM plugin](./wasm-plugins.md) entity |
| `params` | object | Additional parameters passed to the WASM function |

### OTLP Metrics

Exports metrics using the OpenTelemetry Protocol (OTLP).

| Property | Type | Description |
|----------|------|-------------|
| `otlp` | object | OTLP endpoint configuration (URL, headers, protocol) |
| `tags` | object | Custom metric tags/labels |
| `metrics` | array of object | Metric definitions to export |

### OTLP Logs

Exports logs using the OpenTelemetry Protocol (OTLP).

| Property | Type | Description |
|----------|------|-------------|
| `otlp` | object | OTLP endpoint configuration (URL, headers, protocol) |

### Custom Metrics

Exposes custom metrics on the `/metrics` endpoint.

| Property | Type | Description |
|----------|------|-------------|
| `tags` | object | Custom metric labels |
| `metrics` | array of object | Metric definitions |

### Metrics

Rewrites metric labels exposed on the `/metrics` endpoint.

| Property | Type | Description |
|----------|------|-------------|
| `labels` | object | Map of existing label names to new label names |

### Mailer

Sends events as email using one of the following providers:

#### Console mailer

Writes emails to the standard output (for testing).

#### Generic mailer

| Property | Type | Description |
|----------|------|-------------|
| `url` | string | URL to push events |
| `headers` | object | HTTP headers |
| `to` | array of string | Recipient email addresses |

#### Mailgun

| Property | Type | Description |
|----------|------|-------------|
| `eu` | boolean | Use EU server (`api.eu.mailgun.net`) instead of US |
| `apiKey` | string | Mailgun API key |
| `domain` | string | Mailgun domain |
| `to` | array of string | Recipient email addresses |

#### Mailjet

| Property | Type | Description |
|----------|------|-------------|
| `publicKey` | string | Mailjet public API key |
| `privateKey` | string | Mailjet private API key |
| `to` | array of string | Recipient email addresses |

#### Sendgrid

| Property | Type | Description |
|----------|------|-------------|
| `apiKey` | string | Sendgrid API key |
| `to` | array of string | Recipient email addresses |

### Custom exporter

Allows using a custom exporter plugin. Create a plugin of type "exporter" on the plugins page, then select it here.

| Property | Type | Description |
|----------|------|-------------|
| `ref` | string | Reference to the custom exporter plugin |
| `config` | object | Exporter configuration |

## Admin API

```
GET    /api/data-exporter-configs           # List all data exporters
POST   /api/data-exporter-configs           # Create a data exporter
GET    /api/data-exporter-configs/:id       # Get a data exporter
PUT    /api/data-exporter-configs/:id       # Update a data exporter
DELETE /api/data-exporter-configs/:id       # Delete a data exporter
PATCH  /api/data-exporter-configs/:id       # Partially update a data exporter
```
