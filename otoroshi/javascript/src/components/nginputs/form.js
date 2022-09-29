import React, { Component } from 'react';
import cloneDeep from 'lodash/cloneDeep';
import isFunction from 'lodash/isFunction';
import isEqual from 'lodash/isEqual';
import isString from 'lodash/isString';

import {
  NgPasswordRenderer,
  NgJsonRenderer,
  NgStringRenderer,
  NgSelectRenderer,
  NgTextRenderer,
  NgNumberRenderer,
  NgBooleanRenderer,
  NgArrayRenderer,
  NgObjectRenderer,
  NgArraySelectRenderer,
  NgObjectSelectRenderer,
  NgDateRenderer,
  NgHiddenRenderer,
  NgSingleCodeLineRenderer,
  NgCodeRenderer,
  NgLocationRenderer,
  LabelAndInput
} from './inputs';

import {
  NgStepNotFound,
  NgRendererNotFound,
  NgValidationRenderer,
  NgFormRenderer,
  NgFlowNotFound,
} from './components';

const Helpers = {
  rendererFor: (type, components) => {
    if (type === 'string') {
      return components.StringRenderer;
    } else if (type === 'bool' || type === 'boolean') {
      return components.BooleanRenderer;
    } else if (type === 'number') {
      return components.NumberRenderer;
    } else if (type === 'array') {
      return components.ArrayRenderer;
    } else if (type === 'object') {
      return components.ObjectRenderer;
    } else if (type === 'date') {
      return components.DateRenderer;
    } else if (type === 'select') {
      return components.SelectRenderer;
    } else if (type === 'array-select') {
      return components.ArraySelectRenderer;
    } else if (type === 'object-select') {
      return components.ObjectSelectRenderer;
    } else if (type === 'code') {
      return components.CodeRenderer;
    } else if (type === 'json') {
      return components.JsonRenderer;
    } else if (type === 'single-line-of-code') {
      return components.SingleCodeLineRenderer;
    } else if (type === 'text') {
      return components.TextRenderer;
    } else if (type === 'hidden') {
      return components.HiddenRenderer;
    } else if (type === 'password') {
      return components.PasswordRenderer;
    } else if (type === 'form') {
      return NgForm;
    } else if (type === 'location') {
      return components.LocationRenderer;
    } else {
      return components.RendererNotFound;
    }
  },
};

export class NgStep extends Component {
  state = { validation: { __valid: true, __errors: [] } };

  componentDidMount() {
    // this.handleValidation();
  }

  validate = (value) => {
    const constraints = this.props.schema.constraints || [];
    if (this.props.schema.typechecks) {
      if (this.props.schema.type === 'string') {
        constraints.push(yup.string().optional());
      } else if (this.props.schema.type === 'number') {
        constraints.push(yup.number().optional());
      } else if (this.props.schema.type === 'bool' || this.props.schema.type === 'boolean') {
        constraints.push(yup.boolean().optional());
      } else if (this.props.schema.type === 'array') {
        // constraints.push(yup.array().of())
      } else if (this.props.schema.type === 'object') {
        // constraints.push(yup.object().of())
      }
    }
    if (this.props.schema.required) {
      if (this.props.schema.type === 'string') {
        constraints.push(yup.string().required());
      } else if (this.props.schema.type === 'number') {
        constraints.push(yup.number().required());
      } else if (this.props.schema.type === 'bool' || this.props.schema.type === 'boolean') {
        constraints.push(yup.boolean().required());
      } else if (this.props.schema.type === 'array') {
        // constraints.push(yup.array().of())
      } else if (this.props.schema.type === 'object') {
        // constraints.push(yup.object().of())
      }
    }
    if (this.props.schema.constraints && this.props.schema.constraints.length > 0) {
      const res = { __valid: true, __errors: [] };
      this.props.schema.constraints.map((validator) => {
        if (validator.validateSync) {
          try {
            validator.validateSync(value);
          } catch (e) {
            res.__valid = false;
            res.__errors.push(e.message);
          }
        } else if (isFunction(validator)) {
          const r = validator(value);
          if (!r.valid) {
            res.__valid = false;
          }
          res.__errors = [...res.__errors, ...r.errors];
        }
      });
      this.props.setValidation(this.props.path, res);
      return res;
    } else {
      const res = { __valid: true, __errors: [] };
      this.props.setValidation(this.props.path, res);
      return res;
    }
  };

