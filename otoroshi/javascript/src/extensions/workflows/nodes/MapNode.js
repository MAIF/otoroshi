import React from 'react'

export const MapNode = (_workflow) => ({
    label: 'fas fa-map',
    name: 'Map',
    workflow: _workflow,
    kind: 'map',
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