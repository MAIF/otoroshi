import React from 'react'

export const IfThenElseNode = (_workflow) => ({
    label: 'fas fa-question',
    name: 'IfThenElse',
    workflow: _workflow,
    type: 'group',
    kind: 'if',
    sources: ['then', 'else'],
    targets: ['predicate']
})