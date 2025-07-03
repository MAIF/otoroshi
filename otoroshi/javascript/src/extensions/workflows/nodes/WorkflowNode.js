import React from 'react'

export const WorkflowNode = (_workflow) => {
    return {
        label: <i className="fas fa-project-diagram" />,
        name: 'Execute Sub-workflow',
        description: 'Helpers for calling other Otoroshi workflows.',
        type: 'group',
        workflow: _workflow,
        kind: 'workflow',
        sourcesIsArray: true,
        sources: ['output'],
        flow: ['returned'],
        schema: {
            returned: {
                type: 'code',
                label: 'Returned value',
                props: {
                    mode: 'json',
                    editorOnly: true,
                },
            }
        },
        targets: [],
        handlePrefix: 'step'
    }
}