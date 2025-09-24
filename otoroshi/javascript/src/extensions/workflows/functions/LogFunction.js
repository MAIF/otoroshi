import React from 'react'


export const LogFunction = {
    kind: 'core.log',
    nodeRenderer: props => {
        const { message } = props.data.content?.args || {}
        return (
            <div className='node-text-renderer'>
                <span>{message?.slice(0, 20)}...</span>
            </div>
        );
    }
}