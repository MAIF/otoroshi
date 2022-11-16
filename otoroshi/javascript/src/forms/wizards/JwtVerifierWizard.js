import React, { useEffect, useState } from 'react';
import * as BackOfficeServices from '../../services/BackOfficeServices';
import { TextInput } from '../../components/inputs';
import { LabelAndInput, NgForm, NgSelectRenderer } from '../../components/nginputs';
import { Button } from '../../components/Button';
import Loader from '../../components/Loader';
import { useHistory } from 'react-router-dom';
import JwtVerifierForm from '../entities/JwtVerifier';
import { JwtVerifier } from '../../components/JwtVerifier';
import { FeedbackButton } from '../../pages/RouteDesigner/FeedbackButton';
import { v4 as uuid } from 'uuid';

function WizardStepButton(props) {
  return (
    <Button
      {...props}
      type="save"
      style={{
        backgroundColor: '#f9b000',
        borderColor: '#f9b000',
        padding: '12px 48px',
      }}
    />
  );
}

function Breadcrumb({ value, onClick }) {
  return (
    <div className="d-flex">
      {value.map((part, i) => {
        return (
          <span
            key={part}
            style={{
              cursor: 'pointer',
              maxWidth: 200,
              whiteSpace: 'pre',
              textOverflow: 'ellipsis',
              overflow: 'hidden',
            }}
            onClick={() => onClick(i)}>
            {part}
            {i + 1 < value.length && <i className="fas fa-chevron-right mx-1" />}
          </span>
        );
      })}
    </div>
  );
}

function Header({ onClose, mode }) {
  return (
    <label style={{ fontSize: '1.15rem' }}>
      <i className="fas fa-times me-3" onClick={onClose} style={{ cursor: 'pointer' }} />
      <span>
        {mode === 'selector' && 'Verifier wizard'}
        {mode === 'creation' && 'Create a new JWT Verifier'}
        {['edition', 'clone', 'continue'].includes(mode) && 'Update the new JWT Verifier'}
        {mode === 'update_in_wizard' && 'Update the verifier configuration'}
      </span>
    </label>
  );
}

function WizardActions({ nextStep, prevStep, step, goBack }) {
  return (
    <div className="d-flex mt-auto justify-content-between align-items-center">
      <label style={{ color: '#f9b000' }} onClick={step !== 1 ? prevStep : goBack}>
        <Button type="outline-save" text="Previous" />
      </label>
      <WizardStepButton className="ms-auto" onClick={nextStep} text="Continue" />
    </div>
  );
}

function Selector({ setMode, disableSelectMode }) {
  return (
    <div className="p-3 w-50">
      <h3>Getting started</h3>
      <div className="d-flex flex-column">
        {[
          { title: 'NEW', text: 'Create a new JWT verifier', mode: 'creation' },
          {
            title: 'SELECT',
            text: 'Use an existing JWT verifier',
            mode: 'edition',
            disabled: disableSelectMode,
          },
          { title: 'CLONE', text: 'Create a new one fron an existing JWT verifier', mode: 'clone' },
        ].map(({ title, text, mode, disabled }) =>
          disabled ? null : (
            <Button
              key={mode}
              type="dark"
              className="py-3 my-2"
              style={{ border: '1px solid #f9b000' }}
              onClick={() => setMode(mode)}>
              <h3
                className="wizard-h3--small"
                style={{
                  textAlign: 'left',
                  fontWeight: 'bold',
                }}>
                {title}
              </h3>
              <label
                className="d-flex align-items-center justify-content-between"
                style={{ flex: 1 }}>
                {text}
                <i className="fas fa-chevron-right ms-3" />
              </label>
            </Button>
          )
        )}
      </div>
    </div>
  );
}

function JwtVerifierSelector({ handleSelect, allowedStrategy, mode }) {
  const [verifiers, setVerifiers] = useState([]);

  useEffect(() => {
    BackOfficeServices.findAllJwtVerifiers().then(setVerifiers);
  }, []);

  return (
    <div className="d-flex flex-column mt-3" style={{ flex: 1 }}>
      <div className="d-flex align-items-center justify-content-between">
        <h3>Select {mode === 'clone' ? 'the verifier to clone' : 'a verifier'}</h3>
      </div>
      <div style={{ maxHeight: '36px' }} className="mt-3">
        <NgSelectRenderer
          placeholder="Select a verifier to continue"
          ngOptions={{
            spread: true,
          }}
          onChange={(id) => {
            handleSelect(verifiers.find((v) => v.id === id));
          }}
          options={verifiers.filter((verifier) =>
            allowedStrategy ? verifier.strategy.type === allowedStrategy : true
          )}
          optionsTransformer={(arr) => arr.map((item) => ({ value: item.id, label: item.name }))}
        />
      </div>
    </div>
  );
}

