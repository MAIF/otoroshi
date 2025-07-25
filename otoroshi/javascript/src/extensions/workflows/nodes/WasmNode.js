import React from 'react'

import WasmPlugin from '../../../forms/ng_plugins/WasmPlugin';

export function WasmNode(_workflow) {
    return {
        label: <i className='fa fa-plug' />,
        name: 'Wasm',
        kind: 'wasm',
        description: 'This node executes an exported function from a WASM plugin',
        workflow: _workflow,
        sources: ['output'],
        schema: WasmPlugin.config_schema,
        flow: WasmPlugin.config_flow({
            source: {
                kind: ''
            }
        }),
    }
}