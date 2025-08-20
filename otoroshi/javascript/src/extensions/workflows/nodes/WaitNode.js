import React from 'react'

export const WaitNode = (_workflow) => {
    return {
        label: 'fas fa-clock',
        name: 'Wait',
        workflow: _workflow,
        kind: 'wait',
        flow: ['duration'],
        schema: {
            duration: {
                type: 'number',
                label: ' Duration'
            }
        },
        sources: ['output'],
        nodeRenderer: props => {
            return <div>{props.data.workflow?.duration || 0} ms</div>
        }
    }
}