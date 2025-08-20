import React from 'react'

export const ErrorNode = (_workflow) => ({
    label: "fas fa-exclamation-triangle",
    name: 'Stop and Error',
    workflow: _workflow,
    kind: 'error',
    sources: ['output'],
})