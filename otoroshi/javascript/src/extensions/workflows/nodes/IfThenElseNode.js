import React from 'react'

export const IfThenElseNode = (_workflow) => ({
    label: <i className='fas fa-question' />,
    name: 'IfThenElse',
    description: 'Route items to different branches (true/false)',
    workflow: _workflow,
    kind: 'if',
    sources: ['then', 'else'],
    targets: ['predicate']
})