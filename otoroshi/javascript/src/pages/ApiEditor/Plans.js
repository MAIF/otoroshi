import React, { useEffect, useState } from 'react';
import { Link, useHistory, useLocation, useParams } from 'react-router-dom';
import { NgForm } from '../../components/nginputs';
import { Button } from '../../components/Button';
import { FeedbackButton } from '../RouteDesigner/FeedbackButton';
import PageTitle from '../../components/PageTitle';
import { Row } from '../../components/Row';
import { ArrayInput, Table } from '../../components/inputs';
import { RestrictionPath } from '../../components/Restrictions';
import { JsonObjectAsCodeInput } from '../../components/inputs/CodeInput';
import JwtVerificationOnly from '../../forms/ng_plugins/JwtVerificationOnly';
import NgClientCredentialTokenEndpoint from '../../forms/ng_plugins/NgClientCredentialTokenEndpoint';
import NgHasClientCertMatchingValidator from '../../forms/ng_plugins/NgHasClientCertMatchingValidator';
import SimpleLoader from './SimpleLoader';
import { useDraftOfAPI, historyPush } from './hooks';
import { DraftOnly, VersionBadge } from './DraftOnly';
import { v4 } from 'uuid';

const STATUS_BADGES = {
  staging: { label: 'Staging', cls: 'api-status-started' },
  published: { label: 'Published', cls: 'api-status-published' },
  deprecated: { label: 'Deprecated', cls: 'api-status-deprecated' },
  closed: { label: 'Closed', cls: 'api-status-removed' },
};

const ACCESS_MODE_LABELS = {
  keyless: 'Keyless',
  apikey: 'API Key',
  mtls: 'mTLS',
  oauth2: 'OAuth2',
  jwt: 'JWT',
};

const ApiKeysConstants = {
  schema: {
    enabled: {
      type: 'bool',
      label: 'Enabled',
      props: {
        placeholder: 'The ApiKey is enabled',
        help: 'If the API key is disabled, then any call using this API key will fail',
      },
    },
    clientIdPattern: {
      type: 'string',
      label: 'ApiKey Id Pattern',
    },
    clientNamePattern: {
      type: 'string',
      label: 'ApiKey Name Pattern',
    },
    description: {
      type: 'string',
      label: 'ApiKey description',
      props: {
        help: 'A useful description for this apikey',
        placeholder: `A useful description for this apikey`,
      },
    },
    authorizedEntities: {
      type: 'array-select',
      label: 'Authorized on',
      props: {
        placeholder: 'The groups/services of the api key',
        help: 'The groups/services linked to this api key',
        optionsFrom: '/bo/api/groups-and-services',
      },
    },
    validUntil: {
      type: 'date',
      label: 'Valid until',
      props: {
        help: 'Auto disable apikey after this date',
      },
    },
    readOnly: {
      type: 'bool',
      label: 'Read only',
      props: {
        placeholder: 'The ApiKey is read only',
        help: 'If the API key is in read only mode, every request done with this api key will only work for GET, HEAD, OPTIONS verbs',
      },
    },
    allowClientIdOnly: {
      type: 'bool',
      label: 'Allow pass by clientid only',
      props: {
        placeholder: 'Allow pass by clientid only',
        help: 'Here you allow client to only pass client id in a specific header in order to grant access to the underlying api',
      },
    },
    constrainedServicesOnly: {
      type: 'bool',
      label: 'Constrained services only',
      props: {
        help: 'This apikey can only be used on services using apikey routing constraints',
      },
    },
    throttlingQuota: {
      type: 'number',
      label: 'Throttling quota',
      props: {
        placeholder: 'Authorized calls per window',
        suffix: 'calls per window',
        help: 'The authorized number of calls per window. See the `otoroshi.throttlingWindow` config. or `OTOROSHI_THROTTLING_WINDOW` environment variable.',
      },
    },
    dailyQuota: {
      type: 'number',
      label: 'Daily quota',
      props: {
        placeholder: 'Authorized calls per day',
        suffix: 'calls per day',
        help: 'The authorized number of calls per day',
      },
    },
    monthlyQuota: {
      type: 'number',
      label: 'Monthly quota',
      props: {
        placeholder: 'Authorized calls per month',
        suffix: 'calls per month',
        help: 'The authorized number of calls per month',
      },
    },
    restrictions: {
      type: 'form',
      label: 'Restrictions',
      schema: {
        enabled: { type: 'boolean', label: 'Enabled' },
        allowLast: { type: 'boolean', label: 'Allow last' },
        allowed: {
          renderer: (props) => (
            <ArrayInput
              label="Allowed"
              value={props.value}
              help="Allowed paths"
              component={RestrictionPath}
              defaultValue={{ method: '*', path: '/.*' }}
              onChange={props.onChange}
            />
          ),
        },
        forbidden: {
          renderer: (props) => (
            <ArrayInput
              label="Forbidden"
              value={props.value}
              help="Forbidden paths"
              component={RestrictionPath}
              defaultValue={{ method: '*', path: '/.*' }}
              onChange={props.onChange}
            />
          ),
        },
        notFound: {
          renderer: (props) => (
            <ArrayInput
              label="Not Found"
              value={props.value}
              help="Not found paths"
              component={RestrictionPath}
              defaultValue={{ method: '*', path: '/.*' }}
              onChange={props.onChange}
            />
          ),
        },
      },
      flow: ['enabled', 'allowLast', 'allowed', 'notFound'],
    },
    rotation: {
      type: 'form',
      label: 'Rotation',
      schema: {
        enabled: {
          type: 'bool',
          label: 'Enabled',
          props: { help: 'Enabled automatic apikey secret rotation' },
        },
        rotationEvery: {
          type: 'number',
          label: 'Rotation every',
          props: { placeholder: 'rotate secrets every', suffix: 'hours', help: 'rotate secrets every' },
        },
        gracePeriod: {
          type: 'number',
          label: 'Grace period',
          props: { placeholder: 'period when both secrets can be used', suffix: 'hours', help: 'period when both secrets can be used' },
        },
        nextSecret: {
          type: 'string',
          label: 'Next client secret',
          props: { disabled: true },
        },
      },
      flow: ['enabled', 'rotationEvery', 'gracePeriod', 'nextSecret'],
    },
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
  },
};

