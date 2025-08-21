import React from 'react';
import { FromMemory, FromMemoryFlow } from './FromMemory';
import { ValueToCheck } from './ValueToCheck';

export const ArrayAppendOperator = {
    kind: '$array_append',
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
    operators: true
}