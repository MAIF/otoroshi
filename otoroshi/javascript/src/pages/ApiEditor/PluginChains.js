import React, { useEffect, useRef, useState } from 'react';
import { Link, useHistory, useLocation, useParams } from 'react-router-dom';
import { v4 as uuid } from 'uuid';
import { nextClient } from '../../services/BackOfficeServices';
import { Table } from '../../components/inputs';
import { NgForm } from '../../components/nginputs';
import { dynamicTitleContent } from '../../components/DynamicTitleSignal';
import PageTitle from '../../components/PageTitle';
import { FeedbackButton } from '../RouteDesigner/FeedbackButton';
import Designer from '../RouteDesigner/Designer';
import { Button } from '../../components/Button';
import SimpleLoader from './SimpleLoader';
import { useDraftOfAPI, historyPush, linkWithQuery } from './hooks';
import { DraftOnly, VersionBadge } from './DraftOnly';
import { MAX_WIDTH } from './constants';

const FLOW_FORM_SETTINGS = {
  schema: (item) => ({
    name: {
      type: 'string',
      label: 'Plugin chain name',
    },
  }),
  flow: [
    {
      type: 'group',
      name: 'Informations',
      collapsable: false,
      fields: ['name'],
    },
  ],
};

export function EditPluginChains(props) {
  const history = useHistory();
  const params = useParams();
  const location = useLocation();

  const { item, updateItem } = useDraftOfAPI();

  const [flow, setFlow] = useState();

  useEffect(() => {
    if (item && !flow) {
      const currentFlow = item.flows.find((flow) => flow.id === params.flowId);

      if (currentFlow) {
        setFlow(currentFlow);
      }
    }
  }, [item]);

  const updateFlow = () => {
    return updateItem({
      ...item,
      flows: item.flows.map((ite) => {
        if (ite.id === params.flowId) {
          return flow;
        } else {
          return ite;
        }
      }),
    }).then(() => historyPush(history, location, `/apis/${params.apiId}/plugin-chains`));
  };

  if (!item) return <SimpleLoader />;

  return (
    <div className="page">
      <PageTitle title="Plugin chains Settings" />
      <NgForm
        schema={FLOW_FORM_SETTINGS.schema(item)}
        flow={FLOW_FORM_SETTINGS.flow}
        value={flow}
        onChange={setFlow}
      />
      <DraftOnly>
        <div className="displayGroupBtn">
          <Button type="success" onClick={updateFlow}>
            <div className="d-flex align-items-center">
              Save <VersionBadge size="xs" className="ms-2" />
            </div>
          </Button>
        </div>
      </DraftOnly>
    </div>
  );
}

export function NewPluginChains(props) {
  const history = useHistory();
  const params = useParams();
  const location = useLocation();

  useEffect(() => {
    props.setTitle(undefined);
  }, []);

  const [flow, setFlow] = useState({
    id: uuid(),
    name: 'New plugin chains name',
    plugins: [],
  });

  const { item, updateItem } = useDraftOfAPI();

  const createFlow = () => {
    return updateItem({
      ...item,
      flows: [...item.flows, flow],
    }).then(() =>
      historyPush(history, location, `/apis/${params.apiId}/plugin-chains/${flow.id}/designer`)
    );
  };

  if (!item) return <SimpleLoader />;

  return (
    <div className="page">
      <PageTitle title="Plugin chains Settings" />
      <NgForm
        schema={FLOW_FORM_SETTINGS.schema(item)}
        flow={FLOW_FORM_SETTINGS.flow}
        value={flow}
        onChange={setFlow}
      />
      <DraftOnly>
        <div className="displayGroupBtn">
          <Button type="success" onClick={createFlow}>
            <div className="d-flex align-items-center">
              Create <VersionBadge size="xs" className="ms-2" />
            </div>
          </Button>
        </div>
      </DraftOnly>
    </div>
  );
}

