import React from 'react';
import { FromMemory, FromMemoryFlow } from './FromMemory';

export const ArrayAtOperator = _workflow => ({
    label: "fas fa-list-ol",
    name: 'Array At',
    kind: '$array_at',
    workflow: _workflow,
    flow: ['idx', 'fromMemory', 'array', FromMemoryFlow],
    schema: {
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
    operator: true
});

// Parse Date Operator;