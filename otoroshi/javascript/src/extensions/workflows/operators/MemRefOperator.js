import React from 'react';

export const MemRefOperator = _workflow => ({
    label: <i className="fas fa-memory" />,
    name: 'Memory Reference',
    kind: '$mem_ref',
    description: 'Gets a value from the memory',
    workflow: _workflow,
    flow: ['name', 'path'],
    schema: {
        name: {
            type: 'string',
            label: 'Memory Name',
        },
        path: {
            type: 'string',
            label: 'Memory Path',
            help: 'Only useful if the variable is an object'
        }
    },
    sources: [],
    operator: true
});

// String Lower Case Operator;