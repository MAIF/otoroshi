import React from 'react'

export const FlatMapNode = (_workflow) => ({
    label: <i className="fas fa-layer-group" />,
    name: 'Aggregate',
    description: 'Combine a field from many items into a list in a single item',
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
    }
    //     {
    //   "kind": "foreach",
    //   "values": <array_expr>,
    //   "node": <workflow>
    // }
})