
import React from 'react'

import { Handle, Position } from '@xyflow/react';

export function Node(props) {

    const { data } = props

    const isSelected = false

    const isTargetConnected = data.edges?.find(f => f.target === props.id)

    return (
        <>
            <Handle type="source" position={Position.Left} />

            <button
                className={`d-flex-center m-0 node ${isSelected ? 'node--selected' : ''}`}
                onDoubleClick={() => {
                    data.onDoubleClick(data)
                }}>
                <div className='node-one-output d-flex-center'>
                    {data.label || data.item?.label}
                </div>
                <i className='fas fa-trash node-trash' />
            </button>

            <Handle type="target" position={Position.Right} id="a" />

            {!isTargetConnected &&
                <div className='node-one-output-dot'>
                    <div className='node-one-output-bar'></div>
                    <div className='node-one-output-add' onClick={e => {
                        e.stopPropagation()
                        data.openNodesExplorer(props)
                    }}>
                        <i className='fas fa-plus' />
                    </div>
                </div>}
        </>
    );
}