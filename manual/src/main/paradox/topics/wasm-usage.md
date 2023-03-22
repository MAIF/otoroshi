# Otoroshi and WASM

WebAssembly (WASM) is a simple machine model and executable format with an extensive specification. It is designed to be portable, compact, and execute at or near native speeds. Otoroshi already supports the execution of WASM files by providing different plugins that can be applied on routes. These plugins are:

- `WasmRouteMatcher`: useful to define if a route can handle a request
- `WasmPreRoute`: useful to check request and extract useful stuff for the other plugins
- `WasmAccessValidator`: useful to control access to a route (jump to the next section to learn more about it)
- `WasmRequestTransformer`: transform the content of an incoming request (body, headers, etc ...)
- `WasmBackend`: execute a WASM file as Otoroshi target. Useful to implement user defined logic and function at the edge
- `WasmResponseTransformer`: transform the content of the response produced by the target
- `WasmSink`: create a sink plugin to handle unmatched requests
- `WasmRequestHandler`: create a plugin that can handle the whole request lifecycle
- `WasmJob`: create a job backed by a wasm function

To simplify the process of WASM creation and usage, Otoroshi provides:

- otoroshi ui integration: a full set of plugins that let you pick which WASM function to runtime at any point in a route
- otoroshi `wasm-manager`: a code editor in the browser that let you write your plugin in `Rust` or `Assembly Script` without having to think about compiling it to WASM (you can find a complete tutorial about it @ref:[here](../how-to-s/wasm-manager-installation.md))

@@@ div { .centered-img }
<img src="../imgs/otoroshi-wasm-manager-1.png" title="screenshot of a wasm manager instance" />
@@@

## Available tutorials

here is the list of available tutorials about wasm in Otoroshi

1. @ref:[install a wasm manager](../how-to-s/wasm-manager-installation.md)
2. @ref:[use a wasm plugin](../how-to-s/wasm-usage.md)

## WASM plugins entities

TODO

## Plugins api

