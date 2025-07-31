import React from 'react'

import WasmPlugin from '../../../forms/ng_plugins/WasmPlugin';

export function WasmNode(_workflow) {
    const flow = WasmPlugin.config_flow({
        source: {
            kind: 'local'
        }
    })

    return {
        label: <i className='fa fa-plug' />,
        name: 'Wasm',
        kind: 'wasm',
        description: 'This node executes an exported function from a WASM plugin',
        workflow: {
            ..._workflow,
            source: {
                ...(_workflow?.source || {}),
                kind: 'local'
            }
        },
        sources: ['output'],
        schema: {
            ...WasmPlugin.config_schema,
            params: {
                type: 'code',
                label: 'Params',
                props: {
                    editorOnly: true,
                },
            }
        },
        flow: [...flow, 'params']
    }
}