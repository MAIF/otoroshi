import React, { Component, Suspense } from 'react';
import Select from 'react-select';
import isFunction from 'lodash/isFunction';
import { OffSwitch, OnSwitch } from '../inputs/BooleanInput';
import { Location } from '../Location';
import { ObjectInput } from '../inputs';
import isEqual from 'lodash/isEqual';

const CodeInput = React.lazy(() => Promise.resolve(require('../inputs/CodeInput')));

export class NgLocationRenderer extends Component {
  render() {
    const schema = this.props.schema;
    const props = schema.props || {};
    const FormRenderer = this.props.components.FormRenderer;

    return <FormRenderer
      embedded={true}
      breadcrumb={[]} // TODO
      setBreadcrumb={this.props.setBreadcrumb} // TODO
      rawSchema={{
        label: 'Location',
        collapsable: true,
        collapsed: true
      }}>
      <Location
        {...props}
        tenant={this.props.value?.tenant || 'default'}
        teams={this.props.value?.teams || ['default']}
        onChangeTenant={tenant => this.props.onChange({
          ...this.props.value,
          tenant
        })}
        onChangeTeams={teams => this.props.onChange({
          ...this.props.value,
          teams
        })}
      />
    </FormRenderer>
  }
}

export class NgDotsRenderer extends Component {
  render() {
    const schema = this.props.schema || {};
    const props = schema.props || this.props || {};

    const options = props.options || this.props.options;
    const isValueArray = Array.isArray(this.props.value);

    const onClick = selectedValue => {
      if (isValueArray) {
        if (this.props.value.includes(selectedValue))
          return this.props.value.filter(opt => opt !== selectedValue);
        else
          return [...this.props.value, selectedValue];
      }
      else {
        return selectedValue;
      }
    }

    return (
      <LabelAndInput {...this.props}>
        {options.map(option => {
          const selected = isValueArray ? this.props.value.includes(option) : this.props.value === option;
          return <button className={`btn btn-sm ${selected ? 'btn-info' : 'btn-dark'} me-2 px-3`}
            type="button"
            key={option}
            style={{
              borderRadius: '24px'
            }}
            onClick={() => this.props.onChange(onClick(option))}>
            {selected && <i className='fas fa-check me-1' />}
            {option}
          </button>
        })}
      </LabelAndInput>
    )
  }
}

export class SingleLineCode extends Component {
  render() {
    return <div>SingleLineCode</div>;
  }
}

export function LabelAndInput(_props) {
  const schema = _props.schema || {};
  const props = schema.props || {};
  const label = _props.label || props.label || _props.rawSchema?.label || _props.name || '...';
  const ngOptions = _props.ngOptions || props.ngOptions || _props.rawSchema?.props?.ngOptions || {};
  const labelColumn = _props.labelColumn || props.labelColumn || 2;

  if (ngOptions.spread) return _props.children;

  const margin = _props.margin || props.margin || _props.rawSchema?.props?.margin || "mb-3"

  return (
    <div className={`row ${margin}`}>
      <label
        className={`col-xs-12 col-sm-${labelColumn} col-form-label`}
        style={{
          textAlign: labelColumn === 2 ? 'right' : 'left',
        }}>
        {label.replace(/_/g, ' ')}{' '}
        {_props.help && (
          <i
            className="far fa-question-circle"
            data-toggle="tooltip"
            data-placement="top"
            title={_props.help}
            data-bs-original-title={_props.help}
            aria-label={_props.help}
          />
        )}
      </label>
      <div className={`col-sm-${12 - labelColumn}`}>{_props.children}</div>
    </div>
  );
}

export class NgSingleCodeLineRenderer extends Component {
  render() {
    return (
      <LabelAndInput {...this.props}>
        <SingleLineCode value={this.props.value} onChange={(e) => this.props.onChange(e)} />
      </LabelAndInput>
    );
  }
}

