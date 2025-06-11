import React from 'react'

export const ForEachNode = (_workflow) => ({
    label: <i className="fas fa-sync" />,
    name: 'For Each',
    description: 'Apply node for each item',
    workflow: _workflow,
    //     {
    //   "kind": "foreach",
    //   "values": <array_expr>,
    //   "node": <workflow>
    // }
})