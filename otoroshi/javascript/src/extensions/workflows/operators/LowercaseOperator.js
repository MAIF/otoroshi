import React from 'react'

export const LowercaseOperator = _workflow => ({
    label: "fas fa-arrow-down",
    name: 'String Lowercase',
    kind: '$str_lower_case',
    workflow: _workflow,
    flow: ['value'],
    schema: {
        value: {
            type: 'string',
            label: 'String Value'
        }
    },
    sources: ['output'],
    operator: true
});