export class NgCodeRenderer extends Component {
  render() {
    return (
      <LabelAndInput {...this.props}>
        <Suspense fallback={<div>Loading</div>}>
          <CodeInput
            {...this.props.rawSchema?.props}
            value={this.props.value}
            onChange={(e) => this.props.onChange(e)}
            style={{ width: '100%' }}
          />
        </Suspense>
      </LabelAndInput>
    );
  }
}

export class NgJsonRenderer extends Component {
  render() {
    return (
      <Suspense fallback={<div>Loading</div>}>
        <CodeInput
          {...this.props.rawSchema?.props}
          value={JSON.stringify(this.props.value, null, 2)}
          onChange={(e) => {
            try {
              this.props.onChange(JSON.parse(e));
            } catch (ex) { }
          }}
          style={{ width: '100%' }}
        />
      </Suspense>
    );
  }
}

export class NgStringRenderer extends Component {
  render() {
    const schema = this.props.schema;
    const props = schema.props || {};
    return (
      <LabelAndInput {...this.props}>
        <input
          type="text"
          className="form-control"
          placeholder={props.placeholder}
          title={props.help}
          value={this.props.value}
          onChange={(e) => this.props.onChange(e.target.value)}
          {...props}
        />
      </LabelAndInput>
    );
  }
}

export class NgPasswordRenderer extends Component {
  render() {
    const schema = this.props.schema;
    const props = schema.props || {};
    return (
      <LabelAndInput {...this.props}>
        <input
          type="password"
          className="form-control"
          placeholder={props.placeholder}
          title={props.help}
          value={this.props.value}
          onChange={(e) => this.props.onChange(e.target.value)}
          {...props}
        />
      </LabelAndInput>
    );
  }
}

export class NgNumberRenderer extends Component {
  render() {
    const schema = this.props.schema;
    const props = schema.props || {};
    return (
      <LabelAndInput {...this.props}>
        <input
          type="number"
          className="form-control"
          placeholder={props.placeholder}
          title={props.help}
          value={this.props.value}
          onChange={(e) => this.props.onChange(~~e.target.value)}
          {...props}
        />
      </LabelAndInput>
    );
  }
}

export class NgHiddenRenderer extends Component {
  render() {
    const schema = this.props.schema;
    const props = schema.props || {};
    return (
      <input
        type="hidden"
        className="form-control"
        placeholder={props.placeholder}
        title={props.help}
        value={this.props.value}
        onChange={(e) => this.props.onChange(e.target.value)}
        {...props}
      />
    );
  }
}

export class NgTextRenderer extends Component {
  render() {
    const schema = this.props.schema;
    const props = schema.props || {};
    return (
      <LabelAndInput {...this.props}>
        <textarea
          placeholder={props.placeholder}
          className="form-control"
          title={props.help}
          onChange={(e) => this.props.onChange(e.target.value)}
          {...props}>
          {this.props.value}
        </textarea>
      </LabelAndInput>
    );
  }
}

export class NgDateRenderer extends Component {
  render() {
    const schema = this.props.schema;
    const props = schema.props || {};
    return (
      <LabelAndInput {...this.props}>
        <input
          type="date"
          className="form-control"
          placeholder={props.placeholder}
          title={props.help}
          value={this.props.value}
          onChange={(e) => this.props.onChange(e.target.value)}
          {...props}
        />
      </LabelAndInput>
    );
  }
}

export class NgBooleanRenderer extends Component {
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
      <LabelAndInput {...this.props}>
        {this.props.value && <OnSwitch onChange={this.toggleOff} />}
        {!this.props.value && <OffSwitch onChange={this.toggleOn} />}
      </LabelAndInput>
    );
  }
}

export class NgArrayRenderer extends Component {

  canShowActions(path) {
    const breadcrumbAsArray = this.props.breadcrumb || [];
    const pathAsArray = path || this.props.path || [];

    if (this.props.breadcrumb === undefined)
      return true;

    if (path)
      return isEqual(pathAsArray, breadcrumbAsArray)

    return pathAsArray.length >= breadcrumbAsArray.length &&
      (pathAsArray.join('-').startsWith(pathAsArray.join('-')) ||
        pathAsArray.join('-').startsWith(pathAsArray.join('-')))
  }

