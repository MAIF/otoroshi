import React, { useEffect, useState } from 'react';
import { Link, useHistory, useLocation, useParams } from 'react-router-dom';
import { useQuery } from 'react-query';
import { components } from 'react-select';
import { findDraftsByKind, nextClient } from '../../services/BackOfficeServices';
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
          options: item.clients.map(({ id, name }) => ({ value: id, label: name })),
        },
      },
      plan_ref: {
        type: 'select',
        label: 'Plan',
        props: {
          options: item.documentation?.plans || [],
          noOptionsMessage: ({ children, ...props }) => {
            return <components.NoOptionsMessage {...props}>No Plans</components.NoOptionsMessage>;
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
    };
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

  const { isDraft, version } = useDraftOfAPI();

  const columns = [
    {
      title: 'Name',
      filterId: 'name',
      content: (item) => item.name,
    },
  ];

  useEffect(() => {
    props.setTitle({
      value: 'Subscriptions',
      noThumbtack: true,
      children: <VersionBadge />,
    });
  }, []);

  const client = nextClient.forEntityNext(nextClient.ENTITIES.API_SUBSCRIPTIONS);

  const fetchSubscriptions = () => {
    if (version) {
      if (isDraft) return findDraftsByKind('api-subscription').then((d) => ({ data: d }));

      return nextClient.forEntityNext(nextClient.ENTITIES.API_SUBSCRIPTIONS).findAllWithPagination({
        page: 1,
        pageSize: 15,
        filtered: [
          {
            id: 'api_ref',
            value: params.apiId,
          },
        ],
      });
    }
  };

  const deleteItem = (item) => {
    if (isDraft) return nextClient.forEntityNext(nextClient.ENTITIES.DRAFTS).deleteById(item.id);

    return client.delete(item).then(() => window.location.reload());
  };

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
      fetchItems={fetchSubscriptions}
      defaultSort="name"
      defaultSortDesc="true"
      showActions
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
  const [draftSubscription, setDraftSubscription] = useState();

  const { item, isDraft, version } = useDraftOfAPI();

  useEffect(() => {
    props.setTitle(undefined);
  }, []);

  const rawSubscription = useQuery(
    ['getSubscription', params.subscriptionId, version, isDraft],
    () => {
      if (isDraft)
        return nextClient.forEntityNext(nextClient.ENTITIES.DRAFTS).findById(params.subscriptionId);
      return nextClient
        .forEntityNext(nextClient.ENTITIES.API_SUBSCRIPTIONS)
        .findById(params.subscriptionId);
    },
    {
      enabled: !!version,
      onSuccess: (res) => {
        if (isDraft) setDraftSubscription(res);
        else setSubscription(res);
      },
    }
  );

  const goToSubscriptions = () =>
    historyPush(history, location, `/apis/${params.apiId}/subscriptions`);

  const updateSubscription = () => {
    if (isDraft)
      return nextClient.forEntityNext(nextClient.ENTITIES.DRAFTS).update(draftSubscription);

    return nextClient
      .forEntityNext(nextClient.ENTITIES.API_SUBSCRIPTIONS)
      .update(subscription)
      .then(goToSubscriptions);
  };

  if (!item || (isDraft ? !draftSubscription : !subscription)) return <SimpleLoader />;

  const sub = isDraft ? draftSubscription : subscription;

  console.log(draftSubscription);

  return (
    <>
      <PageTitle title={sub.name} {...props}>
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
          value={isDraft ? draftSubscription.content : subscription}
          schema={SUBSCRIPTION_FORM_SETTINGS.schema(item)}
          flow={SUBSCRIPTION_FORM_SETTINGS.flow}
          onChange={(res) =>
            isDraft
              ? setDraftSubscription({ ...draftSubscription, content: res })
              : setSubscription(res)
          }
        />
      </div>
    </>
  );
}

export function NewSubscription(props) {
  const location = useLocation();
  const params = useParams();
  const history = useHistory();

  const [subscription, setSubscription] = useState();
  const [error, setError] = useState();

  const { item, version, isDraft } = useDraftOfAPI();

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
    return (
      isDraft
        ? nextClient.forEntityNext(nextClient.ENTITIES.DRAFTS)
        : nextClient.forEntityNext(nextClient.ENTITIES.API_SUBSCRIPTIONS)
    )
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
      });
  };

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
