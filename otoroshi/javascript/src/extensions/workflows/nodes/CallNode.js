import React from 'react'
import { ArgsInput } from '../ArgsInput'
import { CORE_FUNCTIONS } from '../models/Functions'

export function CallNode(_workflow) {
    return {
        label: <i className='fas fa-play' />,
        name: 'Call',
        kind: 'call',
        description: 'Execute a function with args',
        workflow: _workflow,
        flow: ['function', 'args'],
        schema: {
            function: {
                type: 'select',
                props: {
                    creatable: true,
                    options: Object.keys(CORE_FUNCTIONS),
                },
                label: 'Function',
                placeholder: 'Select a function to execute'
            },
            args: {
                renderer: ArgsInput
            }
        }
    }
}