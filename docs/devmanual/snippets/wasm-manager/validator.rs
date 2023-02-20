mod types;

use extism_pdk::*;

pub fn execute(Json(context): Json<types::WasmAccessValidatorContext>) -> FnResult<Json<types::WasmAccessValidatorResponse>> {
    match context.request.headers.get("foo") {
        Some(foo) => if foo == "bar" {
            Ok(Json(types::WasmAccessValidatorResponse { 
                result: true,
                error: None
            }))
        } else {
            Ok(Json(types::WasmAccessValidatorResponse { 
                result: false, 
                error: Some(types::WasmAccessValidatorError { 
                    message: format!("{} is not authorized", foo).to_owned(),  
                    status: 401
                })  
            }))
        },
        None => Ok(Json(types::WasmAccessValidatorResponse { 
            result: false, 
            error: Some(types::WasmAccessValidatorError { 
                message: "you're not authorized".to_owned(),  
                status: 401
            })  
        }))
    }
}