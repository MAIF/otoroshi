import React from 'react';
import { ValueToCheck } from './ValueToCheck';

export const DecodeBase64Operator = _workflow => ({
    label: <i className="fas fa-unlock" />,
    name: 'Decode Base64',
    kind: '$decode_base64',
    description: 'Decodes a base64 string',
    workflow: _workflow,
    flow: ['value'],
    schema: {
        value: ValueToCheck('Base64 String')
    },
    sources: [],
    operator: true
});

// Subtract Operator;