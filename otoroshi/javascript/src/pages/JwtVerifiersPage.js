import React, { Component, useEffect } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table, TextInput } from '../components/inputs';
import { JwtVerifier } from '../components/JwtVerifier';
import { LabelAndInput, NgCodeRenderer, NgForm, NgJsonRenderer } from '../components/nginputs';

export class JwtVerifiersPage extends Component {
  state = {
    showWizard: true // TODO - resert to false
  }

  columns = [
    { title: 'Name', content: (item) => item.name },
    { title: 'Description', content: (item) => item.description },
  ];

  componentDidMount() {
    this.props.setTitle(`Global Jwt Verifiers`);
  }

  gotoVerifier = (verifier) => {
    window.location = `/bo/dashboard/jwt-verifiers/edit/${verifier.id}`;
  };

  render() {
    const { showWizard } = this.state;

    return (
      <div>
        {showWizard && <JwtVerifierWizard hide={() => this.setState({ showWizard: false })} />}
        <Table
          parentProps={this.props}
          selfUrl="jwt-verifiers"
          defaultTitle="All Global Jwt Verifiers"
          defaultValue={BackOfficeServices.createNewJwtVerifier}
          itemName="Jwt Verifier"
          formSchema={this.formSchema}
          formFlow={this.formFlow}
          columns={this.columns}
          stayAfterSave={true}
          fetchItems={BackOfficeServices.findAllJwtVerifiers}
          updateItem={BackOfficeServices.updateJwtVerifier}
          deleteItem={BackOfficeServices.deleteJwtVerifier}
          createItem={BackOfficeServices.createJwtVerifier}
          navigateTo={this.gotoVerifier}
          itemUrl={(i) => `/bo/dashboard/jwt-verifiers/edit/${i.id}`}
          showActions={true}
          showLink={false}
          rowNavigation={true}
          firstSort={0}
          extractKey={(item) => item.id}
          formComponent={JwtVerifier}
          formPassProps={{ global: true }}
          export={true}
          kubernetesKind="JwtVerifier"
          injectTopBar={() => (
            <button
              onClick={() => {
                this.setState({
                  showWizard: true
                })
              }}
              className="btn btn-primary"
              style={{ _backgroundColor: '#f9b000', _borderColor: '#f9b000', marginLeft: 5 }}>
              <i className="fas fa-hat-wizard" /> Create with wizard
            </button>
          )}
        />
      </div>
    );
  }
}

function Button({ type = 'info', style = {}, onClick = () => { }, text }) {
  return <button
    className={`btn btn-${type}`}
    style={style}
    onClick={onClick}>
    {text}
  </button>
}

function WizardStepButton(props) {
  return <Button
    {...props}
    type='save'
    style={{
      backgroundColor: '#f9b000',
      borderColor: '#f9b000',
      padding: '12px 48px'
    }}
  />
}

// function DangerButton(props) {
//   return <Button type="danger" {...props} />
// }

// function SaveButton(props) {
//   return <Button type="save" {...props} />
// }

// function WarningButton(props) {
//   return <Button type="warning" {...props} />
// }

class JwtVerifierWizard extends React.Component {
  state = {
    step: 1,
    jwtVerifier: {}
  }

  onChange = (field, value) => {
    this.setState({
      jwtVerifier: {
        ...this.state.jwtVerifier,
        [field]: value
      }
    })
  }

  prevStep = () => {
    if (this.state.step - 1 > 0)
      this.setState({ step: this.state.step - 1 });
  };

  nextStep = () => {
    this.setState({
      step: this.state.step + 1
    });
  };

