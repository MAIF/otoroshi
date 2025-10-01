import React from 'react';
import { FromMemory, FromMemoryFlow } from './FromMemory';

export const MapIsEmptyOperator = {
  kind: '$map_is_empty',
  flow: ['map', 'fromMemory', FromMemoryFlow],
  form_schema: {
    map: {
      type: 'object',
      label: 'Source Map',
      visible: (props) => !props.fromMemory,
    },
    ...FromMemory({ isArray: true })
  },
  sources: ['output'],
  operators: true,
};
