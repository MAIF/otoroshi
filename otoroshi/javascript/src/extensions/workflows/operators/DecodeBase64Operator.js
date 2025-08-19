import React from 'react';
import { ValueToCheck } from './ValueToCheck';

export const DecodeBase64Operator = _workflow => ({
    label: "fas fa-unlock",
    name: 'Decode Base64',
    kind: '$decode_base64',
    workflow: _workflow,
    flow: ['value'],
    schema: {
        value: ValueToCheck('Base64 String')
    },
    sources: ['output'],
    operator: true
});

// Subtract Operator;