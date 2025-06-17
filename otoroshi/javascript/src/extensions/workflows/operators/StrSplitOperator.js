import React from 'react';
import { ValueToCheck } from './ValueToCheck';

export const StrSplitOperator = _workflow => ({
    label: <i className="fas fa-cut" />,
    name: 'String Split',
    kind: '$str_split',
    description: 'Splits a string into an array based on a regex',
    workflow: _workflow,
    flow: ['value', 'regex'],
    schema: {
        value: {
            type: 'string',
            label: 'String to Split'
        },
        regex: ValueToCheck('Regex Pattern')
    },
    sources: ['output']
});

// Array Prepend Operator;