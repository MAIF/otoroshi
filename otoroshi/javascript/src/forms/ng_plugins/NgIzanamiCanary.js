export default {
  id: 'cp:otoroshi.next.plugins.NgIzanamiCanary',
  config_schema: {
    experiment_id: {
      type: 'string',
      label: 'Izanami experiment id',
    },
    config_id: {
      type: 'string',
      label: 'Izanami config. id',
    },
    izanami_url: {
      type: 'string',
      label: 'Izanami url',
    },
    tls_config: {
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
    route_config: {
      type: 'object',
      label: 'Route config.',
    },
  },
  config_flow: [
    'experiment_id', 
    'config_id', 
    '---',
    'izanami_url', 
    'client_id', 
    'client_secret', 
    'timeout', 
    '---',
    'tls', 
    '---',
    'route_config', 
  ],
};