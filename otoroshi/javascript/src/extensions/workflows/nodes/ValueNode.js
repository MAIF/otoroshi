import React from 'react'

export const ValueNode = (_workflow) => ({
    label: <i className='fas fa-cube' />,
    name: 'Value',
    description: 'Apply operators on value',
    workflow: _workflow,
    kind: 'value',
    sources: ['output'],
    //     {
    //   "kind": "workflow",
    //   "steps": [ <Node> ],
    //   "returned": <Value>
    // }
})