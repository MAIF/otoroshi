# Workflows

Workflows are a JSON-based orchestration engine that lets you build complex automation pipelines using a visual editor. A workflow is a directed graph of nodes that can call functions, transform data, handle conditions, iterate over collections, and more. Workflows can be executed manually, triggered via API, or scheduled as periodic jobs.

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

For detailed information about node configuration, operators, expression syntax, and examples, see @ref:[Otoroshi Workflows](../topics/workflows.md).

For information about the visual editor, debugging tools, and breakpoints, see @ref:[Workflows Editor](../topics/workflows-editor.md).