export function PluginChainsDesigner(props) {
  const history = useHistory();
  const params = useParams();

  const isCreation = params.action === 'new';

  const { item, updateItem } = useDraftOfAPI();

  const [flow, setFlow] = useState();
  const ref = useRef(flow);

  useEffect(() => {
    ref.current = flow;
  }, [flow]);

  useEffect(() => {
    if (item && !flow) {
      setFlow(item.flows.find((flow) => flow.id === params.flowId));

      dynamicTitleContent.value = (
        <PageTitle
          style={{
            paddingBottom: 0,
          }}
          title={item.flows.find((flow) => flow.id === params.flowId)?.name}
          {...props}
        >
          <FeedbackButton
            type="success"
            className="ms-2 mb-1 d-flex align-items-center"
            onPress={saveFlow}
            text={
              <>
                {isCreation ? 'Create a new flow' : 'Save'}{' '}
                <VersionBadge size="xs" className="ms-2" />
              </>
            }
          />
        </PageTitle>
      );
    }
  }, [item]);

  const saveFlow = () => {
    const { id, name, plugins } = ref.current;

    return updateItem({
      ...item,
      flows: item.flows.map((flow) => {
        if (flow.id === id)
          return {
            ...flow,
            id,
            name,
            plugins,
          };
        return flow;
      }),
    });
  };

  if (!item || !flow) return <SimpleLoader />;

  return (
    <div className="designer">
      <Designer
        history={history}
        value={flow}
        setValue={(value) => setFlow({ ...(value || {}) })}
        setSaveButton={() => {}}
      />
    </div>
  );
}

export function PluginChains(props) {
  const params = useParams();
  const history = useHistory();
  const location = useLocation();

  const { item, updateItem, isDraft } = useDraftOfAPI();

  const columns = [
    {
      title: 'Name',
      content: (item) => item.name,
    },
    {
      title: 'Information',
      style: {
        textAlign: 'center',
        width: 120,
      },
      notFilterable: true,
      cell: (_, item) => {
        return (
          <Button
            className="btn-sm"
            onClick={() => {
              historyPush(history, location, `/apis/${params.apiId}/plugin-chains/${item.id}/edit`);
            }}
          >
            <i className="fas fa-cog" />
          </Button>
        );
      },
    },
  ];

  useEffect(() => {
    props.setTitle({
      value: 'Plugin chains',
      noThumbtack: true,
      children: <VersionBadge />,
    });
  }, []);

  const fetchItems = (_) => Promise.resolve(item.flows);

  const fetchTemplate = () =>
    Promise.resolve({
      id: uuid(),
      name: 'My new plugin chain',
      plugins: [],
    });

  const deleteItem = (deletedFlow) => {
    return updateItem({
      ...item,
      flows: item.flows.filter((flow) => flow.id !== deletedFlow.id),
    });
  };

  if (!item) return <SimpleLoader />;

  return (
    <Table
      parentProps={{ params }}
      navigateTo={(item) =>
        historyPush(history, location, `/apis/${params.apiId}/plugin-chains/${item.id}/designer`)
      }
      navigateOnEdit={(item) =>
        historyPush(history, location, `/apis/${params.apiId}/plugin-chains/${item.id}/designer`)
      }
      selfUrl={`/apis/${params.apiId}/plugin-chains`}
      defaultTitle="Flow"
      itemName="Flow"
      columns={columns}
      deleteItem={deleteItem}
      defaultSort="name"
      defaultSortDesc="true"
      fetchItems={fetchItems}
      fetchTemplate={fetchTemplate}
      showActions={isDraft}
      showLink={false}
      extractKey={(item) => item.id}
      rowNavigation={true}
      hideAddItemAction={true}
      itemUrl={(i) => linkWithQuery(`/bo/dashboard/apis/${params.apiId}/plugin-chains/${i.id}`)}
      rawEditUrl={true}
      injectTopBar={() => (
        <DraftOnly>
          <div className="btn-group input-group-btn">
            <Link
              className="btn btn-success btn-sm"
              to={{
                pathname: 'plugin-chains/new',
                search: location.search,
              }}
            >
              <i className="fas fa-plus-circle" /> Create new Plugin chain
            </Link>
            {props.injectTopBar}
          </div>
        </DraftOnly>
      )}
    />
  );
}
