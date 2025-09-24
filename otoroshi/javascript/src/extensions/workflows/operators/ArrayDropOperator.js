import React from 'react';
import { FromMemory, FromMemoryFlow } from './FromMemory';
import { ValueToCheck } from './ValueToCheck';

export const ArrayDropOperator = {
  kind: '$$array_drop',
  flow: ['n', 'fromMemory', 'array', FromMemoryFlow],
  form_schema: {
    n: {
      type: 'number',
      label: 'N',
      props: {
        description: 'The number of elements to drop'
      }
    },
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
