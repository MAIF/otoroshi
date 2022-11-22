import React, { useEffect, useState } from 'react';
import * as BackOfficeServices from '../../services/BackOfficeServices';
import { TextInput } from '../../components/inputs';
import { LabelAndInput, NgCodeRenderer, NgForm, NgObjectRenderer, NgSelectRenderer, NgStringRenderer } from '../../components/nginputs';
import { Button } from '../../components/Button';
import { SquareButton } from '../../components/SquareButton';
import Loader from '../../components/Loader';
import { Dropdown } from '../../components/Dropdown';
import faker from 'faker';
import bcrypt from 'bcryptjs';
import { useHistory } from 'react-router-dom';
import JwtVerifierForm from '../entities/JwtVerifier';
import { JwtVerifier } from '../../components/JwtVerifier';
import { FeedbackButton } from '../../pages/RouteDesigner/FeedbackButton';
import { v4 as uuid } from 'uuid';
import { FakeLoader } from './FakeLoader';
import { AuthModuleConfig } from '../../components/AuthModuleConfig';

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

function Breadcrumb({ value, onClick }) {
  return <div className='d-flex'>{value.map((part, i) => {
    return <span
      key={part}
      style={{
        cursor: 'pointer',
        maxWidth: 200,
        whiteSpace: 'pre',
        textOverflow: 'ellipsis',
        overflow: 'hidden'
      }}
      onClick={() => onClick(i)}>
      {part}
      {(i + 1) < value.length && <i className='fas fa-chevron-right mx-1' />}
    </span>
  })}</div>
}

function Header({ onClose, mode }) {
  return <label style={{ fontSize: '1.15rem' }}>
    <i
      className="fas fa-times me-3"
      onClick={onClose}
      style={{ cursor: 'pointer' }}
    />
    <span>
      {mode === 'selector' && 'Authentication wizard'}
      {mode === 'creation' && 'Create a new Authentication config'}
      {['edition', 'clone', 'continue'].includes(mode) && 'Update the new Authentication config'}
      {mode === 'update_in_wizard' && 'Update the authentication configuration'}
    </span>
  </label>
}

function WizardActions({ nextStep, prevStep, step, goBack }) {
  return <div className="d-flex mt-auto justify-content-between align-items-center">
    <Button
      type='save'
      onClick={step !== 1 ? prevStep : goBack}
      text="Previous"
    />
    <WizardStepButton
      className="ms-auto"
      onClick={nextStep}
      text='Continue' />
  </div>
}

function Selector({ setMode, disableSelectMode }) {
  return <div className='p-3 w-75'>
    <h3>Getting started</h3>
    <div className='d-flex flex-column'>
      {[
        { title: 'NEW', text: 'Create a new Authentication', mode: 'creation' },
        { title: 'SELECT', text: 'Use an existing Authentication', mode: 'edition', disabled: disableSelectMode },
        { title: 'CLONE', text: 'Create a new one fron an existing Authentication', mode: 'clone' }
      ].map(({ title, text, mode, disabled }) => disabled ? null : <Button
        key={mode}
        type='dark'
        className="py-3 my-2"
        style={{ border: '1px solid #f9b000' }}
        onClick={() => setMode(mode)}>
        <h3 className="wizard-h3--small" style={{
          textAlign: 'left',
          fontWeight: 'bold'
        }}>{title}</h3>
        <label className='d-flex align-items-center justify-content-between'
          style={{ flex: 1 }}>
          {text}
          <i className='fas fa-chevron-right ms-3' />
        </label>
      </Button>
      )}
    </div>
  </div>
}

function AuthenticationSelector({ handleSelect, mode }) {
  const [authentications, setAuthentications] = useState([]);

  useEffect(() => {
    BackOfficeServices.findAllAuthConfigs()
      .then(r => setAuthentications(r.data))
  }, []);

  return <div className='d-flex flex-column mt-3' style={{ flex: 1 }}>
    <div className='d-flex align-items-center justify-content-between'>
      <h3>Select {mode === 'clone' ? 'the authentication configuration to clone' : 'a authentication configuration'}</h3>
    </div>
    <div style={{ maxHeight: '36px' }} className="mt-3">
      <NgSelectRenderer
        placeholder="Select a authentication configuration to continue"
        ngOptions={{
          spread: true
        }}
        onChange={id => {
          handleSelect(authentications.find(v => v.id === id))
        }}
        options={authentications}
        optionsTransformer={arr => arr.map(item => ({ value: item.id, label: item.name }))} />
    </div>
  </div>
}

function GoBackSelection({ goBack }) {
  return <div className="d-flex mt-auto justify-content-between align-items-center m-@">
    <Button type='info'
      className='d-flex align-items-center'
      onClick={goBack}>
      <i className='fas fa-chevron-left me-2' />
      <p className='m-0'>Previous</p>
    </Button>
  </div>
}

