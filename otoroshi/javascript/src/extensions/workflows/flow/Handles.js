import React from 'react'
import { Handle, Position, useNodeConnections } from '@xyflow/react'

function RightHandle({ handle, className, selected }) {
    return <Handle
        id={handle.id}
        type="source"
        position={Position.Right}
        className={`${className} ${(selected ? 'connected' : '')}`}
    >
        {handle.id.split('-')[0]}
        <div className={`handle-dot ms-1 ${selected ? 'handle-dot--selected' : ''}`} />
    </Handle>
}

export default function Handles(props) {

    const connections = useNodeConnections()

    const sources = props.data.sourceHandles
        .reduce((acc, handle) => {
            if (handle.id.startsWith('output')) {
                return { ...acc, output: handle }
            }
            return { ...acc, handles: [...acc.handles, handle] }
        }, { handles: [] })

    return <>
        <div className="handles targets">
            {props.data.targetHandles.map((handle) => {
                const selected = connections.find(connection => connection.targetHandle === handle.id)
                return <Handle
                    key={handle.id}
                    id={handle.id}
                    type="target"
                    position={Position.Left}
                    className={selected ? 'connected' : ''}
                    
                >
                    <div className={`handle-dot me-1 ${selected ? 'handle-dot--selected' : ''}`} />
                    {handle.id.split('-')[0]}
                </Handle>
            })}
        </div>
        <div className="handles sources">
            {sources.handles
                .map(handle => {
                    const selected = connections.find(connection => connection.sourceHandle === handle.id)

                    return <RightHandle handle={handle} key={handle.id} selected={selected} />
                })}
            {props.data.sourcesIsArray && <button
                type="button"
                className="btn btn-primaryColor add-handle"
                onClick={e => {
                    e.stopPropagation()
                    props.data.functions.addHandleSource(props.id)
                }}>
                Add pin <i className='fas fa-plus' />
            </button>}
            {sources.output && <RightHandle
                handle={{ id: sources.output.id }}
                className="my-2"
                selected={connections.find(connection => connection.sourceHandle === `output-${props.id}`)} />}
        </div>
    </>
}