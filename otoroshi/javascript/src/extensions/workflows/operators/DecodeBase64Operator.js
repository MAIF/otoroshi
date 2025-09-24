import React from 'react';
import { ValueToCheck } from './ValueToCheck';

export const DecodeBase64Operator = {
  label: 'fas fa-unlock',
  name: 'Decode Base64',
  kind: '$decode_base64',
  flow: ['value'],
  form_schema: {
    value: ValueToCheck('Base64 String'),
  },
  sources: ['output'],
  operators: true,
};
