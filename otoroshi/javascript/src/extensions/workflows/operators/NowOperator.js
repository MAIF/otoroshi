import React from 'react';

export const NowOperator = _workflow => ({
    label: "fas fa-clock",
    name: 'Now',
    kind: '$now',
    workflow: _workflow,
    flow: [],
    schema: {},
    sources: ['output'],
    operator: true
});

// Is Falsy Operator;