import React, { useEffect, useState } from 'react';
import { Link, useHistory, useLocation, useParams } from 'react-router-dom';
import { useQuery } from 'react-query';
import { components } from 'react-select';
import { nextClient } from '../../services/BackOfficeServices';
import { Table } from '../../components/inputs';
import { NgForm } from '../../components/nginputs';
import PageTitle from '../../components/PageTitle';
import { FeedbackButton } from '../RouteDesigner/FeedbackButton';
import SimpleLoader from './SimpleLoader';
import { useDraftOfAPI, historyPush, linkWithQuery } from './hooks';
import { VersionBadge } from './DraftOnly';
import { MAX_WIDTH } from './constants';

const SUBSCRIPTION_FORM_SETTINGS = {
  schema: (item) => {
    return {
      location: {
        type: 'location',
      },
      name: {
        type: 'string',
        label: 'Name',
      },
      description: {
        type: 'string',
        label: 'Description',
      },
      enabled: {
        type: 'boolean',
        label: 'Enabled',
      },
      owner_ref: {
        label: 'Owner',
        type: 'select',
        props: {
          options: item.clients.map(({ id, name }) => ({ value: id, label: name }))
        }
      },
      plan_ref: {
        type: 'select',
        label: 'Plan',
        props: {
          options: item.documentation?.plans || [],
          noOptionsMessage: ({ children, ...props }) => {
            return (
              <components.NoOptionsMessage {...props}>
                No Plans
              </components.NoOptionsMessage>
            );
          },
          optionsTransformer: {
            value: 'id',
            label: 'name',
          },
        },
      },
      token_refs: {
        array: true,
        label: 'Token refs',
        type: 'string',
      },
    }
  },
  flow: [
    'location',
    {
      type: 'group',
      name: 'Informations',
      collapsable: false,
      fields: ['name', 'description', 'enabled'],
    },
    {
      type: 'group',
      name: 'Ownership',
      collapsable: false,
      fields: ['owner_ref', 'plan_ref', 'token_refs'],
    },
  ],
};

export function Subscriptions(props) {
  const history = useHistory();
  const params = useParams();
  const location = useLocation();

  const { isDraft } = useDraftOfAPI();

  const columns = [
    {
      title: 'Name',
      filterId: 'name',
      content: (item) => item.name,
    }
  ];

  useEffect(() => {
    props.setTitle({
      value: 'Subscriptions',
      noThumbtack: true,
      children: <div
        className='m-0 ms-2'
        style={{ fontSize: '1rem' }}
      >
        <span className={`badge bg-xs bg-danger`}>
          PROD
        </span>
      </div>,
    });
  }, []);

  const client = nextClient.forEntityNext(nextClient.ENTITIES.API_SUBSCRIPTIONS);

  const rawSubscriptions = useQuery(['getSubscriptions'], () => {
    return client.findAllWithPagination({
      page: 1,
      pageSize: 15,
      filtered: [
        {
          id: 'api_ref',
          value: params.apiId,
        },
      ],
    });
  });

  const deleteItem = (item) => client.delete(item).then(() => window.location.reload());

  if (rawSubscriptions.isLoading) return <SimpleLoader />;

  return (
    <Table
      parentProps={{ params }}
      navigateTo={(item) =>
        historyPush(history, location, `/apis/${params.apiId}/subscriptions/${item.id}/edit`)
      }
      navigateOnEdit={(item) =>
        historyPush(history, location, `/apis/${params.apiId}/subscriptions/${item.id}/edit`)
      }
      selfUrl="subscriptions"
      defaultTitle="Subscription"
      itemName="Subscription"
      columns={columns}
      deleteItem={deleteItem}
      fetchTemplate={client.template}
      fetchItems={() => Promise.resolve(rawSubscriptions.data || [])}
      defaultSort="name"
      defaultSortDesc="true"
      showActions={isDraft}
      showLink={false}
      extractKey={(item) => item.id}
      rowNavigation={true}
      hideAddItemAction={true}
      itemUrl={(i) =>
        linkWithQuery(`/bo/dashboard/apis/${params.apiId}/subscriptions/${i.id}/edit`)
      }
      rawEditUrl={true}
    />
  );
}

