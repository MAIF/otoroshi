import React from 'react';
import { FromMemory, FromMemoryFlow } from './FromMemory';

export const MapRenameOperator = {
  kind: '$map_rename',
  flow: ['fromMemory', FromMemoryFlow, 'old_key', 'new_key', 'map'],
  form_schema: {
    ...FromMemory({ isArray: true }),
    old_key: {
      type: 'string',
      label: 'Old key',
    },
    new_key: {
      type: 'string',
      label: 'New key',
    },
    map: {
      type: 'object',
      label: 'Source Map',
      visible: (props) => !props.fromMemory,
    },
  },
  sources: ['output'],
  operators: true,
};
