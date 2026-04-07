import React, { useEffect, useMemo, useState } from 'react';
import { Link, useHistory, useLocation, useParams } from 'react-router-dom';
import { NgForm, NgSelectRenderer } from '../../components/nginputs';
import { Button } from '../../components/Button';
import { FeedbackButton } from '../RouteDesigner/FeedbackButton';
import PageTitle from '../../components/PageTitle';
import { Row } from '../../components/Row';
import { ArrayInput, Table } from '../../components/inputs';
import { RestrictionPath } from '../../components/Restrictions';
import NgClientCredentialTokenEndpoint from '../../forms/ng_plugins/NgClientCredentialTokenEndpoint';
import NgHasClientCertMatchingValidator from '../../forms/ng_plugins/NgHasClientCertMatchingValidator';
import SimpleLoader from './SimpleLoader';
import { useDraftOfAPI, historyPush } from './hooks';
import { DraftOnly, VersionBadge } from './DraftOnly';
import { v4 } from 'uuid';
import ApikeyCalls from '../../forms/ng_plugins/ApikeyCalls';
import { findAuthConfigById } from '../../services/BackOfficeServices';
import NgJwtUserExtractor from '../../forms/ng_plugins/NgJwtUserExtractor';
import { SelectorWizardLauncher } from '../../forms/wizards/SelectorWizardLauncher';
import { NewSubscription } from './Subscriptions';
import { Header } from '../../components/wizardframe';
import { ThrottlingStrategy } from '../ServiceApiKeysPage';

const STATUS_BADGES = {
  staging: { label: 'Staging', cls: 'api-status-started' },
  published: { label: 'Published', cls: 'api-status-published' },
  deprecated: { label: 'Deprecated', cls: 'api-status-deprecated' },
  closed: { label: 'Closed', cls: 'api-status-removed' },
};

const CURRENCIES = [
  { code: 'EUR', name: 'Euro', symbol: '€' },
  { code: 'USD', name: 'United States Dollar', symbol: '$' },
  { code: 'GBP', name: 'British Pound Sterling', symbol: '£' },
  { code: 'JPY', name: 'Japanese Yen', symbol: '¥' },
  { code: 'CHF', name: 'Swiss Franc', symbol: 'CHF' },
  { code: 'AUD', name: 'Australian Dollar', symbol: 'A$' },
  { code: 'CAD', name: 'Canadian Dollar', symbol: 'C$' },
  { code: 'CNY', name: 'Chinese Yuan Renminbi', symbol: '¥' },
  { code: 'SEK', name: 'Swedish Krona', symbol: 'kr' },
  { code: 'NZD', name: 'New Zealand Dollar', symbol: 'NZ$' },
  { code: 'MXN', name: 'Mexican Peso', symbol: '$' },
  { code: 'SGD', name: 'Singapore Dollar', symbol: 'S$' },
  { code: 'HKD', name: 'Hong Kong Dollar', symbol: 'HK$' },
  { code: 'NOK', name: 'Norwegian Krone', symbol: 'kr' },
  { code: 'KRW', name: 'South Korean Won', symbol: '₩' },
  { code: 'TRY', name: 'Turkish Lira', symbol: '₺' },
  { code: 'INR', name: 'Indian Rupee', symbol: '₹' },
  { code: 'RUB', name: 'Russian Ruble', symbol: '₽' },
  { code: 'BRL', name: 'Brazilian Real', symbol: 'R$' },
  { code: 'ZAR', name: 'South African Rand', symbol: 'R' },
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
          props: {
            placeholder: 'rotate secrets every',
            suffix: 'hours',
            help: 'rotate secrets every',
          },
        },
        gracePeriod: {
          type: 'number',
          label: 'Grace period',
          props: {
            placeholder: 'period when both secrets can be used',
            suffix: 'hours',
            help: 'period when both secrets can be used',
          },
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
      },
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
      'regex_issuer_dns',
    ],
  },
  keyless: {
    schema: {},
    flow: [],
  },
  public: {
    schema: {},
    flow: [],
  },
};

