export default {
  id: 'cp:otoroshi.next.plugins.OtoroshiChallenge',
  config_schema: {
    state_resp_leeway: {
      type: 'number',
      label: 'Token leeway',
      help: 'Amount of seconds that can differ between client and server',
      props: {
        suffix: 'seconds',
      },
    },
    response_header_name: {
      type: 'string',
      placeholder: 'Otoroshi-State-Resp by default',
    },
    version: {
      type: 'select',
      label: 'Challeng version',
      help:
        'The version of the challenge, either a simple value passed in headers or a jwt token signed by both parties',
      props: {
        options: [
          { label: 'V1', value: 1 },
          { label: 'V2', value: 2 },
        ],
      },
    },
    ttl: {
      type: 'number',
      label: 'Token TTL',
    },
    request_header_name: {
      type: 'string',
      placeholder: 'Otoroshi-State by default',
    },
    algo_to_backend: {
      type: 'form',
      collapsable: true,
      label: 'Signature alg. to backend',
      schema: {
        type: {
          type: 'select',
          label: 'Type',
          help: 'What kind of algorithm you want to use to verify/sign your JWT token with',
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
          type: 'select',
          label: "size",
          props: {
            options: [
              { label: "512", value: 512 },
              { label: "384", value: 384 },
              { label: "256", value: 256 },
            ]
          }
        },
        secret: {
          type: 'string',
          help: 'The Hmac secret',
        },
        base64: {
          type: 'box-bool',
          props: {
            description: 'Is the secret encoded with base64',
          },
        },
        publicKey: {
          type: 'string',
          help: 'The RSA public key',
        },
        privateKey: {
          type: 'string',
          help: 'The RSA private key, private key can be empty if not used for JWT token signing',
        },
        url: {
          type: 'string',
          help: 'The JWK Set url',
        },
        headers: {
          type: 'object',
          help: 'The HTTP headers passed',
          props: {
            label: 'Headers',
          },
        },
        timeout: {
          type: 'number',
        },
        ttl: {
          type: 'number',
          help: 'Cache TTL for the keyset',
        },
        kty: {
          type: 'select',
          help: 'Type of key',
          props: {
            options: ['RSA', 'EC'],
          },
        },
        mtlsConfig: {
          type: 'form',
          flow: ['certs', 'trustedCerts', 'mtls', 'loose', 'trustAll'],
          schema: {
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
            trustedCerts: {
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
          help: 'The keypair used to sign/verify token',
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
      },
    },
    algo_from_backend: {
      label: 'Signature alg. from backend',
      type: 'form',
      collapsable: true,
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
      },
      schema: {
        type: {
          type: 'select',
          label: 'Type',
          help: 'What kind of algorithm you want to use to verify/sign your JWT token with',
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
          type: 'select',
          label: "size",
          props: {
            options: [
              { label: "512", value: 512 },
              { label: "384", value: 384 },
              { label: "256", value: 256 },
            ]
          }
        },
        secret: {
          type: 'string',
        },
        base64: {
          type: 'box-bool',
          props: {
            description: 'Is the secret encoded with base64',
          },
        },
        publicKey: {
          type: 'string',
          help: 'The RSA public key',
        },
        privateKey: {
          type: 'string',
          help: 'The RSA private key, private key can be empty if not used for JWT token signing',
        },
        url: {
          type: 'string',
          help: 'The JWK Set url',
        },
        headers: {
          type: 'object',
          help: 'The HTTP headers passed',
          props: {
            label: 'Headers',
          },
        },
        timeout: {
          type: 'number',
        },
        ttl: {
          type: 'number',
          help: 'Cache TTL for the keyset',
        },
        kty: {
          type: 'select',
          help: 'Type of key',
          props: {
            options: ['RSA', 'EC'],
          },
        },
        mtlsConfig: {
          type: 'form',
          flow: ['certs', 'trustedCerts', 'mtls', 'loose', 'trustAll'],
          schema: {
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
            trustedCerts: {
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
          help: 'The keypair used to sign/verify token',
          props: {
            label: 'Certificates',
            optionsFrom: '/bo/api/proxy/api/certificates',
            optionsTransformer: {
              label: 'name',
              value: 'id',
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
              type: 'string',
              array: true,
              label: 'Non proxy hosts',
            },
          },
        },
        onlyExposedCerts: {
          type: 'boolean',
        },
      },
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