  renderer = () => {
    if (this.props.schema.component) {
      return this.props.schema.components;
    } else if (this.props.schema.renderer) {
      if (isString(this.props.schema.renderer)) {
        return Helpers.rendererFor(this.props.schema.renderer, this.props.components);
      } else {
        return this.props.schema.renderer;
      }
    } else {
      return Helpers.rendererFor(this.props.schema.type, this.props.components);
    }
  };

  handleValidation = (value) => {
    const constraints = this.props.schema.constraints || [];
    if (constraints && constraints.length > 0) {
      const validation = this.validate(value);
      console.debug(
        'trigger on change for',
        this.props.name,
        'at',
        '/' + this.props.path.join('/'),
        'with value',
        value,
        validation
      );
      this.setState({ validation });
    }
  };

  onChange = (value) => {
    this.handleValidation(value);
    if (this.props.onChange) {
      this.props.onChange(value);
    }
  };

  render() {
    const Renderer = this.renderer();
    // const validation = this.validate(this.props.value);
    const validation = this.state.validation;
    const ValidationRenderer = this.props.components.ValidationRenderer;

    return (
      <ValidationRenderer key={this.props.path.join('/')} validation={validation}>
        <Renderer
          validation={validation}
          {...this.props}
          embedded
          onChange={this.onChange}
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
    JsonRenderer: NgJsonRenderer,
    LocationRenderer: NgLocationRenderer,
    FlowNotFound: NgFlowNotFound
  };

  static setTheme = (theme) => {
    NgForm.DefaultTheme = theme;
  };

  state = { validation: { valid: true, graph: {} } };
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
  };

  handleTasks = () => {
    if (this.tasks.length > 0) {
      const tasks = cloneDeep(this.tasks);
      this.tasks = [];
      let currentValidation = cloneDeep(this.state.validation);
      const handleNext = () => {
        const task = tasks.pop();
        if (task) {
          const { path, validation } = task;
          let current = currentValidation.graph;
          path.map((segment) => {
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
          if (!isEqual(currentValidation, this.state.validation)) {
            this.setState({ validation: currentValidation });
            this.rootOnChange(this.getValue());
          }
        }
      };
      handleNext();
    }
  };

  getValue = () => {
    return this.props.value
      ? isFunction(this.props.value)
        ? this.props.value()
        : this.props.value
      : null;
  };

  validation = () => {
    if (!this.props.embedded) {
      return this.state.validation;
    } else {
      return this.props.validation;
    }
  };

  rootOnChange = (value) => {
    const validation = this.validation();
    if (this.props.onChange) this.props.onChange(value, validation);
    if (this.props.onValidationChange) this.props.onValidationChange(validation);
  };

  setValidation = (path, validation) => {
    if (!this.props.embedded) {
      this.tasks.push({ path, validation });
    } else {
      this.props.setValidation(path, validation);
    }
  };

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
      schema.itemRenderer ||
      schema.conditionalSchema
    ) {
      const possible = {
        select: 'select',
        code: 'code',
        singleLineCode: 'single-line-of-code',
        markdown: 'code',
        text: 'text',
        hidden: 'hidden',
        password: 'password',
        form: 'form',
      };
      let renderer = possible[schema.format] || null;
      let itemRenderer = null;
      if (schema.array && schema.format === 'form') {
        itemRenderer = Helpers.rendererFor(renderer, this.props.components);
      }
      const config = {
        type: schema.array ? 'array' : schema.format === 'form' ? 'form' : schema.type,
        of: schema.array ? schema.type : null,
        constraints: schema.constraints,
        visible: schema.visible,
        renderer: schema.array ? null : renderer,
        schema: schema.schema,
        flow: schema.flow,
        collapsable: schema.collapsable,
        collapsed: schema.collapsed,
        label: schema.label,
        itemRenderer: itemRenderer || schema.itemRenderer,
        props: {
          label: schema.label,
          placeholder: schema.placeholder,
          help: schema.help,
          disabled: schema.disabled,
        },
      };
      return config;
    } else {
      return schema;
    }
  };

