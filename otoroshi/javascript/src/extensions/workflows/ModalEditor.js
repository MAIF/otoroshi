import React, { useState } from 'react'
import { NgForm } from '../../components/nginputs';

function getWorkflow(node) {

    const { workflow, kind } = node.data

    if (kind.startsWith('$') && workflow)
        return workflow[kind]

    return workflow
}

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

    const value = getWorkflow(node)

    const [state, setState] = useState(value ? Object.fromEntries(Object.entries(value).filter(([key, _]) => Object.keys(schema).includes(key))) : {})

    return <div className='modal-editor'>
        <p className='p-3 m-0 whats-next-title'>{node.data.name}</p>
        <div className='p-3'>
            <NgForm
                schema={schema}
                flow={flow}
                value={state}
                onChange={newData => {
                    if (node.data.operator) {
                        node.data.functions.handleWorkflowChange(node.id, {
                            [node.data.kind]: newData
                        })
                    } else {
                        node.data.functions.handleWorkflowChange(node.id, newData)
                    }
                    setState(newData)
                }} />
        </div>
    </div>
}