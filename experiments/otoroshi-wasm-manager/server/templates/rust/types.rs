use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::HashMap;

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

#[derive(Serialize, Deserialize, Debug)]
pub struct WasmQueryContext {
    pub snowflake: Option<String>,
    pub backend: Backend,
    pub apikey: Option<Apikey>,
    pub user: Option<User>,
    pub raw_request: RawRequest,
    pub config: Value,
    pub global_config: Value,
    pub attrs: Value,
    pub route: Route,
    pub raw_request_body: Option<String>,
    pub request: OtoroshiRequest,
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
    pub body: Option<String>,
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
pub struct OtoroshiPluginResponse {
    pub content: String,
}

#[derive(Serialize, Deserialize)]
pub struct WasmQueryResponse {
    pub headers: Option<HashMap<String, String>>,
    pub body: String,
    pub status: u32,
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

#[derive(Serialize, Deserialize)]
pub struct WasmTransformerResponse {
    pub headers: HashMap<String, String>,
    pub cookies: Value,
    pub body: Option<String>,
}

#[derive(Serialize, Deserialize)]
pub struct WasmSinkMatchesResponse {
    pub result: bool,
}

#[derive(Serialize, Deserialize)]
pub struct WasmSinkHandleResponse {
    pub status: u32,
    pub headers: HashMap<String, String>,
    pub body: Option<String>,
    pub bodyBase64: Option<String>,
}