export class AuthenticationWizard extends React.Component {
  state = {
    step: 1,
    mode: this.props.mode || 'selector',
    authenticationConfig: this.props.authentication || {
      name: ''
    },
    breadcrumb: ['Informations']
  }

  onChange = (field, value, callback = () => { }) => {
    this.setState({
      authenticationConfig: {
        ...this.state.authenticationConfig,
        [field]: value
      }
    }, callback);
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

  updateBreadcrumb = (value, i) => {
    if (i >= this.state.breadcrumb.length) {
      this.setState({
        breadcrumb: [...this.state.breadcrumb, value]
      });
    }
    else {
      this.setState({
        breadcrumb: this.state.breadcrumb.map((v, j) => {
          if (j === i)
            return value
          return v
        })
      })
    }
  }

  render() {
    const { step, authenticationConfig, mode } = this.state;

    if (mode === 'update_in_wizard') {
      return <div className="wizard">
        <div className="wizard-container">
          <div className='d-flex' style={{ flexDirection: 'column', padding: '2.5rem', flex: 1 }}>
            <Header onClose={this.props.hide} mode={mode} />
            <div className="wizard-content">
              <AuthModuleConfig
                value={authenticationConfig}
                onChange={authenticationConfig => this.setState({ authenticationConfig })} />

              <div className="d-flex mt-auto ms-auto justify-content-between align-items-center">
                <FeedbackButton
                  style={{
                    backgroundColor: '#f9b000',
                    borderColor: '#f9b000',
                    padding: '12px 48px'
                  }}
                  onPress={() => BackOfficeServices.updateAuthConfig(authenticationConfig)}
                  onSuccess={this.props.hide}
                  icon={() => <i className='fas fa-paper-plane' />}
                  text="Save the authentication configuration"
                />
              </div>
            </div>
          </div>
        </div>
      </div>
    } else {
      const STEPS = [
        {
          component: InformationsStep,
          props: {
            name: authenticationConfig.name,
            onChange: value => {
              this.onChange('name', value)
              this.updateBreadcrumb(value, 0);
            }
          }
        },
        {
          component: TypeStep,
          props: {
            value: authenticationConfig.type,
            onChange: type => {
              if (type) {
                if (type !== authenticationConfig.type)
                  this.nextStep();
                this.onChange('type', type, () => {
                  BackOfficeServices.createNewAuthConfig(type)
                    .then(template => {
                      this.setState({
                        ...template,
                        ...authenticationConfig
                      }, () => {
                        this.setState({
                          breadcrumb: [this.state.breadcrumb[0], type]
                        });
                      })
                    });
                });
              }
            }
          }
        },
        {
          component: OAuth2PreConfiguration,
          index: 3,
          condition: value => 'oauth2' === value.type,
          props: {
            value: authenticationConfig,
            onChange: configuration => {
              this.setState({
                authenticationConfig: {
                  ...authenticationConfig,
                  configuration
                }
              }, () => {
                this.updateBreadcrumb(configuration, 2);
                this.nextStep();
              })
            }
          }
        },
        {
          component: OAuth1Configuration,
          index: 3,
          condition: value => 'oauth1' === value.type,
          props: {
            value: authenticationConfig,
            onChange: authenticationConfig => {
              this.setState({ authenticationConfig }, () => {
                this.updateBreadcrumb('configuration', 2);
              })
            }
          }
        },
        {
          component: InMemoryConfiguration,
          index: 3,
          condition: value => 'basic' === value.type,
          props: {
            value: authenticationConfig,
            onChange: authenticationConfig => {
              this.setState({ authenticationConfig }, () => {
                this.updateBreadcrumb('configuration', 2);
              })
            }
          }
        },
        {
          component: LdapConfiguration,
          index: 3,
          condition: value => 'ldap' === value.type,
          props: {
            value: authenticationConfig,
            onChange: authenticationConfig => {
              this.setState({ authenticationConfig }, () => {
                this.updateBreadcrumb('configuration', 2);
              })
            }
          }
        },
        {
          component: SAMLConfiguration,
          index: 3,
          condition: value => 'saml' === value.type,
          props: {
            value: authenticationConfig,
            onChange: authenticationConfig => {
              this.setState({ authenticationConfig }, () => {
                this.updateBreadcrumb('configuration', 2);
              })
            }
          }
        },
        {
          component: OAuth2RawConfiguration,
          condition: value => 'oauth2' === value.type && "raw-config" === value.configuration,
          index: 4
        },
        {
          component: OAuth2FastConfiguration,
          condition: value => 'oauth2' === value.type && "fast-config" === value.configuration,
          index: 4
        }
      ]
        .filter(item => item.hide === undefined)

      const showSummary = !STEPS.find((item, i) => {
        return (step === (i + 1) || step === item.index) && (item.condition ? item.condition(authenticationConfig) : true)
      });

      return (
        <div className="wizard">
          <div className="wizard-container">
            <div className='d-flex' style={{ flexDirection: 'column', padding: '2.5rem', flex: 1 }}>
              <Header onClose={this.props.hide} mode={mode} />

              {mode === 'selector' && <Selector setMode={mode => this.setState({ mode })} disableSelectMode={this.props.disableSelectMode} />}

              {mode !== 'selector' && <>
                {['edition', 'clone'].includes(mode) ?
                  <AuthenticationSelector
                    mode={mode}
                    handleSelect={authentication => {
                      if (this.props.onConfirm && mode === 'edition') {
                        this.props.onConfirm(authentication.id);
                      } else {
                        this.setState({
                          mode: 'continue',
                          authenticationConfig: {
                            ...authentication,
                            id: `auth_mod_${uuid()}`
                          }
                        })
                      }
                    }} /> :
                  <>
                    <Breadcrumb value={this.state.breadcrumb} onClick={i => this.setState({ step: i + 1 })} />
                    <div className="wizard-content">
                      {STEPS.map(({ component, props, condition, onChange, index }, i) => {
                        if ((step === (i + 1) || step === index) && (condition ? condition(authenticationConfig) : true)) {
                          const defaultProps = {
                            value: authenticationConfig,
                            onChange: value => this.setState({ authenticationConfig: value }, onChange)
                          };

                          const allProps = props ? {
                            ...props,
                            onChange: e => props.onChange(e, i)
                          } : defaultProps;

                          return React.createElement(component, { key: component.name, ...allProps });
                        } else {
                          return null;
                        }
                      })}
                      {showSummary && <WizardLastStep
                        onConfirm={this.props.onConfirm}
                        breadcrumb={this.state.breadcrumb}
                        value={{
                          ...authenticationConfig,
                          strategy: {
                            ...authenticationConfig.strategy,
                            transformSettings: authenticationConfig.strategy?.type === 'Transform' ? {
                              location: authenticationConfig.strategy?.transformSettings?.location ? authenticationConfig.source : authenticationConfig.strategy?.transformSettings?.out_location?.source
                            } : undefined
                          }
                        }} />}
                      {!showSummary && <WizardActions
                        nextStep={this.nextStep}
                        prevStep={this.prevStep}
                        step={step}
                        goBack={() => {
                          this.setState({
                            mode: this.props.mode || 'selector'
                          })
                        }} />}
                    </div>
                  </>}
              </>}
              {['edition', 'clone'].includes(mode) && <GoBackSelection goBack={() => {
                this.setState({
                  mode: this.props.mode || 'selector'
                })
              }} />}
            </div>
          </div>
        </div>
      );
    }
  }
}

function WizardLastStep({ value, breadcrumb, onConfirm }) {
  const [authentication, setAuthentication] = useState();
  const history = useHistory();

  const [error, setError] = useState(false);
  const [creating, setCreating] = useState(false);

  const create = () => {
    setCreating(true);
    return BackOfficeServices.createNewAuthConfig(value.type)
      .then(template => {
        console.log(template)
        BackOfficeServices.createAuthConfig({
          ...template,
          ...value
        })
          .then(res => {
            if (res.error) {
              setError(true);
            } else if (onConfirm) {
              onConfirm(res.id)
            } else {
              setAuthentication(res);
            }
          })
      })
  }

  return (
    <>
      <h3 style={{ textAlign: 'center' }} className="mt-3">
        Summary
      </h3>

      <div className='d-flex mx-auto' style={{
        flexDirection: 'column'
      }}>
        {breadcrumb.map((part, i) => {
          return <FakeLoader
            key={part}
            started={creating}
            text={i === 0 ? `Informations` : part}
            timeout={1000 + i * 250} />
        })}
      </div>

      {!creating && <Button type='save'
        className='mx-auto mt-3'
        onClick={create}
      >
        <i className='fas fa-check me-1' />
        Confirm
      </Button>}

      {(authentication || error) && <Button type='save'
        className='mx-auto mt-3'
        disabled={error}
        onClick={() => history.push(`/auth-configs/edit/${authentication.id}`)}
      >
        <i className={`fas fa-${error ? 'times' : 'check'} me-1`} />
        {error ? 'Something wrong happened : try to check your configuration' : 'Check the created authentication configuration'}
      </Button>}
    </>
  )
}

function InformationsStep({ name, onChange }) {
  return (
    <>
      <h3>Let's start with a name for your Authentication</h3>

      <div>
        <TextInput
          autoFocus={true}
          placeholder="Your authentication configuration name..."
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
      </div>
    </>
  )
}

function TypeStep({ value, onChange }) {
  const PROVIDERS = [
    {
      type: 'oauth2',
      title: 'OAuth2 / OIDC provider',
      desc: 'OAuth 2.0 is the industry-standard protocol for authorization. OAuth 2.0 focuses on client developer simplicity while providing specific authorization flows for web applications, desktop applications, mobile phones, and living room devices.'
    },
    {
      type: 'oauth1',
      title: 'OAuth1 provider',
      desc: 'OAuth is an authorization method used to provide access to resources over the HTTP protocol.'
    },
    {
      type: 'basic',
      title: 'In memory provider',
      desc: 'This database provider allows Otoroshi to be used with an in-memory database. While some users use the in-memory database for testing, this is generally discouraged'
    },
    {
      type: 'ldap',
      title: 'Ldap auth. provider',
      desc: 'The Lightweight Directory Access Protocol is an open, vendor-neutral, industry standard application protocol for accessing and maintaining distributed directory information services over an Internet Protocol (IP) network.'
    },
    {
      type: 'saml',
      title: 'SAML v2 provider',
      desc: 'Security Assertion Markup Language 2.0 is a version of the SAML standard for exchanging authentication and authorization identities between security domains.'
    }
  ];

  const schema = {
    type: {
      renderer: () => {
        return <div
          style={{
            display: 'flex',
            gap: '10px',
            flexWrap: 'wrap',
            justifyContent: 'flex-start'
          }}>
          {PROVIDERS.map(({ type, desc, title }) => <SelectableButton
            value={type}
            expected={value}
            title={title}
            desc={desc}
            onChange={onChange}
          />)}
        </div>
      }
    }
  }

  return (
    <>
      <h3>Choose your provider</h3>
      <NgForm
        value={value}
        schema={schema}
        flow={[
          'type'
        ]}
        onChange={() => { }}
      />
    </>
  )
}

function OAuth2PreConfiguration({ value, onChange }) {

  const schema = {
    configuration: {
      renderer: () => {
        return <div
          style={{
            display: 'flex',
            gap: '10px',
            flexWrap: 'wrap',
            justifyContent: 'flex-start'
          }}>
          {[
            {
              type: 'fast-config',
              title: 'Fast configuration',
              desc: 'Get configuration provider from OIDC or Keycloak url'
            },
            {
              type: 'raw-config',
              title: 'Raw configuration',
              desc: 'Define all fields in the configuration from scratch'
            }
          ].map(({ type, desc, title }) => <SelectableButton
            value={type}
            expected={value}
            title={title}
            desc={desc}
            onChange={onChange}
          />)}
        </div>
      }
    }
  }

  return <>
    <h3>Let's start with the OAuth2 configuration</h3>
    <NgForm
      value={value}
      schema={schema}
      flow={[
        'configuration'
      ]}
      onChange={() => { }}
    />
  </>
}

function OAuth2RawConfiguration({ value, onChange, hideTitle }) {

  const schema = {
    clientId: {
      type: 'string',
      label: 'Client ID'
    },
    clientSecret: {
      type: 'string',
      label: 'Client Secret'
    },
    authorizeUrl: {
      type: 'string',
      label: 'Authorize URL'
    },
    tokenUrl: {
      type: 'string',
      label: 'Token URL'
    },
    introspectionUrl: {
      type: 'string',
      label: 'Introspection URL'
    },
    userInfoUrl: {
      type: 'string',
      label: 'Userinfo URL'
    },
    loginUrl: {
      type: 'string',
      label: 'Login URL'
    },
    logoutUrl: {
      type: 'string',
      label: 'Logout URL'
    },
    callbackUrl: {
      type: 'string',
      label: 'Callback URL'
    },
    accessTokenField: {
      type: 'string',
      label: 'Access token field name'
    },
    scope: {
      type: 'string',
      label: "Scope"
    }
  };

  const flow = [
    {
      type: 'group',
      name: 'Credentials',
      collapsable: false,
      fields: [
        'clientId',
        'clientSecret'
      ]
    },
    {
      type: 'group',
      name: 'URLs',
      collapsable: false,
      fields: [
        'clientSecret',
        'authorizeUrl',
        'tokenUrl',
        'introspectionUrl',
        'userInfoUrl',
        'loginUrl',
        'logoutUrl',
        'callbackUrl'
      ]
    },
    {
      type: 'group',
      name: 'Token',
      collapsable: false,
      fields: ['accessTokenField', 'scope']
    }
  ];

  return <>
    {!hideTitle && <h3>OAuth2 configuration</h3>}
    <NgForm
      schema={schema}
      flow={flow}
      value={value}
      onChange={onChange}
    />
  </>
}

function OAuth2FastConfiguration({ value, onChange }) {

  const schema = {
    source: {
      type: 'dots',
      label: 'Source',
      props: {
        options: ['OIDC config', 'Keycloak config']
      }
    },
    url: {
      type: 'string',
      label: 'URL of the OIDC config',
      visible: props => props.source === 'OIDC config',
    },
    keycloakContent: {
      type: 'code',
      label: 'Keycloak configuration',
      visible: props => props.source === 'Keycloak config',
      props: {
        label: 'Value',
        editorOnly: true
      }
    },
    fetchConfig: {
      renderer: props => {
        if (props.rootValue?.url?.length > 0 || props.rootValue?.keycloakContent?.length > 0) {
          return <LabelAndInput label=" ">
            <FeedbackButton
              style={{
                backgroundColor: '#f9b000',
                borderColor: '#f9b000'
              }}
              onPress={'OIDC config' === props.rootValue?.source ? fetchConfig : fetchKeycloakConfig}
              icon={() => <i className='fas fa-paper-plane' />}
              text="Fetch configuration"
            />
          </LabelAndInput>
        } else {
          return null;
        }
      }
    }
  }

  const flow = [
    {
      type: 'group',
      collapsable: false,
      name: ' ',
      fields: [
        'source',
        'url',
        'keycloakContent',
        'fetchConfig'
      ]
    }
  ]

  const call = (url, body) => {
    return fetch(url, {
      method: 'POST',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(body)
    })
      .then(r => {
        if (r.status === 500) {
          throw "Can't fetch URL";
        } else {
          return r;
        }
      })
      .then((r) => r.json())
      .then(r => {
        onChange({
          ...r,
          configuration: value.configuration,
          name: value.name,
          source: value.source,
          type: value.type,
          url: value.url
        })
      });
  }

  const fetchConfig = () => {
    return call(`/bo/api/oidc/_fetchConfig`, {
      url: value.url,
      ...value
    });
  };

  const fetchKeycloakConfig = () => {
    const config = JSON.parse(value.keycloakContent);
    const serverUrl = config['auth-server-url'];
    const realm = config.realm;
    const configUrl = `${serverUrl}/realms/${realm}/.well-known/openid-configuration`;
    const clientId = config.resource;
    const clientSecret = (config.credential && config.credentials.secret) ? config.credentials.secret : '';
    return call(`/bo/api/oidc/_fetchConfig`, {
      url: configUrl,
      ...value,
      clientId,
      clientSecret
    });
  }

  return <>
    <h3>Get configuration from</h3>

    <NgForm
      value={value}
      schema={schema}
      flow={flow}
      onChange={onChange}
    />

    {Object.keys(value).length > 5 &&
      <OAuth2RawConfiguration value={value} onChange={onChange} hideTitle={true} />
    }
  </>
}

function OAuth1Configuration({ value, onChange }) {
  const schema = {
    httpMethod: {
      type: 'dots',
      label: 'Http Method',
      props: {
        defaultValue: 'get',
        help: 'Method used to get request_token and access token',
        options: ['post', 'get']
      },
    },
    consumerKey: {
      type: 'string',
      label: 'Consumer key'
    },
    consumerSecret: {
      type: 'string',
      label: 'Consumer secret'
    },
    requestTokenURL: {
      type: 'string',
      label: 'Request Token URL'
    },
    authorizeURL: {
      type: 'string',
      label: 'Authorize URL'
    },
    accessTokenURL: {
      type: 'string',
      label: 'Access token URL'
    },
    profileURL: {
      type: 'string',
      label: 'Profile URL'
    },
    callbackURL: {
      type: 'string',
      label: 'Callback URL',
      props: {
        subTitle: 'Endpoint used to get back user after authentication on provider'
      }
    }
  }

  return <>
    <h3>Let's start with the OAuth1 configuration</h3>
    <NgForm
      value={value}
      schema={schema}
      flow={[
        {
          type: 'group',
          name: 'Credentials',
          fields: [
            'httpMethod',
            'consumerKey',
            'consumerSecret'
          ]
        },
        {
          type: 'group',
          name: 'URLs',
          fields: [
            'requestTokenURL',
            'authorizeURL',
            'accessTokenURL',
            'profileURL',
            'callbackURL'
          ]
        }
      ]}
      onChange={onChange}
    />
  </>
}

function InMemoryConfiguration({ value, onChange }) {

  const addUser = () => {
    const firstName = faker.name.firstName();
    const lastName = faker.name.lastName();
    onChange({
      ...value,
      users: [
        ...(value.users || []),
        {
          name: `${firstName} ${lastName}`,
          email: `${firstName.toLowerCase()}.${lastName.toLowerCase()}@oto.tools`,
          metadata: {}
        }
      ]
    });
  }

  const onUsersChange = (user, i) => {
    onChange({
      ...value,
      users: (value.users || []).map((u, j) => (i === j) ? user : u)
    })
  }

  const removeUser = i => {
    onChange({
      ...value,
      users: (value.users || []).filter((_, j) => (i !== j))
    })
  }

  return <div>
    <h3>In memory configuration</h3>

    <div>
      <div className='d-flex mb-3'>
        <label style={{ flex: 1 }}>Name</label>
        <label style={{ flex: 1 }}>Email</label>
        <label style={{ minWidth: '84px' }} className="text-center">Has Password?</label>
        <label style={{ minWidth: '84px' }} className="text-center" >Actions</label>
      </div>
      {(value.users || []).map((user, i) => <User {...user}
        key={`user-${i}`}
        removeUser={() => removeUser(i)}
        onChange={user => onUsersChange(user, i)} />)}

      {(value.users || []).length === 0 && <p>No users.</p>}

      <Button className='btn-sm mt-3' onClick={addUser}>
        <i className="fas fa-plus-circle me-2" /> Add user
      </Button>
    </div>
  </div>
}

function GetPassword({ password }) {
  const [copied, setCopied] = useState(false);

  return <div>
    <p>The generated password is</p>
    <div className="d-flex align-items-center justify-content-center p-3"
      style={{
        fontWeight: 'bold',
        background: '#494949',
        borderRadius: '4px',
        position: 'relative'
      }}>
      <p className='m-0'>{password}</p>

      <Button
        style={{
          position: 'absolute',
          margin: 'auto',
          right: '6px',
          border: '1px solid #eee',
          padding: '8px',
          borderRadius: '4px'
        }}
        onClick={() => {
          setCopied(true);
          setTimeout(() => {
            setCopied(false)
          }, 450);

          const el = document.createElement('textarea');
          el.value = password;
          el.setAttribute('readonly', '');
          el.style.position = 'absolute';
          el.style.left = '-9999px';
          document.body.appendChild(el);
          el.select();
          document.execCommand('copy');
          document.body.removeChild(el);
        }}>
        {!copied && <i className="fas fa-copy" />}
        {copied && <i className="fas fa-check" />}
      </Button>
    </div>
  </div>
}

class User extends React.Component {
  state = {
    rawUser: JSON.stringify(this.props.metadata)
  };

