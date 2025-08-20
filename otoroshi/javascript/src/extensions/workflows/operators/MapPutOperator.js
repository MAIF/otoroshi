import React from 'react';
import { FromMemory, FromMemoryFlow } from './FromMemory';
import { ValueToCheck } from './ValueToCheck';

export const MapPutOperator = _workflow => ({
    label: "fas fa-plus-square",
    name: 'Map Put',
    kind: '$map_put',
    workflow: _workflow,
    flow: ['key', 'value', 'fromMemory', 'map', FromMemoryFlow],
    schema: {
        ...FromMemory(),
        key: {
            type: 'string',
            label: 'Key'
        },
        value: ValueToCheck('The value to put'),
        map: {
            type: 'object',
            label: 'Target Map',
            props: {
                description: "The map to put the key-value pair in"
            },
            visible: props => !props.fromMemory
        }
    },
    sources: ['output'],
    operator: true
});

// Multiply Operator;