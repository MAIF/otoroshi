export default {
  id: 'cp:otoroshi.next.plugins.TimeControlledCanaryMode',
  config_schema: {
    root: {
      label: 'root',
      type: 'string',
    },
    targets: {
      label: 'targets',
      type: 'array',
      array: true,
      format: 'form',
      schema: {
        predicate: {
          label: 'predicate',
          type: 'form',
          collapsable: true,
          collapsed: true,
          schema: {
            rack: {
              label: 'rack',
              type: 'string',
            },
            provider: {
              label: 'provider',
              type: 'string',
            },
            dataCenter: {
              label: 'dataCenter',
              type: 'string',
            },
            zone: {
              label: 'zone',
              type: 'string',
            },
            positions: {
              label: 'positions',
              type: 'array',
              array: true,
              format: 'form',
              schema: {
                latitude: {
                  label: 'latitude',
                  type: 'number',
                },
                longitude: {
                  label: 'longitude',
                  type: 'number',
                },
                radius: {
                  label: 'radius',
                  type: 'number',
                },
              },
              flow: ['latitude', 'longitude', 'radius'],
            },
            type: {
              type: 'select',
              props: {
                label: 'type',
                options: ['AlwaysMatch', 'NetworkLocationMatch', 'GeolocationMatch'],
              },
            },
            region: {
              label: 'region',
              type: 'string',
            },
            dc: {
              label: 'dc',
              type: 'string',
            },
          },
          flow: ['rack', 'provider', 'dataCenter', 'zone', 'positions', 'type', 'region', 'dc'],
        },
        hostname: {
          label: 'hostname',
          type: 'string',
        },
        protocol: {
          label: 'protocol',
          type: 'form',
          collapsable: true,
          collapsed: true,
          schema: {
            value: {
              label: 'value',
              type: 'string',
            },
          },
          flow: ['value'],
        },
        port: {
          label: 'port',
          type: 'number',
        },
        weight: {
          label: 'weight',
          type: 'number',
        },
        tls: {
          label: 'tls',
          type: 'bool',
        },
        tls_config: {
          label: 'tls_config',
          type: 'form',
          collapsable: true,
          collapsed: true,
          schema: {
            enabled: {
              label: 'enabled',
              type: 'bool',
            },
            certs: {
              label: 'certs',
              type: 'array',
              array: true,
              format: null,
            },
            loose: {
              label: 'loose',
              type: 'bool',
            },
            trust_all: {
              label: 'trust_all',
              type: 'bool',
            },
            trusted_certs: {
              label: 'trusted_certs',
              type: 'array',
              array: true,
              format: null,
            },
          },
          flow: ['enabled', 'certs', 'loose', 'trust_all', 'trusted_certs'],
        },
        id: {
          label: 'id',
          type: 'string',
        },
        ip_address: {
          label: 'ip_address',
          type: 'string',
        },
      },
      flow: [
        'predicate',
        'hostname',
        'protocol',
        'port',
        'weight',
        'tls',
        'tls_config',
        'id',
        'ip_address',
      ],
    },
    start: {
      label: 'Start date',
      type: 'date',
      props: { type: 'datetime' },
    },
    stop: {
      label: 'Stop date',
      type: 'date',
      props: { type: 'datetime' },
    },
    increment_percent: {
      label: 'Step',
      type: 'number',
      props: {
        suffix: '%',
      },
    },
  },
  config_flow: ['start', 'stop', 'increment_percent', 'root', 'targets'],
};