  isAnObject = v => typeof v === 'object' && v !== null && !Array.isArray(v);

  defaultValues = (current) => ({
    number: () => 0,
    boolean: () => false,
    bool: () => false,
    array: () => [],
    select: () => current?.props?.options[0] || '',
    form: () => ({
      ...this.generateDefaultValue(current.schema)
    }),
    object: () => { },
    json: () => { },
  })

  generateDefaultValue = obj => {
    return Object.entries(obj)
      .reduce((acc, current) => {
        const value = this.defaultValues(current[1])[current[1].type]
        return {
          ...acc,
          [current[0]]: value ? value() : ''
        }
      }, {})
  }

  render() {
    const schema = this.props.schema;
    const props = schema.props || {};
    const ItemRenderer = schema.itemRenderer || this.props.rawSchema.itemRenderer;

    const showActions = this.canShowActions()

    return (
      <LabelAndInput {...this.props}>
        <div style={{
          display: 'flex', flexDirection: 'column', width: '100%'
        }}>
          {Array.isArray(this.props.value) &&
            this.props.value.map((value, idx) => {
              const path = [...this.props.path, String(idx)]
              const showItem = this.canShowActions(path)
              return (
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    width: '100%',
                    outline: showItem ? 'rgb(65, 65, 62) solid 1px' : 'none',
                    padding: showItem ? '6px' : 0,
                    marginBottom: showItem ? '6px' : 0
                  }} key={path}>
                  {!ItemRenderer && (
                    <input
                      type="text"
                      className="form-control"
                      placeholder={props.placeholder}
                      title={props.help}
                      value={value}
                      onChange={(e) => {
                        const newArray = this.props.value ? [...this.props.value] : [];
                        newArray.splice(idx, 1, e.target.value);
                        this.props.onChange(newArray);
                      }}
                      style={{ width: '100%' }}
                      {...props}
                    />
                  )}
                  {ItemRenderer && (
                    <ItemRenderer
                      embedded
                      fromArray
                      breadcrumb={this.props.breadcrumb}
                      setBreadcrumb={this.props.setBreadcrumb}
                      useBreadcrumb={this.props.useBreadcrumb}
                      ngOptions={this.props.itemNgOptions || { spread: true }}
                      path={path}
                      flow={this.props.flow}
                      schema={this.props.schema}
                      components={this.props.components}
                      validation={this.props.validation}
                      setValidation={this.props.setValidation}
                      rootValue={this.props.rootValue}
                      rootOnChange={this.props.rootOnChange}
                      rawSchema={{
                        ...this.props.rawSchema,
                        collapsable: false,
                        noBorder: true,
                        noTitle: true,
                      }}
                      rawFlow={this.props.rawFlow}
                      value={value}
                      onChange={(e) => {
                        const newArray = this.props.value ? [...this.props.value] : [];
                        newArray.splice(idx, 1, e);
                        this.props.onChange(newArray);
                      }}
                      {...props}
                    />
                  )}
                  {showActions && <button
                    type="button"
                    className="btn btn-sm btn-danger"
                    style={{ width: 42, marginLeft: 5 }}
                    onClick={(e) => {
                      const newArray = this.props.value ? [...this.props.value] : [];
                      newArray.splice(idx, 1);
                      this.props.onChange(newArray);
                    }}>
                    <i className="fas fa-trash" />
                  </button>}
                </div>
              );
            })}
          {showActions && <button
            type="button"
            className="btn btn-sm btn-info float-end"
            style={{ width: 42, marginTop: 5 }}
            onClick={() => {
              let newArr = [...(this.props.value || [])]

              if (schema.of)
                return this.props.onChange([...newArr, ''])
              else {
                const newArray = [...newArr, this.generateDefaultValue(schema)]
                this.props.onChange(newArray);
              }
            }}>
            <i className="fas fa-plus-circle" />
          </button>}
        </div>
      </LabelAndInput>
    );
  }
}

