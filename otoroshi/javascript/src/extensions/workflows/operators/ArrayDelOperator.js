import React from 'react';
import { FromMemory, FromMemoryFlow } from './FromMemory';

export const ArrayDelOperator = {
    kind: '$array_del',
    flow: ['idx', 'fromMemory', 'array', FromMemoryFlow],
    form_schema: {
        ...FromMemory({ isArray: true }),
        idx: {
            type: 'number',
            label: 'Index to Delete'
        },
        array: {
            type: 'array',
            label: 'Source Array',
            array: true,
            format: null
        }
    },
    sources: ['output'],
    operators: true
}