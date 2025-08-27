import React from 'react';
import { ValueToCheck } from './ValueToCheck';

export const EqOperator = {
    kind: '$eq',
    flow: ['a', 'b'],
    form_schema: {
        a: ValueToCheck('First value', false),
        b: ValueToCheck('Second value', false)
    },
    sources: ['output'],
    operators: true
}