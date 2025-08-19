import React from 'react'

export const ForEachNode = (_workflow) => ({
    label: "fas fa-sync",
    name: 'For Each',
    workflow: _workflow,
    kind: 'foreach',
    type: 'group',
    flow: ['values'],
    schema: {
        values: {
            type: 'code',
            label: 'Values to iterate',
            props: {
                editorOnly: true,
            },
        }
    },
    sources: ['ForEachLoop', 'output'],
    targets: []
})