import React from 'react';
import { ValueToCheck } from './ValueToCheck';

export const NeqOperator = _workflow => ({
    label: <i className="fas fa-not-equal" />,
    name: 'Not Equals',
    kind: '$neq',
    description: 'Checks if two values are not equal',
    workflow: _workflow,
    flow: ['a', 'b'],
    schema: {
        a: ValueToCheck('First value'),
        b: ValueToCheck('Second value')
    },
    sources: ['output'],
    operator: true
});

// Array At Operator;