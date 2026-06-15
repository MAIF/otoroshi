---
title: Workflow input reference
sidebar_position: 32
---

# Workflow input reference

Every workflow run starts with an **input**: a JSON object that the caller hands to the workflow engine. The *shape* of that object is not fixed &mdash; it depends entirely on **where the workflow is triggered from** (a request plugin, a scheduled job, a data exporter, an authentication module, etc.). This page documents, for each launch context, exactly which fields you can expect in the input.

## How the input is exposed inside a workflow

When the engine runs a workflow, the input object is written into the workflow [memory](./workflows.md) under **two keys**, `workflow_input` and `input`:

```scala
wfRun.memory.set("workflow_input", input)
wfRun.memory.set("input", input)
```

Inside any node you can therefore reference input fields with expressions using either key:

```json
{
  "kind": "call",
  "function": "core.hello",
  "args": {
    "name": "${workflow_input.request.headers.x-user}"
  },
  "result": "res"
}
```

`${workflow_input.<path>}` and `${input.<path>}` are equivalent. `workflow_input` is the recommended form because it is stable: some nodes may rewrite the `input` memory slot during execution, whereas `workflow_input` always reflects the original input.

:::note
The tables below list the **top-level keys** of the input object for each context. Nested objects that are shared across several contexts (`request`, the typed body, `route`&hellip;) are described once in [Shared structures](#shared-structures).
:::

## Summary

| Launch context | Plugin / component | Top-level input keys |
|---|---|---|
| Route backend | `WorkflowBackend` | `snowflake`, `backend`, `apikey`, `user`, `raw_request`, `request`, `route`, `config`, `global_config`, `attrs` |
| Request transform | `WorkflowRequestTransformer` | `snowflake`, `raw_request`, `otoroshi_request`, `apikey`, `user`, `request`, `route`, `config`, `global_config`, `attrs` |
| Response transform | `WorkflowResponseTransformer` | `snowflake`, `raw_response`, `otoroshi_response`, `apikey`, `user`, `request`, `route`, `config`, `global_config`, `attrs` |
| Access control | `WorkflowAccessValidator` | `snowflake`, `apikey`, `user`, `request`, `route`, `config`, `global_config`, `attrs` |
| WebSocket message | `WorkflowWebsocketTransformer` | `snowflake`, `idx`, `request`, `otoroshi_request`, `target`, `route`, `config`, `attrs`, `action`, `message` |
| Resume a paused workflow | `WorkflowResumeBackend` / resume API | `query`, `body` (stored under the `resume_data` memory key) |
| Scheduled job | `WorkflowJob` | user-defined (the job `config` object) |
| Data exporter | `WorkflowCallSettings` exporter | `events`, `config` |
| Exporter custom filter | data exporter `customFilter` (kind `workflow`) | `event`, `config` |
| Exporter custom transform | data exporter `customTransform` (kind `workflow`) | `event`, `config` |
| Authentication | `WorkflowAuthModule` | `phase`, `input` |
| Nested call | `core.workflow_call` function | user-defined (the `input` argument) |
| Test / debug | admin UI editor & test endpoint | user-defined |

---

## Shared structures

Several contexts embed the same nested objects. They are described once here and referenced from each context below.

### The `request` object

Produced by `JsonHelpers.requestToJson` from the incoming client request. Present in every HTTP-triggered context.

```json
{
  "id": "1516789…",
  "method": "GET",
  "headers": { "Host": "api.foo.bar", "Accept": "application/json" },
  "cookies": [
    {
      "name": "session",
      "value": "…",
      "path": "/",
      "domain": "foo.bar",
      "http_only": true,
      "max_age": 3600,
      "secure": true,
      "same_site": "Lax"
    }
  ],
  "tls": true,
  "uri": "/api/users?page=1",
  "query": { "page": ["1"] },
  "path": "/api/users",
  "version": "HTTP/1.1",
  "has_body": false,
  "remote": "10.0.0.4",
  "client_cert_chain": null,
  "path_params": { "id": "42" }
}
```

| Field | Type | Description |
|---|---|---|
| `id` | string | Internal request id |
| `method` | string | HTTP method |
| `headers` | object | Header name &rarr; value (simple map) |
| `cookies` | array | Request cookies |
| `tls` | boolean | `true` when the connection is secured and trusted |
| `uri` | string | Full request URI (path + query) |
| `query` | object | Query parameter name &rarr; array of values |
| `path` | string | Request path |
| `version` | string | HTTP protocol version |
| `has_body` | boolean | Whether the request carries a body |
| `remote` | string | Client remote address |
| `client_cert_chain` | array \| null | Client certificate chain (mTLS), `null` if none |
| `path_params` | object | Path parameters extracted by the matched route |

### Body handling (`body_json` / `body_str` / `body_bytes`)

For contexts that read the request/response body (the backend, request and response transformers), the body is decoded according to its `Content-Type` and merged into the relevant object (`request` for the backend and request transformer, `otoroshi_response` for the response transformer). Exactly **one** of these keys is added:

| Content-Type | Key added | Type |
|---|---|---|
| `application/json` | `body_json` | parsed JSON |
| `application/xml`, `text/*` | `body_str` | string |
| anything else (binary) | `body_bytes` | array of byte values |

If the body is empty, none of these keys is added.

### `raw_request` vs `request` vs `otoroshi_request`

- `raw_request` &mdash; the **original** client request, untouched (`request` object shape above).
- `otoroshi_request` &mdash; the request **as Otoroshi will forward it** to the backend (after previous plugins ran): `url`, `method`, `headers`, `query`, `version`, `cookies`, `client_cert_chain`, `backend`.
- `otoroshi_response` &mdash; the response **as Otoroshi will return it**: `status`, `headers`, `cookies` (+ the decoded body in the response transformer).

### Other common keys

- `route` &mdash; the full JSON of the [NgRoute](../entities/routes.md) the workflow runs for.
- `apikey` &mdash; a light JSON view of the matched [API key](../entities/apikeys.mdx), or `null`.
- `user` &mdash; a light JSON view of the connected [user](../entities/auth-modules.md), or `null`.
- `config` &mdash; the configuration of the plugin instance that triggered the workflow (for the workflow plugins this mostly contains `ref` and `async`).
- `global_config` &mdash; the Otoroshi global configuration JSON.
- `attrs` &mdash; the JSON view of the per-request context attributes (`TypedMap`).
- `snowflake` &mdash; the unique id of the current request.

---

## HTTP traffic contexts

These workflows are attached to a route through a plugin and run while a request is being processed.

### Route backend &mdash; `WorkflowBackend`

The workflow *is* the backend: its `returned` value becomes the HTTP response (`status`, `headers`, body). Input built from `NgbBackendCallContext.jsonWithTypedBody`:

```json
{
  "snowflake": "1516…",
  "backend": { /* selected target */ },
  "apikey": null,
  "user": null,
  "raw_request": { /* request object */ },
  "request": {
    "url": "https://backend.svc/api/users",
    "method": "GET",
    "headers": { /* … */ },
    "query": { /* … */ },
    "version": "HTTP/1.1",
    "cookies": [],
    "client_cert_chain": null,
    "backend": { /* … */ },
    "body_json": { /* decoded request body, if any */ }
  },
  "route": { /* NgRoute JSON */ },
  "config": { "ref": "workflow_xxx", "async": false },
  "global_config": { /* … */ },
  "attrs": { /* … */ }
}
```

Here `request` is the **outgoing** request (`NgPluginHttpRequest`) with the decoded body merged in; `raw_request` is the original client request.

### Request transformer &mdash; `WorkflowRequestTransformer`

Runs before the call to the backend and rewrites the outgoing request (`method`, `url`, `headers`, `cookies`, body) from its `returned` value. Input from `NgTransformerRequestContext.jsonWithTypedBody`:

```json
{
  "snowflake": "1516…",
  "raw_request": { /* original request, NgPluginHttpRequest JSON */ },
  "otoroshi_request": { /* request to be forwarded */ },
  "apikey": null,
  "user": null,
  "request": {
    /* request object (client request) … */
    "body_json": { /* decoded body, if any */ }
  },
  "route": { /* NgRoute JSON */ },
  "config": { "ref": "workflow_xxx" },
  "global_config": { /* … */ },
  "attrs": { /* … */ }
}
```

The decoded body is merged into the `request` key.

### Response transformer &mdash; `WorkflowResponseTransformer`

Runs after the backend answered and rewrites the response (`status`, `headers`, `cookies`, body) from its `returned` value. Input from `NgTransformerResponseContext.jsonWithTypedBody`:

```json
{
  "snowflake": "1516…",
  "raw_response": { /* original response */ },
  "otoroshi_response": {
    "status": 200,
    "headers": { /* … */ },
    "cookies": [],
    "body_json": { /* decoded response body, if any */ }
  },
  "apikey": null,
  "user": null,
  "request": { /* request object */ },
  "route": { /* NgRoute JSON */ },
  "config": { "ref": "workflow_xxx" },
  "global_config": { /* … */ },
  "attrs": { /* … */ }
}
```

For this context the decoded body is merged into `otoroshi_response` (not `request`).

### Access control &mdash; `WorkflowAccessValidator`

Decides whether the request is allowed. It runs **before** the body is read, so no body fields are present. The workflow must `return` an object like `{ "result": true }` to allow, or `{ "result": false, "error": { "message": "…", "status": 403 } }` to deny. Input from `NgAccessContext.wasmJson`:

```json
{
  "snowflake": "1516…",
  "apikey": null,
  "user": null,
  "request": { /* request object */ },
  "route": { /* NgRoute JSON */ },
  "config": { "ref": "workflow_xxx" },
  "global_config": { /* … */ },
  "attrs": { /* … */ }
}
```

### WebSocket message &mdash; `WorkflowWebsocketTransformer`

Runs for each WebSocket frame, in both directions (a separate workflow can be configured for the incoming and the outgoing direction). Input is `NgWebsocketPluginContext.wasmJson` augmented with `action` and `message`:

```json
{
  "snowflake": "1516…",
  "idx": 0,
  "request": { /* request object */ },
  "otoroshi_request": { /* forwarded request */ },
  "target": { /* selected target */ },
  "route": { /* NgRoute JSON */ },
  "config": { /* … */ },
  "attrs": { /* … */ },
  "action": "incoming_message",
  "message": {
    "kind": "text",
    "payload": "the frame content"
  }
}
```

| Field | Description |
|---|---|
| `action` | direction of the frame: `"incoming_message"` or `"outgoing_message"` |
| `message.kind` | `"text"` or `"binary"` |
| `message.payload` | the frame content: a string for `text`, an array of bytes for `binary` |

### Resuming a paused workflow &mdash; `WorkflowResumeBackend` / resume API

A workflow can pause and be resumed later (see [pause &amp; resume](./workflows.md)). On resume, the **original** `workflow_input` is preserved, and the *resume payload* is written to a separate memory key, `resume_data`:

```json
{
  "query": { "approved": "true" },
  "body": { /* parsed JSON body of the resume call, if any */ }
}
```

`body` is present only when the resume call carries a body. Reference it from your workflow with `${resume_data.body…}` / `${resume_data.query…}`.

---

## Scheduled job &mdash; `WorkflowJob`

When a workflow is attached to a `WorkflowJob` (cron or interval), the input is the **job's own `config` object** (`rawConfig`), exactly as you fill it in the job configuration. There is no request, route or HTTP context.

```json
{
  // whatever you put in the job "config" field
  "environment": "prod",
  "batch_size": 100
}
```

If the job has no configuration, the input is an empty object `{}`.

---

## Data exporter contexts

A workflow can be plugged into the [data exporters](../entities/data-exporters.mdx) pipeline in three different roles.

### Workflow exporter &mdash; `WorkflowCallSettings`

The exporter forwards batches of events to a workflow. Input:

```json
{
  "events": [ { /* event 1 */ }, { /* event 2 */ } ],
  "config": { /* the DataExporterConfig JSON (id, name, enabled, tags, metadata, …) */ }
}
```

| Field | Type | Description |
|---|---|---|
| `events` | array | The batch of analytics/audit events to export |
| `config` | object | The data exporter configuration |

### Exporter custom filter (kind `workflow`)

When a data exporter uses a workflow as its `customFilter`, the workflow is called **once per event** and must `return` a boolean (`true` to keep the event, `false` to drop it), either directly or as `{ "result": true }`. Input:

```json
{
  "event": { /* the single event being filtered */ },
  "config": { /* the customFilter config */ }
}
```

### Exporter custom transform (kind `workflow`)

When a data exporter uses a workflow as its `customTransform`, the workflow is called **once per event** and must `return` the transformed event (the returned `event` field, or the whole returned object). Input:

```json
{
  "event": { /* the single event being transformed */ },
  "config": { /* the customTransform config */ }
}
```

:::note
For the custom filter / transform, `config` is the configuration of the filter/transform itself, **not** the whole exporter configuration (which is what the [Workflow exporter](#workflow-exporter--workflowcallsettings) receives).
:::

---

## Authentication module &mdash; `WorkflowAuthModule`

A workflow can implement a custom authentication provider. It is invoked at several **phases** of the login/logout flow, and the input always has the same envelope:

```json
{
  "phase": "pa_callback",
  "input": { /* phase-specific payload (see below) */ }
}
```

The `phase` value tells the workflow which step of the flow is being handled. The nested `input` object depends on the phase:

| `phase` | Triggered for | `input` keys |
|---|---|---|
| `pa_login_page` | private apps &mdash; render the login page | `request`, `global_config`, `service`, `route`, `is_route` |
| `pa_logout` | private apps &mdash; logout | `request`, `global_config`, `service`, `route`, `user` |
| `pa_callback` | private apps &mdash; auth callback | `request`, `global_config`, `service`, `route` |
| `bo_login_page` | back-office &mdash; render the login page | `request`, `global_config` |
| `bo_logout` | back-office &mdash; logout | `request`, `global_config`, `user` |
| `bo_callback` | back-office &mdash; auth callback | `request`, `global_config` |

Example for the `pa_login_page` phase:

```json
{
  "phase": "pa_login_page",
  "input": {
    "request": { /* request object */ },
    "global_config": { /* … */ },
    "service": { /* legacy ServiceDescriptor JSON */ },
    "route": { /* NgRoute JSON */ },
    "is_route": true
  }
}
```

Depending on the phase, the workflow is expected to `return` a rendered response, a `logout_url`, or a user object (`PrivateAppsUser` / `BackOfficeUser` shape) on the callback phases.

---

## Nested call &mdash; `core.workflow_call` function

A workflow can call another workflow with the `core.workflow_call` function. The callee's input is **whatever you pass** in the `input` argument (an empty object if omitted):

```json
{
  "kind": "call",
  "function": "core.workflow_call",
  "args": {
    "workflow_id": "other_workflow",
    "input": {
      "foo": "bar"
    }
  },
  "result": "sub_res"
}
```

In `other_workflow`, `${workflow_input.foo}` resolves to `"bar"`.

---

## Test &amp; debug &mdash; admin UI editor and test endpoint

When you run a workflow from the [workflows editor](./workflows-editor.mdx) (or through the `POST /extensions/workflows/_test` back-office endpoint), the input is the **`input` field you provide in the test payload** &mdash; an arbitrary JSON object you control:

```json
{
  "workflow_id": "my_workflow",
  "workflow": { /* the workflow definition */ },
  "functions": { /* optional custom functions */ },
  "input": "{ \"name\": \"foo\" }"
}
```

The `input` field is sent as a JSON-encoded string and parsed into the object handed to the workflow. This is the place to reproduce, by hand, any of the input shapes documented above when designing or debugging a workflow.
