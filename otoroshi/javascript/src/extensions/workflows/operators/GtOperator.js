import React from 'react';

export const GtOperator = _workflow => ({
    label: "fas fa-greater-than",
    name: 'Greater Than',
    kind: '$gt',
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
})