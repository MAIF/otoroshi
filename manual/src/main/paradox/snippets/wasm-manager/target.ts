import { Host, Config, } from '@extism/as-pdk';
import { JSON } from "json-as/assembly";
// (1)
import { 
  WasmQueryResponse, 
  WasmQueryContext, 
} from "./types";

// do not forget this one, and don't change the name, it's mandatory
function assemblyScriptAbort(
  message: string | null,
  fileName: string | null,
  lineNumber: u32,
  columnNumber: u32
): void { }

// (2)
export function execute(): i32 {
  let str = Host.inputString();
  let context = JSON.parse<WasmQueryContext>(str);
  // (3)
  let headers = new Map<string, string>();
  headers.put("foo", "bar");
  // (4)
  let response = new WasmQueryResponse(
    headers,
    "{\"foo\": \"bar\"}",
    200
  );
  Host.outputString(JSON.stringify<WasmQueryResponse>(response));
  return 0;
}