function GoBackSelection({ goBack }) {
  return (
    <div className="d-flex mt-auto justify-content-between align-items-center m-@">
      <Button type="info" className="d-flex align-items-center" onClick={goBack}>
        <i className="fas fa-chevron-left me-2" />
        <p className="m-0">Go back to selection</p>
      </Button>
    </div>
  );
}

export class JwtVerifierWizard extends React.Component {
  init = () => {
    if (this.props.jwtVerifier) {
      if (!this.props.allowedNewStrategy) {
        return this.props.jwtVerifier;
      } else {
        return {
          strict: false,
          strategy: {
            type: 'PassThrough',
            verificationSettings: { fields: { iss: 'The Issuer' }, arrayFields: {} },
          },
          ...this.props.jwtVerifier,
        };
      }
    } else {
      return {
        type: 'global',
        strict: false,
        source: { type: 'InHeader', name: 'X-JWT-Token', remove: '' },
        algoSettings: { type: 'HSAlgoSettings', size: 512, secret: 'secret' },
        strategy: {
          type: 'PassThrough',
          verificationSettings: { fields: { iss: 'The Issuer' }, arrayFields: {} },
        },
      };
    }
  };

  state = {
    step: 1,
    jwtVerifier: this.init(),
    breadcrumb: ['Informations'],
    mode: this.props.mode || 'selector',
  };

  onChange = (field, value) => {
    this.setState({
      jwtVerifier: {
        ...this.state.jwtVerifier,
        [field]: value,
      },
    });
  };

  prevStep = () => {
    if (this.state.step - 1 > 0) this.setState({ step: this.state.step - 1 });
  };

  nextStep = () => {
    this.setState({
      step: this.state.step + 1,
    });
  };

  updateBreadcrumb = (value, i) => {
    if (i >= this.state.breadcrumb.length) {
      this.setState({
        breadcrumb: [...this.state.breadcrumb, value],
      });
    } else {
      this.setState({
        breadcrumb: this.state.breadcrumb.map((v, j) => {
          if (j === i) return value;
          return v;
        }),
      });
    }
  };

