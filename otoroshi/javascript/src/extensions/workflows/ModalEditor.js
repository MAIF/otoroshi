import React, { useState } from 'react'
import { NgForm } from '../../components/nginputs';

function getWorkflow(node) {

    const { workflow, kind } = node.data

    if (kind.startsWith('$') && workflow)
        return workflow[kind]

    return workflow
}

function setEnabled(state) {
    if (state?.enabled === undefined)
        return {
            ...state,
            enabled: true
        }
    return state
}

export function ModalEditor({ node }) {

    if (!node)
        return null

    const isAnOperator = node.data.operator

    console.log(node.data)

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
            fields: [!isAnOperator ? 'enabled' : '', 'description'].filter(field => field.length > 0),
        },
        {
            type: 'group',
            name: 'Configuration',
            fields: [...(node.data.flow || []), !isAnOperator ? 'result' : ''].filter(field => field.length > 0)
        }
    ]

    const value = setEnabled(getWorkflow(node))

    const [state, setState] = useState(value ? Object.fromEntries(Object.entries(value).filter(([key, _]) => Object.keys(schema).includes(key))) : {})

    console.log('state', state)

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