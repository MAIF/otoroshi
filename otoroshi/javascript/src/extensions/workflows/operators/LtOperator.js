import React from 'react';

export const LtOperator = _workflow => ({
    label: "fas fa-less-than",
    name: 'Less Than',
    kind: '$lt',
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
    sources: ['output'],
    operator: true
});

// Divide Operator;