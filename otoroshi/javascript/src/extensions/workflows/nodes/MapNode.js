import React from 'react'

export const MapNode = (_workflow) => ({
    label: 'fas fa-map',
    name: 'Map',
    workflow: _workflow,
    kind: 'map',
    type: 'group',
    sources: ['Item', 'output'],
    targets: []
})