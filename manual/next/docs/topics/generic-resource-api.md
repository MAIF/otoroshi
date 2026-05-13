---
sidebar_position: 33
---

# Generic Admin API (`/apis/...`)

Most entities managed by Otoroshi (routes, API keys, certificates, auth modules, JWT verifiers, data exporters, custom extension entities, …) are exposed through a single, uniform, resource-oriented HTTP API rooted at `/apis/`. The dashboard itself is a regular consumer of that API.

This page documents that API surface in depth: URL layout, supported features and the query string options that work uniformly across all resources.

If you are looking for the high-level overview of the admin API or for the OpenAPI descriptor, see [Admin REST API](../api.md).

## Resource model

Every entity managed by Otoroshi is exposed as a **resource** identified by:

| Field          | Description                                                                | Example                |
|----------------|----------------------------------------------------------------------------|------------------------|
| `group`        | API group, similar to a Kubernetes API group                               | `proxy.otoroshi.io`    |
| `version`      | API version (`v1`, `v2`, …). A version may be `served`, `deprecated` and/or be the storage version | `v1`                   |
| `kind`         | Resource kind (singular, PascalCase)                                       | `Route`, `ApiKey`      |
| `pluralName`   | Plural form used in URLs                                                   | `routes`, `apikeys`    |
| `singularName` | Singular form used in URLs and audit messages                              | `route`, `apikey`      |

Resources are auto-registered: built-in entities are exposed by default and any [admin extension](./admin-extensions.md) can register additional resources that immediately get the same admin API surface.

You can list all resources currently served by an instance — including their JSON schema — at:

```
GET /apis/entities
GET /apis/entities?schema=false   # without JSON Schemas (lighter response)
```

The response contains the Otoroshi version and an array of resource descriptors (group, version, kind, plural / singular name, schema).

## URL conventions

All generic endpoints live under `/apis/{group}/{version}/{pluralName}` and follow this layout:

