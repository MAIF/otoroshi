import { Host, Config, } from '@extism/as-pdk';
import { JSON } from "assemblyscript-json"; 

function myAbort(
  message: string | null,
  fileName: string | null,
  lineNumber: u32,
  columnNumber: u32
): void { }

export function count_vowels(): i32 {
  let input = Host.inputString();

  let context: JSON.Obj = <JSON.Obj>(JSON.parse(input));

  Host.outputString(`{ "body": ${context.stringify()}}`)
  return 0;
}
