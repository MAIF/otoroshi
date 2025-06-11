import React, { useState } from 'react'
import { NgForm } from '../../components/nginputs';

export function ModalEditor({ node }) {

    if (!node)
        return null

    const [state, setState] = useState()

    const schema = {
        description: {
            type: 'string',
            label: 'Description',
            help: 'A comment for readability/debugging',
        },
        enabled: {
            label: 'Enabled',
            type: 'box-bool',
            props: {
                description: 'Boolean to toggle the node',
            },
        },
        result: {
            type: 'string',
            label: 'Result',
            props: {
                description: 'Name of memory variable to store output'
            }
        },
        returned: {
            label: 'Returned value',
            type: 'string',
            props: {
                description: 'Immediate result to return from the workflow '
            }
        },
        ...node.schema
    }
    const flow = [
        {
            type: 'group',
            name: 'Informations',
            collapsable: false,
            fields: ['description', 'enabled', 'result', 'returned'],
        },
        {
            type: 'group',
            name: 'Configuration',
            fields: node.flow
        }
    ]

    console.log(node)

    return <div className='modal-editor'>
        <p className='p-3 whats-next-title'>{node.name}</p>
        <div className='p-3'>
            <NgForm
                schema={schema}
                flow={flow}
                value={state}
                onChange={setState} />
        </div>
    </div>
}