  handleErrorWithMessage = (message) => () => {
    this.setState({ error: message });
  };

  registerWebAuthn = (e) => {
    if (e && e.preventDefault) {
      e.preventDefault();
    }

    const username = this.props.email;
    const label = this.props.name;

    return this.props.save().then(() => {
      return fetch(`/bo/api/proxy/api/auths/${this.props.authModuleId}/register/start`, {
        method: 'POST',
        credentials: 'include',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          username,
          password: '',
          label,
          origin: window.location.origin,
        }),
      })
        .then((r) => r.json())
        .then((resp) => {
          const requestId = resp.requestId;
          const publicKeyCredentialCreationOptions = { ...resp.request };
          const handle = publicKeyCredentialCreationOptions.user.id + '';
          publicKeyCredentialCreationOptions.challenge = base64url.decode(
            publicKeyCredentialCreationOptions.challenge
          );
          publicKeyCredentialCreationOptions.user.id = base64url.decode(
            publicKeyCredentialCreationOptions.user.id
          );
          publicKeyCredentialCreationOptions.excludeCredentials = publicKeyCredentialCreationOptions.excludeCredentials.map(
            (c) => {
              return { ...c, id: base64url.decode(c.id) };
            }
          );
          return navigator.credentials
            .create(
              {
                publicKey: publicKeyCredentialCreationOptions,
              },
              this.handleErrorWithMessage('Webauthn error')
            )
            .then((credentials) => {
              const json = responseToObject(credentials);
              return fetch(`/bo/api/proxy/api/auths/${this.props.authModuleId}/register/finish`, {
                method: 'POST',
                credentials: 'include',
                headers: {
                  Accept: 'application/json',
                  'Content-Type': 'application/json',
                },
                body: JSON.stringify({
                  requestId,
                  webauthn: json,
                  otoroshi: {
                    origin: window.location.origin,
                    username,
                    password: '',
                    label,
                    handle,
                  },
                }),
              })
                .then((r) => r.json())
                .then((resp) => {
                  this.props.updateAll();
                  console.log('done');
                  this.setState({
                    error: null,
                    message: `Registration done for '${username}'`,
                  });
                });
            }, this.handleErrorWithMessage('Webauthn error'))
            .catch(this.handleError);
        });
    });
  };

  hashPassword = (password) => {
    this.props.onChange({
      ...this.props,
      password: bcrypt.hashSync(password, bcrypt.genSaltSync(10))
    })
  };

  setPassword = () => {
    window.newPrompt('Type password', { type: 'password' })
      .then((value1) => {
        window.newPrompt('Re-type password', { type: 'password' })
          .then((value2) => {
            if (value1 && value2 && value1 === value2) {
              this.hashPassword(value1);
            } else {
              window.newAlert('Passwords does not match !', 'Error');
            }
          });
      });
  }

  generatePassword = () => {
    const password = faker.random.alphaNumeric(16);
    this.hashPassword(password);
    window.newAlert(() => <GetPassword password={password} />, 'Generated password');
  }

  render() {
    const { email, name, metadata, password } = this.props;

    return (
      <div className='mb-1'>
        <div className='d-flex'>
          <NgStringRenderer
            inputStyle={{ border: "none", flex: 1, marginRight: ".25em" }}
            ngOptions={{
              spread: true
            }}
            value={name}
            onChange={name => this.props.onChange({
              ...this.props.user,
              name
            })}
          />
          <NgStringRenderer
            inputStyle={{ flex: 1, border: "none" }}
            ngOptions={{
              spread: true
            }}
            value={email}
            onChange={email => this.props.onChange({
              ...this.props.user,
              email
            })}
          />
          <div className='d-flex align-items-center justify-content-center' style={{ minWidth: '84px' }}>
            <i className={`fas fa-${password ? 'check' : 'times'}`} />
          </div>
          <div style={{ minWidth: '84px' }} className='d-flex align-items-center justify-content-center'>
            <Dropdown>
              <SquareButton
                className='btn-sm'
                type='danger'
                onClick={this.props.removeUser}
                text="Remove user"
                icon="fa-trash" />
              <SquareButton
                className='btn-sm'
                onClick={this.generatePassword}
                text="Generate password"
                icon="fa-cog" />
            </Dropdown>
          </div>
        </div>
        {/* <div className="col-12">
          <div className="row mb-3">
            <label for="input-Name" className="col-xs-12 col-sm-2 col-form-label">
              Metadata
            </label>
            <div className="col-sm-10 d-flex">
              <input
                type="text"
                placeholder="User metadata"
                className="form-control"
                value={
                  this.state.rawUser !== JSON.stringify(metadata)
                    ? this.state.rawUser
                    : JSON.stringify(metadata)
                }
                onChange={(e) => {
                  try {
                    const finalValue = JSON.parse(e.target.value);
                    this.setState({ rawUser: JSON.stringify(finalValue) });
                    this.props.onChange(email, 'metadata', finalValue);
                  } catch (err) {
                    this.setState({ rawUser: e.target.value });
                  }
                }}
              />
            </div>
          </div>
        </div> */}
      </div>
    );
  }
}

