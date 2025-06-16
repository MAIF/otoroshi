import React from 'react'

export const MapNode = (_workflow) => ({
    label: <i className='fas fa-map' />,
    name: 'Map',
    description: 'Route items to different branches (true/false)',
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
    sources: ['node'],
    targets: []
})