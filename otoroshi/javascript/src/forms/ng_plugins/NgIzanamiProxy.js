export default {
  id: 'cp:otoroshi.next.plugins.NgIzanamiV1Proxy',
  config_schema: {
    path: {
      type: 'string',
      label: 'Izanami path',
    },
    feature_pattern: {
      type: 'string',
      label: 'Izanami feature pattern',
    },
    config_pattern: {
      type: 'string',
      label: 'Izanami config pattern',
    },
    auto_context: {
      type: 'bool',
      label: 'Auto. context',
    },
    features_enabled: {
      type: 'bool',
      label: 'Features',
    },
    features_with_context_enabled: {
      type: 'bool',
      label: 'Features with context',
    },
    configuration_enabled: {
      type: 'bool',
      label: 'Configuration',
    },
    izanami_url: {
      type: 'string',
      label: 'Izanammi url',
    },
    client_id: {
      type: 'string',
      label: 'Izanami client id',
    },
    client_secret: {
      type: 'string',
      label: 'Izanami client secret',
    },
    timeout: {
      type: 'string',
      label: 'Izanami call timeout',
    },
    tls: {
      label: 'Custom TLS setup',
      type: 'form',
      collapsable: true,
      collapsed: true,
      label: 'Custom TLS setup',
      schema: Object.entries({
        enabled: {
          label: 'enabled',
          type: 'box-bool',
          props: {
            description:
              'If enabled, Otoroshi will try to provide client certificate trusted by the target server, trust all servers, etc.',
          },
        },
        certs: {
          type: 'array-select',
          help: 'The certificate used when performing a mTLS call',
          props: {
            label: 'Certificates',
            optionsFrom: '/bo/api/proxy/api/certificates',
            optionsTransformer: {
              label: 'name',
              value: 'id',
            },
          },
        },
        trusted_certs: {
          type: 'array-select',
          help: 'The trusted certificate used when performing a mTLS call',
          props: {
            label: 'Trusted certificates',
            optionsFrom: '/bo/api/proxy/api/certificates',
            optionsTransformer: {
              label: 'name',
              value: 'id',
            },
          },
        },
        loose: {
          label: 'loose',
          type: 'box-bool',
          props: {
            description:
              'If enabled, Otoroshi will accept any certificate and disable hostname verification',
          },
        },
        trust_all: {
          label: 'trust_all',
          type: 'box-bool',
          props: {
            description: 'If enabled, Otoroshi will accept trust all certificates',
          },
        },
      }).reduce((obj, entry) => {
        if (entry[0] === 'enabled')
          return {
            ...obj,
            [entry[0]]: entry[1],
          };
        else
          return {
            ...obj,
            [entry[0]]: {
              ...entry[1],
              visible: (value) => value?.enabled,
            },
          };
      }, {}),
      flow: ['enabled', 'loose', 'trust_all', 'certs', 'trusted_certs'],
    },
  },
  config_flow: [
    'path',
    'feature_pattern',
    'config_pattern',
    '---',
    'auto_context',
    'features_enabled',
    'features_with_context_enabled',
    'configuration_enabled',
    '---',
    'izanami_url',
    'client_id',
    'client_secret',
    'timeout',
    '---',
    'tls',
  ],
};