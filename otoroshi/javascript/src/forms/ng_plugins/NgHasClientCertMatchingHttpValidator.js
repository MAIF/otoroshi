export default {
  id: 'cp:otoroshi.next.plugins.NgHasClientCertMatchingHttpValidator',
  config_schema: {
    url: {
      type: 'string',
      label: 'URL',
    },
    method: {
      type: 'string',
      label: 'Http Method',
    },
    timeout: {
      type: 'number',
      label: 'Call timeout',
    },
    headers: {
      type: 'object',
      label: 'Http headers',
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
    }
  },
  config_flow: [
    'url',
    'method',
    'timeout',
    'headers',
    'tls',
  ],
};