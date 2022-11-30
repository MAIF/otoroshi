export default {
  id: 'cp:otoroshi.next.plugins.OtoroshiInfos',
  config_schema: {
    header_name: {
      type: 'string',
      props: {
        label: 'Header name',
      },
    },
    version: {
      type: 'string',
      props: {
        label: 'Version',
      },
    },
    ttl: {
      type: 'number',
      props: {
        label: 'Time to live',
      },
    },
    algo: {
      type: 'form',
      collapsable: true,
      collapsed: false,
      flow: ['type'],
      label: 'Algo.',
      schema: {
        type: {
          type: 'select',
          label: 'Type.',
          props: {
            options: [
              'HSAlgoSettings',
              'RSAlgoSettings',
              'ESAlgoSettings',
              'JWKSAlgoSettings',
              'RSAKPAlgoSettings',
              'ESKPAlgoSettings',
              'KidAlgoSettings',
            ],
          },
        },
        size: {
          type: 'number',
        },
        secret: {
          type: 'string',
        },
        base64: {
          type: 'boolean',
        },
        publicKey: {
          type: 'text',
        },
        privateKey: {
          type: 'text',
        },
        url: {
          type: 'string',
        },
        headers: {
          type: 'object',
          props: {
            label: 'Headers',
          },
        },
        timeout: {
          type: 'number',
        },
        ttl: {
          type: 'number',
        },
        kty: {
          type: 'string',
        },
        mtlsConfig: {
          type: 'object',
          collapsable: true,
          format: 'form',
          flow: ['certs', 'trustedCerts', 'mtls', 'loose', 'trustAll'],
          schema: {
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
            trustedCerts: {
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
            mtls: {
              type: 'boolean',
            },
            loose: {
              type: 'boolean',
            },
            trustAll: {
              type: 'boolean',
            },
          },
        },
        proxy: {
          format: 'form',
          collapsable: true,
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
          schema: {
            host: {
              type: 'string',
            },
            port: {
              type: 'number',
            },
            protocol: {
              type: 'string',
            },
            principal: {
              type: 'string',
            },
            password: {
              type: 'string',
            },
            ntlmDomain: {
              type: 'string',
            },
            encoding: {
              type: 'string',
            },
            nonProxyHosts: {
              type: 'array',
              props: {
                label: 'Non proxy hosts',
              },
            },
          },
        },
        certId: {
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
        onlyExposedCerts: {
          type: 'boolean',
        },
      },
      flow: {
        field: 'type',
        flow: {
          HSAlgoSettings: ['type', 'size', 'secret', 'base64'],
          RSAlgoSettings: ['type', 'size', 'publicKey', 'privateKey'],
          ESAlgoSettings: ['type', 'size', 'publicKey', 'privateKey'],
          JWKSAlgoSettings: [
            'type',
            'url',
            'headers',
            'timeout',
            'ttl',
            'kty',
            'proxy',
            'mtlsConfig',
          ],
          RSAKPAlgoSettings: ['type', 'size', 'certId'],
          ESKPAlgoSettings: ['type', 'size', 'certId'],
          KidAlgoSettings: ['type', 'onlyExposedCerts'],
        },
      }
    },
  },
  config_flow: ['version', 'ttl', 'header_name', 'algo'],
};
