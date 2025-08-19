import React from 'react';

export const ParseDateTimeOperator = _workflow => ({
    label: "fas fa-calendar-check",
    name: 'Parse DateTime',
    kind: '$parse_datetime',
    workflow: _workflow,
    flow: ['value', 'pattern'],
    schema: {
        value: {
            type: 'string',
            label: 'DateTime String'
        },
        pattern: {
            type: 'string',
            label: 'DateTime Pattern'
        }
    },
    sources: ['output']
});

// Array Page Operator;