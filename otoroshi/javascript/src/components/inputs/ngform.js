import React, { Component } from 'react';
import _ from 'lodash';
import Select from 'react-select';
import { OffSwitch, OnSwitch } from './BooleanInput';
import * as yup from 'yup';

class NgFormRenderer extends Component {
  render() {
    if (this.props.root) {
      return (
        <form style={this.props.style} className={this.props.className}>
          {this.props.children}
        </form>
      );
    } else {
      return (
        <div style={{ outline: '1px solid yellow', padding: 5, margin: 5, display: 'flex', flexDirection: 'column' }}>
          {this.props.children}
        </div>
      );
    }
  }
}

class NgStepNotFound extends Component {
  render() {
    return (
      <h3>step not found {this.props.name}</h3>
    );
  }
}

class NgRendererNotFound extends Component {
  render() {
    return (
      <h3>renderer not found {this.props.name}</h3>
    );
  }
}

class NgStep extends Component {

  rendererFor = (type, components) => {
    if (type === "string") {
      return components.StringRenderer;
    } else if (type === "bool" || type === "boolean") {
      return components.BooleanRenderer;
    } else if (type === "number") {
      return components.NumberRenderer;
    } else if (type === "array") {
      return components.ArrayRenderer;
    } else if (type === "object") {
      return components.ObjectRenderer;
    } else if (type === "date") {
      return components.DateRenderer;
    } else if (type === "select") {
      return components.SelectRenderer;
    } else if (type === "array-select") {
      return components.ArraySelectRenderer;
    } else if (type === "object-select") {
      return components.ObjectSelectRenderer;
    } else if (type === "code") {
      return components.TextRenderer;
    } else if (type === "single-line-of-code") {
      return components.StringRenderer;
    } else if (type === "text") {
      return components.TextRenderer;
    } else if (type === "hidden") {
      return components.HiddenRenderer;
    } else if (type === "form") {
      return NgForm;
    } else {
      return components.RendererNotFound;
    }
  }

  validate = (value) => {
    // TODO: handle type checking if mandatory
    if (this.props.schema.constraints && this.props.schema.constraints.length > 0) {
      const res = { __valid: true, __errors: [] }
      this.props.schema.constraints.map(validator => {
        if (validator.validateSync) {
          try {
            validator.validateSync(value);
          } catch(e) {
            res.__valid = false
            res.__errors.push(e.message)
          }
        } else {
          const r = validator(value);
          if (!r.valid) {
            res.__valid = false;
          }
          res.__errors = [...res.__errors, ...r.errors ]
        }
      })
      this.props.setValidation(this.props.path, res);
      return res;
    } else {
      const res = { __valid: true, __errors: [] };
      this.props.setValidation(this.props.path, res);
      return res;
    }
  }

  renderer = () => {
    if (this.props.schema.component) {
      return this.props.schema.components;
    } else if (this.props.schema.renderer) {
      if (_.isString(this.props.schema.renderer)) {
        return this.rendererFor(this.props.schema.renderer, this.props.components);
      } else {
        return this.props.schema.renderer;
      }
    } else {
      return this.rendererFor(this.props.schema.type, this.props.components);
    }
  }

  render() {
    const Renderer = this.renderer();
    const validation = this.validate(this.props.value);
    const ValidationRenderer = this.props.components.ValidationRenderer;
    return (
      <ValidationRenderer validation={validation} >
        <Renderer 
          validation={validation} 
          {...this.props} 
          schema={this.props.schema.schema || this.props.schema} 
          flow={this.props.schema.flow || this.props.flow} 
        />
      </ValidationRenderer>
    );
  }
}

class NgStringRenderer extends Component {
  render() {
    const schema = this.props.schema;
    const props = schema.props || {};
    return (
      <>
        <label>{props.label}</label>
        <input 
          type="text" 
          placeholder={props.placeholder} 
          title={props.help} 
          value={this.props.value} 
          onChange={e => this.props.onChange(e.target.value)} 
          {...props}
        />
      </>
    );
  }
}

class NgNumberRenderer extends Component {
  render() {
    const schema = this.props.schema;
    const props = schema.props || {};
    return (
      <>
        <label>{props.label}</label>
        <input 
          type="number" 
          placeholder={props.placeholder} 
          title={props.help} 
          value={this.props.value} 
          onChange={e => this.props.onChange(e.target.value)} 
          {...props}
        />
      </>
    );
  }
}

