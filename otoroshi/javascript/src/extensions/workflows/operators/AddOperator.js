import React from 'react';

export const AddOperator = _workflow => ({
    label: <i className="fas fa-plus" />,
    name: 'Add',
    kind: '$add',
    description: 'Adds a list of numbers',
    workflow: _workflow,
    flow: ['values'],
    schema: {
        values: {
            type: 'array',
            label: 'Numbers to Add',
            array: true,
            format: null
        }
    },
    sources: ['output'],
    operator: true
});

// Array Append Operator;