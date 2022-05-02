import React from 'react';
import { type } from '@maif/react-forms';

export const PLUGIN_INFORMATIONS_SCHEMA = {
  enabled: {
    visibleOnCollapse: true,
    type: type.bool,
    label: 'Enabled',
  },
  debug: {
    type: type.bool,
    label: 'Debug',
  },
  include: {
    label: 'Include',
    format: 'singleLineCode',
    type: type.string,
    array: true,
    createOption: true,
  },
  exclude: {
    label: 'Exclude',
    format: 'singleLineCode',
    type: type.string,
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

export const DEFAULT_FLOW = {
  Frontend: {
    id: 'Frontend',
    icon: 'user',
    plugin_steps: [],
    description: null,
    field: 'frontend',
    config_schema: {
      domains: {
        type: type.string,
        array: true,
        label: 'Domains'
      },
      methods: {
        type: 'string',
        format: "select",
        isMulti: true,
        label: "methods",
        options: ["GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"],
      }
    },
    config_flow: ['domains', 'strip_path', 'exact', 'headers', 'methods', 'query'],
  },
  Backend: parentNode => ({
    id: 'Backend',
    icon: 'bullseye',
    group: 'Targets',
    field: 'backend',
    config_schema: (generatedSchema) => ({
      ...generatedSchema,
      targets: {
        ...generatedSchema.targets,
        onAfterChange: ({ setValue, entry, getValue }) => {
          let port = getValue(`${entry}.port`)
          port = port ? `:${port}` : ''
          const hostname = getValue(`${entry}.hostname`) || ''
          const isSecured = getValue(`${entry}.tls`)

          setValue(
            `${entry}.custom_target`,
            `${isSecured ? 'HTTPS' : 'HTTP'}@${hostname}@${port}`
          );
        },
        schema: {
          custom_target: {
            label: 'Target',
            type: 'string',
            disabled: true,
            render: ({ value }) => <div className='d-flex'>
              {value.split('@').map((v, i) => <span className="target_information" key={i}>{v}</span>)}
            </div>
          },
          expert_mode: {
            type: 'bool',
            label: null,
            defaultValue: false,
            render: ({ value, onChange }) => {
              return (
                <button
                  type="button"
                  className="btn btn-sm btn-success me-3 mb-3"
                  onClick={() => onChange(!value)}>
                  {!!value ? 'Show less' : 'Show more'}
                </button>
              );
            },
          },

          ...Object.fromEntries(
            Object.entries(generatedSchema.targets.schema).map(([key, value]) => {
              return [
                key,
                {
                  ...value,
                  visible: {
                    ref: parentNode,
                    test: (v, idx) => !!v.targets[idx]?.value?.expert_mode
                  },
                },
              ];
            })
          ),
          hostname: {
            ...generatedSchema.targets.schema.hostname,
            visible: {
              ref: parentNode,
              test: (v, idx) => !!v.targets[idx]?.value?.expert_mode,
            },
            constraints: [
              {
                type: 'blacklist',
                arrayOfValues: ['http:', 'https:', 'tcp:', 'udp:', '/'],
                message: 'You cannot use protocol scheme or / in the Host name',
              },
            ],
          },
        },
        flow: ['custom_target', 'expert_mode', ...generatedSchema.targets.flow],
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
  })
};
