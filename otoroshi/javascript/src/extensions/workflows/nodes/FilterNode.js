import React from 'react'

export const FilterNode = (_workflow) => ({
    label: "fas fa-filter",
    name: 'Filter',
    workflow: _workflow,
    kind: 'filter',
    type: 'group',
    sources: ['output'],
    targets: ['predicate']
})