  render() {
    const { step, jwtVerifier, mode } = this.state;

    if (mode === 'update_in_wizard') {
      return (
        <div className="wizard">
          <div className="wizard-container">
            <div className="d-flex" style={{ flexDirection: 'column', padding: '2.5rem', flex: 1 }}>
              <Header onClose={this.props.hide} mode={mode} />
              <div className="wizard-content">
                <JwtVerifier
                  verifier={jwtVerifier}
                  showHeader={true}
                  strategy={this.props.allowedNewStrategy}
                  onChange={(jwtVerifier) => this.setState({ jwtVerifier })}
                />

                <div className="d-flex mt-auto ms-auto justify-content-between align-items-center">
                  <FeedbackButton
                    style={{
                      backgroundColor: '#f9b000',
                      borderColor: '#f9b000',
                      padding: '12px 48px',
                    }}
                    onPress={() => BackOfficeServices.updateJwtVerifier(jwtVerifier)}
                    onSuccess={this.props.hide}
                    icon={() => <i className="fas fa-paper-plane" />}
                    text="Save the verifier"
                  />
                </div>
              </div>
            </div>
          </div>
        </div>
      );
    } else {
      const STEPS = [
        {
          component: InformationsStep,
          props: {
            name: jwtVerifier.name,
            onChange: (value) => {
              this.onChange('name', value);
              this.updateBreadcrumb(value, 0);
            },
          },
        },
        {
          component: StrategyStep,
          hide: this.props.allowedNewStrategy ? true : undefined,
          props: {
            value: jwtVerifier.strategy?.type,
            onChange: (value) => {
              if (value?.strategy) this.updateBreadcrumb(value.strategy, 1);
              this.setState(
                {
                  jwtVerifier: {
                    ...jwtVerifier,
                    strategy: {
                      ...(jwtVerifier.strategy || {}),
                      type: value?.strategy,
                    },
                  },
                },
                () => {
                  if (value?.strategy) this.nextStep();
                }
              );
            },
          },
        },
        {
          component: DefaultTokenStep,
          hide: this.props.allowedNewStrategy ? true : undefined,
          onChange: (_, index) => {
            this.updateBreadcrumb(`${this.state.jwtVerifier.source?.type || ''} Location`, index);
          },
        },
        {
          component: TokenSignatureStep,
          props: {
            root: 'algoSettings',
            value: jwtVerifier,
            title:
              this.props.allowedNewStrategy === 'PassThrough' ? 'Verify token with' : undefined,
            onChange: (value, index) =>
              this.setState({ jwtVerifier: value }, () => {
                this.updateBreadcrumb(
                  `${this.state.jwtVerifier.algoSettings?.type || ''} Algo.`,
                  index
                );
              }),
          },
        },
        {
          component: TokenSignatureStep,
          condition: (value) => ['Sign', 'Transform'].includes(value.strategy?.type),
          props: {
            value: jwtVerifier['strategy'],
            root: 'algoSettings',
            title: 'Resign token with',
            onChange: (value, index) =>
              this.setState(
                {
                  jwtVerifier: {
                    ...jwtVerifier,
                    ['strategy']: value,
                  },
                },
                () => {
                  this.updateBreadcrumb(
                    `${this.state.jwtVerifier.strategy?.algoSettings?.type || ''} Resign Algo.`,
                    index
                  );
                }
              ),
          },
        },
        {
          component: TokenTransformStep,
          condition: (value) => 'Transform' === value.strategy?.type,
          props: {
            value: jwtVerifier.strategy?.transformSettings,
            onChange: (value, index) => {
              this.setState(
                {
                  jwtVerifier: {
                    ...jwtVerifier,
                    strategy: {
                      ...(jwtVerifier.strategy || {}),
                      transformSettings: value,
                    },
                  },
                },
                () => {
                  const transformSettings =
                    this.state.jwtVerifier.strategy?.transformSettings || {};
                  const sameLocation =
                    transformSettings.location === undefined ? true : transformSettings.location;
                  const outLocation = transformSettings.out_location?.source?.type || '';
                  this.updateBreadcrumb(
                    `${sameLocation ? this.state.jwtVerifier.source?.type : outLocation
                    } Out location.`,
                    index
                  );
                }
              );
            },
          },
        },
      ].filter((item) => item.hide === undefined);

      const showSummary = !STEPS.find((item, i) => {
        return step === i + 1 && (item.condition ? item.condition(jwtVerifier) : true);
      });

      return (
        <div className="wizard">
          <div className="wizard-container">
            <div className="d-flex" style={{ flexDirection: 'column', padding: '2.5rem', flex: 1 }}>
              <Header onClose={this.props.hide} mode={mode} />

              {mode === 'selector' && (
                <Selector
                  setMode={(mode) => this.setState({ mode })}
                  disableSelectMode={this.props.disableSelectMode}
                />
              )}

              {mode !== 'selector' && (
                <>
                  {['edition', 'clone'].includes(mode) ? (
                    <JwtVerifierSelector
                      mode={mode}
                      allowedStrategy={this.props.allowedStrategy}
                      handleSelect={(verifier) => {
                        if (this.props.onConfirm && mode === 'edition') {
                          this.props.onConfirm(verifier.id);
                        } else {
                          this.setState({
                            mode: 'continue',
                            jwtVerifier: {
                              ...verifier,
                              id: `jwt_verifier_${uuid()}`,
                            },
                          });
                        }
                      }}
                    />
                  ) : (
                    <>
                      <Breadcrumb
                        value={this.state.breadcrumb}
                        onClick={(i) => this.setState({ step: i + 1 })}
                      />
                      <div className="wizard-content">
                        {STEPS.map(({ component, props, condition, onChange }, i) => {
                          if (step === i + 1 && (condition ? condition(jwtVerifier) : true)) {
                            const defaultProps = {
                              value: jwtVerifier,
                              onChange: (value) => this.setState({ jwtVerifier: value }, onChange),
                            };

                            const allProps = props
                              ? {
                                ...props,
                                onChange: (e) => props.onChange(e, i),
                              }
                              : defaultProps;

                            return React.createElement(component, {
                              key: component.Type,
                              ...allProps,
                            });
                          } else {
                            return null;
                          }
                        })}
                        {showSummary && (
                          <WizardLastStep
                            onConfirm={this.props.onConfirm}
                            breadcrumb={this.state.breadcrumb}
                            value={{
                              ...jwtVerifier,
                              strategy: {
                                ...jwtVerifier.strategy,
                                transformSettings:
                                  jwtVerifier.strategy?.type === 'Transform'
                                    ? {
                                      location: jwtVerifier.strategy?.transformSettings?.location
                                        ? jwtVerifier.source
                                        : jwtVerifier.strategy?.transformSettings?.out_location
                                          ?.source,
                                    }
                                    : undefined,
                              },
                            }}
                          />
                        )}
                        {!showSummary && (
                          <WizardActions
                            nextStep={this.nextStep}
                            prevStep={this.prevStep}
                            step={step}
                            goBack={() => {
                              this.setState({
                                mode: this.props.mode || 'selector',
                              });
                            }}
                          />
                        )}
                      </div>
                    </>
                  )}
                </>
              )}
              {['edition', 'clone'].includes(mode) && (
                <GoBackSelection
                  goBack={() => {
                    this.setState({
                      mode: this.props.mode || 'selector',
                    });
                  }}
                />
              )}
            </div>
          </div>
        </div>
      );
    }
  }
}

