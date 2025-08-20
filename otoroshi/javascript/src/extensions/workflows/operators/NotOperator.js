import React from 'react';
import { ValueToCheck } from './ValueToCheck';

export const NotOperator = _workflow => ({
    label: "fas fa-exclamation",
    name: 'Not',
    kind: '$not',
    workflow: _workflow,
    flow: ['value'],
    schema: {
        value: ValueToCheck('Boolean Value')
    },
    sources: ['output'],
    operator: true
});

// Projection Operator;