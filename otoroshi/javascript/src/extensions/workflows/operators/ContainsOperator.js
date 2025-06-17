import React from 'react';
import { FromMemory, FromMemoryFlow } from './FromMemory';
import { ValueToCheck } from './ValueToCheck';

export const ContainsOperator = _workflow => ({
    label: <i className="fas fa-search-plus" />,
    name: 'Contains',
    kind: '$contains',
    description: 'Checks if a value is contained in a container',
    workflow: _workflow,
    flow: ['value', 'fromMemory', 'container', FromMemoryFlow],
    schema: {
        ...FromMemory(),
        value: ValueToCheck(),
        container: {
            type: 'code',
            label: 'Container',
            props: {
                ace_config: {
                    maxLines: 1,
                    fontSize: 14,
                },
                editorOnly: true,
            },
            visible: (props) => !props?.fromMemory
        }
    },
    sources: ['output'],
    operator: true
});

// Greater Than or Equal Operator;