import React, { useEffect, useState } from 'react';
import { Link, useHistory, useLocation, useParams } from 'react-router-dom';
import { useQuery } from 'react-query';
import { v4 } from 'uuid';
import { nextClient } from '../../services/BackOfficeServices';
import { Table } from '../../components/inputs';
import { NgSelectRenderer } from '../../components/nginputs';
import NgBackend from '../../forms/ng_plugins/NgBackend';
import { BackendForm } from '../RouteDesigner/BackendNode';
import PageTitle from '../../components/PageTitle';
import { FeedbackButton } from '../RouteDesigner/FeedbackButton';
import { Row } from '../../components/Row';
import SimpleLoader from './SimpleLoader';
import { useDraftOfAPI, historyPush, linkWithQuery } from './hooks';
import { DraftOnly, VersionBadge } from './DraftOnly';

export function Backends(props) {
  const history = useHistory();
  const params = useParams();
  const location = useLocation();

  const columns = [
    {
      title: 'Name',
      filterId: 'name',
      content: (item) => item.name,
    },
    {
      title: 'Backend',
      filterId: 'backend.targets.0.hostname',
      cell: (item) => {
        return (
          <>
            {item.backend.targets[0]?.hostname || '-'}{' '}
            {item.backend.targets.length > 1 && (
              <span
                className="badge bg-secondary"
                style={{ cursor: 'pointer' }}
                title={item.backend.targets
                  .map((v) => ` - ${v.tls ? 'https' : 'http'}://${v.hostname}:${v.port}`)
                  .join('\n')}
              >
                {item.backend.targets.length - 1} more
              </span>
            )}
          </>
        );
      },
    },
  ];

  const { item, updateItem, isDraft } = useDraftOfAPI();

  useEffect(() => {
    props.setTitle({
      value: 'Backends',
      noThumbtack: true,
      children: <VersionBadge />,
    });
  }, []);

  const client = nextClient.forEntityNext(nextClient.ENTITIES.BACKENDS);

  const deleteItem = (newItem) =>
    updateItem({
      ...item,
      backends: item.backends.filter((f) => f.id !== newItem.id),
    });

  if (!item) return <SimpleLoader />;

  return (
    <Table
      parentProps={{ params }}
      navigateTo={(item) =>
        historyPush(history, location, `/apis/${params.apiId}/backends/${item.id}/edit`)
      }
      navigateOnEdit={(item) =>
        historyPush(history, location, `/apis/${params.apiId}/backends/${item.id}/edit`)
      }
      selfUrl="backends"
      defaultTitle="Backend"
      itemName="Backend"
      columns={columns}
      deleteItem={deleteItem}
      fetchTemplate={client.template}
      fetchItems={() => Promise.resolve(item.backends || [])}
      defaultSort="name"
      defaultSortDesc="true"
      showActions={isDraft}
      showLink={false}
      extractKey={(item) => item.id}
      rowNavigation={true}
      hideAddItemAction={true}
      itemUrl={(i) => linkWithQuery(`/bo/dashboard/apis/${params.apiId}/backends/${i.id}/edit`)}
      rawEditUrl={true}
      injectTopBar={() => (
        <DraftOnly>
          <div className="btn-group input-group-btn">
            <Link
              className="btn btn-primary btn-sm"
              to={{
                pathname: 'backends/new',
                search: location.search,
              }}
            >
              <i className="fas fa-plus-circle" /> Create new backend
            </Link>
            {props.injectTopBar}
          </div>
        </DraftOnly>
      )}
    />
  );
}

