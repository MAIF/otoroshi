import React from 'react';

export const StrLowerCaseOperator = _workflow => ({
    label: "fas fa-arrow-down",
    name: 'String Lower Case',
    kind: '$str_lower_case',
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

// Encode Base64 Operator;