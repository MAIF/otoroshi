use std::{collections::HashMap, hash::Hasher};

use extism_pdk::*;
use serde::{Serialize, Deserialize};
use serde_json::Value;


#[derive(Serialize, Deserialize, Debug)]
struct Backend {
    id: String,
    hostname: String,
    port: u32,
    tls: bool,
    weight: u32,
    protocol: String,
    ip_address: Option<String>,
    predicate: Value,
    tls_config: Value
}

#[derive(Serialize, Deserialize, Debug)]
struct Apikey {
    clientId: String,    
    clientName: String,    
    metadata: HashMap<String, String>,    
    tags: Vec<String>    
}


#[derive(Serialize, Deserialize, Debug)]
struct User {
    name: String,
    email: String,
    profile: Value,
    metadata: HashMap<String, String>,
    tags: Vec<String>,
}

#[derive(Serialize, Deserialize, Debug)]
struct RawRequest {
    id: u32,
    method: String,
    headers: HashMap<String, String>,
    cookies: Value,
    tls: bool,
    uri: String,
    path: String,
    version: String,
    has_body: bool,
    remote: String,
    client_cert_chain: Value
}

#[derive(Serialize, Deserialize, Debug)]
struct Frontend {
    domains: Vec<String>,
    strict_path: Option<String>,
    exact: bool,
    headers: HashMap<String, String>,
    query: HashMap<String, String>,
    methods: Vec<String>
}

#[derive(Serialize, Deserialize, Debug)]
struct HealthCheck {
    enabled: bool,
    url: String
}

#[derive(Serialize, Deserialize, Debug)]
struct RouteBackend {
    targets: Vec<Backend>,
    root: String,
    rewrite: bool,
    load_balancing: Value,
    client: Value,
    health_check: HealthCheck
}


#[derive(Serialize, Deserialize, Debug)]
struct Route {
    id: String,
    name: String,
    description: String,
    tags: Vec<String>,
    metadata: HashMap<String, String>,
    enabled: bool,
    debug_flow: bool,
    export_reporting: bool, 
    capture: bool,
    groups: Vec<String>,
    frontend: Frontend,
    backend: RouteBackend,
    backend_ref: Option<String>,
    plugins: Value
}

#[derive(Serialize, Deserialize)]
struct OtoroshiResponse {
    status: u32,
    headers: HashMap<String, String>,
    cookies: Value
}

#[derive(Serialize, Deserialize, Debug)]
struct OtoroshiRequest {
    url: String,
    method: String,
    headers: HashMap<String, String>,
    version: String,
    client_cert_chain: Value,
    backend: Option<Backend>,
    cookies: Value
}

#[derive(Serialize, Deserialize, Debug)]
struct WasmQueryContext {
    snowflake: Option<String>,
    backend: Backend,
    apikey: Option<Apikey>,
    user: Option<User>,
    raw_request: RawRequest,
    config: Value,
    global_config: Value,
    attrs: Value,
    route: Route,
    raw_request_body: Option<String>,
    request: OtoroshiRequest
}

#[derive(Serialize, Deserialize)]
struct WasmAccessValidatorContext { 
    snowflake: Option<String>,
    apikey: Option<Apikey>,
    user: Option<User>,
    request: RawRequest,
    config: Value,
    global_config: Value,
    attrs: Value,
    route: Route,
}

#[derive(Serialize, Deserialize)]
struct WasmRequestTransformerContext {
    snowflake: Option<String>,
    raw_request: OtoroshiRequest,
    otoroshi_request: OtoroshiRequest,
    backend: Backend,
    apikey: Option<Apikey>,
    user: Option<User>,
    request: RawRequest,
    config: Value,
    global_config: Value,
    attrs: Value,
    route: Route,
}

#[derive(Serialize, Deserialize)]
struct WasmResponseTransformerContext {
    snowflake: Option<String>,
    raw_response: OtoroshiResponse,
    otoroshi_response: OtoroshiResponse,
    apikey: Option<Apikey>,
    user: Option<User>,
    request: RawRequest,
    config: Value,
    global_config: Value,
    attrs: Value,
    route: Route,
    body: Option<String>,
}


#[derive(Serialize, Deserialize)]
struct WasmSinkContext {
    snowflake: Option<String>,
    request: RawRequest,
    config: Value,
    global_config: Value,
    attrs: Value,
    origin: String,
    status: u32,
    message: String
}

#[derive(Serialize, Deserialize)]
struct OtoroshiPluginResponse {
    content: String
}

#[derive(Serialize, Deserialize)]
struct WasmQueryResponse {
   headers: Option<HashMap<String, String>>,
   body: String,
   status: u32
}

#[derive(Serialize, Deserialize)]
struct WasmAccessValidatorError {
    message: String,
    status: u32
}

#[derive(Serialize, Deserialize)]
struct WasmAccessValidatorResponse {
    result: bool,
    error: WasmAccessValidatorError
}

#[derive(Serialize, Deserialize)]
struct WasmTransformerResponse {
    headers: HashMap<String, String>,
    cookies: Value,
    body: Option<String>
}

#[derive(Serialize, Deserialize)]
struct WasmSinkMatchesResponse {
    result: bool,
}


#[derive(Serialize, Deserialize)]
struct WasmSinkHandleResponse {
    status: u32,
    headers: HashMap<String, String>,
    body: Option<String>,
    bodyase64: Option<String>,
}


#[plugin_fn]
pub fn http_backend(Json(context): Json<WasmQueryContext>) -> FnResult<Json<WasmQueryResponse>> {

    // info!("{:?}", context);
    // let res = http::request::<()>(&req, None)?;

    let out = WasmQueryResponse { body: serde_json::to_string(&context)?, status: 200, headers: None };
    //
    Ok(Json(out))
}