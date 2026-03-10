import React from 'react';
import { ValueToCheck } from './ValueToCheck';

export const EqOperator = {
  kind: '$eq',
  flow: ['a', 'b'],
  form_schema: {
    a: ValueToCheck('First value'),
    b: ValueToCheck('Second value'),
  },
  sources: ['output'],
  operators: true,
};
