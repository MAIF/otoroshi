import React from 'react';

export const ParseTimeOperator = _workflow => ({
    label: "fas fa-clock",
    name: 'Parse Time',
    kind: '$parse_time',
    workflow: _workflow,
    flow: ['value', 'pattern'],
    schema: {
        value: {
            type: 'string',
            label: 'Time String'
        },
        pattern: {
            type: 'string',
            label: 'Time Pattern'
        }
    },
    sources: ['output']
});

// Memory Reference Operator;