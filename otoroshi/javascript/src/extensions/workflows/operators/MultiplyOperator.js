import React from 'react';

export const MultiplyOperator = _workflow => ({
    label: "fas fa-times",
    name: 'Multiply',
    kind: '$multiply',
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