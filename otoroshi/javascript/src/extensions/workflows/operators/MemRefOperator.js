import React from 'react';

export const MemRefOperator = _workflow => {
    return {
        label: "fas fa-memory",
        name: 'Memory Reference',
        kind: '$mem_ref',
        workflow: _workflow,
        sources: ['output'],
        operator: true,
        nodeRenderer: props => {
            const memRef = props.data.workflow ? props.data.workflow["$mem_ref"] : {}
            return <div className='assign-node'>
                <span >{memRef?.name}</span>
            </div>
        }
    }
}