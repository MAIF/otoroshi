import React from 'react'

export const AssignNode = _workflow => ({
    label: <i className="fas fa-equals" />,
    name: 'Assign',
    kind: 'assign',
    description: 'Assign value to an another variable',
    workflow: _workflow,
    flow: ['values'],
    schema: {
        values: {
            type: 'object',
            label: 'Values'
        }
    },
    sources: ['output'],
    // {
    //   "kind": "assign",
    //   "values": [
    //     {
    //       "name": "<memory_var>",
    //       "value": <any>
    //     }
    //   ]
    // }
})