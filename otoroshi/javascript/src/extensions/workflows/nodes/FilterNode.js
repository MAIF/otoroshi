import React from 'react'

export const FilterNode = (_workflow) => ({
    label: "fas fa-filter",
    name: 'Filter',
    workflow: _workflow,
    kind: 'filter',
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
        not: {
            type: 'bool',
            label: 'Not'
        },
        destination: {
            type: 'string',
            label: 'Destination'
        }
    },
    sources: ['output'],
    targets: ['predicate']
})