| Method   | URL                                              | Purpose                                                |
|----------|--------------------------------------------------|--------------------------------------------------------|
| `GET`    | `/apis/{group}/{version}/{entity}`               | List all resources of this kind                        |
| `POST`   | `/apis/{group}/{version}/{entity}`               | Create a new resource                                  |
| `DELETE` | `/apis/{group}/{version}/{entity}`               | Delete all resources matching the caller's permissions |
| `GET`    | `/apis/{group}/{version}/{entity}/{id}`          | Read a single resource                                 |
| `POST`   | `/apis/{group}/{version}/{entity}/{id}`          | Upsert (create if missing, update otherwise)           |
| `PUT`    | `/apis/{group}/{version}/{entity}/{id}`          | Full update (resource must exist)                      |
| `PATCH`  | `/apis/{group}/{version}/{entity}/{id}`          | Partial update (see [Patch formats](#patch-formats))   |
| `DELETE` | `/apis/{group}/{version}/{entity}/{id}`          | Delete a single resource                               |
| `GET`    | `/apis/{group}/{version}/{entity}/_count`        | Count visible resources                                |
| `GET`    | `/apis/{group}/{version}/{entity}/_template`     | Default template for a new resource                    |
| `GET`    | `/apis/{group}/{version}/{entity}/_schema`       | JSON Schema for the resource                           |
| `POST`   | `/apis/{group}/{version}/{entity}/_bulk`         | Bulk create (NDJSON)                                   |
| `PUT`    | `/apis/{group}/{version}/{entity}/_bulk`         | Bulk update (NDJSON)                                   |
| `PATCH`  | `/apis/{group}/{version}/{entity}/_bulk`         | Bulk patch (NDJSON)                                    |
| `DELETE` | `/apis/{group}/{version}/{entity}/_bulk`         | Bulk delete (NDJSON)                                   |

`{entity}` accepts both the resource `pluralName` and its `kind` (e.g. `routes` or `Route`). `{group}` and `{version}` accept the literal value `any` (or `all`) as a wildcard, useful for tooling that doesn't care about a specific version.

Each operation goes through:

1. **Authentication** — admin API key (`Authorization: Basic`) or backoffice session.
2. **Per-operation authorization** — a resource exposes flags `canRead`, `canCreate`, `canUpdate`, `canDelete`, `canBulk`. Disallowed operations return `401 Unauthorized`.
3. **Multi-tenancy / RBAC** — the response is filtered to the resources the caller is allowed to read; write operations are refused if the caller cannot write the targeted entity.
4. **Validation** — for write operations, the body is validated against the resource JSON Schema (auto-generated by reflection) and any custom write validation hook.
5. **Audit** — every mutation produces an `AdminApiEvent` and most produce a typed `Alert` (e.g. `RouteCreated`, `ApiKeyDeleted`).

## Authentication

```
GET /apis/proxy.otoroshi.io/v1/routes
Authorization: Basic <base64(client_id:client_secret)>
```

Credentials are a backoffice admin API key (managed in the dashboard or via the API itself). When called from the dashboard, a session cookie is used instead.

## Content negotiation

The admin API negotiates request and response formats using the standard `Content-Type` and `Accept` headers.

### Request bodies

| `Content-Type`                          | Behaviour                                                                                          |
|-----------------------------------------|----------------------------------------------------------------------------------------------------|
| `application/json`                      | Standard JSON body                                                                                 |
| `application/yaml`                      | YAML body. If the body looks Kubernetes-armored (`apiVersion` + `spec`), the `spec` is unwrapped automatically and `metadata.name` / `spec.name` is used as the entity name |
| `application/json+oto-patch`            | Compact "dot-path" patch — see [Patch formats](#patch-formats)                                     |
| `application/x-www-form-urlencoded`     | Form-encoded body using dot-paths (`a.b.c=value`)                                                  |
| `application/x-ndjson`                  | Required for `_bulk` endpoints — one JSON object per line                                          |

Any other content type returns `400 bad_content_type`.

### Response formats

The response format is selected from the `Accept` header (the first acceptable type wins):

| `Accept`                            | Response                                                                            |
|-------------------------------------|-------------------------------------------------------------------------------------|
| `application/json` (default)        | Standard JSON                                                                       |
| `application/yaml` / `application/yml` | YAML representation of the entity (with a `kind` field added)                    |
| `application/yaml+k8s` / `application/yml+k8s` | Kubernetes-style envelope: `apiVersion`, `kind`, `metadata.name`, `spec` |
| `application/x-ndjson`              | One JSON object per line — only applicable when listing                             |

Responses are gzipped automatically (except `x-ndjson`). YAML + Kubernetes-style is convenient when piping responses into `kubectl apply -f -` against a cluster running the Otoroshi CRDs.

When negotiating JSON, two query parameters tweak the output:

| Query string   | Default         | Effect                                                                |
|----------------|-----------------|-----------------------------------------------------------------------|
| `pretty`       | `true`          | `pretty=true` forces pretty-printing, `pretty=false` returns compact JSON. Default is controlled by `otoroshi.options.defaultPrettyAdminApi` |
| `envelope`     | `false`         | `envelope=true` wraps the payload in `{ "data": ... }`                |

A `kind` field is added to every JSON / YAML entity in the response, in the form `pluralName.group/version` (e.g. `routes.proxy.otoroshi.io/v1`). This makes individual responses self-describing.

## Listing — filtering, sorting, pagination, projection

`GET /apis/{group}/{version}/{entity}` accepts a rich set of query parameters that are evaluated **server-side** in the following order: **filter → sort → paginate → project**.

### Filtering

There are two filtering modes that can be combined.

#### `filter.<field>=<value>` — exact match

`filter.<path>=<value>` keeps entities whose value at `path` matches exactly. The `path` syntax determines how the value is looked up:

| Syntax                             | Lookup                                                       |
|------------------------------------|--------------------------------------------------------------|
| `filter.name=foo`                  | Top-level field                                              |
| `filter.metadata.team=payments`    | Dotted path (`a.b.c`)                                        |
| `filter./spec/enabled=true`        | JSON Pointer (RFC 6901) when the path contains `/`           |
| `filter.$.path.to.field=value`     | JSONPath when the key starts with `$` and contains a dot     |

Filters apply to the following JSON types:

- strings (exact equality)
- booleans (`true` / `false`)
- numbers
- arrays (matches if any element equals or contains the value)

All `filter.*` parameters are AND-ed.

```
GET /apis/proxy.otoroshi.io/v1/routes?filter.enabled=true&filter.metadata.team=payments
```

#### `filtered=<field>:<value>,...` — substring search

The `filtered` parameter applies a **case-insensitive substring search** over one or more fields. Multiple criteria are comma-separated, multiple alternatives for the same field are pipe-separated:

```
# routes whose name contains "user" AND whose description contains "internal"
GET /apis/proxy.otoroshi.io/v1/routes?filtered=name:user,description:internal

# routes whose name contains "user" OR "admin"
GET /apis/proxy.otoroshi.io/v1/routes?filtered=name:user|admin
```

This is the mode used by the dashboard's search boxes.

### Sorting

`sorted=<field>:<reverse>,...`, where `<reverse>` is a boolean (`true` to reverse, `false` for natural order). Multiple sort fields are applied in order. Numbers are sorted numerically, everything else lexicographically (case-insensitive).

```
GET /apis/proxy.otoroshi.io/v1/routes?sorted=name:false
GET /apis/proxy.otoroshi.io/v1/routes?sorted=metadata.priority:true,name:false
```

### Pagination

| Query string | Default        | Description                              |
|--------------|----------------|------------------------------------------|
| `page`       | `1`            | 1-based page index                       |
| `pageSize`   | unlimited      | Page size (number of items per page)     |

The response includes an `X-Pages` header with the total number of pages so clients can build pagination UIs without an extra count call.

```
GET /apis/proxy.otoroshi.io/v1/routes?page=2&pageSize=50
```

### Projection — `fields`

`fields=<f1>,<f2>,...` returns only the requested fields, reducing payload size. Nested fields are supported using the same path syntax as filters.

```
GET /apis/proxy.otoroshi.io/v1/routes?fields=id,name,frontend.domains
```

When listing, projection is applied to each item; on a single-resource response, projection is applied to the object.

### Combined example

```
GET /apis/proxy.otoroshi.io/v1/routes
    ?filter.enabled=true
    &filtered=name:public
    &sorted=name:false
    &page=1
    &pageSize=20
    &fields=id,name,frontend.domains
    &envelope=true
    &pretty=false
```

### `in_mem` shortcut

`in_mem=true` reads entities from the local in-memory state instead of the datastore. This is significantly faster when the dataset is large but only sees the local node's view; for cluster-wide reads, leave it off.

```
GET /apis/proxy.otoroshi.io/v1/routes/_count?in_mem=true
```

## Patch formats

`PATCH` accepts three different body formats, distinguished by `Content-Type`:

### `application/json` — JSON Patch (RFC 6902)

Standard JSON Patch — an array of `{op, path, value}` operations.

```http
PATCH /apis/proxy.otoroshi.io/v1/routes/route_xxx
Content-Type: application/json

[
  { "op": "replace", "path": "/enabled", "value": false },
  { "op": "add", "path": "/metadata/owner", "value": "team-payments" }
]
```

### `application/json+oto-patch` — dot-path patch

A compact format Otoroshi understands: an array of `{path, value}` objects where `path` uses dot notation. The current resource is used as the default base, allowing very small patches:

```http
PATCH /apis/proxy.otoroshi.io/v1/routes/route_xxx
Content-Type: application/json+oto-patch

[
  { "path": "enabled", "value": "false" },
  { "path": "metadata.owner", "value": "team-payments" }
]
```

String values are auto-coerced (`"true"` / `"false"` → boolean, numeric strings → number, `{...}` / `[...]` → object/array, `"null"` → null).

### `application/x-www-form-urlencoded` — form-encoded patch

Same dot-path semantics but expressed as a form body, useful for HTML forms:

```http
PATCH /apis/proxy.otoroshi.io/v1/routes/route_xxx
Content-Type: application/x-www-form-urlencoded

enabled=false&metadata.owner=team-payments
```

## Bulk operations

Bulk endpoints stream **NDJSON** (newline-delimited JSON) in both directions, allowing very large operations without loading everything in memory.

| Method   | URL                                            | Body line                                       | Output line                                                                  |
|----------|------------------------------------------------|-------------------------------------------------|------------------------------------------------------------------------------|
| `POST`   | `/apis/{group}/{version}/{entity}/_bulk`       | A full entity                                   | `{status, created, id, id_field}` or `{status, error, error_description, entity}` |
| `PUT`    | `/apis/{group}/{version}/{entity}/_bulk`       | A full entity (must exist)                      | `{status, updated, id, id_field}` or error line                              |
| `PATCH`  | `/apis/{group}/{version}/{entity}/_bulk`       | `{ id: "...", patch: [ ...JSON Patch ops... ] }` | `{status, updated, id, id_field}` or error line                             |
| `DELETE` | `/apis/{group}/{version}/{entity}/_bulk`       | `{ id: "..." }`                                 | `{status, deleted, id, id_field}` or error line                              |

All bulk endpoints require `Content-Type: application/x-ndjson`.

The query parameter `_group=<n>` (1-9) controls how many entries are processed concurrently. Default is `1` (sequential).

```http
POST /apis/proxy.otoroshi.io/v1/routes/_bulk
Content-Type: application/x-ndjson

{"id":"route_a","name":"a","frontend":{...},"backend":{...}}
{"id":"route_b","name":"b","frontend":{...},"backend":{...}}
```

Response (also NDJSON):

```
{"status":201,"created":true,"id":"route_a","id_field":"id"}
{"status":400,"error":"bad_entity","error_description":"entity already exists","entity":{...}}
```

A bulk endpoint never returns a single `4xx` for the whole stream — each line carries its own status, so partial successes are normal.

## Templates and schemas

Two helpers are available to build clients, forms or generators:

- `GET /apis/{group}/{version}/{entity}/_template` returns a sensible default JSON for the resource. Query parameters are forwarded to the template builder, allowing customisation (e.g. `?domain=api.example.com`).
- `GET /apis/{group}/{version}/{entity}/_schema` returns the JSON Schema for the resource (auto-generated by reflection unless overridden).
- `GET /apis/entities` returns the full catalog of resources with their schemas.

## Response headers

| Header                       | Where         | Meaning                                                                          |
|------------------------------|---------------|----------------------------------------------------------------------------------|
| `X-Pages`                    | List endpoints | Total number of pages, given the current `pageSize`. `-1` for non-paginated      |
| `Otoroshi-Api-Deprecated`    | Any           | `yes` if the requested resource version is marked deprecated. Plan your migration |
| `Otoroshi-Entity-Updated`    | `POST /:id`   | `true` / `false` — whether the upsert actually changed the stored entity         |

## Status codes

| Code | When                                                                                |
|------|-------------------------------------------------------------------------------------|
| 200  | Successful read, update, delete                                                     |
| 201  | Successful create (`POST /apis/{group}/{version}/{entity}`)                         |
| 204  | `DELETE /apis/{group}/{version}/{entity}` (delete all)                              |
| 400  | Validation error, unsupported content type, bad request body                        |
| 401  | Authentication missing / invalid, or operation forbidden by RBAC or resource flags  |
| 404  | Resource (kind or `id`) not found                                                   |
| 5xx  | Storage / unexpected internal errors                                                |

Errors are always JSON objects of the form:

```json
{
  "error": "bad_request",
  "error_description": "<details>"
}
```

Validation errors include the list of failed validators in `error_description`.

## Examples

### List with pagination and filtering

```bash
curl -u "$ID:$SECRET" \
  "http://otoroshi-api.oto.tools:8080/apis/proxy.otoroshi.io/v1/routes\
?filter.enabled=true\
&filtered=name:public\
&sorted=name:false\
&page=1&pageSize=20\
&fields=id,name,frontend.domains"
```

### Create from YAML

```bash
curl -u "$ID:$SECRET" \
  -H "Content-Type: application/yaml" \
  --data-binary @route.yaml \
  http://otoroshi-api.oto.tools:8080/apis/proxy.otoroshi.io/v1/routes
```

### Patch a single field

```bash
curl -u "$ID:$SECRET" -X PATCH \
  -H "Content-Type: application/json+oto-patch" \
  -d '[{"path":"enabled","value":"false"}]' \
  http://otoroshi-api.oto.tools:8080/apis/proxy.otoroshi.io/v1/routes/route_xxx
```

### Bulk create from a file

```bash
curl -u "$ID:$SECRET" -X POST \
  -H "Content-Type: application/x-ndjson" \
  --data-binary @routes.ndjson \
  "http://otoroshi-api.oto.tools:8080/apis/proxy.otoroshi.io/v1/routes/_bulk?_group=4"
```

### Export to Kubernetes YAML

```bash
curl -u "$ID:$SECRET" \
  -H "Accept: application/yaml+k8s" \
  http://otoroshi-api.oto.tools:8080/apis/proxy.otoroshi.io/v1/routes/route_xxx
```

## Other top-level `/apis/` endpoints

A few non-resource endpoints complete the `/apis/` surface:

| Endpoint                                | Description                                                                          |
|-----------------------------------------|--------------------------------------------------------------------------------------|
| `GET /apis/health`                      | Health probe — `200` when healthy, `503` otherwise                                   |
| `GET /apis/metrics?format=&filter=`     | Metrics — JSON or Prometheus depending on `Accept` (`application/prometheus`) or `format` |
| `GET /apis/cluster`                     | Cluster topology and members                                                         |
| `GET /apis/cluster/node/{infos,version,health,metrics}` | Per-node introspection endpoints                                     |
| `GET /apis/entities`                    | Catalog of registered resources (with optional JSON Schemas)                         |

For the OpenAPI descriptor and the `/apis/openapi*`, `/apis/workflows/doc*`, `/apis/plugins.*` documentation endpoints (and how to protect them with a shared secret), see [Admin REST API](../api.md).
