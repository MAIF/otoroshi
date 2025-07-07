import React from 'react';

export const IsFalsyOperator = _workflow => ({
    label: <i className="fas fa-times-circle" />,
    name: 'Is Falsy',
    kind: '$is_falsy',
    description: 'Checks if a value is falsy',
    workflow: _workflow,
    flow: ['value'],
    schema: {
        fromMemory: {
            type: 'box-bool',
            label: 'Read memory',
            props: {
                description: 'Is the value from memory?'
            }
        },
        value: {
            type: 'code',
            label: 'Value to check',
            props: {
                ace_config: {
                    maxLines: 1,
                    fontSize: 14,
                },
                editorOnly: true,
            },
            visible: (props) => !props?.fromMemory
        },
        name: {
            type: 'string',
            label: 'Variable name',
        },
        path: {
            type: 'string',
            label: 'Variable path',
            help: 'Only useful if the variable is an object'
        },
    },
    sources: ['output'],
    operator: true
});

// Add Operator;