function WizardLastStep({ value, breadcrumb, onConfirm }) {
  const [verifier, setVerifier] = useState();
  const history = useHistory();

  const [error, setError] = useState(false);
  const [creating, setCreating] = useState(false);

  const create = () => {
    setCreating(true);
    BackOfficeServices.createNewJwtVerifier().then((template) => {
      BackOfficeServices.createJwtVerifier({
        ...template,
        name: value.name || 'Default name',
        source: value.source,
        algoSettings: {
          ...template.algoSettings,
          ...value.algoSettings,
        },
        strategy: {
          ...template.strategy,
          ...value.strategy,
          type: value.strategy.type,
        },
      }).then((res) => {
        if (res.error) {
          setError(true);
        } else if (onConfirm) {
          onConfirm(template.id);
        } else {
          setVerifier(template);
        }
      });
    });
  };

  return (
    <>
      <h3 style={{ textAlign: 'center' }} className="mt-3">
        Creation steps
      </h3>

      <div
        className="d-flex mx-auto"
        style={{
          flexDirection: 'column',
        }}>
        {breadcrumb.map((part, i) => {
          return (
            <LoaderItem
              text={i === 0 ? `Informations` : part}
              timeout={1000 + i * 250}
              key={part}
              started={creating}
            />
          );
        })}
      </div>

      {!creating && (
        <Button type="save" className="mx-auto mt-3" onClick={create}>
          <i className="fas fa-check me-1" />
          Confirm
        </Button>
      )}

      {(verifier || error) && (
        <Button
          type="save"
          className="mx-auto mt-3"
          disabled={error}
          onClick={() => history.push(`/jwt-verifiers/edit/${verifier.id}`)}>
          <i className={`fas fa-${error ? 'times' : 'check'} me-1`} />
          {error
            ? 'Something wrong happened : try to check your configuration'
            : 'See the created verifier'}
        </Button>
      )}
    </>
  );
}

function InformationsStep({ name, onChange }) {
  return (
    <>
      <h3>Let's start with a name for your JWT verifier</h3>

      <div>
        <TextInput
          autoFocus={true}
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
      </div>
    </>
  );
}