const AccessModePluginConfigurationForm = {
  jwt: {
    schema: JwtVerificationOnly.config_schema,
    flow: JwtVerificationOnly.config_flow,
  },
  oauth2: {
    schema: NgClientCredentialTokenEndpoint.config_schema,
    flow: NgClientCredentialTokenEndpoint.config_flow,
  },
  mtls: {
    schema: NgHasClientCertMatchingValidator.config_schema,
    flow: NgHasClientCertMatchingValidator.config_flow,
  },
  apikey: {
    schema: {
      wipe_backend_request: {
        label: 'Wipe backend request',
        type: 'box-bool',
        props: { description: 'Remove the apikey from call made to downstream service' },
      },
      update_quotas: {
        label: 'Update quotas',
        type: 'box-bool',
        props: { description: 'Each call with an apikey will update its quota' },
      },
      pass_with_user: {
        label: 'Pass with user',
        type: 'box-bool',
        props: { description: 'Allow the path to be accessed via an Authentication module' },
      },
      mandatory: {
        label: 'Mandatory',
        type: 'box-bool',
        props: {
          description:
            'Allow an apikey and an authentication module to be used on a same path. If disabled, the endpoint can be called without apikey.',
        },
      },
      validate: {
        label: 'Validate',
        type: 'box-bool',
        props: {
          description:
            'Check that the api key has not expired, has not reached its quota limits and is authorized to call the Otoroshi service',
        },
      },
    },
  },
};

function AccessModeConfigurationTypeSelector({ onChange, value }) {
  return (
    <Row title="Type">
      <div className="d-flex flex-column gap-2 m-2">
        {[
          {
            id: 'keyless',
            key: 'Keyless',
            text: 'Open access without any authentication. Clients can call the API freely without providing credentials. Useful for public APIs that do not require identification or rate limiting per client.',
          },
          {
            id: 'mtls',
            key: 'MTLS',
            text: "Mutual TLS authentication requiring the client to present a valid client certificate. Both parties verify each other's identity, ensuring a strong level of trust and encryption between the client and the gateway.",
          },
          {
            id: 'oauth2',
            key: 'OAuth2',
            text: 'Machine-to-machine authentication using the OAuth 2.0 client credentials flow. The client obtains an access token from an authorization server and includes it in each request to the API.',
          },
          {
            id: 'apikey',
            key: 'Apikey',
            text: 'Authentication via a unique API key provided by the client in the request headers or query parameters. Enables identification, rate limiting, and usage tracking per client.',
          },
          {
            id: 'jwt',
            key: 'JWT',
            text: 'Authentication using a signed JSON Web Token. The client includes a JWT in the request, which the gateway validates against a trusted issuer to verify identity and granted permissions.',
          },
        ].map(({ key, text, id }) => (
          <button
            type="button"
            className={`btn d-flex flex-column ${value === id ? 'btn-primaryColor' : 'btn-quiet'} pb-3`}
            onClick={() => onChange(id)}
            key={id}
          >
            <div style={{ fontWeight: 'bold', textAlign: 'left' }} className="py-2">
              {key}
            </div>
            <p className="m-0" style={{ textAlign: 'left', fontSize: '.9rem' }}>
              {text}
            </p>
          </button>
        ))}
      </div>
    </Row>
  );
}

