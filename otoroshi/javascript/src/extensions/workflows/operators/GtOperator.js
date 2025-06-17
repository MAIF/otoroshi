import React from 'react';

export const GtOperator = _workflow => ({
    label: <i className="fas fa-greater-than" />,
    name: 'Greater Than',
    kind: '$gt',
    description: 'Checks if a number is greater than another number',
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

// String Upper Case Operator;