function AccessModeConfigurationTypeSelector({ onChange, value, onEdit }) {
  return (
    <Row title="Type">
      <div style={{
        display: 'grid',
        gridTemplateColumns: '1fr 1fr',
        gap: '8px',
        margin: '8px',
      }}>
        {[
          { id: 'keyless', key: 'Keyless', icon: 'fa-lock-open', text: 'Open access without authentication. Clients call freely without credentials.' },
          { id: 'public', key: 'Public', icon: 'fa-globe', text: 'Available to all developers. Subscribe directly from the portal without approval.' },
          { id: 'apikey', key: 'API Key', icon: 'fa-key', text: 'Unique key in headers or query params. Enables rate limiting and usage tracking.' },
          { id: 'jwt', key: 'JWT', icon: 'fa-id-badge', text: 'Signed JSON Web Token validated against a trusted issuer to verify identity.' },
          { id: 'mtls', key: 'mTLS', icon: 'fa-certificate', text: 'Mutual TLS — both client and gateway verify each other\'s certificate.' },
          { id: 'oauth2-local', key: 'OAuth2 Local', icon: 'fa-shield-halved', text: 'Client credentials flow with a local authorization server.' },
          { id: 'oauth2-remote', key: 'OAuth2 Remote', icon: 'fa-shield', text: 'Client credentials flow with a remote external authorization server.' },
        ].map(({ key, text, id, icon }) => {
          const selected = value === id;
          return (
            <button
              type="button"
              key={id}
              onClick={() => onChange(id)}
              style={{
                background: selected
                  ? 'color-mix(in srgb, var(--color-primary) 12%, transparent)'
                  : 'rgba(255,255,255,0.03)',
                border: `1px solid ${selected ? 'var(--color-primary)' : 'var(--border-color)'}`,
                borderRadius: '10px',
                padding: '12px 14px',
                textAlign: 'left',
                cursor: 'pointer',
                transition: 'all 150ms ease',
              }}
              onMouseEnter={e => { if (!selected) e.currentTarget.style.border = '1px solid var(--border-color-strong)'; }}
              onMouseLeave={e => { if (!selected) e.currentTarget.style.border = '1px solid var(--border-color)'; }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '6px' }}>
                <i className={`fas ${icon}`} style={{ color: selected ? 'var(--color-primary)' : 'var(--text-muted)', fontSize: '13px', width: '16px' }} />
                <span style={{ fontWeight: 600, fontSize: '13px', color: selected ? 'var(--color-primary)' : 'var(--color_level1)' }}>
                  {key}
                </span>
                {selected && <i className="fas fa-circle-check ms-auto" style={{ color: 'var(--color-primary)', fontSize: '12px' }} />}
              </div>
              <p style={{ margin: 0, fontSize: '12px', lineHeight: '1.4' }}>
                {text}
              </p>

              {selected && <button className='btn btn-success btn-sm ms-auto d-flex' onClick={e => {
                e.stopPropagation()
                onEdit()
              }}>
                Edit
              </button>}
            </button>
          );
        })}
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

function AccessModeLayout({ children, hide, onConfirm }) {
  return (
    <div className="wizard">
      <div className="wizard-container" style={{ maxWidth: 750 }}>
        <div
          style={{
            flex: 1,
            display: 'flex',
            flexDirection: 'column',
            padding: '2.5rem',
            paddingBottom: '5rem',
          }}
        >
          <label style={{ fontSize: '1.15rem', marginBottom: '2rem' }}>
            <i className="fas fa-times me-3" onClick={hide} style={{ cursor: 'pointer' }} />
            <span>Edit</span>
          </label>
          {children}

          <div
            style={{
              position: 'fixed',
              right: '2rem',
              bottom: 0,
              left: 0,
              zIndex: 1000,
              background: 'var(--bg-color_level1)',
              borderTop: '1px solid var(--border-color)',
            }}
            className="p-3 d-flex justify-content-end"
          >
            <button onClick={onConfirm} type="btn" className="btn btn-success">
              Save
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

function AccessModeConfiguration({ value, hide, onConfirm }) {
  const [accessModeConfiguration, setAccessModeConfiguration] = useState(() => value);

  const accessModeConfigurationType = value.access_mode_configuration_type;

  if (
    ['mtls', 'oauth2-local', 'oauth2-remote', 'jwt', 'public', 'keyless'].includes(
      accessModeConfigurationType
    )
  )
    return <AccessModeConfigurationExceptApikey value={value} hide={hide} onConfirm={onConfirm} />;

  return (
    <AccessModeLayout
      hide={hide}
      onConfirm={() => onConfirm(accessModeConfiguration)}
    >
      <NgForm
        value={accessModeConfiguration}
        schema={ApiKeysConstants.schema}
        flow={[
          'clientNamePattern',
          'description',
          'validUntil',
          'readOnly',
          'allowClientIdOnly',
          'constrainedServicesOnly',
          'restrictions',
          'rotation',
          { type: 'group', name: 'Miscellaneous', fields: ['metadata', 'tags'] },
        ]}
        onChange={setAccessModeConfiguration}
      />
    </AccessModeLayout>
  );
}

function AccessModeConfigurationExceptApikey({ value, hide, onConfirm }) {
  const [accessModeConfiguration, setAccessModeConfiguration] = useState(() => value);
  const accessModeConfigurationType = value.access_mode_configuration_type;

  return (
    <AccessModeLayout
      onConfirm={() => onConfirm(accessModeConfiguration)}
      hide={hide}
    >
      <NewAccessModeSettingsForm
        schema={AccessModePluginConfigurationForm[accessModeConfigurationType].schema}
        flow={AccessModePluginConfigurationForm[accessModeConfigurationType].flow}
        value={accessModeConfiguration}
        onChange={setAccessModeConfiguration}
      />
    </AccessModeLayout>
  );
}

function PlanForm({ plan, onChange }) {
  const [openAccessModeModal, setAccessModeModal] = useState(false);

  const schema = useMemo(() => ({
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
            "This is the initial phase of a plan, where it exists in draft mode. You can configure the plan, but it won't be visible or accessible to users",
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
        <AccessModeConfigurationTypeSelector
          onChange={props.onChange}
          value={props.value}
          onEdit={() => setAccessModeModal(true)} />
      ),
    },
    rateLimiting: {
      type: 'form',
      label: 'Rate Limiting & Quotas',
      schema: {
        strategy: {
          renderer: props => {
            return <ThrottlingStrategy value={props.value} onChange={props.onChange} />
          }
        },
        perIp: {
          type: 'bool',
          label: 'Per IP',
        },
        customPattern: {
          type: 'string',
          label: 'Custom group via expression (header, JWT claim)',
        },
      },
      flow: ['strategy', 'perIp', 'customPattern'],
    },
    pricing: {
      type: 'form',
      label: 'Pricing',
      flow: ['enabled', 'name', 'price', 'currency', 'params'],
      schema: {
        enabled: {
          type: 'bool',
          label: 'Enabled',
        },
        name: {
          visible: (props) => props?.enabled,
          type: 'string',
          label: 'Name',
        },
        price: {
          visible: (props) => props?.enabled,
          type: 'number',
          label: 'Price',
        },
        currency: {
          visible: (props) => props?.enabled,
          type: 'select',
          label: 'Currency',
          props: {
            options: CURRENCIES.map(({ code, name, symbol }) => ({
              value: code,
              label: `${name} - ${symbol}`,
            })),
          },
        },
        params: {
          visible: (props) => props?.enabled,
          type: 'code',
          label: 'Extra parameters',
          props: {
            ngOptions: {
              spread: true,
            },
          },
        },
      },
    },
    tags: { type: 'array', label: 'Tags' },
    metadata: { type: 'object', label: 'Metadata' },
    validation: {
      type: 'form',
      label: 'Plan validation',
      schema: {
        kind: {
          type: 'dots',
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
          },
        },
        config: {
          type: 'json',
          label: 'Validation config.',
          props: {
            defaultValue: '{}',
            height: 100,
          },
        },
      },
      flow: ['kind', 'config'],
    },
    visibility: {
      label: 'Visibility',
      type: 'form',
      schema: {
        kind: {
          label: 'Kind',
          type: 'dots',
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
          type: 'any',
          label: 'Visibility config.',
          props: {
            mode: 'jsonOrPlaintext',
            language: 'json',
            useInternalState: true,
            defaultValue: '{}',
            height: 100,
          },
        },
      },
      flow: ['kind', 'config'],
    },
  }), [setAccessModeModal]);

  const flow = useMemo(() => [
    { type: 'group', name: 'General', collapsable: false, fields: ['name', 'description'] },
    {
      type: 'group',
      name: 'Lifecycle',
      collapsable: false,
      fields: ['status', 'statusDescription'],
    },
    'visibility',
    {
      type: 'group',
      name: 'Access Mode',
      collapsable: false,
      fields: ['access_mode_configuration_type'],
    },
    'rateLimiting',
    'pricing',
    'validation',
    { type: 'group', name: 'Metadata', collapsed: true, fields: ['tags', 'metadata'] },
  ], []);

  return (
    <>
      {openAccessModeModal && (
        <AccessModeConfiguration
          value={plan.access_mode_configuration}
          onConfirm={(data) => {
            onChange({
              ...plan,
              access_mode_configuration: data,
            });
            setAccessModeModal(undefined);
          }}
          hide={() => setAccessModeModal(undefined)}
        />
      )}
      <NgForm
        value={plan}
        schema={schema}
        flow={flow}
        onChange={onChange} />
    </>
  );
}