  recursiveSearch = (paths, value) => {
    if (paths.length === 0)
      return value
    return this.recursiveSearch(paths.slice(1), (value || {})[paths.slice(0, 1)])
  }

  isAnObject = variable => {
    return typeof variable === 'object' &&
      variable !== null &&
      !Array.isArray(variable);
  }

  getFlow = (value, schema) => {
    if (isFunction(this.props.flow)) {
      return this.props.flow(value, this.props)
    }

    // useful to match the case of a json flow 
    /*
      {
        schema: {
          name: { ...}
        }
        flow: {
          field: 'name',
          flow: {
            Foo: ['a sub flow'],
            Bar: ['a other sub flow']
          }
        }
      }
    */
    if (this.isAnObject(this.props.flow) &&
      this.props.flow.field &&
      this.props.flow.flow) {
      const paths = this.props.flow.field.split('.')
      const flow = this.props.flow.flow[this.recursiveSearch(paths, (value || {}))] ||
        this.props.flow.flow[this.recursiveSearch(paths, (this.props.rootValue || {}))]

      if (!flow)
        return Object.values(this.props.flow.flow)[0]
      else
        return flow

    }

    if (this.props.flow?.length === 0)
      return Object.keys(schema)

    return this.props.flow || []
  }

  renderCustomFlow({ name, fields, renderer }, config) {
    return renderer({
      name,
      fields,
      renderStepFlow: subName => this.renderInlineStepFlow(subName, config)
    })
  }

  renderGroupFlow({ name, fields, collapsed }, config) {
    const FormRenderer = config.components.FormRenderer;

    return <FormRenderer
      embedded={true}
      rawSchema={{
        label: isFunction(name) ? name(config) : name,
        collapsable: true,
        collapsed: collapsed === undefined ? false : true
      }}>
      {fields.map(subName => this.renderStepFlow(subName, config))}
    </FormRenderer>
  }

  renderGridFlow({ name, fields }, config) {
    return <div className='row'>
      <LabelAndInput label={name}>
        <div className='d-flex flex-wrap ms-3'>
          {fields
            .map(subName => <div className='flex' style={{ minWidth: '50%' }} key={`${name}-${subName}`}>
              {this.renderStepFlow(subName, config)}
            </div>)
          }
        </div>
      </LabelAndInput>
    </div>
  }

  renderInlineStepFlow(name, {
    schema, value, root, path, validation, components, StepNotFound
  }) {
    const stepSchema = schema[name];

    if (stepSchema) {
      const visible =
        'visible' in stepSchema
          ? isFunction(stepSchema.visible)
            ? stepSchema.visible(value)
            : stepSchema.visible
          : true;
      if (visible) {
        const newPath = root ? [name] : [...path, name];
        // console.log(newPath)
        return (
          <NgStep
            key={newPath.join('/')}
            name={name}
            embedded
            fromArray={this.props.fromArray}
            path={newPath}
            validation={validation}
            setValidation={this.setValidation}
            components={components}
            schema={this.convertSchema(stepSchema)}
            value={value ? value[name] : null}
            onChange={(e) => {
              const newValue = value ? { ...value, [name]: e } : { [name]: e };
              this.rootOnChange(newValue);
            }}
            rootValue={value}
            rootOnChange={this.rootOnChange}
          />
        );
      } else {
        return null;
      }
    } else {
      return <StepNotFound name={name} />;
    }
  }

