import React, { useEffect, useState } from 'react';
import { QueryClient, QueryClientProvider, useQuery } from 'react-query';
import { useParams } from 'react-router-dom';
import * as BackOfficeServices from '../../services/BackOfficeServices';

import Loader from '../../components/Loader';

import { ReactFlowProvider } from '@xyflow/react';
import { WorkflowsDesigner } from './WorkflowsDesigner';
import { WorkflowSidebar } from './WorkflowSidebar';
import { NODES, NODES_BY_CATEGORIES, nodesCatalogSignal } from './models/Functions';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: false,
      refetchOnWindowFocus: false,
    },
  },
});

const applyWorkflowsStyles = () => {
  const pageContainer = document.getElementById('content-scroll-container');
  const parentPageContainer = document.getElementById('content-scroll-container-parent');

  const pagePadding = pageContainer.style.paddingBottom;
  pageContainer.style.paddingBottom = 0;

  const parentPadding = parentPageContainer.style.padding;
  parentPageContainer.style.setProperty('padding', '0px', 'important');

  return () => {
    pageContainer.style.paddingBottom = pagePadding;
    parentPageContainer.style.padding = parentPadding;
  };
};

export function WorkflowsContainer(props) {
  useEffect(() => {
    props.setTitle(undefined);
    return applyWorkflowsStyles();
  }, []);

  return (
    <QueryClientProvider client={queryClient}>
      <Container {...props} />
    </QueryClientProvider>
  );
}

function Container(props) {
  const params = useParams()
  const [rawWorkflow, setRawWorkflow] = useState()

  const client = BackOfficeServices.apisClient('plugins.otoroshi.io', 'v1', 'workflows')

  const workflow = useQuery(['getWorkflow', params.workflowId],
    () => {
      return client
        .findById(params.workflowId)
        .then(workflow => {
          setRawWorkflow(workflow)
          if (params.functionId)
            return Object
              .values(workflow.functions)
              .find(func => func.id === params.functionId)
          return workflow
        })
    })

  const workflows = useQuery('getWorkflows', () => client.findAll())

  const documentation = useQuery('getDoc', BackOfficeServices.getWorkflowDocs)

  useEffect(() => {
    if (workflow.data)
      props.setSidebarContent(
        <WorkflowSidebar {...props} params={params} workflow={workflow.data} />
      )
  }, [workflow.isLoading])

  if (!(workflow.isLoading || documentation.isLoading || workflows.isLoading)) {
    const nodes = NODES(documentation.data)
    nodesCatalogSignal.value = {
      nodes,
      categories: NODES_BY_CATEGORIES(nodes, documentation.data.categories),
      workflows: workflows.data,
      workflow: workflow.data,
    }
  }

  const handleSave = (config, orphans) => {
    if (params.functionId)
      return client.update({
        ...rawWorkflow,
        functions: Object.fromEntries(
          Object
            .entries(rawWorkflow.functions)
            .map(([key, value]) => {
              if (value.id === params.functionId) {
                return [key, {
                  ...value,
                  config,
                  orphans
                }]
              }
              return [key, value]
            })
        )
      })
    else
      return client.update({
        ...rawWorkflow,
        config,
        orphans
      })
  }

  return (
    <Loader
      loading={
        workflow.isLoading ||
        documentation.isLoading ||
        workflows.isLoading ||
        nodesCatalogSignal.value.categories.length === 0
      }
    >
      <ReactFlowProvider>
        <WorkflowsDesigner {...props}
          workflow={workflow.data}
          handleSave={handleSave} />
      </ReactFlowProvider>
    </Loader>
  );
}