function ImportPlanModal({ hide, draft, api, updateAPI }) {
  const [planId, setPlan] = useState();

  const importPlan = () => {
    const plan = draft.plans?.find((p) => p.id === planId);

    return updateAPI({
      ...api,
      plans: [
        ...(api.plans || []),
        {
          ...plan,
          enabled: false,
          id: `prod-${plan.id}`,
        },
      ],
    }).then(() => window.location.reload());
  };

  return (
    <div className="wizard">
      <div className="wizard-container" style={{ padding: '1.5rem' }}>
        <div className="d-flex" style={{ flexDirection: 'column', padding: '2.5rem', flex: 1 }}>
          <Header title="Import a plan from draft" onClose={hide} />
          <div className="wizard-content">
            <NgSelectRenderer
              value={planId}
              onChange={setPlan}
              ngOptions={{ spread: true }}
              options={draft.plans || []}
              optionsTransformer={(arr) =>
                arr.map((item) => ({
                  value: item.id,
                  label: item.name,
                }))
              }
            />
          </div>
        </div>
        <div className="d-flex mt-auto ms-auto justify-content-between align-items-center">
          <FeedbackButton
            style={{
              backgroundColor: 'var(--color-primary)',
              borderColor: 'var(--color-primary)',
              padding: '12px 48px',
            }}
            disabled={!planId}
            onPress={importPlan}
            onSuccess={hide}
            icon={() => <i className="fas fa-paper-plane" />}
            text="Copy to prod, then edit"
          />
        </div>
      </div>
    </div>
  );
}