function SelectableButton({ value, expected, title, desc, onChange }) {
  return <Button
    type={value === expected ? 'save' : 'dark'}
    className="py-3 d-flex align-items-center flex-column col-3"
    style={{
      gap: '12px',
      minHeight: '325px',
      maxWidth: '235px'
    }}
    onClick={() => onChange(value)}
    key={value}
  >
    <div style={{ flex: .2 }}>
      <h3 className="wizard-h3--small " style={{ margin: 0 }}>
        {title}
      </h3>
    </div>
    <div className='d-flex flex-column align-items-center' style={{ flex: 1 }}>
      <label className='d-flex align-items-center' style={{ textAlign: 'left' }}>
        {desc}
      </label>
    </div>
  </Button>
}

function LdapConfiguration({ value, onChange }) {

  const schema = {
    serverUrls: {
      type: 'string',
      array: true,
      label: "LDAP Server URL",
      props: {
        subTitle: "Set your LDAP server"
      }
    },
    searchBase: {
      type: 'string',
      label: 'Search base',
      props: {
        subTitle: 'Example: dc=example,dc=com'
      }
    },
    userBase: {
      type: 'string',
      label: 'Users search base'
    },
    searchFilter: {
      type: 'string',
      label: 'Search Filter',
      props: {
        subTitle: "Example: (uid=${username})"
      }
    },
    adminUsername: {
      type: 'string',
      label: "Admin username (bind DN)",
      props: {
        subTitle: "Example: cn=read-only-admin,dc=example,dc=com"
      }
    },
    adminPassword: {
      type: 'password',
      label: "Admin password",
      props: {
        subTitle: "Example: password"
      }
    },
    nameField: {
      type: 'string',
      label: 'Name field name',
      props: {
        subTitle: "Example: cn"
      }
    },
    emailField: {
      type: 'string',
      label: 'Email field name',
      props: {
        subTitle: "Example: email"
      }
    }
  }

  const flow = [
    {
      type: 'group',
      name: 'Server URLs',
      collapsable: false,
      fields: ['serverUrls']
    },
    {
      type: 'group',
      name: 'Search bases',
      collapsable: false,
      fields: ['searchBase', 'userBase', 'searchFilter']
    },
    {
      type: 'group',
      name: 'Admin credentials',
      collapsable: false,
      fields: ['adminUsername', 'adminPassword']
    },
    {
      type: 'group',
      name: 'Extracted fields',
      collapsable: false,
      fields: ['nameField', 'emailField']
    },
  ];

  return <>
    <h3>LDAP Configuration</h3>
    <NgForm
      value={value}
      flow={flow}
      schema={schema}
      onChange={onChange}
    />
  </>
}

