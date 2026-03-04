import React, { useEffect, useState } from 'react';
import SimpleLoader from './SimpleLoader';
import PageTitle from '../../components/PageTitle';
import { FeedbackButton } from '../RouteDesigner/FeedbackButton';
import MonacoEditor from '@monaco-editor/react';
import { Form } from '../../components/inputs/Form';

import { DraftOnly, useDraftOfAPI, VersionBadge } from './index';
import { PillButton } from '../../components/PillButton';
import { NgForm } from '../../components/nginputs';
import { Button } from '../../components/Button';
import { Row } from '../../components/Row';
import { ArrayInput } from '../../components/inputs';
import { RestrictionPath } from '../../components/Restrictions';
import { JsonObjectAsCodeInput } from '../../components/inputs/CodeInput';
import JwtVerificationOnly from '../../forms/ng_plugins/JwtVerificationOnly';
import NgClientCredentialTokenEndpoint from '../../forms/ng_plugins/NgClientCredentialTokenEndpoint';
import NgHasClientCertMatchingValidator from '../../forms/ng_plugins/NgHasClientCertMatchingValidator';

function ApiDocumentationResource(props) {
  const flow = [
    'path',
    'title',
    'description',
    'content_type',
    'text_content',
    'css_icon_class',
    'json_content',
    'base64_content',
    'site_page',
    'transform',
    'transform_wrapper',
    'url',
    'http_headers',
    'http_timeout',
    'http_follow_redirects',
  ];
  const schema = {
    path: { type: 'string', props: { label: 'Path' } },
    title: { type: 'string', props: { label: 'Title' } },
    description: { type: 'string', props: { label: 'Description' } },
    content_type: { type: 'string', props: { label: 'Content-Type' } },
    text_content: { type: 'text', props: { label: 'Text Content' } },
    css_icon_class: { type: 'string', props: { label: 'CSS Icon class' } },
    json_content: { type: 'text', props: { label: 'Json Content' } },
    base64_content: { type: 'text', props: { label: 'Base64 Content' } },
    site_page: { type: 'bool', props: { label: 'Site page' } },
    transform: { type: 'string', props: { label: 'Transform' } },
    transform_wrapper: { type: 'string', props: { label: 'Transform wrapper' } },
    url: { type: 'string', props: { label: 'URL' } },
    http_headers: { type: 'object', props: { label: 'Http Headers' } },
    http_timeout: { type: 'number', props: { label: 'Http timeout', suffix: 'milliseconds' } },
    http_follow_redirects: { type: 'bool', props: { label: 'Http follow redirects' } },
  };
  return (
    <div className="col-sm-12">
      <Form
        flow={flow}
        schema={schema}
        value={props.itemValue || props.value}
        onChange={
          props.itemValue
            ? (v) => {
              props.value[props.idx] = v;
              props.onChange(props.value);
            }
            : props.onChange
        }
      />
    </div>
  );
}