class NgHiddenRenderer extends Component {
  render() {
    const schema = this.props.schema;
    const props = schema.props || {};
    return (
      <>
        <label>{props.label}</label>
        <input 
          type="hidden" 
          placeholder={props.placeholder} 
          title={props.help} 
          value={this.props.value} 
          onChange={e => this.props.onChange(e.target.value)} 
          {...props}
        />
      </>
    );
  }
}

class NgTextRenderer extends Component {
  render() {
    const schema = this.props.schema;
    const props = schema.props || {};
    return (
      <>
        <label>{props.label}</label>
        <textarea 
          placeholder={props.placeholder} 
          title={props.help} 
          onChange={e => this.props.onChange(e.target.value)} 
          {...props}
        >
          {this.props.value} 
        </textarea>
      </>
    );
  }
}

class NgDateRenderer extends Component {
  render() {
    const schema = this.props.schema;
    const props = schema.props || {};
    return (
      <>
        <label>{props.label}</label>
        <input 
          type="date" 
          placeholder={props.placeholder} 
          title={props.help} 
          value={this.props.value} 
          onChange={e => this.props.onChange(e.target.value)} 
          {...props}
        />
      </>
    );
  }
}

class NgBooleanRenderer extends Component {

  toggleOff = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    if (!this.props.disabled) this.props.onChange(false);
  };

  toggleOn = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    if (!this.props.disabled) this.props.onChange(true);
  };

  render() {
    const schema = this.props.schema;
    const props = schema.props || {};
    return (
      <>
        <label>{props.label}</label>
        {this.props.value && <OnSwitch onChange={this.toggleOff} />}
        {!this.props.value && <OffSwitch onChange={this.toggleOn} />}
      </>
    );
  }
}

