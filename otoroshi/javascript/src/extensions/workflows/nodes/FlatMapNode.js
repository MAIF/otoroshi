import React from 'react'

export const FlatMapNode = (_workflow) => ({
    label: "fas fa-layer-group",
    name: 'Aggregate',
    workflow: _workflow,
    kind: 'flatmap',
    type: 'group',
    flow: ['values'],
    schema: {
        values: {
            type: 'code',
            label: 'Values to iterate',
            props: {
                editorOnly: true,
            },
        },
        destination: {
            type: 'string',
            label: 'Destination'
        }
    },
    sources: ['Item', 'output'],
    targets: []
})