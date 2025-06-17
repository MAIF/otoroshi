import React from 'react';
import { FromMemory, FromMemoryFlow } from './FromMemory';

export const ArrayPageOperator = _workflow => ({
    label: <i className="fas fa-file-alt" />,
    name: 'Array Page',
    kind: '$array_page',
    description: 'Gets a page of an array',
    workflow: _workflow,
    flow: ['page', 'page_size', 'fromMemory', 'array', FromMemoryFlow],
    schema: {
        ...FromMemory({ isArray: true }),
        page: {
            type: 'number',
            label: 'Page Number'
        },
        page_size: {
            type: 'number',
            label: 'Page Size'
        },
        array: {
            type: 'array',
            label: 'Source Array',
            array: true,
            format: null
        }
    },
    sources: ['output']
});

// Map Get Operator;