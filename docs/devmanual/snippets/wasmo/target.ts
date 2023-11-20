// (1)
import { WasmQueryContext, WasmQueryResponse } from './types';

export declare var Host: any;

// (2)
export function execute() {
    const context = JSON.parse(Host.inputString()) as WasmQueryContext;

    // (3)
    const headers = {
      "foo": "bar",
      ...(context.request.headers || {})
    }

    // (4)
    const response: WasmQueryResponse = {
        headers,
        status: 200,
        body: "{\"foo\": \"bar\"}"
    };
    Host.outputString(JSON.stringify(response));

    return 0;
}