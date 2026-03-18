import React, { useEffect, useState } from 'react';
import { Link, useHistory, useLocation, useParams } from 'react-router-dom';
import { NgForm } from '../../components/nginputs';
import { Button } from '../../components/Button';
import { FeedbackButton } from '../RouteDesigner/FeedbackButton';
import PageTitle from '../../components/PageTitle';
import { Row } from '../../components/Row';
import { ArrayInput, Table } from '../../components/inputs';
import { RestrictionPath } from '../../components/Restrictions';
import JwtVerificationOnly from '../../forms/ng_plugins/JwtVerificationOnly';
import NgClientCredentialTokenEndpoint from '../../forms/ng_plugins/NgClientCredentialTokenEndpoint';
import NgHasClientCertMatchingValidator from '../../forms/ng_plugins/NgHasClientCertMatchingValidator';
import SimpleLoader from './SimpleLoader';
import { useDraftOfAPI, historyPush } from './hooks';
import { DraftOnly, VersionBadge } from './DraftOnly';
import { v4 } from 'uuid';
import ApikeyCalls from '../../forms/ng_plugins/ApikeyCalls';
import { findAuthConfigById, subscribeToPlan } from '../../services/BackOfficeServices';
import NgJwtUserExtractor from '../../forms/ng_plugins/NgJwtUserExtractor';
import { SelectorWizardLauncher } from '../../forms/wizards/SelectorWizardLauncher';
import { MAX_WIDTH } from './constants';
import { NewSubscription } from './Subscriptions';

const STATUS_BADGES = {
  staging: { label: 'Staging', cls: 'api-status-started' },
  published: { label: 'Published', cls: 'api-status-published' },
  deprecated: { label: 'Deprecated', cls: 'api-status-deprecated' },
  closed: { label: 'Closed', cls: 'api-status-removed' },
};

const CURRENCIES = [
  { code: "EUR", name: "Euro", symbol: "€" },
  { code: "USD", name: "United States Dollar", symbol: "$" },
  { code: "GBP", name: "British Pound Sterling", symbol: "£" },
  { code: "JPY", name: "Japanese Yen", symbol: "¥" },
  { code: "CHF", name: "Swiss Franc", symbol: "CHF" },
  { code: "AUD", name: "Australian Dollar", symbol: "A$" },
  { code: "CAD", name: "Canadian Dollar", symbol: "C$" },
  { code: "CNY", name: "Chinese Yuan Renminbi", symbol: "¥" },
  { code: "SEK", name: "Swedish Krona", symbol: "kr" },
  { code: "NZD", name: "New Zealand Dollar", symbol: "NZ$" },
  { code: "MXN", name: "Mexican Peso", symbol: "$" },
  { code: "SGD", name: "Singapore Dollar", symbol: "S$" },
  { code: "HKD", name: "Hong Kong Dollar", symbol: "HK$" },
  { code: "NOK", name: "Norwegian Krone", symbol: "kr" },
  { code: "KRW", name: "South Korean Won", symbol: "₩" },
  { code: "TRY", name: "Turkish Lira", symbol: "₺" },
  { code: "INR", name: "Indian Rupee", symbol: "₹" },
  { code: "RUB", name: "Russian Ruble", symbol: "₽" },
  { code: "BRL", name: "Brazilian Real", symbol: "R$" },
  { code: "ZAR", name: "South African Rand", symbol: "R" }
];

const ACCESS_MODE_LABELS = {
  keyless: 'Keyless',
  apikey: 'API Key',
  mtls: 'mTLS',
  oauth2: 'OAuth2',
  jwt: 'JWT',
};

