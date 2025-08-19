import React from 'react';

export const DecrOperator = _workflow => ({
    label: "fas fa-minus-circle",
    name: 'Decrement',
    kind: '$decr',
    workflow: _workflow,
    flow: ['value', 'decrement'],
    schema: {
        value: {
            type: 'number',
            label: 'Value to Decrement'
        },
        decrement: {
            type: 'number',
            label: 'Decrement Amount'
        }
    },
    sources: ['output'],
    operator: true
});