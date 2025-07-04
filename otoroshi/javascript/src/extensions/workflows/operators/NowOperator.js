import React from 'react';

export const NowOperator = _workflow => ({
    label: <i className="fas fa-clock" />,
    name: 'Now',
    kind: '$now',
    description: 'Returns the current timestamp',
    workflow: _workflow,
    flow: [],
    schema: {},
    sources: [],
    operator: true
});

// Is Falsy Operator;