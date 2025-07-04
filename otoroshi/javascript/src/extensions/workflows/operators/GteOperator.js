import React from 'react';

export const GteOperator = _workflow => ({
    label: <i className="fas fa-greater-than-equal" />,
    name: 'Greater Than or Equal',
    kind: '$gte',
    description: 'Checks if a number is greater than or equal to another number',
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

// Increment Operator;