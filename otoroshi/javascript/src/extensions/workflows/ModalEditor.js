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
    const functionData = isAFunction ? data.functions.docs.functions.find(f => f.name === isAFunction) : undefined

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
                flow: Object.keys(functionData?.form_schema || {}),
                schema: functionData?.form_schema || {}
            }
        }
        data.schema = functionData?.form_schema
    } else {
        schema = {
            ...schema,
            ...data.form_schema
        }
    }

    const hasArgsSchema = Object.keys(functionData?.form_schema || {}).length > 0
    const argsFlow = ['args', 'result']

    const defaultFlow = [
        ...(data.flow || Object.keys(data.schema || {})),
        'result'
    ]
        .filter(field => field.length > 0)

    const getConfigurationGroup = () => {
        const configuration = {
            type: 'group',
            name: 'Configuration',
            collapsable: false,
            fields: []
        }

        if (isAFunction) {
            if (hasArgsSchema)
                return { ...configuration, fields: argsFlow }
            else
                return { ...configuration, fields: ['result'] }
        } else {
            return {
                ...configuration,
                fields: defaultFlow
            }
        }
    }

    let flow = [
        {
            type: 'group',
            name: 'General',
            collapsable: false,
            fields: [
                !isAnOperator ? 'enabled' : '',
                'description'
            ].filter(field => field.length > 0),
        },
        getConfigurationGroup()
    ]

    const value = setEnabled({
        ...node.data.information,
        ...node.data.content,
        coreFunctions: docs.functions
    })

    const [state, setState] = useState(value)

    const [jsonView, setJsonView] = useState(false)

    const handleCodeInputChange = newData => {
        onChange({ ...state, ...JSON.parse(newData) })
    }

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

    const getCodeInputValue = () => {
        const fields = flow[1]?.fields

        return Object.fromEntries(
            Object.entries(state).filter(([key, _]) => fields.includes(key))
        )
    }

    return <div className='modal-editor d-flex flex-column' style={{ flex: 1 }}>
        <p className='p-3 m-0 whats-next-title'>{functionData ? functionData.display_name : node.data.display_name}</p>

        <PillButton
            className='mt-3'
            rightEnabled={!jsonView}
            leftText="Visual Editor"
            rightText="Code Editor"
            onLeftClick={() => setJsonView(false)}
            onRightClick={() => setJsonView(true)}
        />

        {jsonView ? <Suspense fallback={<div>Loading ...</div>}>
            <div style={{ flex: 1 }} className='my-3 py-3'>
                <CodeInput
                    mode="json"
                    editorOnly={true}
                    height="100%"
                    value={getCodeInputValue()}
                    onChange={handleCodeInputChange}
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