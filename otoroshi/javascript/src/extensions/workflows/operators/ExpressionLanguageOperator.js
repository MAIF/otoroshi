import React from 'react';

export const ExpressionLanguageOperator = _workflow => ({
    label: "fas fa-code",
    name: 'Expression Language',
    kind: '$expression_language',
    workflow: _workflow,
    flow: ['expression'],
    schema: {
        expression: {
            type: 'string',
            label: 'Expression'
        }
    },
    sources: ['output'],
    operator: true
});

// Not Operator;