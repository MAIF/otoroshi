import React from 'react'

export const ForEachNode = (_workflow) => ({
    label: <i className="fas fa-sync" />,
    name: 'For Each',
    description: 'Apply node for each item',
    workflow: _workflow,
    type: 'group',
    kind: 'foreach',
    flow: ['values'],
    schema: {
        values: {
            type: 'code',
            label: 'Values to iterate',
            props: {
                editorOnly: true,
            },
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