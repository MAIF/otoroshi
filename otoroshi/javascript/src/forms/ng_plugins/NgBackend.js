import React from 'react';

import explainations from '../../explainations';
import { LoadBalancingSelector } from '../../pages/ApiEditor/LoadBalancingSelector';
import { MarkdownInput } from '../../components/nginputs/MarkdownInput';

export default {
  id: 'Backend',
  icon: 'bullseye',
  group: 'Targets',
  field: 'backend',
  schema: {
    root: {
      label: 'Root',
      type: 'string',
      help: 'The root URL of the target service',
    },
    client: {
      label: 'Http client settings',
      type: 'form',
      collapsable: true,
      collapsed: true,
      schema: {
        custom_timeouts: {
          label: 'Custom timeouts',
          type: 'array',
          array: true,
          format: 'form',
          schema: {
            connection_timeout: {
              label: 'Connection timeout',
              type: 'number',
              help: explainations.CIRCUIT_BREAKER_CONNECTION_TIMEOUT,
            },
            call_and_stream_timeout: {
              label: 'Call and stream timeout',
              type: 'number',
              help: explainations.CIRCUIT_BREAKER_CALL_AND_STREAM_TIMEOUT,
            },
            path: {
              label: 'Path',
              type: 'string',
              help: explainations.CIRCUIT_BREAKER_CUSTOM_TIMEOUT_PATH,
            },
            call_timeout: {
              label: 'Call timeout',
              type: 'number',
              help: explainations.CIRCUIT_BREAKER_CALL_TIMEOUT,
            },
            idle_timeout: {
              label: 'Idle timeout',
              type: 'number',
              help: explainations.CIRCUIT_BREAKER_IDLE_TIMEOUT,
            },
            global_timeout: {
              label: 'Global timeout',
              type: 'number',
              help: explainations.CIRCUIT_BREAKER_GLOBAL_TIMEOUT,
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
          label: 'Global timeout',
          type: 'number',
          help: explainations.CIRCUIT_BREAKER_GLOBAL_TIMEOUT,
        },
        max_errors: {
          label: 'Max errors',
          type: 'number',
          help: explainations.CIRCUIT_BREAKER_MAX_ERRORS,
        },
        retry_initial_delay: {
          label: 'Retry initial delay',
          type: 'number',
          help: explainations.CIRCUIT_BREAKER_RETRY_INITIAL_DELAY,
        },
        backoff_factor: {
          label: 'Backoff factor',
          type: 'number',
          help: explainations.CIRCUIT_BREAKER_BACKOFF_FACTOR,
        },
        cache_connection_settings: {
          label: 'Cache connection settings',
          type: 'form',
          collapsable: true,
          collapsed: true,
          schema: {
            queue_size: {
              label: 'Queue size',
              type: 'number',
              help: explainations.CIRCUIT_BREAKER_CACHE_CONNECTION_SETTINGS_QUEUE_SIZE,
            },
            enabled: {
              label: 'Enabled',
              type: 'box-bool',
              help: explainations.CIRCUIT_BREAKER_CACHE_CONNECTION_SETTINGS_ENABLED,
              props: {
                description: 'Use a cache at host connection level to avoid reconnection time',
              },
            },
          },
          flow: ['queue_size', 'enabled'],
        },
        sample_interval: {
          label: 'Sample interval',
          type: 'number',
          help: explainations.CIRCUIT_BREAKER_SAMPLE_INTERVAL,
        },
        call_and_stream_timeout: {
          label: 'Call and stream timeout',
          type: 'number',
          help: explainations.CIRCUIT_BREAKER_CALL_AND_STREAM_TIMEOUT,
        },
        retries: {
          label: 'Retries',
          type: 'number',
          help: explainations.CIRCUIT_BREAKER_CLIENT_RETRIES,
        },
        proxy: {
          label: 'Proxy',
          type: 'form',
          collapsable: true,
          collapsed: true,
          schema: {
            host: {
              label: 'Host',
              type: 'string',
            },
            port: {
              label: 'Port',
              type: 'number',
            },
            protocol: {
              type: 'dots',
              label: 'Protocol',
              props: {
                options: ['HTTP/1.0', 'HTTP/1.1', 'HTTP/2.0', 'HTTP/3.0'],
              },
            },
            principal: {
              label: 'Principal',
              type: 'string',
            },
            password: {
              label: 'Password',
              type: 'string',
            },
            ntlmDomain: {
              label: 'NtlmDomain',
              type: 'string',
            },
            encoding: {
              label: 'Encoding',
              type: 'string',
            },
            nonProxyHosts: {
              label: 'Non Proxy Hosts',
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
          label: 'Call timeout',
          type: 'number',
          help: 'Specify how long each call should last at most in milliseconds.',
        },
        idle_timeout: {
          label: 'Idle timeout',
          type: 'number',
          help: 'Specify how long each connection can stay in idle state at most in milliseconds.',
        },
        connection_timeout: {
          label: 'Connection timeout',
          type: 'number',
          help: 'Specify how long each connection should last at most in milliseconds.',
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
      label: 'Health check',
      type: 'form',
      collapsable: true,
      collapsed: true,
      schema: {
        description: {
          renderer: () => {
            return (
              <MarkdownInput
                readOnly
                className="form-description"
                preview
                value={`
**Tips**

Send back the numeric value received in the request header defined by \`otoroshi.headers.healthcheck.test\`, 
appending 42 in the response header defined by \`otoroshi.headers.healthcheck.testresult\`.

👉 Example with defaults:

  - Request: Otoroshi-Health-Check-Logic-Test: 123
  - Response: Otoroshi-Health-Check-Logic-Test-Result: 165 (123 + 42)
`}
              />
            );
          },
        },
        enabled: {
          label: 'Enabled',
          type: 'box-bool',
          props: {
            description: 'To help failing fast, you can activate healthcheck on a specific URL.',
          },
        },
        blockOnRed: {
          label: 'Block on red',
          type: 'box-bool',
          props: {
            description:
              'If enabled, Otoroshi will block requests when the healthcheck status is considered unhealthy (red)',
          },
        },
        logicCheck: {
          label: 'Logic check',
          type: 'box-bool',
          props: {
            description:
              'If enabled, Otoroshi will check if the response contains a header with the logic check value + 42 to consider health GREEN.',
          },
        },
        url: {
          label: 'URL Path',
          type: 'string',
          help: "The URL Path to check. Should return an HTTP 200 response. You can also respond with an 'Otoroshi-Health-Check-Logic-Test-Result' header set to the value of the 'Otoroshi-Health-Check-Logic-Test-Test' request header + 42. to make the healthcheck complete.",
        },
        timeout: {
          type: 'number',
          label: 'Timeout',
        },
        healthyStatuses: {
          type: 'number',
          array: true,
          label: 'Healthy statuses',
        },
        unhealthyStatuses: {
          type: 'number',
          array: true,
          label: 'Unhealthy statuses',
        },
        healthyRegexChecks: {
          type: 'string',
          array: true,
          label: 'Healthy regex checks',
          suffix: 'regex',
          placeholder: 'regex',
        },
        unhealthyRegexChecks: {
          type: 'string',
          array: true,
          label: 'Unhealthy regex checks',
          suffix: 'regex',
          placeholder: 'regex',
        },
      },
      flow: [
        'description',
        'enabled',
        'blockOnRed',
        'logicCheck',
        'url',
        'timeout',
        'healthyStatuses',
        'unhealthyStatuses',
        'healthyRegexChecks',
        'unhealthyRegexChecks',
      ],
    },
    targets: {
      array: true,
      format: 'form',
      type: 'object',
      label: 'Targets',
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
              help: 'The rack of this target (based on the rack value in the otoroshi configuration) app.instance',
            },
            provider: {
              label: 'provider',
              type: 'string',
              help: 'The infra. provider of this target (based on the provide value in the otoroshi configuration app.instance)',
            },
            dataCenter: {
              label: 'dataCenter',
              type: 'string',
              help: 'The data center of this target (based on the dc value in the otoroshi configuration) app.instance',
            },
            zone: {
              label: 'zone',
              type: 'string',
              help: 'The zone of this target (based on the zone value in the otoroshi configuration) app.instance',
            },
            positions: {
              label: 'positions',
              type: 'array',
              array: true,
              format: 'form',
              help: 'The possible location with their radius in Km',
              schema: {
                latitude: {
                  label: 'latitude',
                  type: 'number',
                  help: 'The possible location with their radius in Km',
                },
                longitude: {
                  label: 'longitude',
                  type: 'number',
                  help: 'The possible location with their radius in Km',
                },
                radius: {
                  label: 'radius',
                  type: 'number',
                  help: 'The possible location with their radius in Km',
                },
              },
              flow: ['latitude', 'longitude', 'radius'],
            },
            type: {
              type: 'select',
              help: 'The predicate of the target. Only used with experimental client',
              props: {
                label: 'type',
                options: ['AlwaysMatch', 'NetworkLocationMatch', 'GeolocationMatch'],
              },
            },
            region: {
              label: 'region',
              type: 'string',
              help: 'The region of this target (based on the region value in the otoroshi configuration) app.instance',
            },
            dc: {
              label: 'dc',
              type: 'string',
              help: 'The data center of this target (based on the dc value in the otoroshi configuration) app.instance',
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
          label: 'Hostname',
          type: 'string',
          help: 'Hostname for your service',
          props: {
            defaultValue: 'changeme.oto.tools',
          },
        },
        backup: {
          label: 'Backup',
          type: 'bool',
          help: 'Tell if this target is a backup one (a secondary one). Backup targets are only used when all the primary targets have failed',
          props: {
            defaultValue: false,
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
          label: 'Port',
          type: 'number',
          props: {
            defaultValue: 80,
          },
        },
        weight: {
          label: 'Weight',
          type: 'number',
          props: {
            defaultValue: 1,
          },
        },
        tls: {
          label: 'TLS',
          type: 'bool',
        },
        tls_config: {
          label: 'Custom TLS setup',
          type: 'form',
          collapsable: true,
          collapsed: true,
          schema: Object.entries({
            enabled: {
              label: 'Enabled',
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
              label: 'Loose',
              type: 'box-bool',
              props: {
                description:
                  'If enabled, Otoroshi will accept any certificate and disable hostname verification',
              },
            },
            trust_all: {
              label: 'Trust all',
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
        ip_address: {
          label: 'IP address',
          type: 'string',
          help: 'The ip address of the target. Could be useful to perform manual DNS resolution. Only used with experimental client',
        },
      },
      props: {
        shouldKeepFirstItem: true,
        v2: {
          template: {
            hostname: 'request.otoroshi.io',
            protocol: 'HTTP/1.1',
            port: 443,
            weight: 1,
            tls: true,
            tls_config: {
              enabled: false,
              loose: false,
              trust_all: false,
              certs: [],
              trusted_certs: [],
            },
            ip_address: null,
            predicate: {
              type: 'AlwaysMatch',
            },
          },
          folded: ['hostname', 'port', 'protocol', 'backup'],
          flow: [
            'hostname',
            'port',
            'protocol',
            'weight',
            'ip_address',
            'tls',
            'backup',
            'predicate',
            'tls_config',
          ],
        },
      },
      // flow: [
      //   {
      //     type: 'group',
      //     collapsed: true,
      //     name: (props) => {
      //       const port = props.value?.port;
      //       const hostname = props.value?.hostname || '';
      //       const isSecured = props.value?.tls;

      //       return `${isSecured ? 'https' : 'http'}://${hostname}${port ? `:${port}` : ''}`;
      //     },
      //     full_fields: [
      //       'hostname',
      //       'port',
      //       'protocol',
      //       'weight',
      //       'ip_address',
      //       'tls',
      //       'predicate',
      //       'tls_config',
      //     ],
      //     fields: ['hostname', 'port', 'protocol', 'tls'],
      //   },
      // ],
    },
    rewrite: {
      label: 'Full path rewrite',
      type: 'bool',
    },
    load_balancing: {
      label: 'Load Balancing',
      type: 'form',
      collapsable: true,
      collapsed: true,
      schema: {
        type: {
          renderer: (props) => {
            return <LoadBalancingSelector onChange={props.onChange} value={props.value} />;
          },
        },
        ratio: {
          label: 'ratio',
          type: 'number',
          help: 'The percentage of targets in with the best response in the whole target pool to choose. If 0,5, then more than 50% of the calls will happen on the target with the best response time.',
        },
      },
      flow: (item) => {
        if (item?.type === 'WeightedBestResponseTime') {
          return ['type', 'ratio'];
        } else {
          return ['type'];
        }
      },
    },
  },
  flow: ['root', 'rewrite', 'targets', 'client', 'load_balancing', 'health_check'],
};
