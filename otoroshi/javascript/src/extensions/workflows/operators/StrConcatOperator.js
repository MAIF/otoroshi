import React from 'react';

export const StrConcatOperator = _workflow => ({
    label: <i className="fas fa-link" />,
    name: 'String Concatenate',
    kind: '$str_concat',
    description: 'Concatenates a list of strings',
    workflow: _workflow,
    flow: ['values', 'separator'],
    schema: {
        values: {
            type: 'array',
            label: 'Strings to Concatenate',
            array: true,
            format: null
        },
        separator: {
            type: 'string',
            label: 'Separator'
        }
    },
    sources: ['output']
});

// Greater Than Operator;