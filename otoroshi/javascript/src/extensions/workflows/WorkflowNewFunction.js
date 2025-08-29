import React, { useEffect, useState } from 'react'
import { useParams } from "react-router-dom"
import * as BackOfficeServices from '../../services/BackOfficeServices'

import Loader from '../../components/Loader'

import { WorkflowSidebar } from './WorkflowSidebar'
import { NgForm } from '../../components/nginputs'
import { FeedbackButton } from '../../pages/RouteDesigner/FeedbackButton'

export function WorkflowNewFunction(props) {
    const [workflow, setWorkflow] = useState()
    const [newFunction, setFunction] = useState()

    const params = useParams()

    const client = BackOfficeServices.apisClient('plugins.otoroshi.io', 'v1', 'workflows')

    useEffect(() => {
        client.findById(params.workflowId)
            .then(workflow => {
                props.setSidebarContent(<WorkflowSidebar {...props} workflow={workflow} />);
                setWorkflow(workflow)
            })

        props.setTitle("New function")
    }, [])

    const schema = {
        name: {
            type: 'string',
            label: 'Name',
            props: { placeholder: 'New Workflow' }
        },
        description: {
            type: 'string',
            label: 'Description',
            props: { placeholder: 'New Workflow' }
        },
        config: {
            type: 'code',
            label: 'Configuration',
            props: {
                showGutter: false,
                ace_config: {
                    mode: 'json',
                    onLoad: (editor) => editor.renderer.setPadding(10),
                    fontSize: 14,
                },
                editorOnly: true,
                height: '10rem',
            },
        },
    }

    return <Loader loading={!workflow}>
        <NgForm
            value={newFunction}
            schema={schema}
            onChange={setFunction}
        />
        <FeedbackButton
            type="success"
            className="d-flex ms-auto"
            onPress={() => { }}
            text="Create"
        />
    </Loader>
}