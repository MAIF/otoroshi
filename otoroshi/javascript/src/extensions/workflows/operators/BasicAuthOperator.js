import React from 'react';

export const BasicAuthOperator = _workflow => ({
    label: <i className="fas fa-shield-alt" />,
    name: 'Basic Auth',
    kind: '$basic_auth',
    description: 'Returns a basic authentication header',
    workflow: _workflow,
    flow: ['user', 'password'],
    schema: {
        user: {
            type: 'string',
            label: 'Username'
        },
        password: {
            type: 'string',
            label: 'Password'
        }
    },
    sources: [],
    operator: true
});

// Equals Operator;