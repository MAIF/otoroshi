import React, { useEffect, useState } from 'react';
import { Link, useHistory, useLocation, useParams } from 'react-router-dom';
import { useQuery } from 'react-query';
import { v4 } from 'uuid';
import { nextClient } from '../../services/BackOfficeServices';
import { Table } from '../../components/inputs';
import NgBackend from '../../forms/ng_plugins/NgBackend';
import { BackendForm } from '../RouteDesigner/BackendNode';
import PageTitle from '../../components/PageTitle';
import { FeedbackButton } from '../RouteDesigner/FeedbackButton';
import SimpleLoader from './SimpleLoader';
import { useDraftOfAPI, historyPush, linkWithQuery } from './hooks';
import { DraftOnly, VersionBadge } from './DraftOnly';
import { MAX_WIDTH } from './constants';

export function HttpClientSettings(props) {
  const history = useHistory();
  const params = useParams();
  const location = useLocation();

  const columns = [
    {
      title: 'Name',
      filterId: 'name',
      content: (item) => item.name,
    },
  ];

  const { item, updateItem, isDraft } = useDraftOfAPI();

  useEffect(() => {
    props.setTitle({
      value: 'HTTP client settings',
      noThumbtack: true,
      children: <VersionBadge />,
    });
  }, []);

  const client = nextClient.forEntityNext(nextClient.ENTITIES.BACKEND_CLIENTS);

  const deleteItem = (newItem) =>
    updateItem({
      ...item,
      clients: item.clients.filter((f) => f.id !== newItem.id),
    });

  if (!item) return <SimpleLoader />;

  return (
    <Table
      parentProps={{ params }}
      navigateTo={(item) =>
        historyPush(history, location, `/apis/${params.apiId}/http-client-settings/${item.id}/edit`)
      }
      navigateOnEdit={(item) =>
        historyPush(history, location, `/apis/${params.apiId}/http-client-settings/${item.id}/edit`)
      }
      selfUrl="http-client-settings"
      defaultTitle="HTTP client settings"
      itemName="HTTP client settings"
      columns={columns}
      deleteItem={deleteItem}
      fetchTemplate={client.template}
      fetchItems={() => Promise.resolve(item.clients || [])}
      defaultSort="name"
      defaultSortDesc="true"
      showActions={isDraft}
      showLink={false}
      extractKey={(item) => item.id}
      rowNavigation={true}
      hideAddItemAction={true}
      itemUrl={(i) =>
        linkWithQuery(`/bo/dashboard/apis/${params.apiId}/http-client-settings/${i.id}/edit`)
      }
      rawEditUrl={true}
      injectTopBar={() => (
        <DraftOnly>
          <div className="btn-group input-group-btn">
            <Link
              className="btn btn-primary btn-sm"
              to={{
                pathname: 'http-client-settings/new',
                search: location.search,
              }}
            >
              <i className="fas fa-plus-circle" /> Create new HTTP client settings
            </Link>
            {props.injectTopBar}
          </div>
        </DraftOnly>
      )}
    />
  );
}

export function NewHttpClientSettings(props) {
  const params = useParams();
  const history = useHistory();
  const location = useLocation();

  const [client, setClient] = useState();

  const { item, updateItem } = useDraftOfAPI();

  const saveBackend = () => {
    return updateItem({
      ...item,
      clients: [...item.clients, client],
    }).then(() => historyPush(history, location, `/apis/${params.apiId}/http-client-settings`));
  };

  useQuery(
    ['getHttpClientSettingsTemplate'],
    () =>
      fetch(
        `/bo/api/proxy/apis/apis.otoroshi.io/v1/apis/${params.apiId}/http-client-settings/_template`,
        {
          credentials: 'include',
          headers: {
            Accept: 'application/json',
          },
        }
      ).then((r) => r.json()),
    {
      retry: 0,
      onSuccess: (client) =>
        setClient({
          id: v4(),
          name: 'My new HTTP client settings',
          client,
        }),
    }
  );

  if (!client || !item) return <SimpleLoader />;

  return (
    <>
      <PageTitle title="New HTTP client settings" {...props} style={{ paddingBottom: 0 }}>
        <FeedbackButton
          type="success"
          className="ms-2 mb-1 d-flex align-items-center"
          onPress={saveBackend}
          text={
            <>
              Create <VersionBadge size="xs" />
            </>
          }
        />
      </PageTitle>

      <div
        style={{
          maxWidth: MAX_WIDTH,
          margin: 'auto',
        }}
      >
        <BackendForm
          state={{
            form: {
              schema: {
                id: { type: 'string', disabled: true, props: { label: 'Id', placeholder: '---' } },
                name: {
                  label: 'Name',
                  type: 'string',
                  placeholder: 'New HTTP client settings',
                },
                client: {
                  ...NgBackend.schema.client,
                  collapsable: false,
                  collapsed: false,
                },
              },
              flow: [
                {
                  type: 'group',
                  name: 'Informations',
                  collapsable: false,
                  fields: ['name'],
                },
                'client',
              ],
              value: client,
            },
          }}
          onChange={setClient}
        />
      </div>
    </>
  );
}

export function EditHttpClientSettings(props) {
  const params = useParams();
  const history = useHistory();
  const location = useLocation();

  const { item, updateItem } = useDraftOfAPI();

  const [client, setClient] = useState();

  useEffect(() => {
    props.setTitle(undefined)
  }, [])

  useEffect(() => {
    if (item && !client) {
      setClient(item.clients.find((item) => item.id === params.httpClientSettingsId));
    }
  }, [item]);

  const updateBackend = () => {
    return updateItem({
      ...item,
      clients: item.clients.map((item) => {
        if (item.id === client.id) return client;
        return item;
      }),
    }).then(() => historyPush(history, location, `/apis/${params.apiId}/http-client-settings`));
  };

  if (!item) return <SimpleLoader />;

  return (
    <>
      <PageTitle title="Update Http Client settings" {...props} style={{ paddingBottom: 0 }}>
        <DraftOnly>
          <FeedbackButton
            type="success"
            className="ms-2 mb-1 d-flex align-items-center"
            onPress={updateBackend}
            text={
              <>
                Update <VersionBadge size="xs" />
              </>
            }
          />
        </DraftOnly>
      </PageTitle>

      <div
        style={{
          maxWidth: MAX_WIDTH,
          margin: 'auto',
        }}
      >
        <BackendForm
          state={{
            form: {
              schema: {
                name: {
                  label: 'Name',
                  type: 'string',
                  placeholder: 'New HTTP client settings',
                  disabled: client?.name === 'default_client',
                },
                client: {
                  ...NgBackend.schema.client,
                  collapsable: false,
                  collapsed: false,
                },
              },
              flow: [
                {
                  type: 'group',
                  name: 'Informations',
                  collapsable: false,
                  fields: ['name'],
                },
                'client',
              ],
              value: client,
            },
          }}
          onChange={setClient}
        />
      </div>
    </>
  );
}
