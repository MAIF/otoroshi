import React from 'react';

export const LtOperator = _workflow => ({
    label: <i className="fas fa-less-than" />,
    name: 'Less Than',
    kind: '$lt',
    description: 'Checks if a number is less than another number',
    workflow: _workflow,
    flow: ['a', 'b'],
    schema: {
        a: {
            type: 'number',
            label: 'First Number'
        },
        b: {
            type: 'number',
            label: 'Second Number'
        }
    },
    sources: [],
    operator: true
});

// Divide Operator;