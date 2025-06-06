
import React from 'react'

// export function Node({ children, data, onClick, isSelected, openNodesExplorer }) {
//     return <button
//         id="node"
//         className={`d-flex items-center justify-center m-0 p-3 node ${isSelected ? 'node--selected' : ''}`}
//         onClick={() => onClick(data)}>

//         <div className='node-one-output'>
//             <div className='node-one-output-dot'></div>
//             <div className='node-one-output-bar'></div>
//             <div className='node-one-output-add' onClick={e => {
//                 e.stopPropagation()
//                 openNodesExplorer()
//             }}>
//                 <i className='fas fa-plus' />
//             </div>
//         </div>
//         {children}
//     </button>
// }

import { useCallback } from 'react';
import { Handle, Position } from '@xyflow/react';

export function Node({ data }) {
    const isSelected = false

    return (
        <>
            <Handle type="target" position={Position.Left} />

            <button
                className={`d-flex-center m-0 node ${isSelected ? 'node--selected' : ''}`}
                onClick={() => {

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