import React, { useState } from 'react'
import { NgForm } from '../../components/nginputs';

export function ModalEditor({ node }) {
    
    if (!node)
        return null

    const [state, setState] = useState(node.data.workflow)

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
        ...node.data.schema
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
            fields: [...(node.data.flow || []), 'result']
        }
    ]

    return <div className='modal-editor'>
        <p className='p-3 m-0 whats-next-title'>{node.data.name}</p>
        <div className='p-3'>
            <NgForm
                schema={schema}
                flow={flow}
                value={state}
                onChange={newData => {
                    node.data.functions.handleWorkflowChange(node.id, newData)
                    setState(newData)
                }} />
        </div>
    </div>
}