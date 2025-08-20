import React from 'react';

export const IncrOperator = _workflow => ({
    label: "fas fa-plus-circle",
    name: 'Increment',
    kind: '$incr',
    workflow: _workflow,
    flow: ['value', 'increment'],
    schema: {
        value: {
            type: 'number',
            label: 'Value to Increment'
        },
        increment: {
            type: 'number',
            label: 'Increment Amount'
        }
    },
    sources: ['output'],
    operator: true
});

// Less Than Operator;