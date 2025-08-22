import React, { Suspense, useState } from 'react'
import { NgForm } from '../../components/nginputs';
import { PillButton } from '../../components/PillButton';
import CodeInput from '../../components/inputs/CodeInput';
import { splitInformationAndContent } from './WorkflowsDesigner';

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
    const isAFunction = data.content.function
    const functionData = isAFunction ? data.functions.docs.functions.find(f => f.name === isAFunction) : {}


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
        schema = {
            ...schema,
            args: {
                type: 'form',
                label: 'Arguments',
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
            fields: [
                !isAnOperator ? 'enabled' : '',
                'description',
                !isAnOperator ? 'result' : ''
            ].filter(field => field.length > 0),
        },
        {
            type: 'group',
            name: 'Configuration',
            fields: isAFunction ? ['args'] : [
                ...(data.flow || Object.keys(data.schema || {}))
            ]
                .filter(field => field.length > 0)
        }
    ]

    const value = setEnabled({
        ...node.data.information,
        ...node.data.content
    })

    const [state, setState] = useState({
        ...value,
        coreFunctions: docs.functions
    })

    const [jsonView, setJsonView] = useState(false)

    const onChange = newData => {
        const { coreFunctions, ...props } = newData

        const { information, content } = splitInformationAndContent(props)

        if (data.operators) {
            data.functions.handleDataChange(id, {
                [data.kind]: content,
                information
            })
        } else {
            data.functions.handleDataChange(id, {
                information,
                content
            })
        }
        setState(newData)
    }

    // probleme on save le data dans le .node et donc on se tape à nouveau tous les champs

    return <div className='modal-editor d-flex flex-column' style={{ flex: 1 }}>
        <p className='p-3 m-0 whats-next-title'>{functionData ? functionData.display_name : node.data.node.name}</p>

        <PillButton
            className='mt-3'
            rightEnabled={!jsonView}
            leftText="FORM"
            rightText="RAW JSON"
            onLeftClick={() => setJsonView(false)}
            onRightClick={() => setJsonView(true)}
        />

        {jsonView ? <Suspense fallback={<div>Loading ...</div>}>
            <div style={{ flex: 1 }} className='my-3 py-3'>
                <CodeInput
                    mode="json"
                    editorOnly={true}
                    height="100%"
                    value={state}
                    onChange={onChange}
                />
            </div>
        </Suspense> :
            <div className='p-3'>
                <NgForm
                    schema={schema}
                    flow={flow}
                    value={state}
                    onChange={onChange} />
            </div>}
    </div>
}