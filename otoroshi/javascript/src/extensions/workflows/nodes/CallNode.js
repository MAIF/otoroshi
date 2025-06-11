import React from 'react'

export const CallNode = (_workflow) => ({
    label: <i className='fas fa-play' />,
    name: 'Call',
    description: 'Execute a function with args',
    workflow: _workflow,
    //     {
    //   "kind": "call",
    //   "function": "<function_name>",
    //   "args": { ... },
    //   "result": "<memory_var_name>"
    // }
})