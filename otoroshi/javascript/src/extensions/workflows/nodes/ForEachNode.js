import React from 'react'

export const ForEachNode = (_workflow) => ({
    label: <i className="fas fa-sync" />,
    name: 'For Each',
    description: 'Apply node for each item',
    workflow: _workflow,
    flow: ['values'],
    schema: {
        values: {
            type: 'object',
            label: 'Values'
        },
        // node: {
        //     renderer: NodeRenderer
        // }
    }
    //     {
    //   "kind": "foreach",
    //   "values": <array_expr>,
    //   "node": <workflow>
    // }
})