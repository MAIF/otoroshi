import React from 'react';
import { ValueToCheck } from './ValueToCheck';

export const EncodeBase64Operator = {
  kind: '$encode_base64',
  flow: ['value'],
  form_schema: {
    value: ValueToCheck('Value to encode'),
  },
  sources: ['output'],
  operators: true,
};
