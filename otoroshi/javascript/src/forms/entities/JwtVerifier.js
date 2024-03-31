import React, { useState } from 'react';
import { Button } from '../../components/Button';
import { LabelAndInput, NgForm } from '../../components/nginputs';

const AlgoSettingsForm = {
  type: 'form',
  label: 'Signature',
  collapsable: true,
  props: {
    showSummary: true,
  },
  schema: {
    type: {
      type: 'dots',
      label: 'Algo.',
      props: {
        resetOnChange: true,
        options: [
          { label: 'Hmac + SHA', value: 'HSAlgoSettings' },
          { label: 'RSASSA-PKCS1 + SHA', value: 'RSAlgoSettings' },
          { label: 'ECDSA + SHA', value: 'ESAlgoSettings' },
          { label: 'JWK Set (only for verification)', value: 'JWKSAlgoSettings' },
          { label: 'RSASSA-PKCS1 + SHA from KeyPair', value: 'RSAKPAlgoSettings' },
          { label: 'ECDSA + SHA from KeyPair', value: 'ESKPAlgoSettings' },
          {
            label: 'Otoroshi KeyPair from token kid (only for verification)',
            value: 'KidAlgoSettings',
          },
        ],
      },
    },
    onlyExposedCerts: {
      type: 'bool',
      label: 'Use only exposed keypairs',
    },
    size: {
      type: 'dots',
      label: 'SHA size',
      props: {
        options: [256, 384, 512],
      },
    },
    secret: {
      type: 'string',
      label: 'HMAC secret',
    },
    base64: {
      type: 'bool',
      label: 'Base64 encoded secret',
    },
    publicKey: {
      type: 'text',
      label: 'Public key',
    },
    privateKey: {
      type: 'text',
      label: 'Private key',
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
    url: {
      type: 'string',
      label: 'URL',
    },
    timeout: {
      type: 'number',
      label: 'HTTP call timeout',
    },
    ttl: {
      type: 'number',
      label: 'Cache TTL for the keyset',
    },
    headers: {
      type: 'object',
      label: 'Headers',
    },
    kty: {
      type: 'select',
      label: 'Key type',
      props: {
        options: ['RSA', 'EC'],
      },
    },
  },
  flow: (props, v) => {
    return {
      KidAlgoSettings: ['type', 'onlyExposedCerts'],
      HSAlgoSettings: ['type', 'size', 'secret', 'base64'],
      RSAlgoSettings: ['type', 'size', 'publicKey', 'privateKey'],
      RSAKPAlgoSettings: ['type', 'size', 'certId'],
      ESKPAlgoSettings: ['type', 'size', 'certId'],
      ESAlgoSettings: ['type', 'size', 'publicKey', 'privateKey'],
      JWKSAlgoSettings: ['type', 'url', 'timeout', 'ttl', 'headers', 'kty'],
      [undefined]: ['type'],
    }[v.value?.type];
  },
};

const VERIFIER_STRATEGIES = [
  {
    strategy: 'PassThrough',
    title: ['Verify'],
    desc: 'PassThrough will only verifiy token signing and fields values if provided. ',
    tags: ['verify'],
  },
  {
    strategy: 'Sign',
    title: ['Verify and re-sign'],
    desc: 'Sign will do the same as PassThrough plus will re-sign the JWT token with the provided algo. settings.',
    tags: ['verify', 'sign'],
  },
  {
    strategy: 'Transform',
    title: ['Verify, re-sign and Transform'],
    desc: 'Transform will do the same as Sign plus will be able to transform the token.',
    tags: ['verify', 'sign', 'transform'],
  },
];

const StrategyForm = {
  type: {
    renderer: (props) => {
      const [open, setOpen] = useState(false);

      if (props.readOnly) {
        return (
          <LabelAndInput label="Type">
            <span
              className="d-flex align-items-center"
              style={{ height: '100%', color: 'var(--color-red)' }}
            >
              {props.value}
            </span>
          </LabelAndInput>
        );
      } else {
        if (!open) {
          return (
            <div className="d-flex align-items-center mb-3">
              <label>
                Selected strategy : <span style={{ fontWeight: 'bold' }}>{props.value}</span>
              </label>
              <Button
                onClick={() => setOpen(true)}
                text="Change of strategy"
                className="ms-3 btn-sm"
              />
            </div>
          );
        } else {
          return (
            <div
              style={{
                display: 'flex',
                gap: '10px',
                flexWrap: 'wrap',
                justifyContent: 'flex-start',
              }}
            >
              {VERIFIER_STRATEGIES.map(({ strategy, desc, title, tags }) => {
                return (
                  <Button
                    type={props.value === strategy ? 'primaryColor' : 'dark'}
                    className="py-3 d-flex align-items-center flex-column col-3"
                    style={{
                      gap: '12px',
                      minHeight: '325px',
                      maxWidth: '235px',
                    }}
                    onClick={() => {
                      props.onChange(strategy);
                      setOpen(false);
                    }}
                    key={strategy}
                  >
                    <div style={{ flex: 0.2 }}>
                      {title.map((t, i) => (
                        <h3
                          className="wizard-h3--small "
                          style={{
                            margin: 0,
                            marginTop: i > 0 ? '1px' : 0,
                          }}
                          key={t}
                        >
                          {t}
                        </h3>
                      ))}
                    </div>
                    <div className="d-flex flex-column align-items-center" style={{ flex: 1 }}>
                      <span className="d-flex align-items-center" style={{ textAlign: 'left' }}>
                        {desc}
                      </span>
                      <div
                        className="mt-auto"
                        style={{
                          padding: '4px',
                          background: '#515151',
                          width: '100%',
                        }}
                      >
                        {['Generate', 'Verify', 'Sign', 'Transform']
                          .filter((tag) => tags.includes(tag.toLocaleLowerCase()))
                          .map((tag) => (
                            <div
                              className="d-flex align-items-center me-1"
                              key={tag}
                              style={{
                                minWidth: '80px',
                                padding: '2px 8px 2px 3px',
                              }}
                            >
                              <i
                                className={`fas fa-${
                                  tags.includes(tag.toLocaleLowerCase()) ? 'check' : 'times'
                                } me-1`}
                                style={{
                                  color: tags.includes(tag.toLocaleLowerCase())
                                    ? 'var(--color-primary)'
                                    : '#fff',
                                  padding: '4px',
                                  minWidth: '20px',
                                }}
                              />
                              <span>{tag}</span>
                            </div>
                          ))}
                      </div>
                    </div>
                  </Button>
                );
              })}
            </div>
          );
        }
      }
    },
  },
};

const JwtLocationExamples = {
  renderer: (props) => {
    if (props.readOnly) return null;
    else {
      return (
        <div className="mt-3 p-3">
          <h4>Examples</h4>
          <p>
            A common use case is a request containing a JSON Web token prefixed with Bearer to
            indicate that the user accessing the resources is authenticated.
            <br />
            In our example, the token location is the Authorization header.
            <br />
            To match this case, you must set Authorization for the name entry and Bearer followed by
            a space for the Remove value entry.
          </p>
          <NgForm
            value={{
              header: {
                Authorization: 'Bearer XXX.XXX.XXX',
              },
            }}
            schema={{
              header: {
                ngOptions: {
                  spread: true,
                },
                type: 'json',
                props: {
                  editorOnly: true,
                  height: '50px',
                  defaultValue: {
                    Authorization: 'Bearer XXX.XXX.XXX',
                  },
                },
              },
            }}
            flow={[
              {
                type: 'group',
                collapsable: false,
                name: 'A bearer token expected in Authorization header',
                fields: ['header'],
              },
            ]}
          />
        </div>
      );
    }
  },
};
export default {
  config_flow: [
    {
      type: 'group',
      name: 'Informations',
      fields: ['id', 'name', 'desc'],
      summaryFields: ['name', 'desc'],
    },
    {
      type: 'group',
      name: 'Organizations & teams',
      fields: ['_loc'],
    },
    'source',
    'algoSettings',
    'strategy',
    'strategy.verificationSettings',
    'strategy.transformSettings',
    // 'strategy.token',
    {
      type: 'group',
      name: 'Misc.',
      collapsed: true,
      fields: ['metadata', 'tags'],
    },
  ],
  config_schema: {
    _loc: {
      type: 'location',
      props: {
        label: 'Location',
      },
    },
    id: {
      type: 'string',
      label: 'Id',
    },
    name: {
      type: 'string',
      label: 'Name',
    },
    desc: {
      type: 'string',
      label: 'Description',
    },
    source: {
      type: 'form',
      collapsable: true,
      label: 'Entry Token location',
      props: {
        showSummary: true,
        ngOptions: {
          spread: true,
        },
      },
      schema: {
        type: {
          type: 'select',
          label: 'Type',
          props: {
            ngOptions: {
              spread: true,
            },
            options: [
              { value: 'InHeader', label: 'Header' },
              { value: 'InQueryParam', label: 'Query string' },
              { value: 'InCookie', label: 'Cookie' },
            ],
          },
        },
        name: {
          type: 'string',
          label: 'Name',
        },
        remove: {
          type: 'string',
          placeholder: 'Bearer ',
          label: 'Remove value',
          props: {
            subTitle: '(Optional): String to remove from the value to access to the token',
          },
        },
        debug: JwtLocationExamples,
      },
      flow: [
        'type',
        {
          type: 'group',
          collapsable: false,
          visible: (props) => props?.type === 'InHeader',
          name: 'Header informations',
          fields: ['name', 'remove', 'debug'],
        },
        {
          type: 'group',
          collapsable: false,
          visible: (props) => props?.type === 'InQueryParam',
          name: 'Query param name',
          fields: ['name'],
        },
        {
          type: 'group',
          collapsable: false,
          visible: (props) => props?.type === 'InCookie',
          name: 'Cookie name',
          fields: ['name'],
        },
      ],
    },
    algoSettings: {
      ...AlgoSettingsForm,
      label: 'Token validation',
      props: {
        showSummary: true,
      },
    },
    strategy: {
      type: 'form',
      collapsable: true,
      label: 'Strategy',
      props: {
        showSummary: true,
        ngOptions: {
          spread: true,
        },
      },
      schema: {
        ...StrategyForm,
        verificationSettings: {
          type: 'form',
          collapsable: true,
          label: 'Verification settings',
          flow: ['fields', 'arrayFields'],
          schema: {
            fields: {
              type: 'object',
              label: 'Verify token fields',
              props: {
                placeholderKey: 'Field name',
                placeholderValue: 'Field value',
                help: 'When the JWT token is checked, each field specified here will be verified with the provided value',
              },
            },
            arrayFields: {
              type: 'object',
              label: 'Verify token array value',
              props: {
                placeholderKey: 'Field name',
                placeholderValue: 'One or more comma separated values in the array',
                help: 'When the JWT token is checked, each field specified here will be verified if the provided value is contained in the array',
              },
            },
          },
        },
        algoSettings: AlgoSettingsForm,
        transformSettings: {
          type: 'form',
          collapsable: true,
          label: 'Transform settings',
          visible: (value) => value?.strategy?.type === 'Transform',
          schema: {
            location: {
              type: 'form',
              collapsable: true,
              label: 'Exit Token location',
              props: {
                ngOptions: {
                  spread: true,
                },
              },
              schema: {
                type: {
                  type: 'select',
                  props: {
                    ngOptions: {
                      spread: true,
                    },
                    options: [
                      { value: 'InHeader', label: 'Header' },
                      { value: 'InQueryParam', label: 'Query string' },
                      { value: 'InCookie', label: 'Cookie' },
                    ],
                  },
                },
                name: {
                  type: 'string',
                  label: 'Name',
                },
                remove: {
                  type: 'string',
                  placeholder: 'Bearer ',
                  label: 'Remove value',
                  props: {
                    subTitle: '(Optional): String to remove from the value to access to the token',
                  },
                },
                debug: JwtLocationExamples,
              },
              flow: [
                'type',
                {
                  type: 'group',
                  collapsable: false,
                  visible: (props) => props?.type === 'InHeader',
                  name: 'Header informations',
                  fields: ['name', 'remove', 'debug'],
                },
                {
                  type: 'group',
                  collapsable: false,
                  visible: (props) => props?.type === 'InQueryParam',
                  name: 'Query param name',
                  fields: ['name'],
                },
                {
                  type: 'group',
                  collapsable: false,
                  visible: (props) => props?.type === 'InCookie',
                  name: 'Cookie name',
                  fields: ['name'],
                },
              ],
            },
            mappingSettings: {
              type: 'form',
              label: 'Mapping settings',
              flow: ['map', 'values', 'remove'],
              schema: {
                map: {
                  type: 'object',
                  label: 'Rename token fields',
                  props: {
                    placeholderKey: 'Field name',
                    placeholderValue: 'Field value',
                    help: 'When the JWT token is transformed, it is possible to change a field name, just specify origin field name and target field name',
                  },
                },
                values: {
                  type: 'object',
                  label: 'Set token fields',
                  props: {
                    placeholderKey: 'Field name',
                    placeholderValue: 'Field value',
                    help: 'When the JWT token is transformed, it is possible to add new field with static values, just specify field name and value',
                  },
                },
                remove: {
                  type: 'string',
                  array: true,
                  label: 'Remove token fields',
                  props: {
                    help: 'When the JWT token is transformed, it is possible to remove fields',
                  },
                },
              },
            },
          },
          flow: ['location', 'mappingSettings'],
        },
        token: {
          visible: (value) => value?.strategy?.type === 'DefaultToken',
          type: 'json',
          label: 'JSON Payload',
          // props: {
          //   mode: 'json',
          //   editorOnly: true,
          // },
        },
      },
      flow: (_, v) => {
        const strategy = v.value?.type;
        return {
          PassThrough: ['type'],
          Sign: ['type', 'algoSettings'],
          Transform: ['type', 'algoSettings'],
          [undefined]: ['type'],
        }[strategy];
      },
    },
    metadata: {
      type: 'object',
      label: 'Metadata',
    },
    tags: {
      type: 'string',
      label: 'Tags',
      array: true,
    },
  },
};
