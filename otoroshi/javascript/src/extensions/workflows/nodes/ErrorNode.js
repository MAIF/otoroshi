import React from 'react'

export const ErrorNode = (_workflow) => ({
    label: <i className="fas fa-exclamation-triangle" />,
    name: 'Stop and Error',
    description: 'Throw an error in the workflow',
    workflow: _workflow,
    //     {
    //   "kind": "error",
    //   "message": "<error_message>",
    //   "details": { ... }
    // }
})