function AccessModeConfigurationTypeSelector({ onChange, value }) {
  return (
    <Row title="Access mode">
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
            text: 'Mutual TLS authentication requiring the client to present a valid client certificate. Both parties verify each other\'s identity, ensuring a strong level of trust and encryption between the client and the gateway.',
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
          }
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

export function ApiDocumentationPlans(props) {

  const { item, updateItem } = useDraftOfAPI()

  const [documentation, setDocumentation] = useState()
  const [accessMode, setAccessMode] = useState()

  useEffect(() => {
    props.setTitle(undefined)
    if (item) {
      setDocumentation(item.documentation)
    }
  }, [item])

  if (!item)
    return null

  const schema = {
    plans: {
      type: 'form',
      array: true,
      label: 'Plans',
      flow: [
        'id',
        'name',
        'description',
        'status',
        'statusDescription',
        'access_mode_configuration_type',
        'access_mode_configuration',
        'tags',
        'metadata',
      ],
      schema: {
        id: {
          type: 'string',
          label: 'Identifier'
        },
        name: {
          type: 'string',
          label: 'Name'
        },
        description: {
          type: 'string',
          label: 'Description'
        },
        access_mode_configuration_type: {
          renderer: (props) => {
            return <AccessModeConfigurationTypeSelector onChange={props.onChange} value={props.value} />;
          }
        },
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
                'This is the initial phase of a plan, where it exists in draft mode. You can configure the plan, but it won’t be visible or accessible to users',
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
        access_mode_configuration: {
          renderer: ({ rootValue, onChange, value }) => {
            if (!rootValue.access_mode_configuration_type)
              return null

            return <Row title="Access mode configuration">
              <Button type='primaryColor' onClick={() => {
                setAccessMode({
                  access_mode_configuration_type: rootValue.access_mode_configuration_type,
                  ...value || {}
                })
              }}>
                Editer {value?.name}
              </Button>
            </Row>
          }
        },
        tags: {
          type: 'array',
          label: 'Tags'
        },
        metadata: {
          type: 'object',
          label: 'Metadata'
        },
      }
    }
  }

  const updatePlans = () => {
    return updateItem({
      ...item,
      documentation
    })
  }

  return <>
    <PageTitle title="Plans" {...props} style={{ paddingBottom: 0 }}>
      <DraftOnly>
        <FeedbackButton
          type="success"
          className="ms-2 mb-1 d-flex align-items-center"
          onPress={updatePlans}
          text="Save"
        />
      </DraftOnly>
    </PageTitle>

    {accessMode && <AccessModeConfiguration
      value={accessMode}
      item={item}
      hide={() => setAccessMode(undefined)}
      props={props} />}

    <NgForm
      flow={['plans']}
      schema={schema}
      value={documentation}
      onChange={setDocumentation}
    />
  </>
}

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
      label: 'ApiKey Id Pattern'
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
        optionsFrom: '/bo/api/groups-and-services'
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
        enabled: {
          type: 'boolean',
          label: "Enabled"
        },
        allowLast: {
          type: 'boolean',
          label: 'Allow last'
        },
        allowed: {
          renderer: props => <ArrayInput
            label="Allowed"
            value={props.value}
            help="Allowed paths"
            component={RestrictionPath}
            defaultValue={{ method: '*', path: '/.*' }}
            onChange={props.onChange}
          />
        },
        forbidden: {
          renderer: props => <ArrayInput
            label="Forbidden"
            value={props.value}
            help="Forbidden paths"
            component={RestrictionPath}
            defaultValue={{ method: '*', path: '/.*' }}
            onChange={props.onChange}
          />
        },
        notFound: {
          renderer: props => <ArrayInput
            label="Not Found"
            value={props.value}
            help="Not found paths"
            component={RestrictionPath}
            defaultValue={{ method: '*', path: '/.*' }}
            onChange={props.onChange}
          />
        }
      },
      flow: ['enabled', 'allowLast', 'allowed', 'notFound']
    },
    rotation: {
      type: 'form',
      label: 'Rotation',
      schema: {
        'enabled': {
          type: 'bool',
          label: 'Enabled',
          props: {
            help: 'Enabled automatic apikey secret rotation',
          },
        },
        'rotationEvery': {
          type: 'number',
          label: 'Rotation every',
          props: {
            placeholder: 'rotate secrets every',
            suffix: 'hours',
            help: 'rotate secrets every',
          },
        },
        'gracePeriod': {
          type: 'number',
          label: 'Grace period',
          props: {
            placeholder: 'period when both secrets can be used',
            suffix: 'hours',
            help: 'period when both secrets can be used',
          },
        },
        'nextSecret': {
          type: 'string',
          label: 'Next client secret',
          props: {
            disabled: true,
          },
        },
      },
      flow: ['enabled', 'rotationEvery', 'gracePeriod', 'nextSecret']
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
    }
  }
}

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
        props: {
          description: 'Remove the apikey fromcall made to downstream service',
        },
      },
      update_quotas: {
        label: 'Update quotas',
        type: 'box-bool',
        props: {
          description: 'Each call with an apikey will update its quota',
        },
      },
      pass_with_user: {
        label: 'Pass with user',
        type: 'box-bool',
        props: {
          description: 'Allow the path to be accessed via an Authentication module',
        },
      },
      mandatory: {
        label: 'Mandatory',
        type: 'box-bool',
        props: {
          description:
            'Allow an apikey and and authentication module to be used on a same path. If disabled, the endpoint can be called without apikey.',
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

function AccessModeConfiguration({ value, item, hide, props }) {

  const [accessModeConfiguration, setAccessModeConfiguration] = useState(() => value)
  const [pluginConfiguration, setPluginConfiguration] = useState(() => value?.pluginConfiguration)

  return <div className="wizard">
    <div className="wizard-container">
      <div
        style={{
          flex: 1,
          display: 'flex',
          flexDirection: 'column',
          padding: '2.5rem',
        }}
      >
        <label style={{ fontSize: '1.15rem', marginBottom: '2rem' }}>
          <i
            className="fas fa-times me-3"
            onClick={hide}
            style={{ cursor: 'pointer' }}
          />
          <span>Edit {value.access_mode_configuration_type}</span>
        </label>

        {value.access_mode_configuration_type === 'apikey' && <>
          <NgForm
            value={accessModeConfiguration}
            schema={{
              ...ApiKeysConstants.schema,
              pluginConfiguration: {
                renderer: () => {
                  return <>
                    {
                      AccessModePluginConfigurationForm[value.access_mode_configuration_type] ?
                        <NewAccessModeSettingsForm
                          schema={AccessModePluginConfigurationForm[value.access_mode_configuration_type].schema}
                          flow={AccessModePluginConfigurationForm[value.access_mode_configuration_type].flow}
                          value={pluginConfiguration}
                          onChange={setPluginConfiguration}
                        /> :
                        <JsonObjectAsCodeInput
                          label="Additional informations"
                          onChange={setPluginConfiguration}
                          value={pluginConfiguration}
                        />
                    }
                  </>
                }
              }
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
              {
                type: 'group',
                name: 'Miscellaneous',
                fields: ['metadata', 'tags']
              },
              {
                type: 'group',
                name: 'Avancé',
                fields: ['pluginConfiguration']
              }
            ]}
            onChange={setAccessModeConfiguration}
          />

        </>}
      </div>
    </div>
  </div>
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

function ApiDocumentationResourceRef(props) {
  const flow = ['title', 'description', 'link', 'icon'];
  const schema = {
    title: {
      type: 'string',
      props: { label: 'Title' },
    },
    description: {
      type: 'string',
      props: { label: 'Description' },
    },
    link: {
      type: 'string',
      props: { label: 'Link' },
    },
    icon: {
      type: ApiDocumentationResource,
      props: { label: 'Icon' },
    },
  };
  return (
    <div className="col-sm-10">
      <Form
        flow={flow}
        schema={schema}
        value={props.itemValue || props.value}
        onChange={
          props.itemValue
            ? (v) => {
              props.value[props.idx] = v;
              props.onChange(props.value);
            }
            : props.onChange
        }
      />
    </div>
  );
}

function ApiDocumentationRedirection(props) {
  const flow = ['from', 'to'];
  const schema = {
    from: {
      type: 'string',
      props: { label: 'From' },
    },
    to: {
      type: 'string',
      props: { label: 'To' },
    },
  };
  return (
    <div className="col-sm-12">
      <Form
        flow={flow}
        schema={schema}
        value={props.itemValue || props.value}
        onChange={
          props.itemValue
            ? (v) => {
              props.value[props.idx] = v;
              props.onChange(props.value);
            }
            : props.onChange
        }
      />
    </div>
  );
}

function ApiDocumentationSidebarItem(props) {
  const flow = ['kind', 'icon', 'label', 'link', 'links'];
  const schema = {
    kind: {
      type: 'select',
      props: {
        label: 'Kind',
        possibleValues: [
          { label: 'Category', value: 'category' },
          { label: 'Link', value: 'link' },
        ],
      },
    },
    link: { type: 'string', props: { label: 'Link' } },
    links: { type: 'array', props: { label: 'Links', component: ApiDocumentationSidebarItem } },
    label: { type: 'string', props: { label: 'Label' } },
    icon: { type: ApiDocumentationResource, props: { label: 'Icon' } },
  };
  return (
    <div className="col-sm-12">
      <Form
        flow={flow}
        schema={schema}
        value={props.itemValue || props.value}
        onChange={
          props.itemValue
            ? (v) => {
              props.value[props.idx] = v;
              props.onChange(props.value);
            }
            : props.onChange
        }
      />
    </div>
  );
}

function ApiDocumentationSidebar(props) {
  const flow = ['label', 'path', 'items', 'icon'];
  const schema = {
    label: { type: 'string', props: { label: 'Label' } },
    path: { type: 'string', props: { label: 'Path' } },
    items: { type: 'array', props: { label: 'Items', component: ApiDocumentationSidebarItem } },
    icon: { type: ApiDocumentationResource, props: { label: 'Icon' } },
  };
  return (
    <div className="col-sm-12">
      <Form
        flow={flow}
        schema={schema}
        value={props.itemValue || props.value}
        onChange={
          props.itemValue
            ? (v) => {
              props.value[props.idx] = v;
              props.onChange(props.value);
            }
            : props.onChange
        }
      />
    </div>
  );
}

export function Documentation(props) {
  const [showJson, setShowJson] = useState(false);
  const { item, updateItem, isDraft } = useDraftOfAPI();
  const [code, setCode] = useState('');
  const [newItem, setNewItem] = useState(null);

  useEffect(() => {
    props.setTitle(undefined);
  }, []);

  useEffect(() => {
    if (item && code === '') {
      setCode(JSON.stringify(item.documentation || {}, null, 2));
      setNewItem(item.documentation);
    }
  }, [item]);

  const updateDoc = () => {
    return updateItem({ ...item, documentation: newItem });
  };

  const flow = [
    'enabled',
    'metadata',
    'tags',
    '>>>Remote',
    'source.url',
    'source.headers',
    'source.timeout',
    'source.follow_redirects',
    '>>>Home',
    'home',
    '>>>Logo',
    'logo',
    '>>>Navigation',
    'navigation',
    '>>>Footer',
    'footer',
    '>>>Banner',
    'banner',
    '>>>Redirections',
    'redirections',
    '>>>References',
    'references',
    '>>>Search',
    'search.enabled',
    '>>>Resources',
    'resources',
  ];
  const schema = {
    enabled: {
      type: 'bool',
      props: { label: 'Enabled' },
    },
    metadata: {
      type: 'object',
      props: { label: 'Metadata' },
    },
    tags: {
      type: 'array',
      props: { label: 'Tags' },
    },
    'source.url': {
      type: 'string',
      props: { label: 'URL' },
    },
    'source.headers': {
      type: 'object',
      props: { label: 'Headers' },
    },
    'source.follow_redirects': {
      type: 'bool',
      props: { label: 'Follow Redirects' },
    },
    'source.timeout': {
      type: 'number',
      props: { label: 'Timeout', suffix: 'milliseconds' },
    },
    home: {
      type: ApiDocumentationResource,
      props: { label: 'Home' },
    },
    logo: {
      type: ApiDocumentationResource,
      props: { label: 'Logo' },
    },
    references: {
      type: 'array',
      props: { component: ApiDocumentationResourceRef },
    },
    resources: {
      type: 'array',
      props: { component: ApiDocumentationResource },
    },
    navigation: {
      type: 'array',
      props: { label: 'Navigation', component: ApiDocumentationSidebar },
    },
    redirections: {
      type: 'array',
      props: { component: ApiDocumentationRedirection },
    },
    footer: {
      type: ApiDocumentationResource,
      props: { label: 'Footer' },
    },
    'search.enabled': {
      type: 'bool',
      props: { label: 'Search' },
    },
    banner: {
      type: ApiDocumentationResource,
      props: { label: 'Banner' },
    }
  };

  if (!item) return <SimpleLoader />;
  return (
    <>
      <PageTitle title="Documentation" {...props}>
        <div className="btn-group" style={{ marginRight: 10 }}>
          <PillButton
            className="mx-auto"
            rightEnabled={!showJson}
            leftText="Form"
            rightText="Json editor"
            onChange={() => {
              setShowJson(!showJson);
              if (!showJson) {
                setCode(JSON.stringify(newItem || {}, null, 2));
              }
            }}
          />
        </div>

        {isDraft ? <FeedbackButton
          type="success"
          className="d-flex ms-auto"
          onPress={updateDoc}
          text={
            <div className="d-flex align-items-center">
              Update <VersionBadge size="xs" />
            </div>
          }
        /> : null}
      </PageTitle>
      {showJson && (
        <MonacoEditor
          height={window.innerHeight - 140}
          width="100%"
          theme="vs-dark"
          defaultLanguage="json"
          value={code}
          options={{
            automaticLayout: true,
            selectOnLineNumbers: true,
            minimap: { enabled: true },
            lineNumbers: true,
            glyphMargin: false,
            folding: true,
            lineDecorationsWidth: 0,
            lineNumbersMinChars: 0,
          }}
          onChange={(newValue) => {
            try {
              setNewItem(JSON.parse(newValue));
            } catch (e) { }
          }}
        />
      )}
      {!showJson && (
        <Form flow={flow} schema={schema} value={newItem || {}} onChange={(e) => setNewItem(e)} />
      )}
    </>
  );
}
