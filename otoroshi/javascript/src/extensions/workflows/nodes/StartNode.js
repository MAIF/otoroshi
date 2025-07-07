import React from 'react'

export function StartNode(_workflow) {
    return {
        label: <i className='fas fa-box' />,
        name: 'Start',
        kind: 'start',
        description: 'Start',
        workflow: _workflow,
        sources: ['output'],
        nodeRenderer: props => {
            return <div className="start-node">
                <svg xmlns="http://www.w3.org/2000/svg" fill="%23f9b000" viewBox="0 0 24 24">
                    <path fillRule="evenodd"
                        d="m3.75 13.5 10.5-11.25L12 10.5h8.25L9.75 21.75 12 13.5H3.75Z" /></svg>
            </div>
        }
    }
}