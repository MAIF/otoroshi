import React, { useState } from 'react'
import { NgForm } from '../../components/nginputs';

export function ModalEditor({ node }) {

    if (!node)
        return null

    const schema = {
        description: {
            type: 'string',
            label: 'Description',
            help: 'A comment for readability/debugging',
        },
        enabled: {
            label: 'Enabled',
            type: 'bool',
        },
        result: {
            type: 'string',
            label: 'Result',
            help: 'Name of memory variable to store output'

        },
        returned: {
            label: 'Returned value',
            type: 'string',
            help: 'Immediate result to return from the workflow'
        },
        ...node.schema
    }
    const flow = [
        {
            type: 'group',
            name: 'Informations',
            // collapsed: true,
            fields: ['enabled', 'description'],
        },
        {
            type: 'group',
            name: 'Configuration',
            fields: [...(node.flow || []), 'result', 'returned']
        }
    ]

    return <div className='modal-editor'>
        <p className='p-3 m-0 whats-next-title'>{node.name}</p>
        <div className='p-3'>
            <NgForm
                schema={schema}
                flow={flow}
                value={node.data.workflow}
                onChange={newData => node.data.functions.handleDataChange(node.id, newData)} />
        </div>
    </div>
}