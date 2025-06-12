import React from 'react'

export const WaitNode = (_workflow) => ({
    label: <i className='fas fa-clock' />,
    name: 'Wait',
    description: 'Wait before continue with execution',
    workflow: _workflow,
    kind: 'wait',
    //     {
    //   "kind": "wait",
    //   "duration": 1000
    // }
})