import React from 'react';

export const LteOperator = _workflow => ({
    label: <i className="fas fa-less-than-equal" />,
    name: 'Less Than or Equal',
    kind: '$lte',
    description: 'Checks if a number is less than or equal to another number',
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

// String Split Operator;