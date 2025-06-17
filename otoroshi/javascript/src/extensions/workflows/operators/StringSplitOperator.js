import React from 'react'

export const StringSplitOperator = _workflow => ({
    label: <i className="fas fa-cut" />,
    name: 'String Split',
    kind: '$str_split',
    description: 'Splits a string into an array based on a regex',
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