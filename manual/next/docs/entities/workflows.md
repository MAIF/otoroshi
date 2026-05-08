---
title: Workflows
sidebar_position: 22
---
# Workflows

## Overview

Managing a modern API infrastructure often involves repetitive, multi-step operations that go beyond simple request routing: rotating certificates before they expire, cleaning up unused API keys, synchronizing configuration across environments, or orchestrating health checks with automated incident response. Traditionally, these tasks require stitching together external orchestration platforms such as Apache Airflow, n8n, or custom cron scripts, adding operational complexity and another system to maintain.

Otoroshi Workflows bring these automation capabilities directly into the API gateway. A workflow is a JSON-based directed graph of nodes, where each node represents a discrete operation: calling a function, evaluating a condition, iterating over a collection, transforming data, or controlling execution flow. Workflows are designed and edited through a built-in visual editor in the Otoroshi admin UI, making it possible to build, test, and debug complex automation pipelines without leaving the gateway.

### How workflows work

A workflow is structured as a tree of **nodes**. Execution starts at the root node (typically a `workflow` node that contains a sequential list of steps) and progresses through the graph. Each node produces a result that is stored in the workflow's shared **memory**, where subsequent nodes can reference it using expression syntax (e.g., `${nodes.fetch_data.response.body}`). The three primary building blocks are:

- **Nodes** -- the execution units that form the workflow steps. Control flow nodes (`if`, `switch`, `foreach`, `while`, `try`, `parallel`) direct the execution path, while data nodes (`call`, `assign`, `value`) perform work and store results.
- **Functions** -- reusable logic units invoked by `call` nodes. Otoroshi ships with built-in functions for HTTP requests, key-value storage, proxy state access, file I/O, email, WASM execution, event emission, and more. You can also define custom functions within the workflow itself or register them via Scala plugins.
- **Operators** -- data transformation helpers (prefixed with `$`) that let you manipulate JSON values inline: array operations (`$array_append`, `$array_at`, `$projection`), string operations (`$str_concat`, `$str_replace`, `$str_upper_case`), comparisons (`$eq`, `$gt`, `$contains`), arithmetic (`$add`, `$multiply`, `$incr`), encoding (`$encode_base64`, `$basic_auth`), and JQ expressions (`$jq`).

### Execution modes

Workflows support three execution modes:

- **Manual trigger via API** -- execute a workflow on demand by calling the admin API endpoint, useful for one-off operations or integration with external systems.
- **Scheduled as periodic jobs** -- attach a cron expression or interval to a workflow so it runs automatically on a recurring schedule, with configurable instantiation (per cluster node or singleton).
- **Event-driven** -- workflows can be used as route backends or request/response transformers through dedicated plugins (`WorkflowBackend`, `WorkflowRequestTransformer`, `WorkflowResponseTransformer`), executing in response to incoming HTTP traffic.

Workflows also support **pause and resume**: a running workflow can pause its execution at any point, persist its state, and be resumed later via an API call with new input data. This enables human-in-the-loop approval patterns and long-running processes.

### Use cases

- **Automated certificate rotation** -- periodically check certificate expiration dates and trigger renewal before they expire.
- **API key cleanup** -- scan for unused or expired API keys and deactivate or remove them on a schedule.
- **Health check orchestration** -- iterate over all backends, perform health checks, and emit alerts or trigger failover when backends are unresponsive.
- **Data synchronization** -- pull configuration or data from external systems and synchronize it with Otoroshi entities.
- **Incident response automation** -- detect anomalies through health checks or metrics, then automatically notify teams via email, update route configurations, or scale backends.
- **Dynamic API composition** -- use workflows as route backends to aggregate data from multiple upstream services into a single response.

## UI page

