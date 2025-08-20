import React from 'react'

export const ForEachNode = (_workflow) => ({
    label: "fas fa-sync",
    name: 'For Each',
    workflow: _workflow,
    kind: 'foreach',
    type: 'group',
    sources: ['ForEachLoop', 'output'],
    targets: []
})