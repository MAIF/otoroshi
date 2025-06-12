
import React from 'react'

import { Handle, Position, useNodeConnections } from '@xyflow/react';

export function Node(props) {
    const { data } = props
    const isFirst = data.isFirst
    const isLast = data.isLast

    const connections = useNodeConnections();

    return (
        <>
            {!isFirst && <Handle type="source" position={Position.Left} />}

            {props.children ? props.children : <button
                className={`
                    d-flex-center m-0 node 
                    ${props.selected ? 'node--selected' : ''}
                    ${isFirst ? 'node--first' : ''}
                `}
                onDoubleClick={e => {
                    e.stopPropagation()
                    data.functions.onDoubleClick(data)
                }}
            >
                <div className='node-one-output d-flex-center'>
                    {data.label || data.item?.label}
                </div>
                <i className='fas fa-trash node-trash' onClick={e => {
                    e.stopPropagation()
                    props.handleDeleteNode ? props.handleDeleteNode() : data.functions.handleDeleteNode(props.id)
                }} />
            </button>}

            {!isLast && <Handle type="target" position={Position.Right} id="a" />}

            {connections.length < 2 && !isLast && !isFirst &&
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