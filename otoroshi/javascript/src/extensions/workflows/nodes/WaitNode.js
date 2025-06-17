import React from 'react'

export const WaitNode = (_workflow) => ({
    label: <i className='fas fa-clock' />,
    name: 'Wait',
    description: 'Wait before continue with execution',
    workflow: _workflow,
    kind: 'wait',
    flow: ['duration'],
    schema: {
        duration: {
            type: 'number',
            label:' Duration'
        }
    },
    sources: ['output']
    //     {
    //   "kind": "wait",
    //   "duration": 1000
    // }
})