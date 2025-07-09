import React from 'react'

export const WaitNode = (_workflow) => {
    return {
        label: <i className='fas fa-clock' />,
        name: 'Wait',
        description: 'Wait before continue with execution',
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