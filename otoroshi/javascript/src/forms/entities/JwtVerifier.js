import React from 'react';
import { Button } from '../../components/Button';
import { LabelAndInput, NgForm } from '../../components/nginputs';

const AlgoSettingsForm = {
  type: 'form',
  label: 'Signature',
  schema: {
    type: {
      type: 'dots',
      label: 'Algo.',
      props: {
        options: [
          { label: 'Hmac + SHA', value: 'HSAlgoSettings' },
          { label: 'RSASSA-PKCS1 + SHA', value: 'RSAlgoSettings' },
          { label: 'ECDSA + SHA', value: 'ESAlgoSettings' },
          { label: 'JWK Set (only for verification)', value: 'JWKSAlgoSettings' },
          { label: 'RSASSA-PKCS1 + SHA from KeyPair', value: 'RSAKPAlgoSettings' },
          { label: 'ECDSA + SHA from KeyPair', value: 'ESKPAlgoSettings' },
          { label: 'Otoroshi KeyPair from token kid (only for verification)', value: 'KidAlgoSettings' }
        ]
      }
    },
    onlyExposedCerts: {
      type: 'bool',
      label: 'Use only exposed keypairs'
    },
    size: {
      type: 'dots',
      label: 'SHA size',
      props: {
        options: [256, 384, 512]
      }
    },
    secret: {
      type: 'string',
      label: 'HMAC secret'
    },
    base64: {
      type: 'bool',
      label: 'Base64 encoded secret'
    },
    publicKey: {
      type: 'text',
      label: 'Public key'
    },
    privateKey: {
      type: 'text',
      label: 'Private key'
    },
    certId: {
      type: "select",
      label: "Cert. id",
      props: {
        optionsFrom: "/bo/api/proxy/api/certificates",
        optionsTransformer: {
          label: "name",
          value: "id"
        }
      }
    },
    url: {
      type: 'string',
      label: 'URL'
    },
    timeout: {
      type: 'number',
      label: 'HTTP call timeout'
    },
    ttl: {
      type: 'number',
      label: 'Cache TTL for the keyset'
    },
    headers: {
      type: "object",
      label: "Headers"
    },
    kty: {
      type: 'select',
      label: 'Key type',
      props: {
        options: ['RSA', 'EC']
      }
    }
  },
  flow: (props, v) => {
    return {
      KidAlgoSettings: ['type', 'onlyExposedCerts'],
      HSAlgoSettings: ['type', 'size', 'secret', 'base64'],
      RSAlgoSettings: ['type', 'size', 'publicKey', 'privateKey'],
      RSAKPAlgoSettings: ['type', 'size', 'certId'],
      ESKPAlgoSettings: ['type', 'size', 'certId'],
      ESAlgoSettings: ['type', 'size', 'publicKey', 'privateKey'],
      JWKSAlgoSettings: [
        'type',
        'url',
        'timeout',
        'ttl',
        'headers',
        'kty'
      ],
      [undefined]: ['type']
    }[v.value?.type]
  }
}

const StrategyForm = {
  type: {
    renderer: props => {
      return <div
        style={{
          display: 'flex',
          gap: '10px',
          flexWrap: 'wrap',
          justifyContent: 'flex-start'
        }}>
        {[
          {
            strategy: 'DefaultToken', title: ['Generate'],
            desc: 'DefaultToken will add a token if no present.',
            tags: ['generate']
          },
          {
            strategy: 'StrictDefaultToken', title: ['Generate and failed if present'],
            desc: 'DefaultToken will add a token if no present.',
            tags: ['generate']
          },
          {
            strategy: 'PassThrough', title: ['Verify'],
            desc: 'PassThrough will only verifiy token signing and fields values if provided. ',
            tags: ['verify']
          },
          {
            strategy: 'Sign', title: ['Verify and re-sign'],
            desc: 'Sign will do the same as PassThrough plus will re-sign the JWT token with the provided algo. settings.',
            tags: ['verify', 'sign']
          },
          {
            strategy: 'Transform', title: ['Verify, re-sign and Transform'],
            desc: 'Transform will do the same as Sign plus will be able to transform the token.',
            tags: ['verify', 'sign', 'transform']
          }
        ].map(({ strategy, desc, title, tags }) => {
          return <Button
            type={props.value === strategy ? 'save' : 'dark'}
            className="py-3 d-flex align-items-center flex-column col-3"
            style={{
              gap: '12px',
              minHeight: '325px'
            }}
            onClick={() => props.onChange(strategy)}
            key={strategy}
          >
            <div style={{ flex: .2 }}>
              {title.map((t, i) => <h3 className="wizard-h3--small " style={{
                margin: 0,
                marginTop: i > 0 ? '1px' : 0
              }} key={t}>
                {t}
              </h3>)}
            </div>
            <div className='d-flex flex-column align-items-center' style={{ flex: 1 }}>
              <label className='d-flex align-items-center' style={{ textAlign: 'left' }}>
                {desc}
              </label>
              <div className='mt-auto' style={{
                padding: '4px',
                background: '#515151',
                width: '100%'
              }}>
                {[
                  'Generate', 'Verify', 'Sign', 'Transform'
                ]
                  .filter(tag => tags.includes(tag.toLocaleLowerCase()))
                  .map(tag => <div className='d-flex align-items-center me-1'
                    key={tag}
                    style={{
                      minWidth: "80px",
                      padding: '2px 8px 2px 3px'
                    }}>
                    <i className={`fas fa-${tags.includes(tag.toLocaleLowerCase()) ? 'check' : 'times'} me-1`} style={{
                      color: tags.includes(tag.toLocaleLowerCase()) ? '#f9b000' : '#fff',
                      padding: '4px',
                      minWidth: '20px'
                    }} />
                    <span>{tag}</span>
                  </div>)}
              </div>
            </div>
          </Button>
        })}
      </div>
    }
  }
}

