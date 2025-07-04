import React from 'react';

export const DecrOperator = _workflow => ({
    label: <i className="fas fa-minus-circle" />,
    name: 'Decrement',
    kind: '$decr',
    description: 'Decrements a value by a given amount',
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
    sources: [],
    operator: true
});