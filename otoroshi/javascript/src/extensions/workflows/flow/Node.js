
import React, { useEffect } from 'react'

import Handles from './Handles';
import NodeTrashButton from './NodeTrashButton';
import { Handle, Position } from '@xyflow/react';

export function Node(props) {
    const { data } = props
    const isFirst = data.isFirst

    return (
        <>
            <Handles {...props} />

            <button
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
                    {data.label || data.item?.label} {data.name}
                </div>

                <NodeTrashButton {...props} />
            </button>
        </>
    );
}