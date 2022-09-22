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
  'cp:otoroshi.next.tunnel.TunnelPlugin': (plugin) => ({
    //...plugin,
    type: 'object',
    label: null,
    format: 'form',
    flow: ['tunnel_id'],
    schema: {
      //...(plugin || {}).schema,
      tunnel_id: {
        label: 'Tunnel ID',
        type: 'string',
      },
    },
  }),
  'cp:otoroshi.next.plugins.SOAPAction': (plugin) => ({
    ...plugin,
    schema: {
      ...plugin.schema,
      envelope: {
        label: 'envelope',
        type: 'string',
        format: 'code',
      },
    },
  }),
  'cp:otoroshi.next.plugins.SOAPActionConfig': (plugin) => ({
    ...plugin,
    schema: {
      ...plugin.schema,
      envelope: {
        label: 'envelope',
        type: 'string',
        format: 'code',
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
    config_flow: ['domains', 'strip_path', 'exact', 'headers', 'methods', 'query'],
  },
  Backend: (parentNode) => ({
    id: 'Backend',
    icon: 'bullseye',
    group: 'Targets',
    field: 'backend',
    config_schema: (generatedSchema) => ({
      ...generatedSchema,
      targets: {
        ...generatedSchema.targets,
        schema: {
          custom_target: {
            renderer: props => {
              const port = props.rootValue?.port
              const hostname = props.rootValue?.hostname || '';
              const isSecured = props.rootValue?.tls

              return (
                <div
                  className="d-flex-center justify-content-start target_information mb-1"
                  onClick={() => props.onChange(props.value === 'open' ? 'down' : 'open')}>
                  <i className={`me-2 fas fa-chevron-${props.value === 'open' ? 'down' : 'right'}`} />
                  <i className="fas fa-server me-2" />
                  <a>{`${isSecured ? 'https' : 'http'}://${hostname}${port ? `:${port}` : ''}`}</a>
                </div>
              );
            },
          },
          ...Object.fromEntries(
            Object.entries(generatedSchema.targets.schema).map(([key, value]) => [
              key,
              {
                ...value,
                visible: (value) => value?.custom_target === 'open'
              },
            ])
          ),
          hostname: {
            ...generatedSchema.targets.schema.hostname,
            visible: (value) => value?.custom_target === 'open',
            constraints: [
              {
                type: 'blacklist',
                arrayOfValues: ['http:', 'https:', 'tcp:', 'udp:', '/'],
                message: 'You cannot use protocol scheme or / in the Host name',
              },
            ],
          },
        },
        flow: ['custom_target', ...generatedSchema.targets.flow],
      },
    }),
    config_flow: [
      'root',
      'targets',
      'health_check',
      'target_refs',
      'client',
      'rewrite',
      'load_balancing',
    ],
  }),
};
