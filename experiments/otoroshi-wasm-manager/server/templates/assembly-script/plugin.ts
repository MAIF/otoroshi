import { Host, Config, } from '@extism/as-pdk';
import { JSON } from "json-as/assembly";
import { WasmQueryContext } from "./types";

// do not forget this one, and don't change the name, it's mandatory
function assemblyScriptAbort(
  message: string | null,
  fileName: string | null,
  lineNumber: u32,
  columnNumber: u32
): void { }

export function execute(): i32 {
  let str = Host.inputString();

  // let context = <JSON.Obj>(JSON.parse(str))

  let context = JSON.parse<WasmQueryContext>(str)
  // let host = context.headers.Host
  // let foo = Config.get('foo')


  // Host.outputString(`{ "foo_from_header": ${context.headers.foo}, "Host": ${host}, "foo_config": ${(foo == null ? "null" : foo)} }`);

  Host.outputString(`{ "body": ${JSON.stringify<WasmQueryContext>(context)}}`);
  // Host.outputString(`{ "body": ${context.stringify()}`)
  return 0;
}
