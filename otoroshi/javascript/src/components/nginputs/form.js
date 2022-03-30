import React, { Component } from 'react';
import _ from 'lodash';

import { NgPasswordRenderer, NgStringRenderer, NgSelectRenderer, NgTextRenderer, NgNumberRenderer, NgBooleanRenderer, NgArrayRenderer, NgObjectRenderer, NgArraySelectRenderer, NgObjectSelectRenderer, NgDateRenderer, NgHiddenRenderer, NgSingleCodeLineRenderer, NgCodeRenderer } from './inputs';
import { NgStepNotFound, NgRendererNotFound, NgValidationRenderer, NgFormRenderer } from './components';

const Helpers = {
  rendererFor: (type, components) => {
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
      return components.CodeRenderer;
    } else if (type === "single-line-of-code") {
      return components.SingleCodeLineRenderer;
    } else if (type === "text") {
      return components.TextRenderer;
    } else if (type === "hidden") {
      return components.HiddenRenderer;
    } else if (type === "password") {
      return components.PasswordRenderer;
    } else if (type === "form") {
      return NgForm;
    } else {
      return components.RendererNotFound;
    }
  }
}

export class NgStep extends Component {

  validate = (value) => {
    const constraints = this.props.schema.constraints || [];
    if (this.props.schema.typechecks) {
      if (this.props.schema.type === "string") {
        constraints.push(yup.string().optional())
      } else if (this.props.schema.type === "number") {
        constraints.push(yup.number().optional())
      } else if (this.props.schema.type === "bool" || this.props.schema.type === "boolean") {
        constraints.push(yup.boolean().optional())
      } else if (this.props.schema.type === "array") {
        // constraints.push(yup.array().of())
      } else if (this.props.schema.type === "object") {
        // constraints.push(yup.object().of())
      } 
    }
    if (this.props.schema.required) {
      if (this.props.schema.type === "string") {
        constraints.push(yup.string().required())
      } else if (this.props.schema.type === "number") {
        constraints.push(yup.number().required())
      } else if (this.props.schema.type === "bool" || this.props.schema.type === "boolean") {
        constraints.push(yup.boolean().required())
      } else if (this.props.schema.type === "array") {
        // constraints.push(yup.array().of())
      } else if (this.props.schema.type === "object") {
        // constraints.push(yup.object().of())
      } 
    }
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
        } else if (_.isFunction(validator)) {
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
        return Helpers.rendererFor(this.props.schema.renderer, this.props.components);
      } else {
        return this.props.schema.renderer;
      }
    } else {
      return Helpers.rendererFor(this.props.schema.type, this.props.components);
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
          embedded
          schema={this.props.schema.schema || this.props.schema} 
          flow={this.props.schema.flow || this.props.flow} 
          rawSchema={this.props.schema}
          rawFlow={this.props.flow}
        />
      </ValidationRenderer>
    );
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
    HiddenRenderer: NgHiddenRenderer,
    RendererNotFound: NgRendererNotFound,
    ValidationRenderer: NgValidationRenderer,
    SingleCodeLineRenderer: NgSingleCodeLineRenderer,
    CodeRenderer: NgCodeRenderer,
    PasswordRenderer: NgPasswordRenderer,
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
    if (!this.props.embedded) {
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
    if (!this.props.embedded) {
      this.tasks.push({ path, validation });
    } else {
      this.props.setValidation(path, validation);
    }
  }

  convertSchema = (schema) => {
    if (
      schema.array || 
      schema.format || 
      schema.createOption || 
      schema.isMulti || 
      schema.defaultKeyValue ||
      schema.label ||
      schema.placeholder ||
      schema.defaultValue ||
      schema.help ||
      schema.className ||
      schema.style ||
      schema.render ||
      schema.itemRender ||
      schema.conditionalSchema
    ) {
      const possible = {
        'select': 'select',
        'code': 'code',
        'singleLineCode': 'single-line-of-code',
        'markdown': 'code',
        'text': 'text',
        'hidden': 'hidden',
        'password': 'password',
        'form': 'form',
      };
      let renderer = possible[schema.format] || null;
      let itemRenderer = null;
      if (schema.array && schema.format === 'form') {
        itemRenderer = Helpers.rendererFor(renderer, this.props.components)
      }
      const config = {
        type: schema.array ? 'array' : (schema.format === 'form' ? 'form' : schema.type),
        of: schema.array ? schema.type : null,
        constraints: schema.constraints,
        visible: schema.visible,
        renderer: schema.array ? null : renderer,
        schema: schema.schema,
        flow: schema.flow,
        collapsable: schema.collapsable,
        collasped: schema.collasped,
        label: schema.label,
        itemRenderer: itemRenderer,
        props: {
          label: schema.label,
          placeholder: schema.placeholder,
          help: schema.help,
          disabled: schema.disabled,
        }
      }
      return config;
    } else {
      return schema;
    }
  }

  render() {
    const value = this.getValue();
    const flow = (_.isFunction(this.props.flow) ? this.props.flow(value) : this.props.flow) || [];
    const schema = (_.isFunction(this.props.schema) ? this.props.schema(value) : this.props.schema) || {};
    const propsComponents = _.isFunction(this.props.components) ? this.props.components(value) : this.props.components;
    const components = { ...NgForm.DefaultTheme, ...propsComponents }; // TODO: get theme from context also
    const FormRenderer = components.FormRenderer;
    const StepNotFound = components.StepNotFound;
    const embedded = !!this.props.embedded;
    const root = !embedded;
    const validation = root ? this.state.validation : this.props.validation;
    const path = this.props.path || [];
    return (
      <FormRenderer {...this.props}>
        {flow && flow.map(name => {
          const stepSchema = schema[name];
          if (stepSchema) {
            const visible = ('visible' in stepSchema) ? (_.isFunction(stepSchema.visible) ? stepSchema.visible(value) : stepSchema.visible) : true;
            if (visible) {
              return <NgStep
                key={name}
                name={name} 
                embedded
                fromArray={this.props.fromArray}
                path={root ? [name] : [...path, name]}
                validation={validation}
                setValidation={this.setValidation}
                components={components}
                schema={this.convertSchema(stepSchema)} 
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