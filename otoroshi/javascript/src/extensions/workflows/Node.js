
import React from 'react'

import { Handle, Position } from '@xyflow/react';

export function Node({ data }) {
    const isSelected = false

    return (
        <>
            <Handle type="target" position={Position.Left} />

            <button
                className={`d-flex-center m-0 node ${isSelected ? 'node--selected' : ''}`}
                onDoubleClick={() => {
                    data.onDoubleClick(data)
                }}>
                <div className='node-one-output d-flex-center'>
                    {data.label}
                    {/* <div className='node-one-output-dot'></div>
                    <div className='node-one-output-bar'></div>
                    <div className='node-one-output-add' onClick={e => {
                        e.stopPropagation()
                        openNodesExplorer()
                    }}>
                        <i className='fas fa-plus' />
                    </div> */}
                </div>
                <i className='fas fa-trash node-trash' />
            </button>
            <Handle type="source" position={Position.Right} id="a" />
            {/* <Handle type="source" position={Position.Bottom} id="b" style={handleStyle} /> */}
        </>
    );
}