import React from 'react';

export const DivideOperator = _workflow => ({
    label: <i className="fas fa-divide" />,
    name: 'Divide',
    kind: '$divide',
    description: 'Divides a list of numbers',
    workflow: _workflow,
    flow: ['values'],
    schema: {
        values: {
            type: 'array',
            label: 'Numbers to Divide',
            array: true,
            format: null
        }
    },
    sources: ['output'],
    operator: true
});

// Map Put Operator;