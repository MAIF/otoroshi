export default {
  id: 'cp:otoroshi.next.plugins.OtoroshiChallenge',
  config_schema: {
    state_resp_leeway: {
      type: 'number',
    },
    response_header_name: {
      type: 'string',
    },
    algo_from_backend: {
      label: 'Algo. from backend',
      type: 'form',
      collapsable: true,
      flow: ['type'],
      schema: {
        type: {
          type: 'form',
          label: 'Algo settings',
          flow: {
            field: 'type.type',
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
          },
          schema: {
            type: {
              type: 'select',
              props: {
                label: 'Type',
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
              type: 'string',
            },
            privateKey: {
              type: 'string',
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
              type: 'form',
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
        },
      },
    },
    version: {
      type: 'string',
    },
    ttl: {
      type: 'number',
    },
    algo_to_backend: {
      type: 'form',
      collapsable: true,
      label: 'Algo. to backend',
      flow: ['type'],
      schema: {
        type: {
          type: 'form',
          label: 'Algo settings',
          flow: {
            field: 'type.type',
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
          },
          schema: {
            type: {
              type: 'select',
              props: {
                label: 'Type',
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
              type: 'string',
            },
            privateKey: {
              type: 'string',
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
              type: 'form',
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
        },
      },
    },
    request_header_name: {
      type: 'string',
    },
  },
  config_flow: [
    'version',
    'ttl',
    'request_header_name',
    'response_header_name',
    'state_resp_leeway',
    'algo_to_backend',
    'algo_from_backend',
  ],
};