  render() {
    const { step, steps, jwtVerifier } = this.state;

    const STEPS = [
      {
        component: InformationsStep,
        visibleOnStep: 1,
        props: {
          name: jwtVerifier.name,
          onChange: value => this.onChange('name', value)
        }
      },
      {
        component: StrategyStep,
        visibleOnStep: 2,
        large: true,
        props: {
          value: jwtVerifier.strategy?.type,
          onChange: value => {
            this.setState({
              jwtVerifier: {
                ...jwtVerifier,
                strategy: {
                  ...(jwtVerifier.strategy || {}),
                  type: value?.strategy
                }
              }
            })
          }
        }
      },
      {
        component: DefaultTokenStep,
        visibleOnStep: 3,
        large: true
      },
      {
        component: TokenSignatureStep,
        visibleOnStep: 4,
        props: {
          root: 'algoSettings',
          value: jwtVerifier,
          onChange: value => this.setState({ jwtVerifier: value })
        }
      },
      {
        component: TokenSignatureStep,
        visibleOnStep: 5,
        condition: value => ['Sign', 'Transform'].includes(value.strategy?.type),
        props: {
          value: jwtVerifier['strategy'],
          root: 'algoSettings',
          title: 'Resign token with',
          onChange: value => this.setState({
            jwtVerifier: {
              ...jwtVerifier,
              ['strategy']: value
            }
          })
        }
      },
      {
        component: TokenTransformStep,
        visibleOnStep: 6,
        condition: value => 'Transform' === value.strategy?.type,
        props: {
          value: jwtVerifier.strategy?.transformSettings,
          onChange: value => this.setState({
            jwtVerifier: {
              ...jwtVerifier,
              strategy: {
                ...(jwtVerifier.strategy || {}),
                transformSettings: {
                  location: value?.location ? value?.source : value?.out_location
                }
              }
            }
          })
        }
      }
    ];

    const showSummary = !STEPS.find(item => {
      return step === item.visibleOnStep && (item.condition ? item.condition(jwtVerifier) : true)
    })

    console.log(this.state, showSummary, jwtVerifier.strategy?.transformSettings || {
      location: true
    })

    return (
      <div className="wizard">
        <div
          className="wizard-container"
          style={{
            marginTop: STEPS[step - 1]?.large ? '2rem' : '5rem',
            maxWidth: STEPS[step - 1]?.large ? '60vw' : '50vw',
          }}>
          <div className='d-flex' style={{ flexDirection: 'column', padding: '2.5rem' }}>
            <label style={{ fontSize: '1.15rem' }}>
              <i
                className="fas fa-times me-3"
                onClick={this.props.hide}
                style={{ cursor: 'pointer' }}
              />
              <span>{`Create a new JWT Verifier`}</span>
            </label>
            <span>Name > Strategy > Location > Validation</span>

            <div className="wizard-content">
              {STEPS.map(({ component, visibleOnStep, props, condition }) => {
                if (step === visibleOnStep && (condition ? condition(jwtVerifier) : true)) {
                  return React.createElement(component, {
                    ...(props || {
                      value: jwtVerifier,
                      onChange: value => this.setState({ jwtVerifier: value })
                    }), key: component.Type
                  });
                } else {
                  return null;
                }
              })}
              {showSummary && <WizardLastStep value={jwtVerifier} />}
              <div className={`d-flex mt-5 align-items-center ${step !== 1 ? "justify-content-between" : "justify-content-end"}`}>
                {step !== 1 && <label style={{ color: '#f9b000' }} onClick={this.prevStep}>
                  <button className='btn btn-sm btn-outline-save'>Previous</button>
                </label>}
                <WizardStepButton
                  onClick={this.nextStep}
                  text={step === steps ? 'Create' : 'Continue'} />
              </div>
            </div>
          </div>
        </div>
      </div>
    )
  }
}

