import React from 'react';
import { ValueToCheck } from './ValueToCheck';

export const StrSplitOperator = _workflow => ({
    label: "fas fa-cut",
    name: 'String Split',
    kind: '$str_split',
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