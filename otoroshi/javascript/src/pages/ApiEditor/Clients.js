import React, { useEffect, useState } from 'react';
import { Link, useHistory, useLocation, useParams } from 'react-router-dom';
import { NgForm } from '../../components/nginputs';
import { FeedbackButton } from '../RouteDesigner/FeedbackButton';
import PageTitle from '../../components/PageTitle';
import { Table } from '../../components/inputs';
import SimpleLoader from './SimpleLoader';
import { useDraftOfAPI, historyPush } from './hooks';
import { VersionBadge } from './DraftOnly';
import { v4 } from 'uuid';

export function ClientForm({ client, onChange }) {
  const schema = {
    name: { type: 'string', label: 'Name' },
    description: { type: 'string', label: 'Description' },
    metadata: {
      type: 'object',
      label: 'Metadata',
      props: {
        placeholderKey: 'Metadata Name',
        placeholderValue: 'Metadata value',
        help: 'Some useful metadata',
      },
    },
    tags: {
      type: 'array',
      label: 'Tags',
      props: {
        placeholder: 'admin',
        help: 'The tags assigned to this apikey',
      },
    },
  };

  return <div style={{ border: '1px solid var(--input-border)', borderRadius: '1rem' }} className='p-3'>
    <NgForm value={client} schema={schema} onChange={onChange} />
  </div>
}

export function Clients(props) {
  const params = useParams();
  const history = useHistory();
  const { item, version, updateItem } = useDraftOfAPI();

  useEffect(() => {
    props.setTitle({
      value: 'Clients',
      noThumbtack: true,
      children: <VersionBadge />,
    });
  }, []);

  if (!item) return <SimpleLoader />;

  const clients = item.clients || [];

  const columns = [
    {
      title: 'Name',
      filterId: 'name',
      content: (item) => item.name,
    },
    {
      title: 'Description',
      filterId: 'description',
      content: (item) => item.description,
    },
  ];

  const deleteItem = (client) => {
    return updateItem({
      ...item,
      clients: item.clients.filter((c) => c.id !== client.id),
    });
  };

  return (
    <>
      <Table
        parentProps={{ params }}
        navigateTo={(client) =>
          history.push(`/apis/${params.apiId}/clients/${client.id}/edit?version=${version}`)
        }
        navigateOnEdit={(client) =>
          history.push(`/apis/${params.apiId}/clients/${client.id}/edit?version=${version}`)
        }
        selfUrl="clients"
        defaultTitle="Clients"
        itemName="Client"
        formSchema={null}
        formFlow={null}
        columns={columns}
        deleteItem={(item) => deleteItem(item)}
        defaultSort="name"
        defaultSortDesc="true"
        fetchItems={() => Promise.resolve(clients)}
        fetchTemplate={() => Promise.resolve({})}
        showActions={true}
        showLink={false}
        extractKey={(item) => item.id}
        rowNavigation={true}
        hideAddItemAction={true}
        itemUrl={(client) => `/apis/${params.apiId}/clients/${client.id}/edit?version=${version}`}
        rawEditUrl={true}
        injectTopBar={() => (
          <div className="btn-group input-group-btn">
            <Link className="btn btn-success btn-sm" to={`clients/new?version=${version}`}>
              <i className="fas fa-plus-circle" /> Create new client
            </Link>
          </div>
        )}
      />
    </>
  );
}

export function ClientEditor(props) {
  const params = useParams();
  const history = useHistory();
  const location = useLocation();

  const { item, updateItem } = useDraftOfAPI();

  const isNew = !params.clientId;
  const [client, setClient] = useState(
    isNew
      ? {
        id: v4(),
        name: 'New client',
        description: 'New client description',
      }
      : null
  );

  useEffect(() => {
    if (!isNew && item && !client) {
      const found = (item.clients_backend_config || []).find((p) => p.id === params.clientId);
      if (found) {
        setClient(found);
      }
    }
  }, [item]);

  useEffect(() => {
    props.setTitle(undefined);
  }, []);

  if (!item || !client) return <SimpleLoader />;

  const back = () => historyPush(history, location, `/apis/${params.apiId}/clients`);

  const save = () => {
    const clients = isNew
      ? [...(item.clients || []), client]
      : item.clients.map((p) => (p.id === client.id ? client : p));

    return updateItem({ ...item, clients }).then(back);
  };

  return (
    <div className='actions-page'>
      <PageTitle title={isNew ? 'New client' : client.name} {...props} />
      <div className='displayGroupBtn'>
        <FeedbackButton
          type="success"
          onPress={save}
          text={
            <div className="d-flex align-items-center">
              {isNew ? 'Create' : 'Update'} <VersionBadge size="xs" />
            </div>
          }
        />
      </div>
      <ClientForm client={client} onChange={setClient} />
    </div>
  );
}
