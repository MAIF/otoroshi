import React from 'react';
import { FromMemory, FromMemoryFlow } from './FromMemory';
import { ValueToCheck } from './ValueToCheck';

export const ContainsOperator = {
  kind: '$contains',
  flow: ['value', 'fromMemory', 'container', FromMemoryFlow],
  form_schema: {
    ...FromMemory(),
    value: ValueToCheck(),
    container: {
      type: 'json',
      label: 'Container',
      visible: (props) => !props?.fromMemory,
    },
  },
  sources: ['output'],
  operators: true,
};
