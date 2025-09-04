import React, { useEffect, useState } from 'react';
import { useParams, useHistory } from 'react-router-dom';
import * as BackOfficeServices from '../../services/BackOfficeServices';

import Loader from '../../components/Loader';

import { WorkflowSidebar } from './WorkflowSidebar';
import { NgForm } from '../../components/nginputs';
import { FeedbackButton } from '../../pages/RouteDesigner/FeedbackButton';

export function WorkflowNewFunction(props) {
  const [workflow, setWorkflow] = useState()
  const [newFunction, setFunction] = useState()

  const params = useParams()
  const history = useHistory()

  const client = BackOfficeServices.apisClient('plugins.otoroshi.io', 'v1', 'workflows')

  useEffect(() => {
    Promise.all([client.findById(params.workflowId), client.template()]).then(
      ([workflow, template]) => {
        props.setSidebarContent(<WorkflowSidebar {...props} params={params} />)
        setWorkflow(workflow)

        if (params.functionName) {
          setFunction({
            name: params.functionName,
            config: workflow.functions[params.functionName]
          })
          props.setTitle(params.functionName)
        } else {
          setFunction({
            name: 'new_function',
            config: template.config
          })
          props.setTitle('New function')
        }
      }
    )

  }, [params])

  const schema = {
    name: {
      type: 'string',
      label: 'Name',
      props: { placeholder: 'New Workflow' },
      disabled: params.functionName
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
        height: '40vh',
      },
    }
  }

  const flow = [
    {
      type: 'group',
      name: 'Configuration',
      collapsable: false,
      fields: ['name', 'config']
    }
  ]

  const create = () => {
    const name = newFunction.name.toLowerCase().replace(/\s/g, '_')

    if (workflow.functions[name]) {
      window.newAlert("The function already exists - Renamed it")
    } else {
      return client.update({
        ...workflow,
        functions: {
          ...workflow.functions,
          [name]: newFunction.config,
        },
      })
        .then(() => history.push(`/extensions/workflows/${params.workflowId}/functions/${newFunction.name}/designer`))
    }
  }

  return (
    <Loader loading={!workflow}>
      <NgForm
        flow={flow}
        value={newFunction}
        schema={schema}
        onChange={setFunction} />
      <FeedbackButton
        type="success"
        className="d-flex ms-auto"
        onPress={create}
        text={
          params.functionName ? 'Save' : 'Create'
        } />
    </Loader>
  );
}