function WizardLastStep({ value }) {
  console.log(value)

  useEffect(() => {
    BackOfficeServices.createNewJwtVerifier()
      .then(template => {
        console.log({
          ...template,
          name: value.name,
          strict: value.strategy === 'StrictDefaultToken',
          source: value.source,
          algoSettings: {
            ...template.algoSettings,
            ...value.algoSettings,
          },
          strategy: {
            ...template.strategy,
            ...value.strategy,
          }
        })
        // BackOfficeServices.createJwtVerifier({
        //   ...template,
        //   name: value.name,
        //   strict: value.strategy === 'StrictDefaultToken',
        //   source: value.source,
        //   algoSettings: {
        //     type: convertAlgo[value.signature.algo],
        //     ...value.signature
        //   },
        //   strategy: {
        //     ...template.strategy,
        //     ...value.strategy,
        //   }
        // })
      })
  }, [])

  return (
    <>
      <h3 style={{ textAlign: 'center' }} className="mt-3">
        Creation in process ...
      </h3>
    </>
  )
}

function InformationsStep({ name, onChange }) {
  return (
    <>
      <h3>Let's start with a name for your JWT verifier</h3>

      <TextInput
        placeholder="Your verifier name..."
        flex={true}
        className="my-3"
        style={{
          fontSize: '2em',
          color: '#f9b000',
        }}
        label="Route name"
        value={name}
        onChange={onChange}
      />
    </>
  )
}

function StrategyStep({ value, onChange }) {

  const schema = {
    strategy: {
      renderer: props => {
        return <div
          style={{
            display: 'flex',
            gap: '10px',
            flexWrap:'wrap',
            justifyContent:'center'
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
            return <button
              type="button"
              className={`btn ${value === strategy ? 'btn-save' : 'btn-dark'} py-3 d-flex align-items-center flex-column col-3`}
              style={{
                gap: '12px'
              }}
              onClick={() => props.onChange(strategy)}
              key={strategy}
            >
              <div style={{ }}>
                {title.map((t, i) => <h3 className="wizard-h3--small " style={{
                  margin: 0,
                  marginTop: i > 0 ? '1px' : 0
                }} key={t}>{t}</h3>)}
              </div>
              <div className='d-flex flex-column align-items-center' style={{ }}>
                <label className='d-flex align-items-center' style={{ textAlign: 'left' }}>
                  {desc}
                </label>
                <div className='mt-3' style={{
                  borderRadius: '16px',
                  padding: '4px',
                  background: '#515151',
                  width: 'fit-content'
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
            </button>
          })}
        </div>
      }
    }
  }

  const flow = [
    'strategy'
  ];

  return (
    <>
      <h3>What kind of strategy will be used</h3>
      <NgForm
        value={value}
        schema={schema}
        flow={flow}
        onChange={onChange}
      />
    </>
  )
}

const TokenLocationForm = {
  schema: {
    Source: {
      type: 'form',
      schema: {
        type: {
          type: 'select',
          props: {
            ngOptions: {
              spread: true
            },
            options: ['Header', 'Query string', 'Cookie']
          }
        },
        name: {
          type: 'string',
          label: 'Header name'
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
                        label: 'Header name',
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
                    collapsed: true,
                    name: 'Bearer Token from header',
                    fields: ['header', 'result']
                  }
                ]} />
            </LabelAndInput>
          }
        },
        query: {
          type: 'string',
          placeholder: 'jwt-token',
          props: {
            ngOptions: {
              spread: true
            }
          }
        },
        cookie: {
          type: 'string',
          placeholder: 'jwt-token',
          props: {
            ngOptions: {
              spread: true
            }
          }
        }
      },
      flow: [
        'type',
        {
          type: 'group',
          visible: props => props?.type === 'Header',
          name: 'Header informations',
          fields: ['name', 'remove', 'debug']
        },
        {
          type: 'group',
          visible: props => props?.type === 'Query string',
          name: 'Query param name',
          fields: ['query']
        },
        {
          type: 'group',
          visible: props => props?.type === 'Cookie',
          name: 'Cookie name',
          fields: ['cookie']
        }
      ]
    }
  }
}

