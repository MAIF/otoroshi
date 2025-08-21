import React from 'react';
import { FromMemory, FromMemoryFlow } from './FromMemory';
import { ValueToCheck } from './ValueToCheck';

export const ContainsOperator = {
    kind: '$contains',
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
    operators: true
}