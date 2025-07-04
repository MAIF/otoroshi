import React from 'react';
import { ValueToCheck } from './ValueToCheck'
import { FromMemory, FromMemoryFlow } from './FromMemory';

export const JsonParseOperator = _workflow => ({
    label: <i className="fas fa-file-code" />,
    name: 'JSON Parse',
    kind: '$json_parse',
    description: 'Parses a JSON string',
    workflow: _workflow,
    flow: ['fromMemory', 'value', FromMemoryFlow],
    schema: {
        ...FromMemory(),
        value: {
            ...ValueToCheck("The JSON string to parse"),
            visible: props => !props.fromMemory
        },
    },
    sources: [],
    operator: true
});

// Contains Operator;