
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

const handleStyle = { left: 10 };

export function Node({ data }) {
    const onChange = useCallback((evt) => {
        console.log(evt.target.value);
    }, []);

    const isSelected = false

    return (
        <>
            <Handle type="target" position={Position.Left} />
            
            <button
                id="node"
                className={`d-flex items-center justify-center m-0 p-3 node ${isSelected ? 'node--selected' : ''}`}
                onClick={() => onClick(data)}>

                <div className='node-one-output'>
                    {/* <div className='node-one-output-dot'></div>
                    <div className='node-one-output-bar'></div>
                    <div className='node-one-output-add' onClick={e => {
                        e.stopPropagation()
                        openNodesExplorer()
                    }}>
                        <i className='fas fa-plus' />
                    </div> */}
                </div>
            </button>
            <Handle type="source" position={Position.Right} id="a" />
            {/* <Handle type="source" position={Position.Bottom} id="b" style={handleStyle} /> */}
        </>
    );
}