import { WasmAccessValidatorContext, WasmAccessValidatorResponse, WasmAccessValidatorError } from './types';

export declare var Host: any;

export function execute() {
    let context = JSON.parse(Host.inputString()) as WasmAccessValidatorContext;

    if (context.request.headers["foo"] === "bar") {
        const out: WasmAccessValidatorResponse = {
            result: true
        };
        Host.outputString(JSON.stringify(out));
    } else {
        const error: WasmAccessValidatorResponse = {
            result: false,
            error: {
                message: "you're not authorized",
                status: 401
            }
        };
        Host.outputString(JSON.stringify(error));
    }

    return 0;
}