import React from 'react';

export const MemRefOperator = _workflow => {
    return {
        label: "fas fa-memory",
        name: 'Memory Reference',
        kind: '$mem_ref',
        workflow: _workflow,
        flow: ['name', 'path'],
        schema: {
            name: {
                type: 'string',
                label: 'Memory Name',
            },
            path: {
                type: 'string',
                label: 'Memory Path',
                help: 'Only useful if the variable is an object'
            }
        },
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