function NewAccessModeSettingsForm(props) {
  return (
    <NgForm
      value={props.value}
      onChange={(settings) => {
        if (settings && JSON.stringify(props.value, null, 2) !== JSON.stringify(settings, null, 2))
          props.onChange(settings);
      }}
      schema={props.schema}
      flow={props.flow}
    />
  );
}

function AccessModeConfiguration({ value, hide }) {
  const [accessModeConfiguration, setAccessModeConfiguration] = useState(() => value);
  const [pluginConfiguration, setPluginConfiguration] = useState(() => value?.pluginConfiguration);

  return (
    <div className="wizard">
      <div className="wizard-container">
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', padding: '2.5rem' }}>
          <label style={{ fontSize: '1.15rem', marginBottom: '2rem' }}>
            <i className="fas fa-times me-3" onClick={hide} style={{ cursor: 'pointer' }} />
            <span>Edit {value.access_mode_configuration_type}</span>
          </label>

          {value.access_mode_configuration_type === 'apikey' && (
            <NgForm
              value={accessModeConfiguration}
              schema={{
                ...ApiKeysConstants.schema,
                pluginConfiguration: {
                  renderer: () => (
                    <>
                      {AccessModePluginConfigurationForm[value.access_mode_configuration_type] ? (
                        <NewAccessModeSettingsForm
                          schema={AccessModePluginConfigurationForm[value.access_mode_configuration_type].schema}
                          flow={AccessModePluginConfigurationForm[value.access_mode_configuration_type].flow}
                          value={pluginConfiguration}
                          onChange={setPluginConfiguration}
                        />
                      ) : (
                        <JsonObjectAsCodeInput
                          label="Additional informations"
                          onChange={setPluginConfiguration}
                          value={pluginConfiguration}
                        />
                      )}
                    </>
                  ),
                },
              }}
              flow={[
                'enabled',
                'clientIdPattern',
                'clientNamePattern',
                'description',
                'authorizedEntities',
                'validUntil',
                'readOnly',
                'allowClientIdOnly',
                'constrainedServicesOnly',
                'throttlingQuota',
                'dailyQuota',
                'monthlyQuota',
                'restrictions',
                'rotation',
                { type: 'group', name: 'Miscellaneous', fields: ['metadata', 'tags'] },
                { type: 'group', name: 'Avancé', fields: ['pluginConfiguration'] },
              ]}
              onChange={setAccessModeConfiguration}
            />
          )}
        </div>
      </div>
    </div>
  );
}

function PlanForm({ plan, onChange }) {
  const [accessMode, setAccessMode] = useState();

  const schema = {
    name: { type: 'string', label: 'Name' },
    description: { type: 'string', label: 'Description' },
    status: {
      type: 'dots',
      label: 'Status',
      props: {
        defaultValue: 'staging',
        options: ['staging', 'published', 'deprecated', 'closed'],
      },
    },
    statusDescription: {
      renderer: ({ rootValue }) => {
        const descriptions = {
          staging:
            'This is the initial phase of a plan, where it exists in draft mode. You can configure the plan, but it won\'t be visible or accessible to users',
          published:
            'When your plan is finalized, you can publish it to allow subscriptions through the APIM Portal. Once published, subscriptions can access the API via the plan. Published plans remain editable.',
          deprecated:
            'Deprecating a plan makes it unavailable on the APIM Portal, preventing new subscriptions. However, existing subscriptions remain unaffected, ensuring no disruption to current API subscriptions',
          closed:
            'Closing a plan terminates all associated subscriptions, and this action is irreversible. API subscriptions previously subscribed to the plan will no longer have access to the API',
        };
        return (
          <div className="row mb-3" style={{ marginTop: '-1rem' }}>
            <label className="col-xs-12 col-sm-2 col-form-label" />
            <div className="col-sm-10" style={{ fontStyle: 'italic' }}>
              {descriptions[rootValue?.status]}
            </div>
          </div>
        );
      },
    },
    access_mode_configuration_type: {
      renderer: (props) => (
        <AccessModeConfigurationTypeSelector onChange={props.onChange} value={props.value} />
      ),
    },
    access_mode_configuration: {
      renderer: ({ rootValue, value }) => {
        if (!rootValue.access_mode_configuration_type) return null;
        return (
          <Row title="Access mode configuration">
            <Button
              type="primaryColor"
              onClick={() => {
                setAccessMode({
                  access_mode_configuration_type: rootValue.access_mode_configuration_type,
                  ...(value || {}),
                });
              }}
            >
              Edit {rootValue.access_mode_configuration_type}
            </Button>
          </Row>
        );
      },
    },
    tags: { type: 'array', label: 'Tags' },
    metadata: { type: 'object', label: 'Metadata' },
  };

  const flow = [
    { type: 'group', name: 'General', collapsable: false, fields: ['name', 'description'] },
    { type: 'group', name: 'Lifecycle', collapsable: false, fields: ['status', 'statusDescription'] },
    { type: 'group', name: 'Access Mode', collapsable: false, fields: ['access_mode_configuration_type', 'access_mode_configuration'] },
    { type: 'group', name: 'Metadata', collapsed: true, fields: ['tags', 'metadata'] },
  ];

  return (
    <>
      {accessMode && (
        <AccessModeConfiguration
          value={accessMode}
          hide={() => setAccessMode(undefined)}
        />
      )}
      <NgForm value={plan} schema={schema} flow={flow} onChange={onChange} />
    </>
  );
}

