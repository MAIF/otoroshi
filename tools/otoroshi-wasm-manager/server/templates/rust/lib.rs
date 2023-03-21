mod types;

use extism_pdk::*;
use std::collections::HashMap;

#[plugin_fn]
pub fn execute(Json(_context): Json<types::WasmAccessValidatorContext>) -> FnResult<Json<types::WasmAccessValidatorResponse>> {
    let out = types::WasmAccessValidatorResponse { 
        result: false, 
        error: Some(types::WasmAccessValidatorError { 
            message: "you're not authorized".to_owned(),  
            status: 401
        })  
    };
    Ok(Json(out))
}

/*

// WasmRouteMatcher

#[plugin_fn]
pub fn matches_route(Json(_context): Json<types::WasmMatchRouteContext>) -> FnResult<Json<types::WasmMatchRouteResponse>> {
    ///
}

// -------------------------

// WasmPreRoute

#[plugin_fn]
pub fn pre_route(Json(_context): Json<types::WasmPreRouteContext>) -> FnResult<Json<types::WasmPreRouteResponse>> {
    ///
}

// -------------------------

// WasmAccessValidator

#[plugin_fn]
pub fn can_access(Json(_context): Json<types::WasmAccessValidatorContext>) -> FnResult<Json<types::WasmAccessValidatorResponse>> {
    ///
}

// -------------------------

// WasmRequestTransformer


#[plugin_fn]
pub fn transform_request(Json(_context): Json<types::WasmRequestTransformerContext>) -> FnResult<Json<types::WasmTransformerResponse>> {
    ///
}

// -------------------------

// WasmBackend

#[plugin_fn]
pub fn call_backend(Json(_context): Json<types::WasmQueryContext>) -> FnResult<Json<types::WasmQueryResponse>> {
    ///
}

// -------------------------

// WasmResponseTransformer

#[plugin_fn]
pub fn transform_response(Json(_context): Json<types::WasmResponseTransformerContext>) -> FnResult<Json<types::WasmTransformerResponse>> {
    ///
}

// -------------------------

// WasmSink

#[plugin_fn]
pub fn sink_matches(Json(_context): Json<types::WasmSinkContext>) -> FnResult<Json<types::WasmSinkMatchesResponse>> {
    ///
}

#[plugin_fn]
pub fn sink_handle(Json(_context): Json<types::WasmSinkContext>) -> FnResult<Json<types::WasmSinkHandleResponse>> {
    ///
}

// -------------------------

// WasmRequestHandler

#[plugin_fn]
pub fn handle_request(Json(_context): Json<types::WasmRequestHandlerContext>) -> FnResult<Json<types::WasmRequestHandlerResponse>> {
    ///
}

// -------------------------

// WasmJob

#[plugin_fn]
pub fn job_run(Json(_context): Json<types::WasmJobContext>) -> FnResult<Json<types::WasmJobResult>> {
    ///
}

*/