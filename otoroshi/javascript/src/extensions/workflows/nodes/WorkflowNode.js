import React from 'react'

export const WorkflowNode = (_workflow) => {
    return {
        label: <i className="fas fa-project-diagram" />,
        name: 'Execute Sub-workflow',
        description: 'Helpers for calling other Otoroshi workflows.',
        type: 'group',
        workflow: _workflow,
        kind: 'workflow',
        sources: [],
        targets: []
        // {
        //   "kind": "workflow",
        //   "steps": [ <Node> ],
        //   "returned": <Value>
        // }
    }
}