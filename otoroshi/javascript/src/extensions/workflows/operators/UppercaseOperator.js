import React from 'react'

export const UppercaseOperator = _workflow => ({
    label: "fa-arrow-up",
    name: 'String Uppercase',
    kind: '$str_upper_case',
    workflow: _workflow,
    sources: ['output']
});