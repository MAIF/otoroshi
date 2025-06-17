import React from 'react';

export const StrUpperCaseOperator = _workflow => ({
    label: <i className="fas fa-arrow-up" />,
    name: 'String Upper Case',
    kind: '$str_upper_case',
    description: 'Converts a string to uppercase',
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