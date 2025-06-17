import React from 'react';

export const SubtractOperator = _workflow => ({
    label: <i className="fas fa-minus" />,
    name: 'Subtract',
    kind: '$subtract',
    description: 'Subtracts a list of numbers',
    workflow: _workflow,
    flow: ['values'],
    schema: {
        values: {
            type: 'array',
            label: 'Numbers to Subtract',
            array: true,
            format: null
        }
    },
    sources: ['output']
});

// JSON Parse Operator;