the following parts illustrates the apis for the different plugins. Otoroshi uses [Extism](https://extism.org/) to handle content sharing between the JVM and the wasm VM. All structures are sent to/from the plugins as json strings. 

for instance, if we want to write a `WasmBackendCall` plugin using javascript, we could write something like

```js
function backend_call() {
  const input_str = Host.inputString(); // here we get the context passed by otoroshi as json string
  const backend_call_context = JSON.parse(input_str); // and parse it
  if (backend_call_context.path === '/hello') {
    Host.outputString(JSON.stringify({  // now we return a json string to otoroshi with the "backend" call result
      headers: { 
        'content-type': 'application/json' 
      },
      body_json: { 
        message: `Hello ${ctx.request.query.name[0]}!` 
      },
      status: 200,
    }));
  } else {
    Host.outputString(JSON.stringify({  // now we return a json string to otoroshi with the "backend" call result
      headers: { 
        'content-type': 'application/json' 
      },
      body_json: { 
        error: "not found"
      },
      status: 404,
    }));
  }
  return 0; // we return 0 to tell otoroshi that everything went fine
}
```

the following examples are written in rust. the rust macros provided by extism makes the usage of `Host.inputString` and `Host.outputString` useless. Remember that it's still used under the hood and that the structures are passed as json strings.

### WasmRouteMatcher

TODO

```rs
#[plugin_fn]
pub fn matches_route(Json(_context): Json<types::WasmMatchRouteContext>) -> FnResult<Json<types::WasmMatchRouteResponse>> {
    ///
}

#[derive(Serialize, Deserialize, Debug)]
pub struct WasmMatchRouteContext {
    pub snowflake: Option<String>,
    pub route: Route,
    pub request: RawRequest,
    pub config: Value,
    pub attrs: Value,
}

#[derive(Serialize, Deserialize, Debug)]
pub struct WasmMatchRouteResponse {
  pub result: bool,
}
```

### WasmPreRoute

TODO

```rs
#[plugin_fn]
pub fn pre_route(Json(_context): Json<types::WasmPreRouteContext>) -> FnResult<Json<types::WasmPreRouteResponse>> {
    ///
}

#[derive(Serialize, Deserialize, Debug)]
pub struct WasmPreRouteContext {
    pub snowflake: Option<String>,
    pub route: Route,
    pub request: RawRequest,
    pub config: Value,
    pub global_config: Value,
    pub attrs: Value,
}

#[derive(Serialize, Deserialize, Debug)]
pub struct WasmPreRouteResponse {
    pub error: bool,
    pub status: Option<u32>,
    pub headers: Option<HashMap<String, String>>,
    pub body_bytes: Option<Vec<u8>>,
    pub body_base64: Option<String>,
    pub body_json: Option<Value>,
    pub body_str: Option<String>,
}
```

### WasmAccessValidator

TODO

```rs
#[plugin_fn]
pub fn can_access(Json(_context): Json<types::WasmAccessValidatorContext>) -> FnResult<Json<types::WasmAccessValidatorResponse>> {
    ///
}

#[derive(Serialize, Deserialize)]
pub struct WasmAccessValidatorContext {
    pub snowflake: Option<String>,
    pub apikey: Option<Apikey>,
    pub user: Option<User>,
    pub request: RawRequest,
    pub config: Value,
    pub global_config: Value,
    pub attrs: Value,
    pub route: Route,
}

#[derive(Serialize, Deserialize)]
pub struct WasmAccessValidatorError {
    pub message: String,
    pub status: u32,
}

#[derive(Serialize, Deserialize)]
pub struct WasmAccessValidatorResponse {
    pub result: bool,
    pub error: Option<WasmAccessValidatorError>,
}
```

### WasmRequestTransformer

TODO

```rs
#[plugin_fn]
pub fn transform_request(Json(_context): Json<types::WasmRequestTransformerContext>) -> FnResult<Json<types::WasmTransformerResponse>> {
    ///
}

#[derive(Serialize, Deserialize)]
pub struct WasmRequestTransformerContext {
    pub snowflake: Option<String>,
    pub raw_request: OtoroshiRequest,
    pub otoroshi_request: OtoroshiRequest,
    pub backend: Backend,
    pub apikey: Option<Apikey>,
    pub user: Option<User>,
    pub request: RawRequest,
    pub config: Value,
    pub global_config: Value,
    pub attrs: Value,
    pub route: Route,
    pub request_body_bytes: Option<Vec<u8>>,
}
```

### WasmBackend

TODO

```rs
#[plugin_fn]
pub fn call_backend(Json(_context): Json<types::WasmQueryContext>) -> FnResult<Json<types::WasmQueryResponse>> {
    ///
}

#[derive(Serialize, Deserialize, Debug)]
pub struct WasmBackendContext {
    pub snowflake: Option<String>,
    pub backend: Backend,
    pub apikey: Option<Apikey>,
    pub user: Option<User>,
    pub raw_request: RawRequest,
    pub config: Value,
    pub global_config: Value,
    pub attrs: Value,
    pub route: Route,
    pub request_body_bytes: Option<Vec<u8>>,
    pub request: OtoroshiRequest,
}

#[derive(Serialize, Deserialize)]
pub struct WasmBackendResponse {
    pub headers: Option<HashMap<String, String>>,
    pub body_bytes: Option<Vec<u8>>,
    pub body_base64: Option<String>,
    pub body_json: Option<Value>,
    pub body_str: Option<String>,
    pub status: u32,
}
```

### WasmResponseTransformer

TODO

```rs
#[plugin_fn]
pub fn transform_response(Json(_context): Json<types::WasmResponseTransformerContext>) -> FnResult<Json<types::WasmTransformerResponse>> {
    ///
}

#[derive(Serialize, Deserialize)]
pub struct WasmResponseTransformerContext {
    pub snowflake: Option<String>,
    pub raw_response: OtoroshiResponse,
    pub otoroshi_response: OtoroshiResponse,
    pub apikey: Option<Apikey>,
    pub user: Option<User>,
    pub request: RawRequest,
    pub config: Value,
    pub global_config: Value,
    pub attrs: Value,
    pub route: Route,
    pub response_body_bytes: Option<Vec<u8>>,
}

#[derive(Serialize, Deserialize)]
pub struct WasmTransformerResponse {
    pub headers: HashMap<String, String>,
    pub cookies: Value,
    pub body_bytes: Option<Vec<u8>>,
    pub body_base64: Option<String>,
    pub body_json: Option<Value>,
    pub body_str: Option<String>,
}
```

### WasmSink

TODO

```rs
#[plugin_fn]
pub fn sink_matches(Json(_context): Json<types::WasmSinkContext>) -> FnResult<Json<types::WasmSinkMatchesResponse>> {
    ///
}

#[plugin_fn]
pub fn sink_handle(Json(_context): Json<types::WasmSinkContext>) -> FnResult<Json<types::WasmSinkHandleResponse>> {
    ///
}

#[derive(Serialize, Deserialize)]
pub struct WasmSinkContext {
    pub snowflake: Option<String>,
    pub request: RawRequest,
    pub config: Value,
    pub global_config: Value,
    pub attrs: Value,
    pub origin: String,
    pub status: u32,
    pub message: String,
}

#[derive(Serialize, Deserialize)]
pub struct WasmSinkMatchesResponse {
    pub result: bool,
}

#[derive(Serialize, Deserialize)]
pub struct WasmSinkHandleResponse {
    pub status: u32,
    pub headers: HashMap<String, String>,
    pub body_bytes: Option<Vec<u8>>,
    pub body_base64: Option<String>,
    pub body_json: Option<Value>,
    pub body_str: Option<String>,
}
```

### WasmRequestHandler

TODO

```rs
#[plugin_fn]
pub fn handle_request(Json(_context): Json<types::WasmRequestHandlerContext>) -> FnResult<Json<types::WasmRequestHandlerResponse>> {
    ///
}

#[derive(Serialize, Deserialize, Debug)]
pub struct WasmRequestHandlerContext {
    pub request: RawRequest
}

#[derive(Serialize, Deserialize, Debug)]
pub struct WasmRequestHandlerResponse {
    pub status: u32,
    pub headers: HashMap<String, String>,
    pub body_bytes: Option<Vec<u8>>,
    pub body_base64: Option<String>,
    pub body_json: Option<Value>,
    pub body_str: Option<String>,
}
```

### WasmJob

TODO

```rs
#[plugin_fn]
pub fn job_run(Json(_context): Json<types::WasmJobContext>) -> FnResult<Json<types::WasmJobResult>> {
    ///
}

#[derive(Serialize, Deserialize, Debug)]
pub struct WasmJobContext {
    pub attrs: Value,
    pub global_config: Value,
    pub snowflake: Option<String>,
}

#[derive(Serialize, Deserialize, Debug)]
pub struct WasmJobResult {

}
```

### Common types

```rs
#[derive(Serialize, Deserialize, Debug)]
pub struct Backend {
    pub id: String,
    pub hostname: String,
    pub port: u32,
    pub tls: bool,
    pub weight: u32,
    pub protocol: String,
    pub ip_address: Option<String>,
    pub predicate: Value,
    pub tls_config: Value,
}

#[derive(Serialize, Deserialize, Debug)]
pub struct Apikey {
    #[serde(alias = "clientId")]
    pub client_id: String,
    #[serde(alias = "clientName")]
    pub client_name: String,
    pub metadata: HashMap<String, String>,
    pub tags: Vec<String>,
}

#[derive(Serialize, Deserialize, Debug)]
pub struct User {
    pub name: String,
    pub email: String,
    pub profile: Value,
    pub metadata: HashMap<String, String>,
    pub tags: Vec<String>,
}

#[derive(Serialize, Deserialize, Debug)]
pub struct RawRequest {
    pub id: u32,
    pub method: String,
    pub headers: HashMap<String, String>,
    pub cookies: Value,
    pub tls: bool,
    pub uri: String,
    pub path: String,
    pub version: String,
    pub has_body: bool,
    pub remote: String,
    pub client_cert_chain: Value,
}

#[derive(Serialize, Deserialize, Debug)]
pub struct Frontend {
    pub domains: Vec<String>,
    pub strict_path: Option<String>,
    pub exact: bool,
    pub headers: HashMap<String, String>,
    pub query: HashMap<String, String>,
    pub methods: Vec<String>,
}

#[derive(Serialize, Deserialize, Debug)]
pub struct HealthCheck {
    pub enabled: bool,
    pub url: String,
}

#[derive(Serialize, Deserialize, Debug)]
pub struct RouteBackend {
    pub targets: Vec<Backend>,
    pub root: String,
    pub rewrite: bool,
    pub load_balancing: Value,
    pub client: Value,
    pub health_check: Option<HealthCheck>,
}

#[derive(Serialize, Deserialize, Debug)]
pub struct Route {
    pub id: String,
    pub name: String,
    pub description: String,
    pub tags: Vec<String>,
    pub metadata: HashMap<String, String>,
    pub enabled: bool,
    pub debug_flow: bool,
    pub export_reporting: bool,
    pub capture: bool,
    pub groups: Vec<String>,
    pub frontend: Frontend,
    pub backend: RouteBackend,
    pub backend_ref: Option<String>,
    pub plugins: Value,
}

#[derive(Serialize, Deserialize)]
pub struct OtoroshiResponse {
    pub status: u32,
    pub headers: HashMap<String, String>,
    pub cookies: Value,
}

#[derive(Serialize, Deserialize, Debug)]
pub struct OtoroshiRequest {
    pub url: String,
    pub method: String,
    pub headers: HashMap<String, String>,
    pub version: String,
    pub client_cert_chain: Value,
    pub backend: Option<Backend>,
    pub cookies: Value,
}
```

## Host functions

otoroshi provides some host function in order make wasm interact with otoroshi internals. Those functions are enabled with specific authorizations to avoid security issues with third party plugins.

### authorizations

TODO

### host functions abi

you'll find here the raw signatures for the otoroshi host functions. we are currently in the process of writing higher level functions to hide the complexity.

every time you the the following signature: `(context: u64, size: u64) -> u64` it means that otoroshi is expecting for a pointer to the call context (which is a json string) and it's size. The return is a pointer to the response (which is a json string).

the signature `(unused: u64) -> u64` means that there is no need for a params but as we technically need one (and hope to don't need one in the future), you have to pass something like `0` as parameter.

```rust
extern "C" {
  // log messages in otoroshi (log levels are 0 to 6 for trace, debug, info, warn, error, critical, max)
  fn proxy_log(logLevel: i32, message: u64, size: u64) -> i32;
  // trigger an otoroshi wasm event that can be exported through data exporters
  fn proxy_log_event(context: u64, size: u64) -> u64;
  // an http client
  fn proxy_http_call(context: u64, size: u64) -> u64;
  // access the current otoroshi state containing a snapshot of all otoroshi entities
  fn proxy_state(context: u64) -> u64;
  fn proxy_state_value(context: u64, size: u64) -> u64;
  // access the current otoroshi cluster configuration
  fn proxy_cluster_state(context: u64) -> u64;
  fn proxy_cluster_state_value(context: u64, size: u64) -> u64;
  // access the current otoroshi static configuration
  fn proxy_global_config(unused: u64) -> u64;
  // access the current otoroshi dynamic configuration
  fn proxy_config(unused: u64) -> u64;
  // access a persistent key/value store shared by every wasm plugins
  fn proxy_datastore_keys(context: u64, size: u64) -> u64;
  fn proxy_datastore_get(context: u64, size: u64) -> u64;
  fn proxy_datastore_exists(context: u64, size: u64) -> u64;
  fn proxy_datastore_pttl(context: u64, size: u64) -> u64;
  fn proxy_datastore_setnx(context: u64, size: u64) -> u64;
  fn proxy_datastore_del(context: u64, size: u64) -> u64;
  fn proxy_datastore_incrby(context: u64, size: u64) -> u64;
  fn proxy_datastore_pexpire(context: u64, size: u64) -> u64;
  fn proxy_datastore_all_matching(context: u64, size: u64) -> u64;
  // access a persistent key/value store for the current plugin instance only
  fn proxy_plugin_datastore_keys(context: u64, size: u64) -> u64;
  fn proxy_plugin_datastore_get(context: u64, size: u64) -> u64;
  fn proxy_plugin_datastore_exists(context: u64, size: u64) -> u64;
  fn proxy_plugin_datastore_pttl(context: u64, size: u64) -> u64;
  fn proxy_plugin_datastore_setnx(context: u64, size: u64) -> u64;
  fn proxy_plugin_datastore_del(context: u64, size: u64) -> u64;
  fn proxy_plugin_datastore_incrby(context: u64, size: u64) -> u64;
  fn proxy_plugin_datastore_pexpire(context: u64, size: u64) -> u64;
  fn proxy_plugin_datastore_all_matching(context: u64, size: u64) -> u64;
  // access an in memory key/value store for the current plugin instance only
  fn proxy_plugin_map_set(context: u64, size: u64) -> u64;
  fn proxy_plugin_map_get(context: u64, size: u64) -> u64;
  fn proxy_plugin_map(unused: u64) -> u64;
  // access an in memory key/value store shared by every wasm plugins
  fn proxy_global_map_set(context: u64, size: u64) -> u64;
  fn proxy_global_map_get(context: u64, size: u64) -> u64;
  fn proxy_global_map(unused: u64) -> u64;
}
```