export function NewBackend(props) {
  const params = useParams();
  const history = useHistory();
  const location = useLocation();

  const [backend, setBackend] = useState();

  const { item, updateItem } = useDraftOfAPI();

  useEffect(() => {
    props.setTitle(undefined);
  }, []);

  const saveBackend = () => {
    return updateItem({
      ...item,
      backends: [
        ...item.backends,
        {
          ...backend,
          ...backend.backend,
        },
      ],
    }).then(() => historyPush(history, location, `/apis/${params.apiId}/backends`));
  };

  useQuery(['getTemplate'], nextClient.forEntityNext(nextClient.ENTITIES.BACKENDS).template, {
    retry: 0,
    onSuccess: (data) =>
      setBackend({
        id: v4(),
        name: 'My new backend',
        backend: {
          ...data.backend,
          targets: [
            {
              id: 'target_1',
              hostname: 'request.otoroshi.io',
              port: 443,
              tls: true,
              weight: 1,
            },
          ],
        },
      }),
  });

  if (!backend || !item) return <SimpleLoader />;

  console.log(backend);

  return (
    <div className='page'>
      <PageTitle title="New Backend" {...props} style={{ paddingBottom: 0 }} />
      <div className='displayGroupBtn'>
        <FeedbackButton
          type="success"
          onPress={saveBackend}
          text={<div className="d-flex align-items-center">
            Create <VersionBadge size="xs" />
          </div>}
        />
      </div>
      <BackendForm
        state={{
          form: {
            schema: {
              name: {
                label: 'Name',
                type: 'string',
                placeholder: 'New backend',
                disabled: backend?.name === 'default_backend',
              },
              client: {
                renderer: (props) => (
                  <Row title="HTTP client">
                    <NgSelectRenderer
                      id="client_select"
                      value={props.rootValue.client}
                      placeholder="Select an existing http client"
                      label={' '}
                      ngOptions={{ spread: true }}
                      isClearable
                      onChange={(client) => {
                        props.rootOnChange({
                          ...props.rootValue,
                          client,
                        });
                      }}
                      options={item.clients}
                      optionsTransformer={(arr) =>
                        arr.map((item) => ({ label: item.name, value: item.id }))
                      }
                    />
                  </Row>
                ),
              },
              backend: {
                type: 'form',
                schema: {
                  ...NgBackend.schema,
                },
                flow: NgBackend.flow.filter((f) => f !== 'client'),
              },
            },
            flow: ['name', 'client', 'backend'],
            value: backend,
          },
        }}
        onChange={setBackend}
      />
    </div>
  );
}

export function EditBackend(props) {
  const params = useParams();
  const history = useHistory();
  const location = useLocation();

  const { item, updateItem } = useDraftOfAPI();

  const [backend, setBackend] = useState();

  useEffect(() => {
    props.setTitle(undefined);
    if (item && !backend) {
      setBackend(item.backends.find((item) => item.id === params.backendId));
    }
  }, [item]);

  const updateBackend = () => {
    return updateItem({
      ...item,
      backends: item.backends.map((item) => {
        if (item.id === backend.id) return backend;
        return item;
      }),
    }).then(() => historyPush(history, location, `/apis/${params.apiId}/backends`));
  };

  if (!item || !backend) return <SimpleLoader />;

  return (
    <div className='page'>
      <PageTitle title="Backend Settings" {...props} style={{ paddingBottom: 0 }} />

      <div className='displayGroupBtn'>
        <DraftOnly>
          <FeedbackButton
            type="success"
            onPress={updateBackend}
            text={<div className="d-flex align-items-center">
              Save <VersionBadge size="xs" />
            </div>}
          />
        </DraftOnly>
      </div>

      <BackendForm
        state={{
          form: {
            schema: {
              name: {
                label: 'Name',
                type: 'string',
                placeholder: 'New backend',
              },
              client: {
                renderer: (props) => (
                  <Row title="HTTP client">
                    <NgSelectRenderer
                      id="client_select"
                      value={props.rootValue.client}
                      placeholder="Select an existing http client"
                      label={' '}
                      ngOptions={{ spread: true }}
                      isClearable
                      onChange={(client) => {
                        props.rootOnChange({
                          ...props.rootValue,
                          client,
                        });
                      }}
                      options={item.clients}
                      optionsTransformer={(arr) =>
                        arr.map((item) => ({ label: item.name, value: item.id }))
                      }
                    />
                  </Row>
                ),
              },
              backend: {
                type: 'form',
                label: 'Configuration',
                schema: NgBackend.schema,
                flow: NgBackend.flow.filter((f) => f !== 'client'),
              },
            },
            flow: [{
              type: 'group',
              fields: ['name', 'client'],
              collapsable: false
            }, 'backend'],
            value: backend,
          },
        }}
        onChange={setBackend}
      />
    </div>
  );
}
