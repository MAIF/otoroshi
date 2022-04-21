import React from 'react';
import { type, format, constraints, SingleLineCode } from '@maif/react-forms';

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

const PROTOCOLS = {
  http: 'http',
  https: 'https',
  udp: 'udp',
  tcp: 'tcp'
}

export const DEFAULT_FLOW = {
  Frontend: {
    id: 'Frontend',
    icon: 'user',
    plugin_steps: ['PreRoute'],
    description: 'Exposition',
    default: true,
    field: 'frontend',
    onInputStream: true,
    config_schema: {
      domains: {
        type: type.string,
        props: {
          mode: 'json',
        },
        array: true,
        format: 'singleLineCode',
        label: 'Domains',
      },
    },
    config_flow: ['domains', 'stripPath', 'exact', 'headers', 'methods', 'query'],
  },
  Backend: {
    id: 'Backend',
    icon: 'bullseye',
    group: 'Targets',
    default: true,
    onTargetStream: true,
    field: 'backend',
    config_schema: (generatedSchema) => ({
      ...generatedSchema,
      targets: {
        ...generatedSchema.targets,
        onAfterChange: ({ setValue, entry, previousValue, value, getValue }) => {
          if (value && previousValue) {
            const target = value.custom_target

            if (target && previousValue.custom_target && target !== previousValue.custom_target) {
              const parts = target.split('://')

              if (parts.length > 1) {
                const afterScheme = parts[1];
                const afterSchemeParts = afterScheme.split('/');

                const hostname = afterSchemeParts.shift();
                setValue(`${entry}.hostname`, hostname)

                const pathname = '/' + afterSchemeParts.join('/');
                // if (getValue('plugin.root').length < 2)
                setValue('plugin.root', pathname);
              }
            }
            else if (value.hostname !== previousValue.hostname) {
              const parts = (target || '').split('://')
              const scheme = parts.length > 1 ? `${parts[0]}://` : ''
              const hostname = value.hostname || ''
              setValue(`${entry}.custom_target`, `${scheme}${hostname}`)
            }
          }
          else {
            const port = getValue(`${entry}.port`)
            const hostname = getValue(`${entry}.hostname`)
            const root = getValue('plugin.root')
            if (port && hostname && root) {
              console.log(`http${port === 443 ? 's' : ''}://${hostname}${root}`)
              setValue(`${entry}.custom_target`, `http${port === 443 ? 's' : ''}://${hostname}${root}`)
            }
          }
        },
        schema: {
          custom_target: {
            label: 'Target',
            type: 'string',
            constraints: [
              { type: 'required' }
            ]
          },
          expert_mode: {
            type: 'bool',
            label: null,
            defaultValue: false,
            render: ({ value, onChange }) => {
              return <button
                type="button"
                className="btn btn-sm btn-success me-3 mb-3"
                onClick={() => onChange(!value)
                }>
                {!!value ? 'Show less' : 'Show more'}
              </button>
            }
          },

          ...Object.fromEntries(
            Object.entries(generatedSchema.targets.schema).map(([key, value]) => {
              return [
                key,
                {
                  ...value,
                  visible: {
                    ref: 'plugin',
                    test: (v, idx) => !!v.targets[idx]?.value?.expert_mode
                  },
                },
              ];
            })
          ),
          hostname: {
            ...generatedSchema.targets.schema.hostname,
            visible: {
              ref: 'plugin',
              test: (v, idx) => !!v.targets[idx]?.value?.expert_mode,
            },
            constraints: [
              {
                type: 'blacklist',
                arrayOfValues: ['http:', 'https:', 'tcp:', 'udp:', '/'],
                message: 'You cannot use protocol scheme or / in the Host name'
              }
            ]
          }
        },
        flow: ['custom_target', 'expert_mode', ...generatedSchema.targets.flow]
      },
    }),
    config_flow: [
      'root',
      'targets',
      'healthCheck',
      'targetRefs',
      'client',
      'rewrite',
      'loadBalancing',
    ],
  },
};
