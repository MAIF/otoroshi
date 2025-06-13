import React from 'react'
import { Handle, Position } from '@xyflow/react'

export default function Handles(props) {
    return <>
        <div className="handles targets">
            {props.data.targetHandles.map((handle) => {
                return <Handle
                    key={handle.id}
                    id={handle.id}
                    type="target"
                    position={Position.Left}
                >
                    <div className='handle-dot me-1' />
                    {handle.id}
                </Handle>
            })}
        </div>
        <div className="handles sources">
            {props.data.sourceHandles.map((handle) => {
                return <Handle
                    key={handle.id}
                    id={handle.id}
                    type="source"
                    position={Position.Right}
                >
                    {handle.id}
                    <div className='handle-dot ms-1' />
                </Handle>
            })}
        </div>
    </>
}