import React from 'react'

export const ErrorNode = (_workflow) => ({
    label: <i className="fas fa-exclamation-triangle" />,
    name: 'Stop and Error',
    description: 'Throw an error in the workflow',
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