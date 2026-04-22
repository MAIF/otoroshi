import React, { useEffect, useState } from 'react';
import { useHistory, useLocation, useParams } from 'react-router-dom';
import { useQuery } from 'react-query';
import { components } from 'react-select';
import { findDraftsByKind, nextClient } from '../../services/BackOfficeServices';
import * as BackOfficeServices from '../../services/BackOfficeServices';
import { Table } from '../../components/inputs';
import { NgAnyRenderer, NgForm } from '../../components/nginputs';
import PageTitle from '../../components/PageTitle';
import { FeedbackButton } from '../RouteDesigner/FeedbackButton';
import SimpleLoader from './SimpleLoader';
import { useDraftOfAPI, historyPush, linkWithQuery } from './hooks';
import { VersionBadge } from './DraftOnly';
import { Button } from '../../components/Button';
import { Row } from '../../components/Row';

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
      status: {
        renderer: (props) => {
          const STATUS_OPTIONS = ['disabled', 'pending', 'enabled', 'deprecated', 'custom'];
          const isCustom = props.value !== undefined && !['disabled', 'pending', 'enabled', 'deprecated'].includes(props.value);
          const dotValue = isCustom ? 'custom' : (props.value || 'disabled');

          console.log(isCustom, dotValue)

          return (
            <Row title="Status">
              <div className="d-flex align-items-center gap-2 flex-wrap">
                {STATUS_OPTIONS.map((s) => {
                  const active = dotValue === s;
                  return (
                    <button
                      key={s}
                      onClick={() => props.onChange(s)}
                      className={`btn btn-sm ${active ? 'btn-success' : 'btn-primary'}`}
                    >
                      {s}
                    </button>
                  );
                })}
              </div>
              {dotValue === 'custom' && (
                <input
                  className="form-control mt-2"
                  style={{ maxWidth: 300 }}
                  placeholder="Custom status value..."
                  value={isCustom ? props.value : ''}
                  onChange={(e) => props.onChange(e.target.value)}
                  autoFocus
                />
              )}
            </Row>
          );
        },
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
          options: item.plans || [],
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
        type: 'any',
        label: 'Token refs',
        props: {
          mode: 'jsonOrPlaintext',
          language: 'json',
          useInternalState: true,
          defaultValue: '{}',
          height: 150,
        },
      },
      payment_ref: {
        type: 'any',
        label: 'Payment information',
        props: {
          mode: 'jsonOrPlaintext',
          language: 'json',
          useInternalState: true,
          defaultValue: '{}',
          height: 200
        }
      },
      metadata: {
        type: 'object',
        label: 'Metadata'
      },
      tags: {
        type: 'array',
        label: 'Tags'
      },
    };
  },
  flow: (isEdition) => [
    'location',
    {
      type: 'group',
      name: 'Informations',
      collapsable: false,
      fields: ['name', 'description', isEdition ? 'status' : undefined].filter(f => f),
    },
    {
      type: 'group',
      name: 'Ownership',
      collapsable: false,
      fields: ['owner_ref', 'plan_ref', isEdition ? 'token_refs' : undefined].filter(f => f),
    },
    {
      type: 'group',
      name: 'Payment',
      collapsable: false,
      fields: ['payment_ref']
    },
    {
      type: 'group',
      name: 'Misc.',
      collapsed: true,
      fields: ['metadata', 'tags'],
    }
  ],
};

export function Subscriptions(props) {
  const history = useHistory();
  const params = useParams();
  const location = useLocation();

  const { isDraft, version, item } = useDraftOfAPI();

  const columns = [
    {
      title: 'Name',
      filterId: 'name',
      content: (item) => item.name,
    },
    {
      title: 'Status',
      filterId: 'status',
      notFilterable: true,
      content: (item) => item.content?.status,
      cell: (v, subscription, table) => {
        const sub = (subscription.content ?? subscription) || {}
        if (sub.status === 'pending') {
          return <Button
            type="success"
            className='btn-sm'
            onClick={() => {
              BackOfficeServices.confirmSubscription(item.id, subscription.id, isDraft ? 'Draft' : 'Published')
                .then(() => window.location.reload())
            }}>Confirm</Button>
        }

        return <span className="badge bg-success">
          {sub.status}
        </span>
      }
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

    return client.delete(item)
      .then(() => window.location.reload());
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

  const [subscription, setSubscription] = useState();
  const [draftSubscription, setDraftSubscription] = useState();

  const { item, isDraft, version } = useDraftOfAPI();

  useEffect(() => {
    props.setTitle(undefined);
  }, []);

  useQuery(
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

  const updateSubscription = () => {
    if (isDraft)
      return nextClient.forEntityNext(nextClient.ENTITIES.DRAFTS).update(draftSubscription);

    return nextClient
      .forEntityNext(nextClient.ENTITIES.API_SUBSCRIPTIONS)
      .update(subscription)
  };

  if (!item || (isDraft ? !draftSubscription : !subscription)) return <SimpleLoader />;

  const sub = isDraft ? draftSubscription : subscription;

  return <div className='page'>
    <PageTitle title={sub.name} {...props} />
    <div className='displayGroupBtn'>
      <FeedbackButton
        type="success"
        onPress={updateSubscription}
        text={
          <div className="d-flex align-items-center">
            Save <VersionBadge size="xs" />
          </div>
        }
      />
    </div>
    <NgForm
      value={isDraft ? draftSubscription.content : subscription}
      schema={SUBSCRIPTION_FORM_SETTINGS.schema(item)}
      flow={SUBSCRIPTION_FORM_SETTINGS.flow(true)}
      onChange={(res) =>
        isDraft
          ? setDraftSubscription({ ...draftSubscription, content: res })
          : setSubscription(res)
      }
    />
  </div>
}

export function NewSubscription(props) {
  const location = useLocation();
  const params = useParams();
  const history = useHistory();

  const [subscription, setSubscription] = useState({
    plan_ref: props.plan?.id
  });
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
          plan_ref: props.plan?.id || sub.plan_ref,
          subscription_kind: props.plan?.access_mode_configuration_type || sub.subscription_kind,
          consumer_ref: item.consumers?.length > 0 ? item.consumers[0]?.id : undefined,
        }),
    }
  );

  if (!item || !subscription) return <SimpleLoader />;

  const updateSubscription = () => {
    return BackOfficeServices.subscribeToPlan(params.apiId, props.plan.id, isDraft ? 'Draft' : 'Published', {
      ...subscription,
      api_ref: params.apiId,
      draft: version === 'staging' || version === 'draft',
    })
      .then(() => {
        historyPush(history, location, `/apis/${params.apiId}/subscriptions`);
      });
  };

  console.log(subscription)

  return <div className='page'>
    <PageTitle title="Subscription Settings" {...props} />

    <NgForm
      value={subscription}
      schema={SUBSCRIPTION_FORM_SETTINGS.schema(item)}
      flow={SUBSCRIPTION_FORM_SETTINGS.flow(false)}
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

    <div className='displayGroupBtn'>
      <FeedbackButton
        type="success"
        onPress={updateSubscription}
        text={<div className="d-flex align-items-center">
          Create <VersionBadge size="xs" />
        </div>}
      />
    </div>
  </div>
}
