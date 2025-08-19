import React from 'react';

export const BasicAuthOperator = _workflow => ({
    label: "fas fa-shield-alt",
    name: 'Basic Auth',
    kind: '$basic_auth',
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
    sources: ['output'],
    operator: true
});

// Equals Operator;