  renderStepFlow(name, config) {
    if (this.isAnObject(name)) {
      const composedFlow = name
      if (composedFlow.type === 'grid') {
        return this.renderGridFlow(composedFlow, config)
      } else if (composedFlow.type === 'group') {
        return this.renderGroupFlow(composedFlow, config)
      } else if (composedFlow.type === 'custom') {
        return this.renderCustomFlow(composedFlow, config)
      } else {
        return React.createElement(config.components.FlowNotFound, {
          type: composedFlow.type
        })
      }
    } else {
      return this.renderInlineStepFlow(name, config);
    }
  }

  render() {
    const value = this.getValue();
    const schema =
      (isFunction(this.props.schema) ? this.props.schema(value) : this.props.schema) || {};
    const flow = this.getFlow(value, schema);
    const propsComponents = isFunction(this.props.components)
      ? this.props.components(value)
      : this.props.components;
    const components = { ...NgForm.DefaultTheme, ...propsComponents }; // TODO: get theme from context also
    const FormRenderer = components.FormRenderer;
    const StepNotFound = components.StepNotFound;
    const embedded = !!this.props.embedded;
    const root = !embedded;
    const validation = root ? this.state.validation : this.props.validation;
    const path = this.props.path || [];

    const config = { schema, value, root, path, validation, components, StepNotFound }

    return (
      <FormRenderer {...this.props}>
        {flow &&
          flow.map(name => this.renderStepFlow(name, config))}
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
  };

  render() {
    return <>{this.props.children(this.state.value, this.onChange)}</>;
  }
}

export class NgManagedState extends Component {
  state = {
    value: this.props.defaultValue || {},
    firstValue: this.props.defaultValue || {},
    lastValue: this.props.defaultValue || {},
    validation: { valid: true },
    isDirty: false,
  };

  onChange = (value, validation) => {
    const isDirty = !isEqual(lastValue, value);
    this.setState({ value, validation, isDirty }, () => {
      if (this.props.onValidationChange) {
        this.props.onValidationChange(validation);
      }
      if (this.props.onValueChange) {
        this.props.onValueChange(value);
      }
    });
  };

  onSubmit = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    const { validation, isDirty, value } = this.state;
    if (isDirty && validation.valid) {
      this.setState({ lastValue: value, isDirty: false }, () => {
        if (this.props.onSubmit) {
          this.props.onSubmit(value);
        }
      });
    }
  };

  onReset = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    const { lastValue } = this.state;
    this.setState({ value: lastValue, lastValue, isDirty: false });
  };

  resetButton = () => {
    let ResetButton = (
      <button
        type="button"
        className="btn btn-danger"
        disabled={this.state.isDirty}
        onClick={this.onReset}>
        Reset
      </button>
    );
    if (this.props.noReset) {
      ResetButton = null;
    }
    if (this.props.resetButton) {
      ResetButton = this.props.resetButton(this.onReset, this.state.isDirty);
    }
    return ResetButton;
  };

  submitButton = () => {
    let SubmitButton = (
      <button
        type="button"
        className="btn btn-success"
        disabled={this.state.isDirty}
        onClick={this.onSubmit}>
        Submit
      </button>
    );
    if (this.props.noSubmit) {
      SubmitButton = null;
    }
    if (this.props.submitButton) {
      SubmitButton = this.props.submitButton(this.onSubmit, this.state.isDirty);
    }
    return SubmitButton;
  };

  render() {
    const resetButton = this.resetButton();
    return (
      <div style={this.props.style || { display: 'flex', flexDirection: 'column', width: '100%' }}>
        <NgForm
          style={this.props.formStyle}
          flow={this.props.flow}
          schema={this.props.schema}
          value={this.state.value}
          onChange={this.onChange}
        />
        <div className="btn-group">
          {resetButton}
          {submitButton}
        </div>
      </div>
    );
  }
}
