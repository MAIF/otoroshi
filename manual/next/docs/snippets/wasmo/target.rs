// (1)
mod types;

// (2)
use extism_pdk::*;
use std::collections::HashMap;

#[plugin_fn]
pub fn execute(Json(context): Json<types::WasmQueryContext>) -> FnResult<Json<types::WasmQueryResponse>> {
    // (3)
    let mut headers = HashMap::new();
    headers.insert("foo".to_string(), "bar".to_string());

    // (4)
    let response = types::WasmQueryResponse { 
        headers: Some(headers.into_iter().chain(context.raw_request.headers).collect()), 
        body: "{\"foo\": \"bar\"}".to_owned(),
        status: 200
    };
  
    Ok(Json(response))
}