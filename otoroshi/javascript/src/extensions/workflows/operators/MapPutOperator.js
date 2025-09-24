import React from 'react';
import { FromMemory, FromMemoryFlow } from './FromMemory';
import { ValueToCheck } from './ValueToCheck';

export const MapPutOperator = {
  kind: '$map_put',
  flow: ['key', 'value', 'fromMemory', 'map', FromMemoryFlow],
  form_schema: {
    ...FromMemory(),
    key: {
      type: 'string',
      label: 'Key',
    },
    value: ValueToCheck('The value to put'),
    map: {
      type: 'object',
      label: 'Target Map',
      props: {
        description: 'The map to put the key-value pair in',
      },
      visible: (props) => !props.fromMemory,
    },
  },
  sources: ['output'],
  operators: true,
};
