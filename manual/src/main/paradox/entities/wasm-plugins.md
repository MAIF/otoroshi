# WASM Plugins

WASM Plugins are reusable WebAssembly plugin configurations that can be referenced by multiple routes. Instead of configuring WASM settings inline on each route, you can create a WASM plugin entity and reference it by ID, making it easier to manage and share WASM logic across your infrastructure.

## UI page

You can find all WASM plugins [here](http://otoroshi.oto.tools:8080/bo/dashboard/wasm-plugins)

## Properties

* `id`: unique identifier of the WASM plugin
* `name`: display name of the plugin
* `description`: description of the plugin
* `tags`: list of tags associated to the plugin
* `metadata`: list of metadata associated to the plugin
* `steps`: the list of proxy engine steps where this plugin will be applied (see below)
* `config`: the WASM plugin configuration (see below)

## Steps

The `steps` field defines at which points in the proxy engine pipeline this WASM plugin can be used:

* `MatchRoute`: custom route matching logic
* `Router`: custom routing decision
* `Sink`: handle requests that don't match any route
* `PreRoute`: request enrichment before routing
* `ValidateAccess`: access control validation
* `TransformRequest`: modify the request before forwarding to backend
* `TransformResponse`: modify the response before sending to client
* `HandlesRequest`: handle the complete request/response lifecycle (acts as a backend)
* `CallBackend`: custom backend call logic
* `HandlesTunnel`: handle tunnel connections
* `Job`: periodic job execution

## Configuration

* `source`: the WASM source definition (see source types below)
* `memoryPages`: number of memory pages allocated to the WASM VM (default: 50)
* `functionName`: the name of the function to invoke in the WASM module (optional, defaults to the step-specific function name)
* `config`: a key-value map of configuration passed to the WASM plugin at runtime
* `instances`: number of concurrent WASM VM instances (default: 1)
* `wasi`: enable WebAssembly System Interface support (default: false)
* `opa`: enable Open Policy Agent mode for policy evaluation (default: false)
* `httpWasm`: enable HTTP WASM mode (default: false)
* `allowedHosts`: list of hostnames the WASM plugin is allowed to call via HTTP
* `allowedPaths`: map of file paths the WASM plugin is allowed to access
* `killOptions`: VM termination options for resource management
* `authorizations`: fine-grained access control for the WASM plugin (see below)

## Source types

The `source` field defines where the WASM module is loaded from:

* `base64`: the WASM binary encoded as a base64 string
* `file`: a local file path pointing to a `.wasm` file
* `http`: an HTTP/HTTPS URL to download the WASM module from
* `wasmo`: a reference to a WASM module compiled and managed by a Wasmo instance
* `local`: a reference to another WASM plugin entity by ID

## Authorizations

The `authorizations` field controls what the WASM plugin is allowed to access at runtime:

* `httpAccess`: allow the plugin to make outbound HTTP calls (default: false)
* `proxyHttpCallTimeout`: timeout in milliseconds for HTTP calls made by the plugin (default: 5000)
* `globalDataStoreAccess`: read/write rights to the global persistent key-value store
* `pluginDataStoreAccess`: read/write rights to the plugin-scoped persistent key-value store
* `globalMapAccess`: read/write rights to the global in-memory store
* `pluginMapAccess`: read/write rights to the plugin-scoped in-memory store
* `proxyStateAccess`: allow reading the current Otoroshi proxy state (default: false)
* `configurationAccess`: allow reading the Otoroshi configuration (default: false)

Each data store access is defined as `{ "read": true/false, "write": true/false }`.

## Learn more

For a detailed overview of WASM in Otoroshi, available plugin types, and how they integrate with the proxy engine, see @ref:[Otoroshi and WASM](../topics/wasm-usage.md).

For practical guides:

* @ref:[Install a Wasmo](../how-to-s/wasmo-installation.md) to set up a Wasmo instance for compiling WASM plugins
* @ref:[Use a WASM plugin](../how-to-s/wasm-usage.md) for a step-by-step tutorial on creating and using WASM plugins
