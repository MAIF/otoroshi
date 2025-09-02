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

        if (params.functionId) {
          setFunction(Object.values(workflow.functions).find(func => func.id === params.functionId))
          props.setTitle(`${workflow.name}`)
        } else {
          setFunction(template)
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
      disabled: params.functionId
    },
    description: {
      type: 'string',
      label: 'Description',
      props: { placeholder: 'New Workflow' },
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
    },
    metadata: {
      type: 'object',
      label: 'Metadata'
    },
    tags: {
      type: 'array',
      label: 'Tags'
    }
  }

  const flow = [
    {
      type: 'group',
      name: 'Configuration',
      collapsable: false,
      fields: ['name', 'description', 'config']
    },
    {
      type: 'group',
      name: 'Misc.',
      collapsed: true,
      fields: [
        'tags',
        'metadata'
      ],
    }]

  const create = () => {
    const name = `self.${newFunction.name.toLowerCase().replace(/\s/g, '_')}`

    if (Object.keys(workflow.functions).includes(name)) {
      window.newAlert("The function already exists - renamed")
    } else {
      return client.update({
        ...workflow,
        functions: {
          ...workflow.functions,
          [name]: newFunction,
        },
      })
        .then(() => history.push(`/extensions/workflows/${params.workflowId}/functions/${newFunction.id}/designer`))
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
          params.functionId ? 'Save' : 'Create'
        } />
    </Loader>
  );
}
