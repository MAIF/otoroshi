import React from 'react';
import { ValueToCheck } from './ValueToCheck';

export const NotOperator = {
    kind: '$not',
    flow: ['value'],
    form_schema: {
        value: ValueToCheck('Boolean Value')
    },
    sources: ['output'],
    operators: true
}