const ApiKeysConstants = {
  schema: {
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
  apikey: {
    schema: ApikeyCalls.config_schema,
    flow: ApikeyCalls.config_flow,
  },
  jwt: {
    schema: NgJwtUserExtractor.config_schema,
    flow: ['verifier', 'name_path', 'email_path', 'meta_path'],
  },
  'oauth2-local': {
    schema: NgClientCredentialTokenEndpoint.config_schema,
    flow: NgClientCredentialTokenEndpoint.config_flow,
  },
  'oauth2-remote': {
    schema: {
      verifier: {
        label: 'Authentication module',
        type: 'AuthenticationWizard',
        props: {
          componentLauncher: SelectorWizardLauncher,
          componentsProps: {
            entityName: 'Authentication configuration',
            entityField: 'authentication',
            findById: findAuthConfigById,
          },
        },
      }
    },
    flow: ['verifier'],
  },
  mtls: {
    schema: NgHasClientCertMatchingValidator.config_schema,
    flow: [
      // 'serial_numbers',
      // 'subject_dns',
      // 'issuer_dns',
      'regex_subject_dns',
      'regex_issuer_dns'
    ],
  },
  keyless: {
    schema: {},
    flow: []
  },
  public: {
    schema: {},
    flow: []
  }
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
            id: 'public',
            key: 'Public',
            text: 'Public plan available to all developers. Clients can subscribe directly from the developer portal without requiring manual approval.'
          },
          {
            id: 'mtls',
            key: 'MTLS',
            text: "Mutual TLS authentication requiring the client to present a valid client certificate. Both parties verify each other's identity, ensuring a strong level of trust and encryption between the client and the gateway.",
          },
          {
            id: 'oauth2-local',
            key: 'OAuth2 Local',
            text: 'Machine-to-machine authentication using the OAuth 2.0 client credentials flow. The client obtains an access token from an authorization server and includes it in each request to the API.',
          },
          {
            id: 'oauth2-remote',
            key: 'OAuth2 Remote',
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

function AccessModeLayout({ children, hide, accessModeConfigurationType, onConfirm }) {
  return <div className="wizard">
    <div className="wizard-container" style={{ maxWidth: 750 }}>
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', padding: '2.5rem', paddingBottom: '5rem' }}>
        <label style={{ fontSize: '1.15rem', marginBottom: '2rem' }}>
          <i className="fas fa-times me-3" onClick={hide} style={{ cursor: 'pointer' }} />
          <span>Edit {accessModeConfigurationType}</span>
        </label>
        {children}

        <div style={{
          position: 'fixed',
          right: '2rem',
          bottom: 0,
          left: 0,
          zIndex: 1000,
          background: 'var(--bg-color_level1)',
          borderTop: '1px solid var(--bg-color_level3)'
        }} className='p-3 d-flex justify-content-end'>
          <button onClick={onConfirm} type="btn" className='btn btn-primaryColor'>
            Save {accessModeConfigurationType}
          </button>
        </div>
      </div>
    </div>
  </div>
}

function AccessModeConfiguration({ value, hide, onConfirm }) {
  const [accessModeConfiguration, setAccessModeConfiguration] = useState(() => value);

  const accessModeConfigurationType = value.access_mode_configuration_type

  if (['mtls', 'oauth2-local', 'oauth2-remote', 'jwt', 'public', 'keyless'].includes(accessModeConfigurationType))
    return <AccessModeConfigurationExceptApikey value={value} hide={hide} onConfirm={onConfirm} />

  return <AccessModeLayout
    hide={hide}
    accessModeConfigurationType={accessModeConfigurationType}
    onConfirm={() => onConfirm(accessModeConfiguration)}>
    <NgForm
      value={accessModeConfiguration}
      schema={ApiKeysConstants.schema}
      flow={[
        'clientIdPattern',
        'clientNamePattern',
        'description',
        'authorizedEntities',
        'validUntil',
        'readOnly',
        'allowClientIdOnly',
        'constrainedServicesOnly',
        'restrictions',
        'rotation',
        { type: 'group', name: 'Miscellaneous', fields: ['metadata', 'tags'] }
      ]}
      onChange={setAccessModeConfiguration}
    />
  </AccessModeLayout>
}

function AccessModeConfigurationExceptApikey({ value, hide, onConfirm }) {
  const [accessModeConfiguration, setAccessModeConfiguration] = useState(() => value)
  const accessModeConfigurationType = value.access_mode_configuration_type

  return <AccessModeLayout
    accessModeConfigurationType={accessModeConfigurationType}
    onConfirm={() => onConfirm(accessModeConfiguration)}
    hide={hide}>
    <NewAccessModeSettingsForm
      schema={AccessModePluginConfigurationForm[accessModeConfigurationType].schema}
      flow={AccessModePluginConfigurationForm[accessModeConfigurationType].flow}
      value={accessModeConfiguration}
      onChange={setAccessModeConfiguration}
    />
  </AccessModeLayout>
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
      visible: props => !['keyless', 'public'].includes(props?.access_mode_configuration_type),
      renderer: ({ rootValue, value }) => {
        if (!rootValue.access_mode_configuration_type) return null;

        return (
          <Row title="Access mode configuration">
            <Button
              type="primaryColor"
              onClick={() => {
                setAccessMode({
                  ...(value || {}),
                  access_mode_configuration_type: rootValue.access_mode_configuration_type,
                });
              }}
            >
              Edit the configuration
            </Button>
          </Row>
        );
      },
    },
    rateLimiting: {
      type: 'form',
      label: 'Rate Limiting & Quotas',
      schema: {
        strategy: {
          type: 'select',
          label: 'Strategy',
          props: {
            defaultValue: 'LegacyThrottlingStrategyConfig',
            options: [
              { value: 'LocalTokensBucketStrategyConfig', label: 'Local tokens bucket' },
              { value: 'LegacyThrottlingStrategyConfig', label: 'Legacy throttling strategy' },
              { value: 'FixedWindowStrategyConfig', label: 'Fixed window' },
            ],
          }
        },
        perIp: {
          type: 'bool',
          label: 'Per IP'
        },
        customPattern: {
          type: 'string',
          label: 'Custom group via expression (header, JWT claim)'
        }
      },
      flow: ['strategy', 'perIp', 'customPattern']
    },
    pricing: {
      type: 'form',
      label: 'Pricing',
      flow: ['enabled', 'name', 'price', 'currency', 'params'],
      schema: {
        enabled: {
          type: 'bool',
          label: "Enabled",
        },
        name: {
          visible: props => props?.enabled,
          type: 'string',
          label: 'Name'
        },
        price: {
          visible: props => props?.enabled,
          type: 'number',
          label: 'Price'
        },
        currency: {
          visible: props => props?.enabled,
          type: 'select',
          label: 'Currency',
          props: {
            options: CURRENCIES.map(({ code, name, symbol }) => ({
              value: code,
              label: `${name} - ${symbol}`
            }))
          }
        },
        params: {
          visible: props => props?.enabled,
          type: 'code',
          label: 'Extra parameters',
          props: {
            ngOptions: {
              spread: true,
            }
          }
        }
      },
    },
    tags: { type: 'array', label: 'Tags' },
    metadata: { type: 'object', label: 'Metadata' },
    validation: {
      type: 'form',
      label: 'Plan validation',
      schema: {
        kind: {
          type: 'select',
          label: 'Kind',
          props: {
            defaultValue: 'auto',
            options: [
              { value: 'auto', label: 'Auto' },
              { value: 'manual', label: 'Manual' },
              { value: 'webhook', label: 'Webhook' },
              { value: 'workflow', label: 'Workflow' },
              { value: 'wasm', label: 'Wasm' },
              { value: 'custom', label: 'Custom' },
            ],
          }
        },
        config: {
          type: 'json',
          label: 'Validation config.',
          props: {
            defaultValue: '{}',
            height: 100
          }
        }
      },
      flow: ['kind', 'config']
    },
    visibility: {
      label: "Visibility",
      type: 'form',
      schema: {
        kind: {
          label: 'Kind',
          type: 'select',
          props: {
            defaultValue: 'public',
            options: [
              { value: 'public', label: 'Public' },
              { value: 'semi_public', label: 'Semi Public' },
              { value: 'private', label: 'Private' },
              { value: 'custom', label: 'Custom' },
            ],
          },
        },
        config: {
          type: 'json',
          label: 'Visibility config.',
          props: {
            defaultValue: '{}',
            height: 100
          }
        }
      },
      flow: [
        "kind",
        "config",
      ],
    },
  };

  const flow = [
    { type: 'group', name: 'General', collapsable: false, fields: ['name', 'description'] },
    { type: 'group', name: 'Lifecycle', collapsable: false, fields: ['status', 'statusDescription'] },
    'visibility',
    { type: 'group', name: 'Access Mode', collapsable: false, fields: ['access_mode_configuration_type', 'access_mode_configuration'] },
    'rateLimiting',
    'pricing',
    'validation',
    { type: 'group', name: 'Metadata', collapsed: true, fields: ['tags', 'metadata'] },
  ];

  return (
    <>
      {accessMode && (
        <AccessModeConfiguration
          value={accessMode}
          onConfirm={data => {
            onChange({
              ...plan,
              access_mode_configuration: data
            })
            setAccessMode(undefined)
          }}
          hide={() => setAccessMode(undefined)}
        />
      )}
      <NgForm value={plan} schema={schema} flow={flow} onChange={onChange} />
    </>
  );
}

