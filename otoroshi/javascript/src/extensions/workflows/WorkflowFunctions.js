import React, { useEffect, useState } from 'react';
import { useParams, Link, useHistory } from 'react-router-dom';
import * as BackOfficeServices from '../../services/BackOfficeServices';

import Loader from '../../components/Loader';

import { WorkflowSidebar } from './WorkflowSidebar';
import { Table } from '../../components/inputs';

export function WorkflowFunctions(props) {
  const params = useParams();
  const history = useHistory();

  const [workflow, setWorkflow] = useState();

  const client = BackOfficeServices.apisClient('plugins.otoroshi.io', 'v1', 'workflows');

  useEffect(() => {
    props.setTitle('Functions');

    client.findById(params.workflowId).then((workflow) => {
      props.setSidebarContent(<WorkflowSidebar {...props} params={params} />);
      setWorkflow(workflow);
    });
  }, []);

  const columns = [
    {
      title: 'Name',
      filterId: 'name',
      cell: (_, item) => item.name,
    },
  ];

  const deleteItem = (removedItem) => {
    const newWorkflow = {
      ...workflow,
      functions: Object.fromEntries(
        Object.entries(workflow.functions).filter(([key, _]) => key !== removedItem.name)
      ),
    };
    return client.update(newWorkflow).then(() => {
      setWorkflow(newWorkflow);
    });
  };

  return (
    <Loader loading={!workflow}>
      <Table
        parentProps={{ params }}
        navigateTo={(item) =>
          history.push(`/extensions/workflows/${workflow.id}/functions/${item.name}/designer`)
        }
        navigateOnEdit={(item) =>
          history.push(`/extensions/workflows/${workflow.id}/functions/${item.name}/designer`)
        }
        selfUrl="extensions/workflows"
        defaultTitle="Functions"
        itemName="Function"
        formSchema={null}
        formFlow={null}
        columns={columns}
        deleteItem={(item) => deleteItem(item)}
        defaultSort="metadata.updated_at"
        defaultSortDesc="true"
        fetchItems={() =>
          Promise.resolve(
            Object.entries(workflow.functions).map(([key, value]) => ({
              ...value,
              name: key,
            }))
          )
        }
        showActions={true}
        showLink={false}
        extractKey={(item) => item.name}
        rowNavigation={true}
        hideAddItemAction={true}
        itemUrl={(i) =>
          `/bo/dashboard/extensions/workflows/${workflow.id}/functions/${i.name}/designer`
        }
        rawEditUrl={true}
        injectTopBar={() => (
          <div className="btn-group input-group-btn">
            <Link className="btn btn-primary btn-sm" to={`functions/new`}>
              <i className="fas fa-plus-circle" /> Add function
            </Link>
            {props.injectTopBar}
          </div>
        )}
      />
    </Loader>
  );
}
