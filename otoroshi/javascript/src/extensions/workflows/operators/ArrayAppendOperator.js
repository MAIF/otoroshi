import React from 'react';
import { FromMemory, FromMemoryFlow } from './FromMemory';
import { ValueToCheck } from './ValueToCheck';

export const ArrayAppendOperator = {
  kind: '$array_append',
  flow: ['value', 'fromMemory', 'array', FromMemoryFlow],
  form_schema: {
    ...FromMemory({ isArray: true }),
    value: ValueToCheck('Value to Append'),
    array: {
      type: 'json',
      label: 'Target Array',
      visible: (props) => !props?.fromMemory,
    },
  },
  sources: ['output'],
  operators: true,
};