// function SubscriptionModal({ ok, cancel, plan, api, props }) {

//   // useEffect(() => {
//   //   subscribeToPlan(api.id, plan.id)
//   //     .then(res => console.log(res))
//   // }, [])

//   return <NewSubscription props={props} />
// }


export function Plans(props) {
  const params = useParams();
  const history = useHistory();
  const [plan, setPlan] = useState()
  const { item, version } = useDraftOfAPI();

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
          : <span>—</span>
      }
    },
    {
      title: 'Status',
      notFilterable: true,
      cell: (_, plan) => {
        return STATUS_BADGES[plan.status] ? <span className={`badge custom-badge ${STATUS_BADGES[plan.status]?.cls}`}>
          {STATUS_BADGES[plan.status]?.label || plan.status}
        </span> : null
      }
    },
    {
      title: 'Subscribe',
      notFilterable: true,
      cell: (_, plan) => <Button type='primary' className='btn-sm' onClick={() => setPlan(plan)}>
        Subscribe
      </Button>
    }
  ]

  const deleteItem = plan => {
    return updateItem({
      ...item,
      documentation: {
        ...documentation,
        plans: item.plans.filter(c => c.id !== plan.id)
      }
    })
  }

  if (plan)
    return <NewSubscription plan={plan} {...props} />

  return <Table
    parentProps={{ params }}
    navigateTo={(plan) => history.push(`/apis/${params.apiId}/plans/${plan.id}/edit?version=${version}`)}
    navigateOnEdit={(plan) => history.push(`/apis/${params.apiId}/plans/${plan.id}/edit?version=${version}`)}
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
    itemUrl={(plan) => `/apis/${params.apiId}/plans/${plan.id}/edit?version=${version}`}
    rawEditUrl={true}
    injectTopBar={() => (<DraftOnly>
      <div className="btn-group input-group-btn">
        <Link className="btn btn-primary btn-sm" to={`plans/new?version=${version}`}>
          <i className="fas fa-plus-circle" /> Create new plan
        </Link>
      </div>
    </DraftOnly>)}
  />
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

  if (!item || !plan) return <SimpleLoader />;

  const back = () => historyPush(history, location, `/apis/${params.apiId}/plans`);

  const save = () => {
    const plans = isNew
      ? [...(item.documentation?.plans || []), plan]
      : item.documentation.plans.map((p) => (p.id === plan.id ? plan : p));
    return updateItem({ ...item, documentation: { ...item.documentation, plans } })
      .then(back);
  };

  return <div style={{ maxWidth: MAX_WIDTH }}>
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
