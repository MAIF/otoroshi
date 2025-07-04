import React from 'react';
import { ValueToCheck } from './ValueToCheck';

export const NotOperator = _workflow => ({
    label: <i className="fas fa-exclamation" />,
    name: 'Not',
    kind: '$not',
    description: 'Negates a boolean value',
    workflow: _workflow,
    flow: ['value'],
    schema: {
        value: ValueToCheck('Boolean Value')
    },
    sources: [],
    operator: true
});

// Projection Operator;