
import React from 'react'

import { Handle, Position } from '@xyflow/react';

export function Node(props) {
    const { data } = props

    const isSelected = false
    const isFirst = data.isFirst
    const isLast = data.isLast

    const isTargetConnected = data.edges?.find(f => f.target === props.id)

    // console.log(props)

    return (
        <>
            {!isFirst && <Handle type="source" position={Position.Left} />}

            <button
                className={`
                    d-flex-center m-0 node 
                    ${isSelected ? 'node--selected' : ''}
                    ${isFirst ? 'node--first' : ''}
                `}
                onDoubleClick={() => {
                    data.functions.onDoubleClick(data)
                }}>
                <div className='node-one-output d-flex-center'>
                    {data.label || data.item?.label}
                </div>
                <i className='fas fa-trash node-trash' />
            </button>

            {!isLast && <Handle type="target" position={Position.Right} id="a" />}

            {!isTargetConnected && !isLast &&
                <div className='node-one-output-dot'>
                    <div className='node-one-output-bar'></div>
                    <div className='node-one-output-add' onClick={e => {
                        e.stopPropagation()
                        data.functions.openNodesExplorer(props)
                    }}>
                        <i className='fas fa-plus' />
                    </div>
                </div>}
        </>
    );
}