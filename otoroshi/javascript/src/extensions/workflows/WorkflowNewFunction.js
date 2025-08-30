import React, { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import * as BackOfficeServices from '../../services/BackOfficeServices';

import Loader from '../../components/Loader';

import { WorkflowSidebar } from './WorkflowSidebar';
import { NgForm } from '../../components/nginputs';
import { FeedbackButton } from '../../pages/RouteDesigner/FeedbackButton';

export function WorkflowNewFunction(props) {
  const [workflow, setWorkflow] = useState();
  const [newFunction, setFunction] = useState();

  const params = useParams();

  const client = BackOfficeServices.apisClient('plugins.otoroshi.io', 'v1', 'workflows');

  useEffect(() => {
    Promise.all([client.findById(params.workflowId), client.template()]).then(
      ([workflow, template]) => {
        props.setSidebarContent(<WorkflowSidebar {...props} workflow={workflow} />);
        setWorkflow(workflow);
        setFunction(template);
      }
    );

    props.setTitle('New function');
  }, []);

  const schema = {
    name: {
      type: 'string',
      label: 'Name',
      props: { placeholder: 'New Workflow' },
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
  };

  const create = () => {
    const name = `self.${newFunction.name.toLowerCase().replace(/\s/g, '_')}`;
    return client.update({
      ...workflow,
      functions: {
        ...workflow.functions,
        [name]: newFunction,
      },
    });
  };

  return (
    <Loader loading={!workflow}>
      <NgForm value={newFunction} schema={schema} onChange={setFunction} />
      <FeedbackButton type="success" className="d-flex ms-auto" onPress={create} text="Create" />
    </Loader>
  );
}
