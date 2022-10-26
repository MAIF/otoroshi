import React, { Component, useState } from 'react';
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
  NgDotsRenderer,
  LabelAndInput,
  NgCustomFormsRenderer,
} from './inputs';

import {
  NgStepNotFound,
  NgRendererNotFound,
  NgValidationRenderer,
  NgFormRenderer,
  NgFlowNotFound,
} from './components';
import { Forms } from '../../forms';
import { camelCase } from 'lodash';

const Helpers = {
  rendererFor: (type, components = {}) => {
    if (type?.endsWith('-no-label')) {
      const Renderer = Helpers.rendererFor(type.replace('-no-label', ''), components);
      return (props) => <Renderer {...props} ngOptions={{ ...props.ngOptions, spread: true }} />
    } else if (type === 'string') {
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
    } else if (type === 'dots') {
      return components.DotsRenderer;
    } else if (type === 'form') {
      return NgForm;
    } else if (type === 'location') {
      return components.LocationRenderer;
    } else {
      const customForms = Forms[type]
      if (customForms) {
        return NgCustomFormsRenderer;
      } else {
        return components.RendererNotFound;
      }
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
      return this.props.schema.component;
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

const firstLetterUppercase = (str) => str.charAt(0).toUpperCase() + str.slice(1);

const Breadcrumb = ({ breadcrumb, setBreadcrumb, toHome }) => {
  if (!breadcrumb && !toHome)
    return null

  console.log(`BREADCRUMB: ${breadcrumb}`);

  return <div className="breadcrumbs my-2">
    <span
      className="breadcrumbs__item px-2"
      onClick={toHome}>
      <img width="20px" style={{ scale: '2.5' }} src="data:image/svg+xml;base64,PD94bWwgdmVyc2lvbj0iMS4wIiBlbmNvZGluZz0iVVRGLTgiPz4KPHN2ZyB3aWR0aD0iNzUycHQiIGhlaWdodD0iNzUycHQiIHZlcnNpb249IjEuMSIgdmlld0JveD0iMCAwIDc1MiA3NTIiIHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8yMDAwL3N2ZyI+CiA8Zz4KICA8cGF0aCBkPSJtMjk0LjYxIDUxNi42aC0zN2MtNC4wODU5IDAtNy4zOTg0LTMuMzE2NC03LjM5ODQtNy40MDIzdi0xNjIuNzloMTQuODAxdjE1NS4zOWgyOS41OTh6Ii8+CiAgPHBhdGggZD0ibTMzOSA1MDEuOGgyMi4xOTl2MTQuODAxaC0yMi4xOTl6Ii8+CiAgPHBhdGggZD0ibTQ5NC40IDUxNi42aC01OS4xOTl2LTE0LjgwMWg1MS43OTd2LTE1NS4zOWgxNC44MDF2MTYyLjc5YzAgMS45NjQ4LTAuNzgxMjUgMy44NDM4LTIuMTY4IDUuMjM0NC0xLjM4NjcgMS4zODY3LTMuMjY5NSAyLjE2OC01LjIzMDUgMi4xNjh6Ii8+CiAgPHBhdGggZD0ibTUxMi42OCAzNjcuNDktMTM2LjY4LTg2LjM1NS0xMzYuNjcgODYuMzU1LTcuOTE4LTEyLjUwNCAxNDAuNTktODguNzk3YzIuNDE4LTEuNTMxMiA1LjUwMzktMS41MzEyIDcuOTE4IDBsMTQwLjU5IDg4Ljc5N3oiLz4KICA8cGF0aCBkPSJtMzYxLjIgMzI0LjJoMjkuNTk4djE0LjgwMWgtMjkuNTk4eiIvPgogIDxwYXRoIGQ9Im0yODcuMiAzMzEuNmgtMTQuNzk3di00NC4zOThjMC00LjA4NTkgMy4zMTI1LTcuMzk4NCA3LjM5ODQtNy4zOTg0aDI5LjU5OGMxLjk2NDggMCAzLjg0NzcgMC43ODEyNSA1LjIzNDQgMi4xNjggMS4zODY3IDEuMzg2NyAyLjE2OCAzLjI2OTUgMi4xNjggNS4yMzA1djI5LjU5OGwtMTQuODAxIDAuMDAzOTA3di0yMi4xOTloLTE0LjgwMXoiLz4KICA8cGF0aCBkPSJtNDEzIDQ0Mi42aC03My45OTZjLTQuMDg1OSAwLTcuNDAyMy0zLjMxMjUtNy40MDIzLTcuMzk4NHYtNzMuOTk2YzAtNC4wODU5IDMuMzE2NC03LjQwMjMgNy40MDIzLTcuNDAyM2g3My45OTZjMS45NjA5IDAgMy44NDM4IDAuNzgxMjUgNS4yMzA1IDIuMTY4IDEuMzkwNiAxLjM4NjcgMi4xNjggMy4yNjk1IDIuMTY4IDUuMjM0NHY3My45OTZjMCAxLjk2MDktMC43NzczNCAzLjg0MzgtMi4xNjggNS4yMzA1LTEuMzg2NyAxLjM5MDYtMy4yNjk1IDIuMTY4LTUuMjMwNSAyLjE2OHptLTY2LjU5OC0xNC44MDFoNTkuMTk5di01OS4xOTVoLTU5LjE5OXoiLz4KICA8cGF0aCBkPSJtMzM5IDM5MC44aDczLjk5NnYxNC44MDFoLTczLjk5NnoiLz4KICA8cGF0aCBkPSJtMzY4LjYgMzYxLjJoMTQuODAxdjczLjk5NmgtMTQuODAxeiIvPgogIDxwYXRoIGQ9Im00OTQuNCA1MDEuOGgyOS41OTh2MTQuODAxaC0yOS41OTh6Ii8+CiAgPHBhdGggZD0ibTUxNi42IDQ5NC40aDE0LjgwMXY0NC4zOThoLTE0LjgwMXoiLz4KICA8cGF0aCBkPSJtMzE2LjggNTM4Ljc5Yy03Ljg1MTYgMC0xNS4zNzktMy4xMTcyLTIwLjkzLTguNjY4cy04LjY3MTktMTMuMDc4LTguNjcxOS0yMC45M2MwLTcuODQ3NyAzLjEyMTEtMTUuMzc5IDguNjcxOS0yMC45M3MxMy4wNzgtOC42NjggMjAuOTMtOC42NjhjNy44NDc3IDAgMTUuMzc5IDMuMTE3MiAyMC45MyA4LjY2OHM4LjY2OCAxMy4wODIgOC42NjggMjAuOTNjMCA3Ljg1MTYtMy4xMTcyIDE1LjM3OS04LjY2OCAyMC45M3MtMTMuMDgyIDguNjY4LTIwLjkzIDguNjY4em0wLTQ0LjM5OHYwLjAwMzkwN2MtMy45MjU4IDAtNy42OTE0IDEuNTU4Ni0xMC40NjUgNC4zMzItMi43NzczIDIuNzc3My00LjMzNTkgNi41MzkxLTQuMzM1OSAxMC40NjUgMCAzLjkyNTggMS41NTg2IDcuNjkxNCA0LjMzNTkgMTAuNDY1IDIuNzczNCAyLjc3NzMgNi41MzkxIDQuMzM1OSAxMC40NjUgNC4zMzU5IDMuOTI1OCAwIDcuNjg3NS0xLjU1ODYgMTAuNDY1LTQuMzM1OSAyLjc3MzQtMi43NzM0IDQuMzMyLTYuNTM5MSA0LjMzMi0xMC40NjUgMC0zLjkyNTgtMS41NTg2LTcuNjg3NS00LjMzMi0xMC40NjUtMi43NzczLTIuNzczNC02LjUzOTEtNC4zMzItMTAuNDY1LTQuMzMyeiIvPgogIDxwYXRoIGQ9Im0zODMuNCA1MzguNzljLTcuODUxNiAwLTE1LjM3OS0zLjExNzItMjAuOTMtOC42NjgtNS41NTA4LTUuNTUwOC04LjY3MTktMTMuMDc4LTguNjcxOS0yMC45MyAwLTcuODQ3NyAzLjEyMTEtMTUuMzc5IDguNjcxOS0yMC45MyA1LjU1MDgtNS41NTA4IDEzLjA3OC04LjY2OCAyMC45My04LjY2OCA3Ljg0NzcgMCAxNS4zNzkgMy4xMTcyIDIwLjkzIDguNjY4czguNjY4IDEzLjA4MiA4LjY2OCAyMC45M2MwIDcuODUxNi0zLjExNzIgMTUuMzc5LTguNjY4IDIwLjkzcy0xMy4wODIgOC42NjgtMjAuOTMgOC42Njh6bTAtNDQuMzk4djAuMDAzOTA3Yy0zLjkyNTggMC03LjY5MTQgMS41NTg2LTEwLjQ2NSA0LjMzMi0yLjc3NzMgMi43NzczLTQuMzM1OSA2LjUzOTEtNC4zMzU5IDEwLjQ2NSAwIDMuOTI1OCAxLjU1ODYgNy42OTE0IDQuMzM1OSAxMC40NjUgMi43NzM0IDIuNzc3MyA2LjUzOTEgNC4zMzU5IDEwLjQ2NSA0LjMzNTkgMy45MjE5IDAgNy42ODc1LTEuNTU4NiAxMC40NjUtNC4zMzU5IDIuNzczNC0yLjc3MzQgNC4zMzItNi41MzkxIDQuMzMyLTEwLjQ2NSAwLTMuOTI1OC0xLjU1ODYtNy42ODc1LTQuMzMyLTEwLjQ2NS0yLjc3NzMtMi43NzM0LTYuNTQzLTQuMzMyLTEwLjQ2NS00LjMzMnoiLz4KICA8cGF0aCBkPSJtMjk0LjYxIDI2NS4wMWMtMTIuNTEyIDAuMTQ4NDQtMjQuNjMzLTQuMzMyLTM0LjAzOS0xMi41ODItNi44NDM4LTYuMzEyNS0xNS44NTItOS43NTM5LTI1LjE2LTkuNjE3MnYtMTQuODAxYzEyLjUxMi0wLjE1MjM0IDI0LjYzMyA0LjMyODEgMzQuMDM5IDEyLjU3OCA2Ljg0MzggNi4zMTI1IDE1Ljg1MiA5Ljc1NzggMjUuMTYgOS42MjExeiIvPgogPC9nPgo8L3N2Zz4K" />
    </span>
    {breadcrumb && breadcrumb
      .map((part, i) => {
        return <span
          className={`breadcrumbs__item ${i === breadcrumb.length - 1 ? 'is-active' : ''}`}
          onClick={() => setBreadcrumb(i)} key={`${part}`}>
          {firstLetterUppercase(camelCase(part))}
        </span>
      })}
  </div>
}

function SubFlow({ fields = [], full_fields = [], render, config }) {
  const [moreFields, showMoreFields] = useState(false);

  const processedFields = isFunction(fields) ? fields(config) : fields;
  const processedAllFields = isFunction(full_fields) ? full_fields(config) : full_fields;
  const hasMoreFields = processedAllFields && processedAllFields.length > 0;

  return <>
    {!moreFields && processedFields.map(render)}
    {hasMoreFields && moreFields && processedAllFields.map(render)}

    {hasMoreFields && !moreFields && <button className='btn btn-sm btn-info mt-2'
      onClick={() => showMoreFields(!moreFields)}
      style={{
        marginLeft: 'auto',
        display: 'block'
      }}>
      Show advanced settings
    </button>}
  </>
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
    DotsRenderer: NgDotsRenderer,
    CustomFormsRenderer: NgCustomFormsRenderer,
    FlowNotFound: NgFlowNotFound,
  };

  static setTheme = (theme) => {
    NgForm.DefaultTheme = theme;
  };

  state = {
    validation: {
      valid: true,
      graph: {}
    }
  };
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
      schema.conditionalSchema ||
      schema.props
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
      } else if (schema.array && schema.type !== "array") {
        itemRenderer = Helpers.rendererFor(schema.type, this.props.components);
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
          ...schema.props,
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
    if (paths.length === 0) return value;
    return this.recursiveSearch(paths.slice(1), (value || {})[paths.slice(0, 1)]);
  };

  isAnObject = v => typeof v === 'object' && v !== null && !Array.isArray(v);

  getFlow = (value, schema) => {
    if (isFunction(this.props.flow)) {
      return {
        fields: this.props.flow(value, this.props)
      }
    } else if (this.isAnObject(this.props.flow) &&
      this.props.flow['otoroshi_flow'] &&
      this.props.flow['otoroshi_full_flow']) {
      return {
        fields: this.props.flow['otoroshi_flow'],
        full_fields: this.props.flow['otoroshi_full_flow']
      }
    } else if (this.isAnObject(this.props.flow) &&
      this.props.flow.field &&
      this.props.flow.flow) {
      /* useful to match the case of a json flow
      {
        flow: {
          field: 'name',
          flow: {
            Foo: ['a sub flow'], Bar: ['a other sub flow']
          }
        }
      }*/
      const paths = this.props.flow.field.split('.');
      const flow =
        this.props.flow.flow[this.recursiveSearch(paths, value || {})] ||
        this.props.flow.flow[this.recursiveSearch(paths, this.props.rootValue || {})];

      if (!flow) {
        return { fields: Object.values(this.props.flow.flow)[0] };
      } else {
        return { fields: flow };
      }
    } else if (!this.props.flow || this.props.flow?.length === 0) {
      return { fields: Object.keys(schema) };
    } else {
      return { fields: this.props.flow || [] };
    }
  };

  renderCustomFlow({ name, fields, renderer }, config) {
    return renderer({
      name,
      fields,
      renderStepFlow: (subName) => this.renderInlineStepFlow(subName, config),
    });
  }

  renderGroupFlow({ groupId, name, fields, collapsed, visible, full_fields, collapsable }, config) {
    const show = isFunction(visible) ? visible(config.value) : (visible !== undefined ? visible : true);
    if (!show) {
      return null;
    } else {
      const part = groupId || name;
      const fullPath = (config.root ? [part] : [...config.path, isFunction(part) ? undefined : part])
        .filter(f => f)
        .map(n => isFunction(n) ? '' : n);

      const label = isFunction(name) ? name(config) : name;

      const FormRenderer = config.components.FormRenderer;
      return <FormRenderer
        embedded={true}
        breadcrumb={config.breadcrumb}
        setBreadcrumb={!config.setBreadcrumb ? null : () => {
          config.setBreadcrumb(fullPath)
        }}
        useBreadcrumb={config.useBreadcrumb}
        path={fullPath}
        rawSchema={{
          label,
          collapsable: config.readOnly ? false : collapsable === undefined ? true : collapsable,
          collapsed: config.readOnly ? false : collapsed === undefined ? false : true
        }}
        key={fullPath}
      >
        <SubFlow
          fields={fields}
          full_fields={full_fields}
          render={field => this.renderStepFlow(field, {
            ...config,
            root: false,
            parent: fullPath.slice(-1)[0]
          })}
          config={{
            ...config,
            root: false,
            parent: fullPath.slice(-1)[0]
          }} />
      </FormRenderer>
    }
  }

  renderGridFlow({ name, fields, visible }, config) {
    const label = isFunction(name) ? name(config) : name;

    const show = isFunction(visible) ? visible(config.value) : (visible !== undefined ? visible : true)

    if (!show)
      return null

    const children = <div className="d-flex flex-wrap ms-3">
      {fields.map((subName) => (
        <div className="flex" style={{ minWidth: '50%' }} key={`${config.path}-${subName}`}>
          {this.renderStepFlow(subName, config)}
        </div>
      ))}
    </div>

    return (
      <div className="row" key={config.path}>
        {label && <LabelAndInput label={label}>
          {children}
        </LabelAndInput>}
        {!label && children}
      </div>
    );
  }

  match(test, breadcrumb) {
    const lowerTest = test.join('-').toLowerCase();
    const lowerBreadcrumb = breadcrumb.join('-').toLowerCase();
    return lowerTest.startsWith(lowerBreadcrumb) || lowerBreadcrumb.startsWith(lowerTest);
  }

  renderInlineStepFlow(name, {
    schema, value, root, path, validation, components, StepNotFound,
    parent, setBreadcrumb, breadcrumb, useBreadcrumb, readOnly
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
        const corePath = parent ? (root ? [name] : [...path, parent, name]) : newPath;

        if (Array.isArray(newPath) && Array.isArray(breadcrumb) && Array.isArray(corePath) &&
          !this.match(newPath, breadcrumb) && !this.match(corePath, breadcrumb))
          return null

        return (
          <NgStep
            key={newPath.join('/')}
            name={name}
            readOnly={readOnly}
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
            breadcrumb={breadcrumb}
            setBreadcrumb={setBreadcrumb}
            useBreadcrumb={useBreadcrumb}
            rootValue={value}
            rootOnChange={this.rootOnChange}
          />
        );
      } else {
        return null;
      }
    } else {
      return <StepNotFound name={name} key={path} />;
    }
  }

  renderStepFlow(name, config) {
    if (this.isAnObject(name)) {
      const composedFlow = name;
      if (composedFlow.type === 'grid') {
        return this.renderGridFlow(composedFlow, config);
      } else if (composedFlow.type === 'group') {
        return this.renderGroupFlow(composedFlow, config);
      } else if (composedFlow.type === 'custom') {
        return this.renderCustomFlow(composedFlow, config);
      } else {
        return React.createElement(config.components.FlowNotFound, {
          type: composedFlow.type,
          key: config.name
        });
      }
    } else {
      return this.renderInlineStepFlow(name, config);
    }
  }

  getBreadcrumb(root) {
    if (this.props.useBreadcrumb) {
      if (!root) {
        return this.props.setBreadcrumb;
      } else
        return (e) => {
          this.setState({ breadcrumb: e })
        }
    }

    return null;
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
    const readOnly = this.props.readOnly;

    const config = {
      schema, value, root, path, validation, components, StepNotFound,
      setBreadcrumb: this.getBreadcrumb(root),
      breadcrumb: root ? this.state.breadcrumb : this.props.breadcrumb,
      useBreadcrumb: this.props.useBreadcrumb,
      readOnly
    }

    return (
      <FormRenderer {...this.props}>
        {config.useBreadcrumb &&
          <Breadcrumb
            breadcrumb={this.state.breadcrumb}
            toHome={root ? () => {
              this.setState({
                breadcrumb: []
              })
            } : null}
            setBreadcrumb={i => {
              this.setState({
                breadcrumb: this.state.breadcrumb.slice(0, i + 1)
              })
            }} />
        }
        {flow.fields &&
          <SubFlow
            {...flow}
            render={name => this.renderStepFlow(name, config)}
            config={config} />
        }
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
