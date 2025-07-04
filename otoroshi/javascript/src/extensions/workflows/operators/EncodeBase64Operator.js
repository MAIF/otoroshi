import React from 'react';
import { ValueToCheck } from './ValueToCheck';

export const EncodeBase64Operator = _workflow => ({
    label: <i className="fas fa-lock" />,
    name: 'Encode Base64',
    kind: '$encode_base64',
    description: 'Encodes a string in base64',
    workflow: _workflow,
    flow: ['value'],
    schema: {
        value: ValueToCheck('Value to encode')
    },
    sources: [],
    operator: true
});

// Expression Language Operator;