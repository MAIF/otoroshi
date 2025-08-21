import React from 'react';
import { ValueToCheck } from './ValueToCheck';

export const NeqOperator = {
    kind: '$neq',
    flow: ['a', 'b'],
    schema: {
        a: ValueToCheck('First value'),
        b: ValueToCheck('Second value')
    },
    sources: ['output'],
    operators: true
}