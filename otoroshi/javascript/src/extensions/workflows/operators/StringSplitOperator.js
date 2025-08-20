import React from 'react'

export const StringSplitOperator = _workflow => ({
    label: "fas fa-cut",
    name: 'String Split',
    kind: '$str_split',
    workflow: _workflow,
    flow: ['value', 'regex'],
    schema: {
        value: {
            type: 'string',
            label: 'String to Split'
        },
        regex: {
            type: 'string',
            label: 'Regex Pattern'
        }
    },
    sources: ['output']
});