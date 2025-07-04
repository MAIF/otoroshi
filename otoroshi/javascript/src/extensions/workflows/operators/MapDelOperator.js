import React from 'react';
import { FromMemory, FromMemoryFlow } from './FromMemory';

export const MapDelOperator = _workflow => ({
    label: <i className="fas fa-minus-square" />,
    name: 'Map Delete',
    kind: '$map_del',
    description: 'Deletes a key from a map',
    workflow: _workflow,
    flow: ['key', 'fromMemory', 'map', FromMemoryFlow],
    schema: {
        ...FromMemory({ isArray: true }),
        key: {
            type: 'string',
            label: 'Key to Delete'
        },
        map: {
            type: 'object',
            label: 'Source Map',
            visible: props => !props.fromMemory
        }
    },
    sources: [],
    operator: true
});

// Array Delete Operator;