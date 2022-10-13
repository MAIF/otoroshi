import React, { Component } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table, TextInput } from '../components/inputs';
import { JwtVerifier } from '../components/JwtVerifier';
import { NgForm } from '../components/nginputs';

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
    hasNextStep: true,
    jwtVerifier: {
      strategy: 'Sign'
    }
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
    if (this.state.hasNextStep)
      this.setState({
        step: this.state.step + 1
      });
  };

  render() {
    const { step, steps, jwtVerifier, hasNextStep } = this.state;

    const STEPS = [
      {
        component: InformationsStep,
        visbibleOnStep: 1,
        props: {
          name: jwtVerifier.name,
          onChange: value => this.onChange('name', value)
        }
      },
      {
        component: StrategyStep,
        visbibleOnStep: 2,
        props: {
          value: jwtVerifier,
          onChange: value => this.setState({ jwtVerifier: value })
        }
      }
    ];

    return (
      <div className="wizard">
        <div className="wizard-container">
          <div className='d-flex flex' style={{ flexDirection: 'column', padding: '2.5rem' }}>
            <label style={{ fontSize: '1.15rem' }}>
              <i
                className="fas fa-times me-3"
                onClick={this.props.hide}
                style={{ cursor: 'pointer' }}
              />
              <span>{`Create a new JWT Verifier`}</span>
            </label>

            <div className="wizard-content">
              {STEPS
                .map(({ component, visbibleOnStep, props }) => {
                  if (step === visbibleOnStep) {
                    return React.createElement(component, {
                      ...props, key: component.Type
                    });
                  } else {
                    return null;
                  }
                })}
              {hasNextStep && (
                <div className="d-flex mt-3 justify-content-between align-items-center">
                  {step !== 1 && <label style={{ color: '#f9b000' }} onClick={this.prevStep}>
                    Previous
                  </label>}
                  <WizardStepButton
                    onClick={this.nextStep}
                    text={step === steps ? 'Create' : 'Continue'} />
                </div>
              )}
            </div>
          </div>
        </div>
      </div >
    )
  }
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
            flexDirection: 'column',
            gap: '10px',
          }}>
          {[
            { value: 'DefaultToken', title: ['Generate'], desc: 'DefaultToken will add a token if no present.' },
            { value: 'PassThrough', title: ['Verify'], desc: 'PassThrough will only verifiy token signing and fields values if provided. ' },
            { value: 'Sign', title: ['Verify', 'and re-sign'], desc: 'Sign will do the same as PassThrough plus will re-sign the JWT token with the provided algo. settings.' },
            { value: 'Transform', title: ['Verify', 're-sign', 'and Transform'], desc: 'Transform will do the same as Sign plus will be able to transform the token.' }
          ].map(({ value, desc, title }) => {
            return <button
              type="button"
              className={`btn ${props?.value === value ? 'btn-save' : 'btn-dark'} py-3 d-flex align-items-center`}
              style={{
                gap: '12px'
              }}
              onClick={() => props.onChange(value)}
              key={value}
            >
              <div style={{ flex: .5 }}>
                {title.map((t, i) => <h3 className="wizard-h3--small " style={{
                  flex: .5,
                  margin: 0,
                  marginTop: i > 0 ? '1px' : 0
                }} key={t}>{t}</h3>)}
              </div>
              <label className='d-flex align-items-center' style={{ flex: 1 }}>
                {desc}
              </label>
            </button>
          })}
        </div>
      }
    }
  }

  console.log(value)

  const flow = [
    'strategy'
  ];

  return (
    <>
      <h3>What kind of strategy will be used.</h3>
      <NgForm
        value={value}
        schema={schema}
        flow={flow}
        onChange={onChange}
      />
    </>
  )
}