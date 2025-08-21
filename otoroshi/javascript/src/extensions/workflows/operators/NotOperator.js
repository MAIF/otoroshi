import React from 'react';
import { ValueToCheck } from './ValueToCheck';

export const NotOperator = {
    kind: '$not',
    flow: ['value'],
    schema: {
        value: ValueToCheck('Boolean Value')
    },
    sources: ['output'],
    operators: true
}