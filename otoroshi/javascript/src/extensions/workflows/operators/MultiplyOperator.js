import React from 'react';

export const MultiplyOperator = _workflow => ({
    label: <i className="fas fa-times" />,
    name: 'Multiply',
    kind: '$multiply',
    description: 'Multiplies a list of numbers',
    workflow: _workflow,
    flow: ['values'],
    schema: {
        values: {
            type: 'array',
            label: 'Numbers to Multiply',
            array: true,
            format: null
        }
    },
    sources: ['output'],
    operator: true
});

// String Concatenate Operator;