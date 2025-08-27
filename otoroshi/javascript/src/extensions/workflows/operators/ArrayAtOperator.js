import React from 'react';
import { FromMemory, FromMemoryFlow } from './FromMemory';

export const ArrayAtOperator = {
    kind: '$array_at',
    flow: ['idx', 'fromMemory', 'array', FromMemoryFlow],
    form_schema: {
        ...FromMemory({ isArray: true }),
        idx: {
            type: 'number',
            label: 'Index'
        },
        array: {
            type: 'array',
            label: 'Source Array',
            array: true,
            format: null,
            visible: (props) => !props?.fromMemory
        }
    },
    sources: ['output'],
    operators: true
}