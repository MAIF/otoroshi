import React from 'react'

export const ErrorNode = (_workflow) => ({
    label: "fas fa-exclamation-triangle",
    name: 'Stop and Error',
    workflow: _workflow,
    kind: 'error',
    flow: ['message', 'details'],
    schema: {
        message: {
            type: 'string',
            label: "Message"
        },
        details: {
            type: 'code',
            labe: 'Details',
            props: {
                editorOnly: true
            }
        }
    },
    sources: ['output'],
    //     {
    //   "kind": "error",
    //   "message": "<error_message>",
    //   "details": { ... }
    // }
})