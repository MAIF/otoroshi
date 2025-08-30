import React, { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import * as BackOfficeServices from '../../services/BackOfficeServices';

import Loader from '../../components/Loader';

import { WorkflowSidebar } from './WorkflowSidebar';

export function WorkflowFunctionsDesigner(props) {
  useEffect(() => {
    props.setTitle('Functions');
  }, []);

  const [workflow, setWorkflow] = useState();
  const params = useParams();

  const client = BackOfficeServices.apisClient('plugins.otoroshi.io', 'v1', 'workflows');

  useEffect(() => {
    client.findById(params.workflowId).then((workflow) => {
      props.setSidebarContent(<WorkflowSidebar {...props} workflow={workflow} />);
      setWorkflow(workflow);
    });
  }, []);

  console.log(workflow?.functions);

  return <Loader loading={!workflow}>Functions designer soon</Loader>;
}
