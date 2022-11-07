export default {
  id: 'Backend',
  icon: 'bullseye',
  group: 'Targets',
  field: 'backend',
  schema: {
    target_refs: {
      label: 'target_refs',
      type: 'array',
      array: true,
      format: null,
    },
    root: {
      label: 'root',
      type: 'string',
    },
    client: {
      label: 'client',
      type: 'form',
      collapsable: true,
      collapsed: true,
      schema: {
        custom_timeouts: {
          label: 'custom_timeouts',
          type: 'array',
          array: true,
          format: 'form',
          schema: {
            connection_timeout: {
              label: 'connection_timeout',
              type: 'number',
            },
            call_and_stream_timeout: {
              label: 'call_and_stream_timeout',
              type: 'number',
            },
            path: {
              label: 'path',
              type: 'string',
            },
            call_timeout: {
              label: 'call_timeout',
              type: 'number',
            },
            idle_timeout: {
              label: 'idle_timeout',
              type: 'number',
            },
            global_timeout: {
              label: 'global_timeout',
              type: 'number',
            },
          },
          flow: [
            {
              type: 'group',
              collapsed: true,
              name: (props) => {
                return `${props.path.slice(-1)}. Custom timeouts`;
              },
              fields: [
                'connection_timeout',
                'call_and_stream_timeout',
                'path',
                'call_timeout',
                'idle_timeout',
                'global_timeout',
              ],
            },
          ],
        },
        global_timeout: {
          label: 'global_timeout',
          type: 'number',
        },
        max_errors: {
          label: 'max_errors',
          type: 'number',
        },
        retry_initial_delay: {
          label: 'retry_initial_delay',
          type: 'number',
        },
        backoff_factor: {
          label: 'backoff_factor',
          type: 'number',
        },
        cache_connection_settings: {
          label: 'cache_connection_settings',
          type: 'form',
          collapsable: true,
          collapsed: true,
          schema: {
            queue_size: {
              label: 'queue_size',
              type: 'number',
            },
            enabled: {
              label: 'enabled',
              type: 'bool',
            },
          },
          flow: ['queue_size', 'enabled'],
        },
        sample_interval: {
          label: 'sample_interval',
          type: 'number',
        },
        call_and_stream_timeout: {
          label: 'call_and_stream_timeout',
          type: 'number',
        },
        retries: {
          label: 'retries',
          type: 'number',
        },
        proxy: {
          label: 'proxy',
          type: 'form',
          collapsable: true,
          collapsed: true,
          schema: {
            host: {
              label: 'host',
              type: 'string',
            },
            port: {
              label: 'port',
              type: 'string',
            },
            protocol: {
              type: 'dots',
              label: 'Protocol',
              props: {
                options: ['HTTP/1.0', 'HTTP/1.1', 'HTTP/2.0', 'HTTP/3.0'],
              },
            },
            principal: {
              label: 'principal',
              type: 'string',
            },
            password: {
              label: 'password',
              type: 'string',
            },
            ntlmDomain: {
              label: 'ntlmDomain',
              type: 'string',
            },
            encoding: {
              label: 'encoding',
              type: 'string',
            },
            nonProxyHosts: {
              label: 'nonProxyHosts',
              type: 'string',
            },
          },
          flow: [
            'host',
            'port',
            'protocol',
            'principal',
            'password',
            'ntlmDomain',
            'encoding',
            'nonProxyHosts',
          ],
        },
        call_timeout: {
          label: 'call_timeout',
          type: 'number',
        },
        idle_timeout: {
          label: 'idle_timeout',
          type: 'number',
        },
        connection_timeout: {
          label: 'connection_timeout',
          type: 'number',
        },
      },
      flow: [
        'custom_timeouts',
        'global_timeout',
        'max_errors',
        'retry_initial_delay',
        'backoff_factor',
        'sample_interval',
        'call_and_stream_timeout',
        'retries',
        'call_timeout',
        'idle_timeout',
        'connection_timeout',
        'cache_connection_settings',
        'proxy',
      ],
    },
    health_check: {
      label: 'health_check',
      type: 'form',
      collapsable: true,
      collapsed: true,
      schema: {
        enabled: {
          label: 'enabled',
          type: 'bool',
        },
        url: {
          label: 'url',
          type: 'string',
        },
      },
      flow: ['enabled', 'url'],
    },
    targets: {
      array: true,
      format: 'form',
      type: 'object',
      props: {
        ngOptions: {
          spread: true,
        },
      },
      schema: {
        predicate: {
          label: 'Predicate',
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
          flow: (value) => {
            const type = value?.type;

            return {
              GeolocationMatch: ['type', 'positions'],
              NetworkLocationMatch: ['type', 'provider', 'region', 'zone', 'dataCenter', 'rack'],
              AlwaysMatch: ['type'],
              [undefined]: ['type'],
            }[type];
          },
        },
        hostname: {
          label: 'hostname',
          type: 'string',
          props: {
            defaultValue: 'changeme.oto.tools',
          },
        },
        protocol: {
          type: 'dots',
          label: 'Protocol',
          props: {
            options: ['HTTP/1.0', 'HTTP/1.1', 'HTTP/2.0', 'HTTP/3.0'],
            defaultValue: 'HTTP/1.1',
          },
        },
        port: {
          label: 'port',
          type: 'number',
          props: {
            defaultValue: 80,
          },
        },
        weight: {
          label: 'weight',
          type: 'number',
          props: {
            defaultValue: 1,
          },
        },
        tls: {
          label: 'tls',
          type: 'bool',
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
              type: 'bool',
            },
            certs: {
              type: 'array-select',
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
              type: 'bool',
            },
            trust_all: {
              label: 'trust_all',
              type: 'bool',
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
          flow: ['enabled', 'certs', 'loose', 'trust_all', 'trusted_certs'],
        },
        ip_address: {
          label: 'ip_address',
          type: 'string',
        },
      },
      flow: [
        {
          type: 'group',
          collapsed: true,
          name: (props) => {
            const port = props.value?.port;
            const hostname = props.value?.hostname || '';
            const isSecured = props.value?.tls;

            return `${isSecured ? 'https' : 'http'}://${hostname}${port ? `:${port}` : ''}`;
          },
          full_fields: [
            'hostname',
            'port',
            'protocol',
            'weight',
            'ip_address',
            'tls',
            'predicate',
            'tls_config',
          ],
          fields: ['hostname', 'port', 'protocol'],
        },
      ],
    },
    rewrite: {
      label: 'Full path rewrite',
      type: 'bool',
    },
    load_balancing: {
      label: 'load_balancing',
      type: 'form',
      collapsable: true,
      collapsed: true,
      schema: {
        type: {
          type: 'select',
          props: {
            label: 'type',
            options: [
              'BestResponseTime',
              'IpAddressHash',
              'Random',
              'RoundRobin',
              'Sticky',
              'WeightedBestResponseTime',
            ],
          },
        },
        ratio: {
          label: 'ratio',
          type: 'number',
        },
      },
      flow: ['type', 'ratio'],
    },
  },
  flow: {
    otoroshi_full_flow: [
      'root',
      'rewrite',
      {
        type: 'group',
        name: 'Targets',
        fields: ['targets'],
        summaryFields: ['targets.hostname']
      },
      'health_check',
      'client',
      'load_balancing',
    ],
    otoroshi_flow: [
      'root',
      {
        type: 'group',
        name: 'Targets',
        fields: ['targets'],
        summaryFields: ['targets.hostname']
      }
    ]
  }
}
