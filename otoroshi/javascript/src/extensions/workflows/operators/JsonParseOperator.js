import React from 'react';
import { ValueToCheck } from './ValueToCheck'
import { FromMemory, FromMemoryFlow } from './FromMemory';

export const JsonParseOperator = {
    kind: '$json_parse',
    flow: ['fromMemory', 'value', FromMemoryFlow],
    schema: {
        ...FromMemory(),
        value: {
            ...ValueToCheck("The JSON string to parse"),
            visible: props => !props.fromMemory
        },
    },
    sources: ['output'],
    operators: true
}