export class NgObjectRenderer extends Component {
  render() {
    const schema = this.props.schema;
    const props = schema.props || {};
    const ItemRenderer = schema.itemRenderer || this.props.rawSchema.itemRenderer;

    return (
      <LabelAndInput {...this.props}>
        <ObjectInput
          ngOptions={{
            spread: true,
          }}
          label={null}
          placeholderKey={props.placeholderKey}
          placeholderValue={props.placeholderValue}
          value={this.props.value}
          onChange={this.props.onChange}
          itemRenderer={
            ItemRenderer
              ? (key, value, idx) => (
                <ItemRenderer
                  embedded
                  flow={this.props.flow}
                  schema={this.props.schema}
                  value={value}
                  key={key}
                  idx={idx}
                  onChange={(e) => {
                    const newObject = this.props.value ? { ...this.props.value } : {};
                    newObject[key] = e;
                    this.props.onChange(newObject);
                  }}
                  {...props}
                />
              )
              : null
          }
        />
      </LabelAndInput>
    );
  }
}

export class NgArraySelectRenderer extends Component {
  state = {};
  componentDidMount() {
    const schema = this.props.schema || {};
    const props = schema.props || {};
    if (props.optionsFrom) {
      this.setState({ loading: true }, () => {
        fetch(props.optionsFrom, {
          method: 'GET',
          credentials: 'include',
        })
          .then((r) => r.json())
          .then((r) => {
            this.setState({
              loading: false,
              options: r,
            });
          })
          .catch((e) => {
            this.setState({ loading: false });
          });
      });
    }
  }

  applyTransformer = (props, r) => {
    if (props.optionsTransformer) {
      if (isFunction(props.optionsTransformer)) return props.optionsTransformer(r || []);
      else
        return (r || []).map((item) => ({
          label: item[props.optionsTransformer.label],
          value: item[props.optionsTransformer.value],
        }));
    } else if ((r || []).length > 0 && r[0].label && r[0].value) {
      return r;
    } else return (r || []).map((rawValue) => ({ label: rawValue, value: rawValue }));
  };

  render() {
    const schema = this.props.schema || {};
    const props = schema.props || {};

    return (
      <LabelAndInput {...this.props}>
        <div style={{ display: 'flex', flexDirection: 'column', width: '100%' }}>
          {Array.isArray(this.props.value) &&
            this.props.value.map((value, idx) => {
              return (
                <div
                  className="d-flex justify-content-between align-items-center mb-1"
                  key={`${value}-${idx}`}>
                  <div style={{ width: '100%', flex: 1 }}>
                    <Select
                      name={`selector-${this.props.name}`}
                      value={value}
                      isLoading={this.state.loading}
                      disabled={props.disabled}
                      placeholder={props.placeholder}
                      optionRenderer={props.optionRenderer}
                      options={this.applyTransformer(
                        props || this.props,
                        this.state.options || props.options || []
                      )}
                      style={{ width: '100%' }}
                      onChange={(e) => {
                        const newArray = this.props.value ? [...this.props.value] : [];
                        newArray.splice(idx, 1, e?.value || '');
                        this.props.onChange(newArray);
                      }}
                    />
                  </div>
                  <button
                    type="button"
                    className="btn btn-sm btn-danger"
                    style={{ width: 42, marginLeft: 5, alignSelf: 'flex-start' }}
                    onClick={(e) => {
                      const newArray = this.props.value ? [...this.props.value] : [];
                      newArray.splice(idx, 1);
                      this.props.onChange(newArray);
                    }}>
                    <i className="fas fa-trash" />
                  </button>
                </div>
              );
            })}
          <button
            type="button"
            className="btn btn-sm btn-info float-end"
            style={{ width: 42, marginTop: 5 }}
            onClick={(e) => {
              const newArray = Array.isArray(this.props.value) ? [...this.props.value, ''] : [''];
              this.props.onChange(newArray);
            }}>
            <i className="fas fa-plus-circle" />
          </button>
        </div>
      </LabelAndInput>
    );
  }
}

