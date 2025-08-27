import React from 'react';
import { FromMemory, FromMemoryFlow } from './FromMemory';

export const MapDelOperator = {
    kind: '$map_del',
    flow: ['key', 'fromMemory', 'map', FromMemoryFlow],
    form_schema: {
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
    sources: ['output'],
    operators: true
}