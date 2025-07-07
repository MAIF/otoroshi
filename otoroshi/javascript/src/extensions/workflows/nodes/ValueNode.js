import React from 'react'
import { ValueToCheck } from '../operators/ValueToCheck'

export const ValueNode = (_workflow) => ({
    label: <i className='fas fa-cube' />,
    name: 'Value',
    description: 'Apply operators on value',
    workflow: _workflow,
    kind: 'value',
    sources: ['output'],
    schema: {
        value: ValueToCheck('Value', false)
    },
    flow: ['value']
    //     {
    //   "kind": "workflow",
    //   "steps": [ <Node> ],
    //   "returned": <Value>
    // }
})