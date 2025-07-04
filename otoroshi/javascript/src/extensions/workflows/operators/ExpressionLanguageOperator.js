import React from 'react';

export const ExpressionLanguageOperator = _workflow => ({
    label: <i className="fas fa-code" />,
    name: 'Expression Language',
    kind: '$expression_language',
    description: 'Evaluates an expression language',
    workflow: _workflow,
    flow: ['expression'],
    schema: {
        expression: {
            type: 'string',
            label: 'Expression'
        }
    },
    sources: [],
    operator: true
});

// Not Operator;