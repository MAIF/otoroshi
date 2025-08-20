import React from 'react';

export const SubtractOperator = _workflow => ({
    label: "fas fa-minus",
    name: 'Subtract',
    kind: '$subtract',
    workflow: _workflow,
    // flow: ['values'],
    // schema: {
    //     values: {
    //         type: 'array',
    //         label: 'Numbers to Subtract',
    //         array: true,
    //         format: null
    //     }
    // },
    sources: ['output']
});

// JSON Parse Operator;