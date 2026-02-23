# Workflows

Workflows are a JSON-based orchestration engine that lets you build complex automation pipelines using a visual editor. A workflow is a directed graph of nodes that can call functions, transform data, handle conditions, iterate over collections, and more. Workflows can be executed manually, triggered via API, or scheduled as periodic jobs.

## UI page

You can find all workflows [here](http://otoroshi.oto.tools:8080/bo/dashboard/extensions/cloud-apim/workflows)

## Properties

* `id`: unique identifier of the workflow
* `name`: display name of the workflow
* `description`: description of the workflow
* `tags`: list of tags associated to the workflow
* `metadata`: list of metadata associated to the workflow
* `config`: the root node configuration of the workflow (a JSON object describing the node graph)
* `job`: optional job scheduling configuration (see below)
* `functions`: a map of custom reusable functions defined within the workflow
* `test_payload`: a JSON object used as input when testing the workflow from the UI
* `orphans`: disconnected nodes and edges stored by the visual editor
* `notes`: visual annotations placed on the workflow canvas in the editor

## Job configuration

A workflow can optionally be scheduled as a periodic job:

* `enabled`: is the job scheduling enabled
* `cron`: a cron expression defining when the workflow should run (e.g., `*/5 * * * * ?` for every 5 seconds)
* `config`: additional job configuration

## Node types

Workflows are composed of nodes. Each node has a type that determines its behavior:

* `workflow`: a sequential execution of child nodes
* `call`: invoke a built-in or custom function
* `assign`: assign a value to a variable in workflow memory
* `value`: return a static value
* `if`: conditional branching (if/then/else)
* `switch`: multi-path conditional branching based on value matching
* `foreach`: iterate over an array and execute a node for each element
* `map`: transform each element of an array
* `filter`: filter elements of an array based on a predicate
* `flatmap`: transform and flatten arrays
* `parallel`: execute multiple nodes in parallel
* `while`: loop while a condition is true
* `try`: try/catch/finally error handling
* `error`: raise an error
* `wait`: wait for a specified duration
* `pause`: pause workflow execution
* `end`: terminate the workflow
* `jump`: jump to another node in the workflow
* `async`: execute a node asynchronously
* `breakpoint`: debugging breakpoint in the visual editor

## Functions

Workflows provide a rich set of built-in functions that can be called from `call` nodes. These include:

* **HTTP**: `http_client` for making HTTP requests
* **Storage**: `store_get`, `store_set`, `store_del`, `store_keys`, `store_match` for persistent key-value storage
* **State**: `state_get`, `state_get_all` for accessing Otoroshi proxy state
* **Configuration**: `config_read` for reading Otoroshi configuration
* **Files**: `file_read`, `file_write`, `file_del` for file system operations
* **WASM**: `wasm_call` for calling WASM functions
* **Workflows**: `workflow_call` for calling other workflows
* **Events**: `emit_event` for emitting custom events
* **Email**: `send_mail` for sending emails
* **System**: `system_call` for executing system commands
* **Logging**: `log` for logging messages

You can also define custom functions in the `functions` map of the workflow entity.

## Learn more

For detailed information about node configuration, operators, expression syntax, and examples, see @ref:[Otoroshi Workflows](../topics/workflows.md).

For information about the visual editor, debugging tools, and breakpoints, see @ref:[Workflows Editor](../topics/workflows-editor.md).
