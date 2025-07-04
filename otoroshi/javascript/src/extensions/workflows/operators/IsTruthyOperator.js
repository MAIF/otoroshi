import React from 'react';

export const IsTruthyOperator = _workflow => ({
    label: <i className="fas fa-check-circle" />,
    name: 'Is Truthy',
    kind: '$is_truthy',
    description: 'Checks if a value is truthy',
    workflow: _workflow,
    flow: [
        'fromMemory',
        'value',
        {
            type: 'group',
            name: 'Memory location',
            collapsable: false,
            collapsed: false,
            fields: ['name', 'path'],
            visible: (props) => props?.fromMemory
        }
    ],
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
    sources: [],
    operator: true
});

// Map Delete Operator;