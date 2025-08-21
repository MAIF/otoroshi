import React from 'react'

export const WaitNode = {
    kind: 'wait',
    sources: ['output'],
    nodeRenderer: props => {
        return <div>{props.data.workflow?.duration || 0} ms</div>
    }
}
