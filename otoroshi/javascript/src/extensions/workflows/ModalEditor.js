import React, { useState } from 'react'
import { NgForm } from '../../components/nginputs';

function setEnabled(state) {
    if (state?.enabled === undefined)
        return {
            ...state,
            enabled: true
        }
    return state
}

export function ModalEditor({ node, docs }) {

    if (!node)
        return null

    const { data, id } = node

    const isAnOperator = data.operators
    const isAFunction = data.function

    let schema = {
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

        }
    }

    if (isAFunction) {
        const functionData = data.functions.docs.functions.find(f => f.name === isAFunction)
        schema = {
            ...schema,
            args: {
                type: 'form',
                flow: Object.keys(functionData.form_schema || {}),
                schema: functionData.form_schema || {}
            }
        }
        data.schema = functionData.form_schema
    } else {
        schema = {
            ...schema,
            ...data.schema
        }
    }

    let flow = [
        {
            type: 'group',
            name: 'Informations',
            fields: [!isAnOperator ? 'enabled' : '', 'description'].filter(field => field.length > 0),
        },
        {
            type: 'group',
            name: 'Configuration',
            fields: isAFunction ? ['args'] :
                [
                    ...(data.flow || Object.keys(data.schema || {})),
                    !isAnOperator ? 'result' : ''
                ]
                    .filter(field => field.length > 0)
        }
    ]

    const value = setEnabled(data)

    const [state, setState] = useState({
        ...value,
        coreFunctions: docs.functions
    })

    return <div className='modal-editor'>
        <p className='p-3 m-0 whats-next-title'>{data.name}</p>
        <div className='p-3'>
            <NgForm
                schema={schema}
                flow={flow}
                value={state}
                onChange={newData => {
                    if (data.operators) {
                        data.functions.handleWorkflowChange(id, {
                            [data.kind]: newData
                        })
                    } else {
                        data.functions.handleWorkflowChange(id, newData)
                    }
                    setState(newData)
                }} />
        </div>
    </div>
}