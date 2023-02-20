import { Host, Config, } from '@extism/as-pdk';
import { JSON } from "json-as/assembly";
import { 
  WasmAccessValidatorResponse, 
  WasmAccessValidatorError, 
  WasmAccessValidatorContext 
} from "./types";

// do not forget this one, and don't change the name, it's mandatory
function assemblyScriptAbort(
  message: string | null,
  fileName: string | null,
  lineNumber: u32,
  columnNumber: u32
): void { }

export function execute(): i32 {
  let str = Host.inputString();
  let context = JSON.parse<WasmAccessValidatorContext>(str);
  let fooHeader = context.request.headers.foo
  if (fooHeader) {
    if (fooHeader === "bar") {
      Host.outputString(JSON.stringify<WasmAccessValidatorResponse>(
        new WasmAccessValidatorResponse(true, null))
      )
    } else {
      Host.outputString(JSON.stringify<WasmAccessValidatorResponse>(
        new WasmAccessValidatorResponse(false, new WasmAccessValidatorError(
          `${fooHeader} is not authorized`, 401
        )))
      )
    }
  } else {
    Host.outputString(JSON.stringify<WasmAccessValidatorResponse>(
      new WasmAccessValidatorResponse(false, new WasmAccessValidatorError(
        `you're not authorized`, 401
      )))
    )
  }
  return 0;
}