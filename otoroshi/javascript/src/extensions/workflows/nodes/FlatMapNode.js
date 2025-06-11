import React from 'react'

export const FlatMapNode = (_workflow) => ({
    label: <i className="fas fa-layer-group" />,
    name: 'Aggregate',
    description: 'Combine a field from many items into a list in a single item',
    workflow: _workflow,
    //     {
    //   "kind": "foreach",
    //   "values": <array_expr>,
    //   "node": <workflow>
    // }
})