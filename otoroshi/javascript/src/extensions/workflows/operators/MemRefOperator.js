import React from 'react';

export const MemRefOperator = {
    kind: '$mem_ref',
    sources: ['output'],
    operators: true,
    nodeRenderer: props => {
        const memRef = props.data.workflow ? props.data.workflow["$mem_ref"] : {}
        return <div className='assign-node'>
            <span >{memRef?.name}</span>
        </div>
    }
}