export class NgObjectSelectRenderer extends Component {
  state = {};
  componentDidMount() {
    const schema = this.props.schema || {};
    const props = schema.props || {};
    if (props.optionsFrom) {
      this.setState({ loading: true }, () => {
        fetch(this.props.schema.props.optionsFrom, {
          method: 'GET',
          credentials: 'include',
        })
          .then((r) => r.json())
          .then((r) => {
            this.setState({ loading: false });
            if (props.optionsTransformer) {
              this.setState({ options: props.optionsTransformer(r) });
            } else {
              this.setState({ options: r });
            }
          })
          .catch((e) => {
            this.setState({ loading: false });
          });
      });
    }
  }
  render() {
    const schema = this.props.schema || {};
    const props = schema.props || {};

    return (
      <LabelAndInput {...this.props}>
        <div style={{ display: 'flex', flexDirection: 'column', width: '100%' }}>
          {this.props.value &&
            Object.keys(this.props.value)
              .map((key) => [key, this.props.key])
              .map((raw, idx) => {
                const [key, value] = raw;
                return (
                  <div
                    style={{
                      display: 'flex',
                      flexDirection: 'row',
                      justifyContent: 'space-between',
                      alignItems: 'center',
                      width: '100%',
                    }}>
                    <input
                      type="text"
                      placeholder={props.placeholderKey}
                      title={props.help}
                      value={key}
                      onChange={(e) => {
                        const newObject = this.props.value ? { ...this.props.value } : {};
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
                      options={this.state.options || props.options}
                      style={{ width: '100%' }}
                      onChange={(e) => {
                        const newObject = this.props.value ? { ...this.props.value } : {};
                        newObject[key] = e.value;
                        this.props.onChange(newObject);
                      }}
                    />
                    <button
                      type="button"
                      className="btn btn-sm btn-danger"
                      style={{ width: 42, marginLeft: 5 }}
                      onClick={(e) => {
                        const newObject = this.props.value ? { ...this.props.value } : {};
                        delete newObject[key];
                        this.props.onChange(newObject);
                      }}>
                      <i className="fas fa-trash" />
                    </button>
                  </div>
                );
              })}
          <button
            type="button"
            className="btn btn-sm btn-info float-end"
            style={{ width: 42, marginTop: 5 }}
            onClick={(e) => {
              const newObject = { ...this.props.value };
              newObject[''] = '';
              this.props.onChange(newObject);
            }}>
            <i className="fas fa-plus-circle" />
          </button>
        </div>
      </LabelAndInput>
    );
  }
}

export class NgSelectRenderer extends Component {
  state = {};
  componentDidMount() {
    const schema = this.props.schema || {};
    const props = schema.props || {};
    if (props.optionsFrom || this.props.optionsFrom) {
      this.setState({ loading: true }, () => {
        fetch(props.optionsFrom || this.props.optionsFrom, {
          method: 'GET',
          credentials: 'include',
        })
          .then((r) => r.json())
          .then((r) => {
            this.setState({
              loading: false,
              options: r,
            });
          })
          .catch((e) => {
            this.setState({ loading: false });
          });
      });
    }
  }

  applyTransformer = (props, r) => {
    if (props.optionsTransformer) {
      if (isFunction(props.optionsTransformer)) {
        return props.optionsTransformer(r || []);
      } else
        return (r || []).map((item) => ({
          label: item[props.optionsTransformer.label],
          value: item[props.optionsTransformer.value],
        }));
    } else if ((r || []).length > 0 && r[0].label && r[0].value) {
      return r;
    } else return (r || []).map((rawValue) => ({ label: rawValue, value: rawValue }));
  };

  render() {
    const schema = this.props.schema || {};
    const props = schema.props || this.props || {};

    return (
      <LabelAndInput {...this.props}>
        <Select
          name={`selector-${this.props.name}`}
          value={this.props.value}
          isMulti={props.isMulti}
          isLoading={this.state.loading}
          disabled={props.disabled}
          placeholder={props.placeholder}
          optionRenderer={props.optionRenderer}
          options={this.applyTransformer(
            props || this.props,
            this.state.options || props.options || this.props.options
          )}
          onChange={(e) => this.props.onChange(e?.value)}
        />
      </LabelAndInput>
    );
  }
}
