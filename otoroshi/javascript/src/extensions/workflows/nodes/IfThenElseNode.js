import React from 'react'

export const IfThenElseNode = (_workflow) => ({
    label: <i className='fas fa-question' />,
    name: 'If',
    description: 'Route items to different branches (true/false)',
    workflow: _workflow,
    //     {
    //   "kind": "if",
    //   "predicate": <bool_expr>,
    //   "then": <Node>,
    //   "else": <Node>
    // }
})