import React from 'react';
import { FromMemory, FromMemoryFlow } from './FromMemory';

export const ArrayDelOperator = _workflow => ({
    label: <i className="fas fa-trash" />,
    name: 'Array Delete',
    kind: '$array_del',
    description: 'Deletes an element from an array',
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
    sources: [],
    operator: true
});

// Decrement Operator;