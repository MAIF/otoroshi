import React, { Component } from 'react';
import _ from 'lodash';
import * as yup from 'yup';

import { NgForm, NgFormState } from './form';

export class NgFormPlayground extends Component {
  
  state = { 
    value: {
      email: 'foo@bar.com',
      done: false,
      name: 'foo',
      embed: {
        foo: 'bar',
        bar: {
          foo: 'foo',
        }
      }
    }
  }

  render() {
    if (!(this.props.globalEnv && this.props.globalEnv.env === 'dev')) {
      return null;
    }
    return (
      <div style={{ marginTop: 40 }}>
        <NgFormPlaygroundOtoroshi globalEnv={this.props.globalEnv} />
        <NgForm 
          style={{ display: 'flex', flexDirection: 'column', width: '100%' }}
          value={this.state.value || {}}
          flow={(state) => state.done ? ["name", "email", "age", "done"] : ["name", "email", "age", "done", "embed"]} 
          schema={(state) => ({
            name: {
              type: "string",
              of: null,
              constraints: [],
              renderer: null,
              component: null,
              props: {
                label: 'Name',
                placeholder: 'Your name',
                help: "Just your name",
                disabled: false,
              }
            },
            email: {
              type: "string",
              of: null,
              constraints: [],
              renderer: null,
              component: null,
              props: {
                placeholder: 'Your email',
                help: "Just your email",
                disabled: false,
                label: 'Email',
              }
            },
            age: {
              type: "number",
              of: null,
              constraints: [
                yup.number().required().positive().integer().min(18).max(9999)
              ],
              renderer: null,
              component: null,
              props: {
                placeholder: 'Your age',
                help: "Just your age",
                disabled: false,
                label: 'Age',
              }
            },
            done: {
              type: "boolean",
              of: null,
              constraints: [],
              renderer: null,
              component: null,
              props: {
                help: "Is it done yet ?",
                disabled: false,
                label: 'Done',
              }
            },
            embed: {
              type: "form",
              flow: ["foo", "bar"],
              schema: {
                foo: {
                  type: "string",
                  of: null,
                  constraints: [
                    yup.string().required().matches("bar")
                  ],
                  renderer: null,
                  component: null,
                  props: {
                    placeholder: 'Foo',
                    help: "The foo",
                    disabled: false,
                    label: 'Foo',
                  }
                },
                bar: {
                  type: "form",
                  flow: ["foo", "select", "arr", "selectarr", "obj", "selectobj"],
                  schema: {
                    foo: {
                      type: "string",
                      of: null,
                      constraints: [],
                      renderer: null,
                      component: null,
                      props: {
                        placeholder: 'Foo',
                        help: "The foo",
                        disabled: false,
                        label: 'Foo',
                      }
                    },
                    select: {
                      type: "string",
                      of: null,
                      constraints: [],
                      renderer: "select",
                      component: null,
                      props: {
                        placeholder: 'select',
                        help: "The select",
                        disabled: false,
                        label: 'select',
                        options: [
                          { label: "GET", value: "get" },
                          { label: "POST", value: "post" },
                        ]
                      }
                    },
                    selectarr: {
                      type: "array",
                      of: "string",
                      constraints: [],
                      renderer: "array-select",
                      component: null,
                      props: {
                        placeholder: 'selectarr',
                        help: "The selectarr",
                        disabled: false,
                        label: 'selectarr',
                        options: [
                          { label: "GET", value: "get" },
                          { label: "POST", value: "post" },
                        ]
                      }
                    },
                    selectobj: {
                      type: "object",
                      of: "string",
                      constraints: [],
                      renderer: "object-select",
                      component: null,
                      props: {
                        placeholder: 'selectobj',
                        help: "The selectobj",
                        disabled: false,
                        label: 'selectobj',
                        options: [
                          { label: "GET", value: "get" },
                          { label: "POST", value: "post" },
                        ]
                      }
                    },
                    arr: {
                      type: "array",
                      of: "string",
                      constraints: [],
                      renderer: null,
                      component: null,
                      props: {
                        placeholder: 'arr',
                        help: "The arr",
                        disabled: false,
                        label: 'arr',
                      }
                    },
                    obj: {
                      type: "object",
                      of: "string",
                      constraints: [],
                      renderer: null,
                      component: null,
                      props: {
                        placeholder: 'obj',
                        help: "The obj",
                        disabled: false,
                        label: 'obj',
                      }
                    }
                  }
                }
              }
            }
          })}
          onChange={(value, validation) => {
            this.setState({ value, validation })
          }}
        />
        <pre style={{ marginTop: 20 }}>
          <code>
            {JSON.stringify(this.state, null, 2)} 
          </code>
        </pre>
      </div>
    );
  }
}

export class NgFormPlaygroundOtoroshi extends Component {

  state = { forms: {} }

  componentDidMount() {
    return fetch(`/bo/api/proxy/api/experimental/forms`, {
      method: 'GET',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
      },
    }).then(r => r.json()).then(r => {
      this.setState({ forms: r });
    });
  }

  render() {
    if (this.props.globalEnv && this.props.globalEnv.env === 'dev') {
      return (
        <div style={{ marginTop: 60, display: 'flex', flexDirection: 'column' }}>
          {Object.keys(this.state.forms)
            .filter(name => name.indexOf('otoroshi.next.plugins') === 0)
            .filter(name => name.indexOf('otoroshi.next.plugins.api') !== 0)
            .filter(name => name.indexOf('otoroshi.next.plugins.wrappers') !== 0)
            .filter(key  => this.state.forms[key].flow.length > 0)
            // .filter(name => name.indexOf('otoroshi.next.plugins.NgChaosConfig') === 0)
            // .filter(name => name.indexOf('otoroshi.next.plugins.ApikeyCalls') === 0)
            .map(key => {
              return (
                <div key={key} style={{ borderBottom: '1px rgb(181, 179, 179) solid', paddingBottom: 50, marginBottom: 20 }}>
                  <h3>{key}</h3>
                  <hr />
                  <NgFormState key={key}>{(value, onChange) => (
                    <NgForm 
                      key={key}
                      value={value}
                      flow={this.state.forms[key].flow}
                      schema={this.state.forms[key].schema}
                      onChange={onChange} />
                  )}</NgFormState>
                </div>
              )
            })}
        </div>
      )
    } else {
      return null;
    }
  }
}