export function Plans(props) {
  const params = useParams();
  const history = useHistory();
  const { item } = useDraftOfAPI();

  useEffect(() => {
    props.setTitle({
      value: 'Plans',
      noThumbtack: true,
      children: <VersionBadge />,
    });
  }, []);

  if (!item) return <SimpleLoader />;

  const plans = item.documentation?.plans || [];

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
    {
      title: 'Type',
      notFilterable: true,
      cell: (_, plan) => {
        return plan.access_mode_configuration_type
          ? <span className="badge custom-badge api-status-started">
            {ACCESS_MODE_LABELS[plan.access_mode_configuration_type] || plan.access_mode_configuration_type}
          </span>
          : <span style={{ color: 'var(--text-muted)' }}>—</span>
      }
    },
    {
      title: 'Status',
      notFilterable: true,
      cell: (_, plan) => {
        return <span className={`badge custom-badge ${STATUS_BADGES[plan.status]?.cls}`}>
          {STATUS_BADGES[plan.status]?.label || plan.status}
        </span>
      }
    }
  ]

  const deleteItem = plan => {

  }

  return (
    <>
      <Table
        parentProps={{ params }}
        navigateTo={(plan) => history.push(`/apis/${params.apiId}/plans/${plan.id}/edit`)}
        navigateOnEdit={(plan) => history.push(`/apis/${params.apiId}/plans/${plan.id}/edit`)}
        selfUrl="plans"
        defaultTitle="Plans"
        itemName="Plan"
        formSchema={null}
        formFlow={null}
        columns={columns}
        deleteItem={(item) => deleteItem(item)}
        defaultSort="name"
        defaultSortDesc="true"
        fetchItems={() => Promise.resolve(plans)}
        fetchTemplate={() => Promise.resolve({})}
        showActions={true}
        showLink={false}
        extractKey={(item) => item.id}
        rowNavigation={true}
        hideAddItemAction={true}
        itemUrl={(plan) => `/apis/${params.apiId}/plans/${plan.id}/edit`}
        rawEditUrl={true}
        injectTopBar={() => (
          <div className="btn-group input-group-btn">
            <Link className="btn btn-primary btn-sm" to="plans/new">
              <i className="fas fa-plus-circle" /> Create new plan
            </Link>
          </div>
        )}
      />
    </>
  );
}

export function PlanEditor(props) {
  const params = useParams();
  const history = useHistory();
  const location = useLocation();

  const { item, updateItem } = useDraftOfAPI();

  const isNew = !params.planId;
  const [plan, setPlan] = useState(isNew ? {
    id: v4(),
    name: 'New plan',
    status: 'staging',
    access_mode_configuration_type: 'apikey',
    access_mode_configuration: {
      enabled: true
    }
  } : null);

  useEffect(() => {
    if (!isNew && item && !plan) {
      const found = (item.documentation?.plans || []).find((p) => p.id === params.planId);
      if (found) setPlan(found);
    }
  }, [item]);

  useEffect(() => {
    props.setTitle(undefined);
  }, []);

  console.log(plan)

  if (!item || !plan) return <SimpleLoader />;

  const back = () => historyPush(history, location, `/apis/${params.apiId}/plans`);

  const save = () => {
    const plans = isNew
      ? [...(item.documentation?.plans || []), plan]
      : item.documentation.plans.map((p) => (p.id === plan.id ? plan : p));
    return updateItem({ ...item, documentation: { ...item.documentation, plans } }).then(back);
  };

  return <div style={{ maxWidth: 1050 }}>
    <PageTitle title={isNew ? 'New Plan' : plan.name} {...props}>
      <DraftOnly>
        <FeedbackButton
          type="success"
          className="d-flex ms-2"
          onPress={save}
          text={
            <div className="d-flex align-items-center">
              {isNew ? 'Create' : 'Update'} <VersionBadge size="xs" />
            </div>
          }
        />
      </DraftOnly>
    </PageTitle>
    <PlanForm plan={plan} onChange={setPlan} />
  </div>
}
