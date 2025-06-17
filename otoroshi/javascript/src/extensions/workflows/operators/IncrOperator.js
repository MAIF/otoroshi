import React from 'react';

export const IncrOperator = _workflow => ({
    label: <i className="fas fa-plus-circle" />,
    name: 'Increment',
    kind: '$incr',
    description: 'Increments a value by a given amount',
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