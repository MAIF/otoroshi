export const IsTruthyOperator = {
    kind: '$is_truthy',
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
    sources: ['output'],
    operators: true
}