function StrategyStep({ value, onChange }) {
  const schema = {
    strategy: {
      renderer: (props) => {
        return (
          <div
            style={{
              display: 'flex',
              gap: '10px',
              flexWrap: 'wrap',
              justifyContent: 'flex-start',
            }}>
            {[
              {
                strategy: 'PassThrough',
                title: ['Verify'],
                desc: 'PassThrough will only verifiy token signing and fields values if provided. ',
                tags: ['verify'],
              },
              {
                strategy: 'Sign',
                title: ['Verify and re-sign'],
                desc:
                  'Sign will do the same as PassThrough plus will re-sign the JWT token with the provided algo. settings.',
                tags: ['verify', 'sign'],
              },
              {
                strategy: 'Transform',
                title: ['Verify, re-sign and Transform'],
                desc:
                  'Transform will do the same as Sign plus will be able to transform the token.',
                tags: ['verify', 'sign', 'transform'],
              },
            ].map(({ strategy, desc, title, tags }) => {
              return (
                <Button
                  type={value === strategy ? 'save' : 'dark'}
                  className="py-3 d-flex align-items-center flex-column col-3"
                  style={{
                    gap: '12px',
                    minHeight: '325px',
                    maxWidth: '235px',
                  }}
                  onClick={() => props.onChange(strategy)}
                  key={strategy}>
                  <div style={{ flex: 0.2 }}>
                    {title.map((t, i) => (
                      <h3
                        className="wizard-h3--small "
                        style={{
                          margin: 0,
                          marginTop: i > 0 ? '1px' : 0,
                        }}
                        key={t}>
                        {t}
                      </h3>
                    ))}
                  </div>
                  <div className="d-flex flex-column align-items-center" style={{ flex: 1 }}>
                    <label className="d-flex align-items-center" style={{ textAlign: 'left' }}>
                      {desc}
                    </label>
                    <div
                      className="mt-auto"
                      style={{
                        padding: '4px',
                        background: '#515151',
                        width: '100%',
                      }}>
                      {['Generate', 'Verify', 'Sign', 'Transform']
                        .filter((tag) => tags.includes(tag.toLocaleLowerCase()))
                        .map((tag) => (
                          <div
                            className="d-flex align-items-center me-1"
                            key={tag}
                            style={{
                              minWidth: '80px',
                              padding: '2px 8px 2px 3px',
                            }}>
                            <i
                              className={`fas fa-${tags.includes(tag.toLocaleLowerCase()) ? 'check' : 'times'
                                } me-1`}
                              style={{
                                color: tags.includes(tag.toLocaleLowerCase()) ? '#f9b000' : '#fff',
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
      },
    },
  };

  const flow = ['strategy'];

  return (
    <>
      <h3>What kind of strategy will be used</h3>
      <NgForm value={value} schema={schema} flow={flow} onChange={onChange} />
    </>
  );
}

const TokenLocationForm = {
  schema: {
    source: {
      type: 'form',
      label: 'Source',
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
        debug: {
          renderer: () => {
            return (
              <LabelAndInput label="Examples">
                <NgForm
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
                    result: {
                      type: 'form',
                      label: 'Form values',
                      schema: {
                        headerName: {
                          type: 'string',
                          label: 'Name',
                          props: {
                            disabled: true,
                            defaultValue: 'Authorization',
                          },
                        },
                        remove: {
                          type: 'string',
                          label: 'Remove value',
                          props: {
                            disabled: true,
                            defaultValue: 'Bearer ',
                          },
                        },
                      },
                      flow: ['headerName', 'remove'],
                    },
                  }}
                  flow={[
                    {
                      type: 'group',
                      collapsable: false,
                      name: 'A bearer token expected in Authorization header',
                      fields: ['header', 'result'],
                    },
                  ]}
                />
              </LabelAndInput>
            );
          },
        },
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
  },
};

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
  );
}

function TokenSignatureStep({ root, value, onChange, title }) {
  const schema = {
    [root]: {
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
    },
  };

  const flow = [root];

  return (
    <>
      <h3>{title || 'Generate token with'}</h3>

      <NgForm value={value} schema={schema} flow={flow} onChange={onChange} />
    </>
  );
}

function TokenTransformStep({ value, onChange }) {
  const schema = {
    location: {
      type: 'bool',
      label: 'Use the same location than the entry token',
      props: {
        defaultValue: true,
      },
    },
    out_location: {
      visible: (props) => props?.location === false,
      label: 'New location',
      type: 'form',
      ...TokenLocationForm,
    },
  };

  const flow = ['location', 'out_location'];

  return (
    <>
      <h3>Location of the generated token</h3>

      <NgForm value={value} schema={schema} flow={flow} onChange={onChange} />
    </>
  );
}

function LoaderItem({ text, timeout, started }) {
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (started) {
      const timeout = setTimeout(() => setLoading(false), timeout);
      return () => timeout;
    }
  }, [started]);

  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: '42px 1fr',
        minHeight: '42px',
        alignItems: 'center',
        justifyContent: 'flex-start',
        marginBottom: '6px',
      }}
      className="mt-3">
      {started && (
        <Loader loading={loading} minLoaderTime={timeout}>
          <i className="fas fa-check fa-2x" style={{ color: '#f9b000' }} />
        </Loader>
      )}
      {!started && <i className="fas fa-square fa-2x" />}
      <div
        style={{
          flex: 1,
          marginLeft: '12px',
          color: loading ? '#eee' : '#fff',
          fontWeight: loading ? 'normal' : 'bold',
        }}>
        <div>{text}</div>
      </div>
    </div>
  );
}