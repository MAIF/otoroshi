import React from 'react';
import { ValueToCheck } from './ValueToCheck';

export const StrSplitOperator = {
    kind: '$str_split',
    flow: ['value', 'regex'],
    form_schema: {
        value: {
            type: 'string',
            label: 'String to Split'
        },
        regex: ValueToCheck('Regex Pattern')
    },
    sources: ['output']
}