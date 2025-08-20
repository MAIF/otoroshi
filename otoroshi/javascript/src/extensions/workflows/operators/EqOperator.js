import React from 'react';
import { ValueToCheck } from './ValueToCheck';

export const EqOperator = _workflow => ({
    label: "fas fa-equals",
    name: 'Equals',
    kind: '$eq',
    workflow: _workflow,
    flow: ['a', 'b'],
    schema: {
        a: ValueToCheck('First value', false),
        b: ValueToCheck('Second value', false)
    },
    sources: ['output'],
    operator: true
});

// Now Operator;