import React from 'react';

export const GteOperator = _workflow => ({
    label: "fas fa-greater-than-equal",
    name: 'Greater Than or Equal',
    kind: '$gte',
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

// Increment Operator;