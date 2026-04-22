import { findAuthConfigById } from "../../services/BackOfficeServices";
import { SelectorWizardLauncher } from "../wizards/SelectorWizardLauncher";

export default {
  id: 'cp:otoroshi.next.plugins.ExposeDaikoku',
  config_schema: {
    authentication_ref: {
      label: 'Authentication module',
      type: 'AuthenticationWizard',
      props: {
        componentLauncher: SelectorWizardLauncher,
        componentsProps: {
          entityName: 'Authentication configuration',
          entityField: 'authentication',
          findById: findAuthConfigById,
        },
      },
    },
    exclude: {
      label: 'Exclude paths',
      type: 'string',
      array: true,
    },
    header_name: {
      label: 'Token header name',
      type: 'string'
    },
    add_fields: {
      type: 'object',
      label: 'Custom fields added to the token',
    },
    algo: {
      type: 'form',
      collapsable: true,
      collapsed: false,
      flow: ['type'],
      label: 'Signature alg.',
      schema: {
        type: {
          type: 'select',
          label: 'Type.',
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
          label: 'size',
          props: {
            options: [
              { label: '512', value: 512 },
              { label: '384', value: 384 },
              { label: '256', value: 256 },
            ],
          },
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
          type: 'string',
          help: 'Type of key',
        },
        mtlsConfig: {
          type: 'object',
          collapsable: true,
          format: 'form',
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
          type: 'select',
          label: 'Cert. id',
          props: {
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
    }
  },
  config_flow: ['authentication_ref', 'exclude', 'header_name', 'add_fields', 'algo'],
};
