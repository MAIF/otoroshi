import React from 'react';

export const AddOperator = _workflow => ({
    label: "fas fa-plus",
    name: 'Add',
    kind: '$add',
    workflow: _workflow,
    operator: true
});