export function SubscriptionDesigner(props) {
  const params = useParams();
  const history = useHistory();
  const location = useLocation();

  const [subscription, setSubscription] = useState();

  const { item } = useDraftOfAPI();

  useEffect(() => {
    props.setTitle(undefined);
  }, []);

  const rawSubscription = useQuery(
    ['getSubscription', params.subscriptionId],
    () =>
      nextClient
        .forEntityNext(nextClient.ENTITIES.API_SUBSCRIPTIONS)
        .findById(params.subscriptionId),
    {
      onSuccess: setSubscription,
    }
  );

  const goToSubscriptions = () => historyPush(history, location, `/apis/${params.apiId}/subscriptions`)

  const updateSubscription = () => {
    return nextClient
      .forEntityNext(nextClient.ENTITIES.API_SUBSCRIPTIONS)
      .update(subscription)
      .then(goToSubscriptions)
  };

  if (!item || rawSubscription.isLoading) return <SimpleLoader />;

  return (
    <>
      <PageTitle title={subscription.name} {...props}>
        <FeedbackButton
          type="success"
          className="d-flex ms-auto"
          onPress={updateSubscription}
          text={
            <div className="d-flex align-items-center">
              Update <VersionBadge size="xs" />
            </div>
          }
        />
      </PageTitle>
      <div style={{ maxWidth: MAX_WIDTH }}>
        <NgForm
          value={subscription}
          schema={SUBSCRIPTION_FORM_SETTINGS.schema(item)}
          flow={SUBSCRIPTION_FORM_SETTINGS.flow}
          onChange={setSubscription}
        />
      </div>
    </>
  );
}

export function NewSubscription(props) {
  const location = useLocation();
  const params = useParams()
  const history = useHistory()

  const [subscription, setSubscription] = useState();
  const [error, setError] = useState();

  const { item, version } = useDraftOfAPI();

  useEffect(() => {
    props.setTitle(undefined);
  }, []);

  useQuery(
    ['getSubscriptionTemplate'],
    () => nextClient.forEntityNext(nextClient.ENTITIES.API_SUBSCRIPTIONS).template(),
    {
      enabled: !!item,
      onSuccess: (sub) =>
        setSubscription({
          ...sub,
          consumer_ref: item.consumers?.length > 0 ? item.consumers[0]?.id : undefined,
        }),
    }
  );

  if (!item || !subscription) return <SimpleLoader />;

  const updateSubscription = () => {
    return nextClient
      .forEntityNext(nextClient.ENTITIES.API_SUBSCRIPTIONS)
      .create({
        ...subscription,
        api_ref: params.apiId,
        draft: version === 'staging' || version === 'draft',
      })
      .then((res) => {
        if (res && res.error) {
          setError(res.error);
        } else {
          historyPush(history, location, `/apis/${params.apiId}/subscriptions`);
        }
      })
  }

  return (
    <>
      <PageTitle title={subscription.name} {...props} />
      <div style={{ maxWidth: MAX_WIDTH }}>
        <NgForm
          value={subscription}
          schema={SUBSCRIPTION_FORM_SETTINGS.schema(item)}
          flow={SUBSCRIPTION_FORM_SETTINGS.flow}
          onChange={(newSub) => {
            setSubscription(newSub);
            setError(undefined);
          }}
        />

        {error && (
          <div
            className="mt-3 p-3"
            style={{
              borderLeft: '2px solid #D5443F',
              background: '#D5443F',
              color: 'var(--text)',
              borderRadius: '.25rem',
            }}
          >
            {error}
          </div>
        )}
        <FeedbackButton
          type="success"
          className="d-flex ms-auto mt-3 d-flex align-items-center"
          onPress={updateSubscription}
          text={
            <>
              Create <VersionBadge size="xs" />
            </>
          }
        />
      </div>
    </>
  );
}
