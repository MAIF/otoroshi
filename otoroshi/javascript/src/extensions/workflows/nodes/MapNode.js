import React from 'react'

export const MapNode = (_workflow) => ({
    label: <i className='fas fa-map' />,
    name: 'Map',
    description: 'Route items to different branches (true/false)',
    workflow: _workflow,
    kind: 'map',
    //     {
    //   "kind": "foreach",
    //   "values": <array_expr>,
    //   "node": <workflow>
    // }
})