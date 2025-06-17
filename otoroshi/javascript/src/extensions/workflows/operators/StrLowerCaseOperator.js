import React from 'react';

export const StrLowerCaseOperator = _workflow => ({
    label: <i className="fas fa-arrow-down" />,
    name: 'String Lower Case',
    kind: '$str_lower_case',
    description: 'Converts a string to lowercase',
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