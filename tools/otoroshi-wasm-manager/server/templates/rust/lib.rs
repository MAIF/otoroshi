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