import React from 'react';

export const StrUpperCaseOperator = _workflow => ({
    label: "fas fa-arrow-up",
    name: 'String Upper Case',
    kind: '$str_upper_case',
    workflow: _workflow,
    flow: ['value'],
    schema: {
        value: {
            type: 'string',
            label: 'String Value'
        }
    },
    sources: ['output']
});

// Is Truthy Operator;