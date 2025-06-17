import React from 'react';

export const ParseTimeOperator = _workflow => ({
    label: <i className="fas fa-clock" />,
    name: 'Parse Time',
    kind: '$parse_time',
    description: 'Parses a time string into a timestamp',
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