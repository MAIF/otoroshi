import React from 'react'

export const ParallelFlowsNode = (_workflow) => ({
    label: <i className='fas fa-code-branch' />,
    name: 'Parallel',
    description: 'Run node in parallel',
    type: 'group',
    workflow: _workflow,
    // {
    //   "kind": "parallel",
    //   "paths": [ <workflow>, ... ]
    // }
})