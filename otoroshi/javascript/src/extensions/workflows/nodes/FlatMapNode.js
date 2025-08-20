import React from 'react'

export const FlatMapNode = (_workflow) => ({
    label: "fas fa-layer-group",
    name: 'Aggregate',
    workflow: _workflow,
    kind: 'flatmap',
    type: 'group',
    sources: ['Item', 'output'],
    targets: []
})