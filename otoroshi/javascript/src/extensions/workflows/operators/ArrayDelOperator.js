import React from 'react';
import { FromMemory, FromMemoryFlow } from './FromMemory';

export const ArrayDelOperator = _workflow => ({
    label: "fas fa-trash",
    name: 'Array Delete',
    kind: '$array_del',
    workflow: _workflow,
    flow: ['idx', 'fromMemory', 'array', FromMemoryFlow],
    schema: {
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
    operator: true
});

// Decrement Operator;