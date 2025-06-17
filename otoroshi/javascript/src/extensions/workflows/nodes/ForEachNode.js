import React from 'react'

export const ForEachNode = (_workflow) => ({
    label: <i className="fas fa-sync" />,
    name: 'For Each',
    description: 'Apply node for each item',
    workflow: _workflow,
    kind: 'foreach',
    type: 'group',
    flow: ['values'],
    schema: {
        values: {
            type: 'code',
            label: 'Values to iterate',
            props: {
                editorOnly: true,
            },
        }
    },
    sources: ['node', 'output'],
    targets: []
})