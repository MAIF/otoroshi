import React from 'react';
import GraphQLForm from './GraphQLForm';
import MocksDesigner from './MocksDesigner';

export const PLUGIN_INFORMATIONS_SCHEMA = {
  enabled: {
    visibleOnCollapse: true,
    type: 'bool',
    label: 'Enabled',
  },
  debug: {
    type: 'bool',
    label: 'Debug',
  },
  include: {
    label: 'Include',
    format: 'singleLineCode',
    type: 'string',
    array: true,
    createOption: true,
  },
  exclude: {
    label: 'Exclude',
    format: 'singleLineCode',
    type: 'string',
    array: true,
    createOption: true,
  },
};

export const EXCLUDED_PLUGINS = {
  plugin_visibility: ['internal'],
  ids: ['otoroshi.next.proxy.ProxyEngine'],
};

export const LEGACY_PLUGINS_WRAPPER = {
  app: 'otoroshi.next.plugins.wrappers.RequestTransformerWrapper',
  transformer: 'otoroshi.next.plugins.wrappers.RequestTransformerWrapper',
  validator: 'otoroshi.next.plugins.wrappers.AccessValidatorWrapper',
  preroute: 'otoroshi.next.plugins.wrappers.PreRoutingWrapper',
  sink: 'otoroshi.next.plugins.wrappers.RequestSinkWrapper',
  composite: 'otoroshi.next.plugins.wrappers.CompositeWrapper',
  listener: '',
  job: '',
  exporter: '',
  'request-handler': '',
};

export const PLUGINS = {
  'cp:otoroshi.next.plugins.SOAPAction': (plugin) => ({
    ...plugin,
    schema: {
      ...plugin.schema,
      envelope: {
        type: 'code',
        props: {
          label: 'Envelope',
          editorOnly: true
        }
      },
    },
  }),
  'cp:otoroshi.next.plugins.SOAPActionConfig': (plugin) => ({
    ...plugin,
    schema: {
      ...plugin.schema,
      envelope: {
        type: 'code',
        props: {
          label: 'Envelope',
          editorOnly: true
        }
      },
    },
  }),
  'cp:otoroshi.next.plugins.GraphQLBackend': (plugin, showAdvancedDesignerView) => ({
    ...plugin,
    schema: {
      turn_view: {
        renderer: () => (
          <button
            type="button"
            className="btn btn-sm btn-info mb-3"
            onClick={() => showAdvancedDesignerView(GraphQLForm)}>
            Edit with the GraphQL Designer
          </button>
        ),
      },
      permissions: {
        type: 'string',
        array: true,
        label: 'Permissions paths',
      },
      ...plugin.schema,
    },
    flow:
      plugin.flow.indexOf('permissions') > -1
        ? ['turn_view', ...plugin.flow]
        : ['turn_view', ...plugin.flow, 'permissions'],
  }),
  'cp:otoroshi.next.plugins.MockResponses': (plugin, showAdvancedDesignerView) => ({
    ...plugin,
    schema: {
      turn_view: {
        renderer: () => (
          <button
            type="button"
            className="btn btn-sm btn-info mb-3"
            onClick={() => showAdvancedDesignerView(MocksDesigner)}>
            Edit with the GraphQL Designer
          </button>
        ),
      },
      form_data: {
        ...plugin.schema.form_data,
        visible: false,
      },
      ...plugin.schema,
    },
    flow: ['turn_view', ...plugin.flow],
  }),
};

export const DEFAULT_FLOW = {
  Frontend: {
    id: 'Frontend',
    icon: 'user',
    plugin_steps: [],
    description: null,
    field: 'frontend',
    config_schema: {
      strip_path: {
        type: 'bool',
        props: {
          label: 'Strip path',
          labelColumn: 6
        }
      },
      exact: {
        type: 'bool',
        props: {
          label: 'Exact',
          labelColumn: 6
        }
      },
      domains: {
        type: 'string',
        array: true,
        label: 'Domains',
      },
      methods: {
        type: 'array-select',
        props: {
          label: 'Methods',
          options: ['GET', 'HEAD', 'POST', 'PUT', 'DELETE', 'OPTIONS', 'PATCH']
            .map(item => ({ label: item, value: item }))
        }
      },
    },
    config_flow: [
      'domains',
      {
        type: 'grid',
        name: 'Flags',
        fields: [
          'strip_path',
          'exact'
        ]
      },
      'headers',
      'methods',
      'query'
    ]
  },
  Backend: {
    id: 'Backend',
    icon: 'bullseye',
    group: 'Targets',
    field: 'backend',
    config_schema: (generatedSchema) => ({
      ...generatedSchema,
      rewrite: {
        ...generatedSchema.rewrite,
        label: 'Full path rewrite'
      },
      targets: {
        array: true,
        format: "form",
        type: "object",
        props: {
          ngOptions: {
            spread: true
          }
        },
        schema: {
          ...generatedSchema.targets.schema,
          tls_config: {
            ...generatedSchema.targets.schema.tls_config,
            schema: Object.entries({
              ...generatedSchema.targets.schema.tls_config.schema,
              certs: {
                type: "array-select",
                props: {
                  label: "Certificates",
                  optionsFrom: "/bo/api/proxy/api/certificates",
                  optionsTransformer: {
                    label: "name",
                    value: "id"
                  }
                }
              },
              trusted_certs: {
                type: "array-select",
                props: {
                  label: "Trusted certificates",
                  optionsFrom: "/bo/api/proxy/api/certificates",
                  optionsTransformer: {
                    label: "name",
                    value: "id"
                  }
                }
              }
            }).reduce((obj, entry) => {
              if (entry[0] === 'enabled')
                return {
                  ...obj,
                  [entry[0]]: entry[1]
                }
              else
                return {
                  ...obj,
                  [entry[0]]: {
                    ...entry[1],
                    visible: value => value?.enabled
                  }
                }
            }, {})
          },
          predicate: {
            ...generatedSchema.targets.schema.predicate,
            flow: (value) => {
              const type = value?.type

              return {
                'GeolocationMatch': ['type', 'positions'],
                'NetworkLocationMatch': ['type', 'provider', 'region', 'zone', 'dataCenter', 'rack'],
                'AlwaysMatch': ['type'],
                [undefined]: ['type']
              }[type]
            }
          }
        },
        flow: [
          {
            type: 'group',
            collapsed: true,
            name: props => {
              const port = props.value?.port
              const hostname = props.value?.hostname || '';
              const isSecured = props.value?.tls

              return `${isSecured ? 'https' : 'http'}://${hostname}${port ? `:${port}` : ''}`
            },
            fields: [
              'hostname',
              'port',
              'weight',
              'protocol',
              'ip_address',
              'predicate',
              'tls_config'
            ]
          }
        ],
      },
      client: {
        ...generatedSchema.client,
        schema: {
          ...generatedSchema.client.schema,
          custom_timeouts: {
            ...generatedSchema.client.schema.custom_timeouts,
            flow: [
              {
                type: 'group',
                collapsed: true,
                name: props => `${props.path.slice(-1)}. ${generatedSchema.client.schema.custom_timeouts.label}`,
                fields: generatedSchema.client.schema.custom_timeouts.flow
              }
            ]
          }
        }
      }
    }),
    config_flow: [
      'root',
      'rewrite',
      {
        type: 'group',
        name: 'Targets',
        fields: ['targets']
      },
      'health_check',
      'client',
      'load_balancing',
    ],
  }
};