export function Plans(props) {
  const params = useParams();
  const history = useHistory();
  const [plan, setPlan] = useState();
  const { item, version, isDraft, draft, updateAPI, api, updateItem } = useDraftOfAPI();

  const [showImportPlanModal, setImportPlanModal] = useState(false);

  useEffect(() => {
    props.setTitle({
      value: 'Plans',
      noThumbtack: true,
      children: <VersionBadge />,
    });
  }, []);

  if (!item) return <SimpleLoader />;

  const plans = item.plans || []

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
        return plan.access_mode_configuration_type ? (
          <span className="badge custom-badge api-status-started">
            {ACCESS_MODE_LABELS[plan.access_mode_configuration_type] ||
              plan.access_mode_configuration_type}
          </span>
        ) : (
          <span>—</span>
        );
      },
    },
    {
      title: 'Status',
      notFilterable: true,
      cell: (_, plan) => {
        return STATUS_BADGES[plan.status] ? (
          <span className={`badge custom-badge ${STATUS_BADGES[plan.status]?.cls}`}>
            {STATUS_BADGES[plan.status]?.label || plan.status}
          </span>
        ) : null;
      },
    },
    {
      title: 'Subscribe',
      notFilterable: true,
      cell: (_, plan) => (
        <Button type="success" className="btn-sm" onClick={() => setPlan(plan)}>
          Subscribe
        </Button>
      ),
    },
  ];

  const deleteItem = (plan) => {
    return updateItem({
      ...item,
      plans: item.plans?.filter((c) => c.id !== plan.id),
    })
  };

  if (plan) return <NewSubscription plan={plan} {...props} />;

  return (
    <>
      {showImportPlanModal && !isDraft && (
        <ImportPlanModal
          draft={draft}
          api={api}
          updateAPI={updateAPI}
          hide={() => setImportPlanModal(false)}
        />
      )}

      <Table
        parentProps={{ params }}
        navigateTo={(plan) =>
          history.push(`/apis/${params.apiId}/plans/${plan.id}/edit?version=${version}`)
        }
        navigateOnEdit={(plan) =>
          history.push(`/apis/${params.apiId}/plans/${plan.id}/edit?version=${version}`)
        }
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
        injectTopBar={() => (
          <div className="d-flex">
            <div className="btn-group input-group-btn mx-1">
              <Link className="btn btn-success btn-sm" to={`plans/new?version=${version}`}>
                <i className="fas fa-plus-circle" /> Create new plan
              </Link>
            </div>
            {!isDraft && (
              <div className="btn-group input-group-btn">
                <button
                  type="button"
                  className="btn btn-primary btn-sm"
                  onClick={() => setImportPlanModal(true)}
                >
                  <i className="fas fa-plus-circle" /> Importer un plan
                </button>
              </div>
            )}
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
  const [plan, setPlan] = useState(
    isNew
      ? {
        id: v4(),
        name: 'New plan',
        status: 'staging',
        access_mode_configuration_type: 'apikey',
        access_mode_configuration: {
          enabled: true,
        },
      }
      : null
  );

  useEffect(() => {
    if (!isNew && item && !plan) {
      const found = item.plans?.find((p) => p.id === params.planId);
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
      ? [...(item.plans || []), plan]
      : item.plans?.map((p) => (p.id === plan.id ? plan : p));
    return updateItem({ ...item, plans })
      .then(back);
  };

  return <div className='page'>
    <PageTitle title={isNew ? 'New Plan' : plan.name} {...props} />
    <div className='displayGroupBtn'>
      <FeedbackButton
        type="success"
        onPress={save}
        text={
          <div className="d-flex align-items-center">
            {isNew ? 'Create' : 'Save'} <VersionBadge size="xs" />
          </div>
        }
      />
    </div>
    <PlanForm plan={plan} onChange={setPlan} />
  </div>
}
