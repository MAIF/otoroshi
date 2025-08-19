import React from 'react';
import { FromMemory, FromMemoryFlow } from './FromMemory';
import { ValueToCheck } from './ValueToCheck';

export const ArrayAppendOperator = _workflow => ({
    label: "fas fa-arrow-right",
    name: 'Array Append',
    kind: '$array_append',
    workflow: _workflow,
    flow: [
        'value',
        'fromMemory',
        'array',
        FromMemoryFlow
    ],
    schema: {
        ...FromMemory({ isArray: true }),
        value: ValueToCheck('Value to Append'),
        array: {
            type: 'code',
            label: 'Target Array',
            props: {
                editorOnly: true,
            },
            visible: props => !props?.fromMemory
        }
    },
    sources: ['output'],
    operator: true
});

// Not Equals Operator;