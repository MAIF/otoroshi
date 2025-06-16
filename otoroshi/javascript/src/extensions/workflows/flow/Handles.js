import React from 'react'
import { Handle, Position, useNodeConnections } from '@xyflow/react'

function RightHandle({ handle, className, selected }) {
    return <Handle
        id={handle.id}
        type="source"
        position={Position.Right}
        className={className}
    >
        {handle.id}
        <div className={`handle-dot ms-1 ${selected ? 'handle-dot--selected' : ''}`} />
    </Handle>
}

export default function Handles(props) {

    const connections = useNodeConnections()

    console.log(connections)

    return <>
        <div className="handles targets">
            {props.data.targetHandles.map((handle) => {
                const selected = connections.find(connection => connection.targetHandle === handle.id)
                return <Handle
                    key={handle.id}
                    id={handle.id}
                    type="target"
                    position={Position.Left}
                >
                    <div className={`handle-dot me-1 ${selected ? 'handle-dot--selected' : ''}`} />
                    {handle.id}
                </Handle>
            })}
        </div>
        <div className="handles sources">
            {props.data.sourceHandles
                .filter(handle => handle.id !== 'output')
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
            {props.data.sourceHandles
                .find(handle => handle.id === 'output') && <RightHandle
                    handle={{ id: 'output' }}
                    className="my-2"
                    selected={connections.find(connection => connection.sourceHandle === 'output')} />}
        </div>
    </>
}