You can find all workflows [here](http://otoroshi.oto.tools:8080/bo/dashboard/extensions/cloud-apim/workflows)

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `id` | string | Unique identifier of the workflow |
| `name` | string | Display name of the workflow |
| `description` | string | Description of the workflow |
| `tags` | array of string | Tags associated to the workflow |
| `metadata` | object | Key/value metadata associated to the workflow |
| `config` | object | The root node configuration (JSON object describing the node graph) |
| `job` | object | Optional job scheduling configuration (see below) |
| `functions` | object | Map of custom reusable functions defined within the workflow |
| `test_payload` | object | JSON object used as input when testing the workflow from the UI |
| `orphans` | object | Disconnected nodes and edges stored by the visual editor |
| `notes` | array | Visual annotations placed on the workflow canvas in the editor |

## Job configuration

A workflow can optionally be scheduled as a periodic job:

| Property | Type | Description |
|----------|------|-------------|
| `enabled` | boolean | Whether the job scheduling is enabled |
| `cron` | string | A cron expression defining when the workflow should run (e.g., `*/5 * * * * ?` for every 5 seconds) |
| `config` | object | Additional job configuration |

## Node types

Workflows are composed of nodes. Each node has a type that determines its behavior:

### Control flow

| Node type | Description |
|-----------|-------------|
| `workflow` | Sequential execution of child nodes |
| `if` | Conditional branching (if/then/else) |
| `switch` | Multi-path conditional branching based on value matching |
| `foreach` | Iterate over an array and execute a node for each element |
| `map` | Transform each element of an array |
| `filter` | Filter elements of an array based on a predicate |
| `flatmap` | Transform and flatten arrays |
| `parallel` | Execute multiple nodes in parallel |
| `while` | Loop while a condition is true |
| `try` | Try/catch/finally error handling |
| `jump` | Jump to another node in the workflow |
| `async` | Execute a node asynchronously |

### Data

| Node type | Description |
|-----------|-------------|
| `call` | Invoke a built-in or custom function |
| `assign` | Assign a value to a variable in workflow memory |
| `value` | Return a static value |

### Execution control

| Node type | Description |
|-----------|-------------|
| `error` | Raise an error |
| `wait` | Wait for a specified duration |
| `pause` | Pause workflow execution |
| `end` | Terminate the workflow |
| `breakpoint` | Debugging breakpoint in the visual editor |

## Built-in functions

Workflows provide a rich set of built-in functions that can be called from `call` nodes:

| Category | Functions | Description |
|----------|-----------|-------------|
| **HTTP** | `http_client` | Make HTTP requests to any endpoint |
| **Storage** | `store_get`, `store_set`, `store_del`, `store_keys`, `store_match` | Persistent key-value storage operations |
| **State** | `state_get`, `state_get_all` | Access Otoroshi proxy state (routes, backends, certificates, etc.) |
| **Configuration** | `config_read` | Read Otoroshi global configuration |
| **Files** | `file_read`, `file_write`, `file_del` | File system operations |
| **WASM** | `wasm_call` | Call WebAssembly functions |
| **Workflows** | `workflow_call` | Call other workflows (composition) |
| **Events** | `emit_event` | Emit custom events to the data exporter pipeline |
| **Email** | `send_mail` | Send emails |
| **System** | `system_call` | Execute system commands |
| **Logging** | `log` | Log messages to the Otoroshi logger | 

You can also define custom functions in the `functions` map of the workflow entity.

## JSON example

Here is a simple workflow that fetches data from an HTTP endpoint and logs the result:

```json
{
  "id": "workflow_fetch_and_log",
  "name": "Fetch and log",
  "description": "Fetches data from an API and logs the response",
  "tags": ["example"],
  "metadata": {},
  "config": {
    "type": "workflow",
    "nodes": [
      {
        "type": "call",
        "id": "fetch_data",
        "function": "http_client",
        "args": {
          "url": "https://api.example.com/data",
          "method": "GET",
          "headers": {
            "Accept": "application/json"
          }
        }
      },
      {
        "type": "call",
        "id": "log_result",
        "function": "log",
        "args": {
          "message": "${nodes.fetch_data.response.body}"
        }
      }
    ]
  },
  "job": {
    "enabled": false,
    "cron": "0 */5 * * * ?",
    "config": {}
  },
  "functions": {},
  "test_payload": {},
  "orphans": { "nodes": [], "edges": [] },
  "notes": []
}
```

## Scheduled workflow example

A workflow that periodically checks the health of all backends:

```json
{
  "id": "workflow_health_check",
  "name": "Periodic health check",
  "description": "Checks the health of all backends every minute",
  "tags": ["monitoring"],
  "metadata": {},
  "config": {
    "type": "workflow",
    "nodes": [
      {
        "type": "call",
        "id": "get_backends",
        "function": "state_get_all",
        "args": {
          "entity": "backends"
        }
      },
      {
        "type": "foreach",
        "id": "check_each",
        "array": "${nodes.get_backends.result}",
        "node": {
          "type": "call",
          "function": "http_client",
          "args": {
            "url": "${item.backend.targets[0].hostname}",
            "method": "GET",
            "timeout": 5000
          }
        }
      }
    ]
  },
  "job": {
    "enabled": true,
    "cron": "0 * * * * ?",
    "config": {}
  }
}
```

## Learn more

For detailed information about node configuration, operators, expression syntax, and examples, see [Otoroshi Workflows](../topics/workflows.md).

For information about the visual editor, debugging tools, and breakpoints, see [Workflows Editor](../topics/workflows-editor.mdx).
