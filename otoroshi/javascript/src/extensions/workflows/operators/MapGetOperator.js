import React from 'react';
import { FromMemory, FromMemoryFlow } from './FromMemory';

export const MapGetOperator = _workflow => ({
    label: <i className="fas fa-search" />,
    name: 'Map Get',
    kind: '$map_get',
    description: 'Gets a value from a map',
    workflow: _workflow,
    flow: ['key', 'fromMemory', 'map', FromMemoryFlow],
    schema: {
        ...FromMemory({ isArray: true }),
        key: {
            type: 'string',
            label: 'Key',
        },
        map: {
            type: 'object',
            label: 'Source Map',
            visible: props => !props.fromMemory
        }
    },
    sources: ['output'],
    operator: true
});

// Parse Time Operator;