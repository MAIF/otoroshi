import React from 'react'

export const SwitchNode = (_workflow) => ({
    label: <i className='fas fa-exchange-alt' />,
    name: 'Switch',
    description: 'Route items depending on defined expressions or rules',
    workflow: _workflow,
    //     {
    //   "kind": "switch",
    //   "paths": [
    //     {
    //       "predicate": <bool_expr>,
    //       "node": <workflow>
    //     }
    //   ]
    // }
})