function DefaultTokenStep({ value, onChange }) {

  return (
    <>
      <h3>The location of the token</h3>
      <NgForm
        value={value}
        schema={TokenLocationForm.schema}
        flow={TokenLocationForm.flow}
        onChange={onChange}
      />
    </>
  )
}

function TokenSignatureStep({ root, value, onChange, title }) {

  const schema = {
    [root]: {
      type: 'form',
      label: 'Signature',
      schema: {
        algo: {
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
        },
        mtls_config: {
          type: 'form',
          noTitle: true,
          flow: [
            'mtls',
            'loose',
            'trustAll',
            'certs',
            'trustedCerts'
          ],
          schema: {
            mtls: {
              type: 'boolean',
              label: 'Custom TLS Settings'
            },
            loose: {
              type: 'boolean',
              label: 'TLS loose'
            },
            trustAll: {
              type: 'boolean',
              label: 'Trust all'
            },
            certs: {
              type: "array-select",
              props: {
                label: "Certificates",
                optionsFrom: "/bo/api/proxy/api/certificates",
                optionsTransformer: {
                  label: "name",
                  value: "id"
                }
              }
            },
            trustedCerts: {
              type: "array-select",
              props: {
                label: "Trusted certificates",
                optionsFrom: "/bo/api/proxy/api/certificates",
                optionsTransformer: {
                  label: "name",
                  value: "id"
                }
              }
            }
          }
        },
        proxy: {
          type: 'form',
          "flow": [
            "host",
            "port",
            "protocol",
            "principal",
            "password",
            "ntlmDomain",
            "encoding",
            "nonProxyHosts"
          ],
          "schema": {
            "host": {
              "type": "string"
            },
            "port": {
              "type": "number"
            },
            "protocol": {
              "type": "string"
            },
            "principal": {
              "type": "string"
            },
            "password": {
              "type": "string"
            },
            "ntlmDomain": {
              "type": "string"
            },
            "encoding": {
              "type": "string"
            },
            "nonProxyHosts": {
              "type": "string",
              array: true,
              "label": "Non proxy hosts"
            }
          }
        }
      },
      flow: (props, v) => {
        return {
          KidAlgoSettings: ['algo', 'onlyExposedCerts'],
          HSAlgoSettings: ['algo', 'size', 'secret', 'base64'],
          RSAlgoSettings: ['algo', 'size', 'publicKey', 'privateKey'],
          RSAKPAlgoSettings: ['algo', 'size', 'certId'],
          ESKPAlgoSettings: ['algo', 'size', 'certId'],
          ESAlgoSettings: ['algo', 'size', 'publicKey', 'privateKey'],
          JWKSAlgoSettings: [
            'algo',
            'url',
            'timeout',
            'ttl',
            'headers',
            'kty',
            {
              type: 'group',
              name: 'TLS settings for JWKS fetching',
              groupId: 'mtls_config',
              fields: ['mtls_config']
            },
            {
              type: 'group',
              name: 'Proxy',
              fields: ['proxy']
            }
          ],
          [undefined]: ['algo']
        }[v.value?.algo]
      }
    }
  }

  const flow = [root];

  return (
    <>
      <h3>{title || 'Generate token with'}</h3>

      <NgForm
        // useBreadcrumb={true}
        value={value}
        schema={schema}
        flow={flow}
        onChange={onChange}
      />
    </>
  )
}

function TokenTransformStep({ value, onChange }) {
  const schema = {
    location: {
      type: 'bool',
      label: 'Use the same location than the entry token',
      props: {
        defaultValue: true
      }
    },
    out_location: {
      visible: props => props?.location === false,
      label: 'New location',
      type: 'form',
      ...TokenLocationForm
    }
  }

  const flow = [
    'location',
    'out_location'
  ];

  console.log(value)

  return (
    <>
      <h3>Location of the generated token</h3>

      <NgForm
        value={value}
        schema={schema}
        flow={flow}
        onChange={onChange}
      />
    </>
  )
}