const DefaultTokenForm = {
  strict: {
    type: 'bool',
    props: {
      label: 'Strict',
      help: "If token already present, the call will fail"
    }
  },
  token: {
    type: 'code',
    label: 'Default value',
    props: {
      mode: 'json',
      editorOnly: true
    }
  },
  verificationSettings: {
    type: 'form',
    label: 'Verification settings',
    flow: ['fields', 'arrayFields'],
    schema: {
      fields: {
        type: 'object',
        props: {
          label: "Verify token fields",
          placeholderKey: "Field name",
          placeholderValue: "Field value",
          help: "When the JWT token is checked, each field specified here will be verified with the provided value"
        }
      },
      arrayFields: {
        type: 'object',
        props: {
          label: "Verify token array value",
          placeholderKey: "Field name",
          placeholderValue: "One or more comma separated values in the array",
          help: "When the JWT token is checked, each field specified here will be verified if the provided value is contained in the array"
        }
      }
    }
  }
}

export default {
  config_flow: [
    {
      type: 'group',
      name: 'Informations',
      fields: ['id', 'name', 'desc']
    },
    '_loc',
    {
      type: 'grid',
      name: 'Flags',
      fields: ['enabled', 'strict']
    },
    {
      type: 'group',
      name: 'Token location',
      fields: ['source']
    },
    {
      type: 'group',
      name: 'Strategy',
      fields: ['strategy']
    },
    {
      type: 'group',
      name: props => props?.strategy?.type === 'DefaultToken'
        ? 'Default token signature'
        : 'Token validation',
      fields: ['algoSettings']
    },
    {
      type: 'group',
      name: 'Misc.',
      collapsed: true,
      fields: [
        'metadata', 'tags'
      ]
    }
  ],
  config_schema: {
    _loc: {
      type: 'location',
      props: {
        label: 'Location'
      }
    },
    id: {
      type: 'string',
      props: {
        label: 'The verifier Id'
      }
    },
    name: {
      type: 'string',
      props: {
        label: 'The verifier name'
      }
    },
    desc: {
      type: 'string',
      props: {
        label: 'The verifier description'
      }
    },
    enabled: {
      type: 'bool',
      props: {
        label: 'Enabled'
      }
    },
    strict: {
      type: 'bool',
      props: {
        label: 'Strict'
      }
    },
    source: {
      type: 'form',
      ngOptions: {
        spread: true
      },
      schema: {
        type: {
          type: 'select',
          props: {
            ngOptions: {
              spread: true
            },
            options: [
              { value: 'InHeader', label: 'Header' },
              { value: 'InQueryParam', label: 'Query string' },
              { value: 'InCookie', label: 'Cookie' }
            ]
          }
        },
        name: {
          type: 'string',
          label: 'Name'
        },
        remove: {
          type: 'string',
          placeholder: 'Bearer ',
          label: 'Remove value',
          props: {
            subTitle: '(Optional): String to remove from the value to access to the token'
          }
        },
        debug: {
          renderer: () => {
            return <LabelAndInput label="Examples">
              <NgForm
                schema={{
                  header: {
                    ngOptions: {
                      spread: true
                    },
                    type: 'json',
                    props: {
                      editorOnly: true,
                      height: '50px',
                      defaultValue: {
                        Authorization: 'Bearer XXX.XXX.XXX'
                      }
                    }
                  },
                  result: {
                    type: 'form',
                    label: 'Form values',
                    schema: {
                      headerName: {
                        type: 'string',
                        label: 'Name',
                        props: {
                          disabled: true,
                          defaultValue: 'Authorization'
                        }
                      },
                      remove: {
                        type: 'string',
                        label: 'Remove value',
                        props: {
                          disabled: true,
                          defaultValue: 'Bearer '
                        }
                      },
                    },
                    flow: ['headerName', 'remove']
                  }
                }}
                flow={[
                  {
                    type: 'group',
                    collapsable: false,
                    name: 'A bearer token expected in Authorization header',
                    fields: ['header', 'result']
                  }
                ]} />
            </LabelAndInput>
          }
        }
      },
      flow: [
        'type',
        {
          type: 'group',
          collapsable: false,
          visible: props => props?.type === 'InHeader',
          name: 'Header informations',
          fields: ['name', 'remove', 'debug']
        },
        {
          type: 'group',
          collapsable: false,
          visible: props => props?.type === 'InQueryParam',
          name: 'Query param name',
          fields: ['name']
        },
        {
          type: 'group',
          collapsable: false,
          visible: props => props?.type === 'InCookie',
          name: 'Cookie name',
          fields: ['name']
        }
      ]
    },
    algoSettings: AlgoSettingsForm,
    strategy: {
      type: 'form',
      schema: {
        ...StrategyForm,
        ...DefaultTokenForm,
        algoSettings: AlgoSettingsForm,
        transformSettings: {
          type: 'form',
          schema: {
            location: {
              type: 'form',
              ngOptions: {
                spread: true
              },
              schema: {
                type: {
                  type: 'select',
                  props: {
                    ngOptions: {
                      spread: true
                    },
                    options: [
                      { value: 'InHeader', label: 'Header' },
                      { value: 'InQueryParam', label: 'Query string' },
                      { value: 'InCookie', label: 'Cookie' }
                    ]
                  }
                },
                name: {
                  type: 'string',
                  label: 'Name'
                },
                remove: {
                  type: 'string',
                  placeholder: 'Bearer ',
                  label: 'Remove value',
                  props: {
                    subTitle: '(Optional): String to remove from the value to access to the token'
                  }
                },
                debug: {
                  renderer: () => {
                    return <LabelAndInput label="Examples">
                      <NgForm
                        schema={{
                          header: {
                            ngOptions: {
                              spread: true
                            },
                            type: 'json',
                            props: {
                              editorOnly: true,
                              height: '50px',
                              defaultValue: {
                                Authorization: 'Bearer XXX.XXX.XXX'
                              }
                            }
                          },
                          result: {
                            type: 'form',
                            label: 'Form values',
                            schema: {
                              headerName: {
                                type: 'string',
                                label: 'Name',
                                props: {
                                  disabled: true,
                                  defaultValue: 'Authorization'
                                }
                              },
                              remove: {
                                type: 'string',
                                label: 'Remove value',
                                props: {
                                  disabled: true,
                                  defaultValue: 'Bearer '
                                }
                              },
                            },
                            flow: ['headerName', 'remove']
                          }
                        }}
                        flow={[
                          {
                            type: 'group',
                            collapsable: false,
                            name: 'A bearer token expected in Authorization header',
                            fields: ['header', 'result']
                          }
                        ]} />
                    </LabelAndInput>
                  }
                }
              },
              flow: [
                'type',
                {
                  type: 'group',
                  collapsable: false,
                  visible: props => props?.type === 'InHeader',
                  name: 'Header informations',
                  fields: ['name', 'remove', 'debug']
                },
                {
                  type: 'group',
                  collapsable: false,
                  visible: props => props?.type === 'InQueryParam',
                  name: 'Query param name',
                  fields: ['name']
                },
                {
                  type: 'group',
                  collapsable: false,
                  visible: props => props?.type === 'InCookie',
                  name: 'Cookie name',
                  fields: ['name']
                }
              ]
            },
            mappingSettings: {
              type: 'form',
              flow: ['map', 'values', 'remove'],
              schema: {
                map: {
                  type: 'object',
                  props: {
                    label: "Rename token fields",
                    placeholderKey: "Field name",
                    placeholderValue: "Field value",
                    help: "When the JWT token is transformed, it is possible to change a field name, just specify origin field name and target field name"
                  }
                },
                values: {
                  type: 'object',
                  props: {
                    label: "Set token fields",
                    placeholderKey: "Field name",
                    placeholderValue: "Field value",
                    help: "When the JWT token is transformed, it is possible to add new field with static values, just specify field name and value"
                  }
                },
                remove: {
                  type: 'string',
                  array: true,
                  props: {
                    label: 'Remove token fields',
                    help: 'When the JWT token is transformed, it is possible to remove fields'
                  }
                }
              }
            }
          },
          flow: [
            'location',
            'mappingSettings'
          ]
        }
      },
      flow: [
        {
          type: 'group',
          name: 'Content',
          fields: v => {
            const strategy = v.value?.type
            console.log(v)
            return {
              'DefaultToken': ['type', 'strict', 'token', 'verificationSettings'],
              'PassThrough': ['type', 'verificationSettings'],
              'Sign': ['type', 'verificationSettings', 'algoSettings'],
              'Transform': ['type', 'verificationSettings', 'algoSettings', 'transformSettings'],
              [undefined]: ['type']
            }[strategy]
          }
        }
      ]
    },
    metadata: {
      type: 'object',
      label: 'Metadata'
    },
    tags: {
      type: 'array',
      props: {
        label: 'Tags'
      }
    }
  }
}