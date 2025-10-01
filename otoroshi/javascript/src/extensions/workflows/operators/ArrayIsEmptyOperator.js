import React from 'react';
import { FromMemory, FromMemoryFlow } from './FromMemory';

export const ArrayIsEmptyOperator = {
  kind: '$array_is_empty',
  flow: ['array', 'fromMemory', FromMemoryFlow],
  form_schema: {
    map: {
      type: 'any',
      label: 'Array',
      visible: (props) => !props.fromMemory,
    },
    ...FromMemory({ isArray: true })
  },
  sources: ['output'],
  operators: true,
};