class NgArrayRenderer extends Component {
  render() {
    const schema = this.props.schema;
    const props = schema.props || {};
    const ItemRenderer = schema.itemRenderer;
    return (
      <>
        <label>{props.label}</label>
        <div style={{ display: 'flex', flexDirection: 'column', width: '100%' }}>
          {this.props.value && this.props.value.map((value, idx) => {
            return (
              <div style={{ display: 'flex', flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', width: '100%'}}>
                {!ItemRenderer && <input 
                  type="text" 
                  placeholder={props.placeholder} 
                  title={props.help} 
                  value={value} 
                  onChange={e => {
                    const newArray = this.props.value ? [...this.props.value] : [];
                    newArray.splice(idx, 1, e.target.value);
                    this.props.onChange(newArray);
                  }} 
                  style={{ width: '100%' }}
                  {...props}
                />}
                {ItemRenderer && <ItemRenderer 
                  value={value} 
                  onChange={e => {
                    const newArray = this.props.value ? [...this.props.value] : [];
                    newArray.splice(idx, 1, e.target.value);
                    this.props.onChange(newArray);
                  }} 
                  {...props}
                />}
                <button type="button" style={{ maxWidth: 70 }} onClick={e => {
                  const newArray = this.props.value ? [...this.props.value] : [];
                  newArray.splice(idx, 1);
                  this.props.onChange(newArray);
                }}>remove</button>
              </div>
            );
          })}
          <button type="button" style={{ maxWidth: 70 }} onClick={e => {
            const newArray = this.props.value ? [...this.props.value, ''] : [''];
            this.props.onChange(newArray);
          }}>add</button>
        </div>
      </>
    );
  }
}

class NgObjectRenderer extends Component {
  render() {
    const schema = this.props.schema;
    const props = schema.props || {};
    const ItemRenderer = schema.itemRenderer;
    return (
      <>
        <label>{props.label}</label>
        <div style={{ display: 'flex', flexDirection: 'column', width: '100%' }}>
          {this.props.value && Object.keys(this.props.value).map(key => [key, this.props.key]).map((raw, idx) => {
            const [key, value] = raw;
            return (
              <div style={{ display: 'flex', flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', width: '100%'}}>
                <input 
                  type="text" 
                  placeholder={props.placeholderKey} 
                  title={props.help} 
                  value={key} 
                  onChange={e => {
                    const newObject = this.props.value ? {...this.props.value} : {};
                    const old = newObject[key];
                    delete newObject[key];
                    newObject[e.target.value] = old;
                    this.props.onChange(newObject);
                  }} 
                  style={{ width: '50%' }}
                  {...props}
                />
                {!ItemRenderer && <input 
                  type="text" 
                  placeholder={props.placeholderValue} 
                  title={props.help} 
                  value={value} 
                  onChange={e => {
                    const newObject = this.props.value ? {...this.props.value} : {};
                    newObject[key] = e.target.value;
                    this.props.onChange(newObject);
                  }} 
                  style={{ width: '50%' }}
                  {...props}
                />}
                {ItemRenderer && <ItemRenderer 
                  value={value} 
                  onChange={e => {
                    const newObject = this.props.value ? {...this.props.value} : {};
                    newObject[key] = e.target.value;
                    this.props.onChange(newObject);
                  }} 
                  {...props}
                />}
                <button type="button" style={{ maxWidth: 70 }} onClick={e => {
                  const newObject = this.props.value ? {...this.props.value} : {};
                  delete newObject[key];
                  this.props.onChange(newObject);
                }}>remove</button>
              </div>
            );
          })}
          <button type="button" style={{ maxWidth: 70 }} onClick={e => {
            const newObject = {...this.props.value};
            newObject[''] = '';
            this.props.onChange(newObject);
          }}>add</button>
        </div>
      </>
    );
  }
}

class NgArraySelectRenderer extends Component {
  state = {};
  // TODO: http load
  render() {
    const schema = this.props.schema;
    const props = schema.props || {};
    return (
      <>
        <label>{props.label}</label>
        <div style={{ display: 'flex', flexDirection: 'column', width: '100%' }}>
          {this.props.value && this.props.value.map((value, idx) => {
            return (
              <div style={{ display: 'flex', flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', width: '100%'}}>
                <Select
                  name={`selector-${this.props.name}`}
                  value={value}
                  isLoading={this.state.loading}
                  disabled={props.disabled}
                  placeholder={props.placeholder}
                  optionRenderer={props.optionRenderer}
                  options={props.options}
                  style={{ width: '100%' }}
                  onChange={(e) => {
                    const newArray = this.props.value ? [...this.props.value] : [];
                    newArray.splice(idx, 1, e.value);
                    this.props.onChange(newArray);
                  }} />
                <button type="button" style={{ maxWidth: 70 }} onClick={e => {
                  const newArray = this.props.value ? [...this.props.value] : [];
                  newArray.splice(idx, 1);
                  this.props.onChange(newArray);
                }}>remove</button>
              </div>
            );
          })}
          <button type="button" style={{ maxWidth: 70 }} onClick={e => {
            const newArray = this.props.value ? [...this.props.value, ''] : [''];
            this.props.onChange(newArray);
          }}>add</button>
        </div>
      </>
    );
  }
}

class NgObjectSelectRenderer extends Component {
  state = {};
  // TODO: http load
  render() {
    const schema = this.props.schema;
    const props = schema.props || {};
    return (
      <>
        <label>{props.label}</label>
        <div style={{ display: 'flex', flexDirection: 'column', width: '100%' }}>
          {this.props.value && Object.keys(this.props.value).map(key => [key, this.props.key]).map((raw, idx) => {
            const [key, value] = raw;
            return (
              <div style={{ display: 'flex', flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', width: '100%'}}>
                <input 
                  type="text" 
                  placeholder={props.placeholderKey} 
                  title={props.help} 
                  value={key} 
                  onChange={e => {
                    const newObject = this.props.value ? {...this.props.value} : {};
                    const old = newObject[key];
                    delete newObject[key];
                    newObject[e.target.value] = old;
                    this.props.onChange(newObject);
                  }} 
                  style={{ width: '50%' }}
                  {...props}
                />
                <Select
                  name={`selector-${this.props.name}`}
                  value={value}
                  isLoading={this.state.loading}
                  disabled={props.disabled}
                  placeholder={props.placeholder}
                  optionRenderer={props.optionRenderer}
                  options={props.options}
                  style={{ width: '100%' }}
                  onChange={(e) => {
                    const newObject = this.props.value ? {...this.props.value} : {};
                    newObject[key] = e.value;
                    this.props.onChange(newObject);
                  }} />
                <button type="button" style={{ maxWidth: 70 }} onClick={e => {
                  const newObject = this.props.value ? {...this.props.value} : {};
                  delete newObject[key];
                  this.props.onChange(newObject);
                }}>remove</button>
              </div>
            );
          })}
          <button type="button" style={{ maxWidth: 70 }} onClick={e => {
            const newObject = {...this.props.value};
            newObject[''] = '';
            this.props.onChange(newObject);
          }}>add</button>
        </div>
      </>
    );
  }
}

class NgSelectRenderer extends Component {
  state = {}
  // TODO: http load
  componentDidMount() {
    if (this.props.optionsFrom) {

    }
  }
  render() {
    const schema = this.props.schema;
    const props = schema.props || {};
    return (
      <>
        <label>{props.label}</label>
        <Select
          name={`selector-${this.props.name}`}
          value={this.props.value}
          isLoading={this.state.loading}
          disabled={props.disabled}
          placeholder={props.placeholder}
          optionRenderer={props.optionRenderer}
          options={props.options}
          onChange={(e) => this.props.onChange(e.value)} 
        />
      </>
    );
  }
}

class NgValidationRenderer extends Component {
  render() {
    if (this.props.validation.__valid) {
      return this.props.children;
    } else {
      console.log(this.props.name, this.props.validation)
      return (
        <div style={{ outline: '1px solid red', display: 'flex', flexDirection: 'column' }}>
          {this.props.children}
          <>
            {this.props.validation.__errors.map(err => <p style={{ color: 'red', marginBottom: 0 }}>{err.message || err}</p>)}
          </>
        </div>
      );
    };
  }
}

export class NgForm extends Component {

  static DefaultTheme = {
    FormRenderer: NgFormRenderer,
    StepNotFound: NgStepNotFound,
    StringRenderer: NgStringRenderer,
    SelectRenderer: NgSelectRenderer,
    TextRenderer: NgTextRenderer,
    NumberRenderer: NgNumberRenderer,
    BooleanRenderer: NgBooleanRenderer,
    ArrayRenderer: NgArrayRenderer,
    ObjectRenderer: NgObjectRenderer,
    ArraySelectRenderer: NgArraySelectRenderer,
    ObjectSelectRenderer: NgObjectSelectRenderer,
    DateRenderer: NgDateRenderer,
    FormRenderer: NgFormRenderer,
    HiddenRenderer: NgHiddenRenderer,
    RendererNotFound: NgRendererNotFound,
    ValidationRenderer: NgValidationRenderer
  };

  static setTheme = (theme) => {
    NgForm.DefaultTheme = theme;
  };

  state = { validation: { valid: true, graph: {}}};
  tasks = [];

  componentDidMount() {
    const value = this.getValue();
    this.rootOnChange(value);
    this.interval = setInterval(() => this.handleTasks(), 100);
  }

  componentWillUnmount() {
    if (this.interval) {
      clearInterval(this.interval);
    }
  }

  isValid = (graph) => {
    return !(JSON.stringify(graph).indexOf('"__valid":false') > -1);
  }

  handleTasks = () => {
    if (this.tasks.length > 0) {
      const tasks = _.cloneDeep(this.tasks);
      this.tasks = [];
      let currentValidation = _.cloneDeep(this.state.validation);
      const handleNext = () => {
        const task = tasks.pop();
        if (task) {
          const { path, validation } = task;
          let current = currentValidation.graph;
          path.map(segment => {
            if (!current[segment]) {
              current[segment] = {};
            }
            current = current[segment];
          });
          current.__valid = validation.__valid;
          current.__errors = validation.__errors;
          if (!current.__valid) {
            currentValidation.valid = false;
          }
          handleNext();
        } else {
          currentValidation.valid = this.isValid(currentValidation.graph);
          if (!_.isEqual(currentValidation, this.state.validation)) {
            this.setState({ validation: currentValidation });
            this.rootOnChange(this.getValue());
          }
        }
      }
      handleNext();
    }
  }

  getValue = () => {
    return this.props.value ? (_.isFunction(this.props.value) ? this.props.value() : this.props.value) : null;
  }

  validation = () => {
    if (this.props.root) {
      return this.state.validation;
    } else {
      return this.props.validation;
    }
  }

  rootOnChange = (value) => {
    const validation = this.validation();
    if (this.props.onChange) this.props.onChange(value, validation);
    if (this.props.onValidationChange) this.props.onValidationChange(validation);
  }

  setValidation = (path, validation) => {
    if (this.props.root) {
      this.tasks.push({ path, validation });
    } else {
      this.props.setValidation(path, validation);
    }
  }

  render() {
    const value = this.getValue();
    const flow = _.isFunction(this.props.flow) ? this.props.flow(value) : this.props.flow;
    const schema = _.isFunction(this.props.schema) ? this.props.schema(value) : this.props.schema;
    const propsComponents = _.isFunction(this.props.components) ? this.props.components(value) : this.props.components;
    const components = { ...NgForm.DefaultTheme, ...propsComponents }; // TODO: get theme from context also
    const FormRenderer = components.FormRenderer;
    const StepNotFound = components.StepNotFound;
    const root = !!this.props.root;
    const validation = root ? this.state.validation : this.props.validation;
    return (
      <FormRenderer {...this.props}>
        {flow.map(name => {
          const stepSchema = schema[name];
          if (stepSchema) {
            const visible = ('visible' in stepSchema) ? (_.isFunction(stepSchema.visible) ? stepSchema.visible(value) : stepSchema.visible) : true;
            if (visible) {
              return <NgStep
                key={name}
                name={name} 
                path={root ? [name] : [...this.props.path, name]}
                validation={validation}
                setValidation={this.setValidation}
                components={components}
                schema={stepSchema} 
                value={value ? value[name] : null}
                onChange={e => {
                  const newValue = value ? { ...value, [name]: e } : { [name]: e };
                  this.rootOnChange(newValue);
                }}
                rootValue={value} 
                rootOnChange={this.rootOnChange}
              />
            } else {
              return null;
            }
          } else {
            return <StepNotFound name={name} />
          }
        })}
      </FormRenderer>
    );
  }
}

export class NgFormState extends Component {

  state = { value: this.props.defaultValue || {}, validation: null };

  onChange = (value, validation) => {
    this.setState({ value, validation }, () => {
      if (this.props.onChange) {
        this.props.onChange(value, validation);
      }
    });
  }

  render() {
    return (
      <>
        {this.props.children(this.state.value, this.onChange)}
      </>
    );
  }
}

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
    return (
      <div style={{ marginTop: 40 }}>
        <NgForm 
          root 
          style={{ display: 'flex', flexDirection: 'column', width: '100%' }}
          value={this.state.value || {}}
          flow={["name", "email", "age", "done", "embed"]} 
          schema={{
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
          }}
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

/*

import { Form } from './Form';

export class NgFormTest extends Component {

  state = { value: this.props.value }

  onChange = (value) => {
    this.setState({ value });
    if (this.props.onChange) {
      this.props.onChange(value);
    }
  }

  componentDidUpdate(prevProps, prevState) {
    if (!_.isEqual(prevProps.value, this.props.value) || !_.isEqual(this.props.value, this.state.value) ) {
      this.setState({ value: this.props.value });
    }
  }

  computeFlow = (rawFlow) => {
    return rawFlow;
  }

  computeSchema = (rawSchema) => {
    const schema = {};
    Object.keys(rawSchema).map(key => {
      const itemSchema = rawSchema[key];
      schema[key] = {
        type: itemSchema.array ? 'array' : itemSchema.type,
        props: {
          label: itemSchema.label
        }
      };
    })
    return schema;
  }

  componentDidCatch(error, errorInfo) {
    this.setState({ error, errorInfo })
    console.log(error, errorInfo)
  }

  render() {
    if (this.state.error) {
      return (
        <>
          <h2 style={{ color: 'red' }}>error</h2>
          <pre>
            <code>
              {JSON.stringify(this.state.error, null, 2)}
            </code>
          </pre>
        </>
      );
    }
    const flow = this.computeFlow(this.props.flow, this.props.schema);
    const schema = this.computeSchema(this.props.schema);
    return (
      <Form
        value={this.state.value}
        onChange={(value) => this.setState({ value })}
        flow={flow}
        schema={schema}
      />
    )
  }
}

export class NgFormPlaygroundTest extends Component {

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
    return (
      <ul>
        {Object.keys(this.state.forms).map(key => {
          return (
            <li key={key}>
              <h3>{key}</h3>
              <NgForm name={key} flow={this.state.forms[key].flow} schema={this.state.forms[key].schema} />
            </li>
          )
        })}
      </ul>
    )
  }
}
*/