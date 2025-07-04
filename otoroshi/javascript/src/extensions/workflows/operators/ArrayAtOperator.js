import React from 'react';
import { FromMemory, FromMemoryFlow } from './FromMemory';

export const ArrayAtOperator = _workflow => ({
    label: <i className="fas fa-list-ol" />,
    name: 'Array At',
    kind: '$array_at',
    description: 'Gets an element from an array',
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
    sources: [],
    operator: true
});

// Parse Date Operator;