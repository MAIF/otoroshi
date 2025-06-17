import React from 'react';

export const ParseDateOperator = _workflow => ({
    label: <i className="fas fa-calendar-alt" />,
    name: 'Parse Date',
    kind: '$parse_date',
    description: 'Parses a date string into a timestamp',
    workflow: _workflow,
    flow: ['value', 'pattern'],
    schema: {
        value: {
            type: 'string',
            label: 'Date String'
        },
        pattern: {
            type: 'string',
            label: 'Date Pattern'
        }
    },
    sources: ['output'],
    operator: true
});

// Parse DateTime Operator;