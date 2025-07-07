import React from 'react';
import { FromMemory, FromMemoryFlow } from './FromMemory';
import { isArray } from 'lodash';
import { ValueToCheck } from './ValueToCheck';

export const ArrayPrependOperator = _workflow => ({
    label: <i className="fas fa-arrow-left" />,
    name: 'Array Prepend',
    kind: '$array_prepend',
    description: 'Prepends a value to an array',
    workflow: _workflow,
    flow: [
        'value',
        'fromMemory',
        'array',
        FromMemoryFlow
    ],
    schema: {
        ...FromMemory({ isArray: true }),
        value: ValueToCheck('Value to Prepend'),
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

// Decode Base64 Operator;