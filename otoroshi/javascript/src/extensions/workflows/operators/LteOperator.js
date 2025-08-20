import React from 'react';

export const LteOperator = _workflow => ({
    label: "fas fa-less-than-equal",
    name: 'Less Than or Equal',
    kind: '$lte',
    workflow: _workflow,
    flow: ['a', 'b'],
    schema: {
        a: {
            type: 'code',
            label: 'First Number',
            props: {
                editorOnly: true
            }
        },
        b: {
            type: 'code',
            label: 'Second Number',
            props: {
                editorOnly: true
            }
        }
    },
    sources: ['output'],
    operator: true
});

// String Split Operator;