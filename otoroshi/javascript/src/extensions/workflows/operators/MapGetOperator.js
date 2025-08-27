import React from 'react';
import { FromMemory, FromMemoryFlow } from './FromMemory';

export const MapGetOperator = {
    kind: '$map_get',
    flow: ['key', 'fromMemory', 'map', FromMemoryFlow],
    form_schema: {
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
    operators: true
}