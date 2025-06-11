import React from 'react'
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
        }
    }
    const flow = [
        {
            type: 'group',
            name: 'Informations',
            collapsable: false,
            fields: ['description', 'enabled', 'result', 'returned'],
        }
    ]

    console.log(node)

    return <div className='modal-editor p-3'>
        <p style={{ fontSize: '1.15rem' }}>{node.name}</p>
        <NgForm
            schema={schema}
            flow={flow}
            value={node.state}
            onChange={console.log} />
        {/* id: Node identifier
        description: A comment for readability/debugging
        enabled: Boolean to toggle the node
        result: Name of memory variable to store output
        returned: Immediate result to return from the workflow */}
    </div>
}