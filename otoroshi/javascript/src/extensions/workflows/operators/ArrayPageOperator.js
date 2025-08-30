import React from 'react';
import { FromMemory, FromMemoryFlow } from './FromMemory';

export const ArrayPageOperator = {
  kind: '$array_page',
  flow: ['page', 'page_size', 'fromMemory', 'array', FromMemoryFlow],
  form_schema: {
    ...FromMemory({ isArray: true }),
    page: {
      type: 'number',
      label: 'Page Number',
    },
    page_size: {
      type: 'number',
      label: 'Page Size',
    },
    array: {
      type: 'array',
      label: 'Source Array',
      array: true,
      format: null,
    },
  },
  sources: ['output'],
};