function SAMLConfiguration({ value, onChange }) {

  const _fetchConfig = (body) => {
    return fetch('/bo/api/saml/_fetchConfig', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(body),
    }).then((res) => {
      res.json()
        .then((r) => {
          if (res.status >= 400)
            throw "Can't fetch data"
          else
            onChange({
              ...r,
              from: 'Raw values',
              configuration: value.configuration,
              name: value.name,
              source: value.source,
              type: value.type,
              url: value.url
            });
        });
    });
  };

  const fetchFromURL = () => _fetchConfig({ url: value.url });

  const fetchConfig = () => _fetchConfig({ xml: value.pasteConfig })

  const schema = {
    from: {
      type: 'dots',
      label: 'Source',
      props: {
        options: [
          'Get entity descriptor from URL',
          'Paste configuration',
          'Raw values'
        ]
      }
    },
    url: {
      type: 'string',
      visible: props => props.from === 'Get entity descriptor from URL',
      label: 'URL'
    },
    pasteConfig: {
      type: 'code',
      visible: props => props.from === 'Paste configuration',
      label: 'Configuration',
      props: {
        editorOnly: true,
        mode: 'xml'
      }
    },
    singleSignOnUrl: {
      type: 'string',
      label: 'Single sign on URL'
    },
    ssoProtocolBinding: {
      type: 'dots',
      label: 'The protocol binding for the login request',
      props: {
        defaultValue: 'post',
        options: [
          { value: 'post', label: 'Post' },
          { value: 'redirect', label: 'Redirect' },
        ],
      },
    },
    singleLogoutUrl: {
      type: 'string',
      label: 'Single Logout URL'
    },
    singleLogoutProtocolBinding: {
      type: 'dots',
      label: 'The protocol binding for the logout request',
      props: {
        defaultValue: 'post',
        options: [
          { value: 'post', label: 'Post' },
          { value: 'redirect', label: 'Redirect' },
        ],
      },
    },
    nameIDFormat: {
      type: 'dots',
      label: 'Name ID Format',
      props: {
        options: [
          { value: 'unspecified', label: 'Unspecified' },
          { value: 'emailAddress', label: 'Email address' },
          { value: 'persistent', label: 'Persistent' },
          { value: 'transient', label: 'Transient' },
          { value: 'kerberos', label: 'Kerberos' },
          { value: 'entity', label: 'Entity' },
        ],
      },
    },
    issuer: {
      type: 'string',
      label: 'URL issuer'
    },
    fetchConfig: {
      renderer: props => {
        if (value.url?.length > 0 || value.pasteConfig?.length > 0) {
          return <LabelAndInput label=" ">
            <FeedbackButton
              style={{
                backgroundColor: '#f9b000',
                borderColor: '#f9b000'
              }}
              onPress={'Paste configuration' === value.from ? fetchConfig : fetchFromURL}
              icon={() => <i className='fas fa-paper-plane' />}
              text="Fetch configuration"
            />
          </LabelAndInput>
        } else {
          return null;
        }
      }
    }
  }

  const flow = [
    {
      type: 'group',
      name: 'Source',
      collapsable: false,
      fields: [
        'from',
        'url',
        'pasteConfig',
        'fetchConfig'
      ]
    },
    {
      type: 'group',
      visible: () => value?.length > 5 || value?.from === 'Raw values',
      name: 'Informations',
      collapsable: false,
      fields: [
        'singleSignOnUrl',
        'ssoProtocolBinding',
        'singleLogoutUrl',
        'singleLogoutProtocolBinding',
        'nameIDFormat',
        'issuer'
      ]
    }
  ];

  return <>
    <h3>SAML V2 Configuration</h3>

    <NgForm
      value={value}
      schema={schema}
      onChange={onChange}
      flow={flow}
    />
  </>
}