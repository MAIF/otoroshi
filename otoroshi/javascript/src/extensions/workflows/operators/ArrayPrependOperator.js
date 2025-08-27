import React from 'react';
import { FromMemory, FromMemoryFlow } from './FromMemory';
import { ValueToCheck } from './ValueToCheck';

export const ArrayPrependOperator = {
    kind: '$array_prepend',
    flow: [
        'value',
        'fromMemory',
        'array',
        FromMemoryFlow
    ],
    form_schema: {
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
    operators: true
}