import React, { Component, Suspense } from 'react';
import Select from 'react-select';
import isFunction from 'lodash/isFunction';
import { OffSwitch, OnSwitch } from '../inputs/BooleanInput';
import { Location } from '../Location';
import { ObjectInput } from '../inputs';
import isEqual from 'lodash/isEqual';
import { Forms } from '../../forms';
import { Button } from '../Button';

const CodeInput = React.lazy(() => Promise.resolve(require('../inputs/CodeInput')));

const ReadOnlyField = ({ value, pre }) => {
  if (pre) {
    return (
      <pre className="d-flex align-items-center" style={{ height: '100%', color: '#fff' }}>
        {value}
      </pre>
    );
  } else {
    return (
      <span className="d-flex align-items-center" style={{ height: '100%', color: '#fff' }}>
        {value}
      </span>
    );
  }
};

export class NgLocationRenderer extends Component {
  render() {
    const schema = this.props.schema;
    const props = schema.props || {};
    const readOnly = this.props.readOnly;
    const FormRenderer = this.props.components.FormRenderer;

    const component = (
      <Location
        {...props}
        readOnly={readOnly}
        tenant={this.props.value?.tenant || 'default'}
        teams={this.props.value?.teams || ['default']}
        onChangeTenant={(tenant) =>
          this.props.onChange({
            ...this.props.value,
            tenant,
          })
        }
        onChangeTeams={(teams) =>
          this.props.onChange({
            ...this.props.value,
            teams,
          })
        }
      />
    );

    if (readOnly) {
      return component;
    } else {
      return (
        <FormRenderer
          embedded={true}
          breadcrumb={[]} // TODO
          setBreadcrumb={this.props.setBreadcrumb} // TODO
          rawSchema={{
            label: 'Location',
            collapsable: true,
            collapsed: true,
          }}>
          {component}
        </FormRenderer>
      );
    }
  }
}

export class NgDotsRenderer extends Component {
  isAnObject = (v) => typeof v === 'object' && v !== null && !Array.isArray(v);

  render() {
    const schema = this.props.schema || {};
    const props = schema.props || this.props || {};
    const readOnly = this.props.readOnly;

    const options = props.options || this.props.options;

    const isValueArray = Array.isArray(this.props.value);

    const onClick = (selectedValue) => {
      const value = this.props.value || props.defaultValue;

      if (isValueArray) {
        if (value.includes(selectedValue)) return value.filter((opt) => opt !== selectedValue);
        else return [...value, selectedValue];
      } else {
        return selectedValue;
      }
    };

    const value = this.props.value || props.defaultValue;
    const schemaProps = this.props.rawSchema?.props;

    return (
      <LabelAndInput {...this.props}>
        <div
          className="d-flex flex-wrap align-items-center"
          style={{ height: '100%', gap: '.6em' }}>
          {readOnly &&
            (isValueArray ? (
              value.map((v) => <ReadOnlyField value={v} key={v} />)
            ) : (
              <ReadOnlyField value={value} />
            ))}
          {!readOnly &&
            options.map((option) => {
              const optObj = this.isAnObject(option);
              const rawOption = optObj ? option.value : option;
              const selected = isValueArray ? value.includes(rawOption) : value === rawOption;

              let backgroundColorFromOption = 'initial';
              let btnBackground = '';

              if (optObj && option.color)
                backgroundColorFromOption = `rgba(${option.color
                  .replace(')', '')
                  .replace('rgb(', '')}, ${selected ? 1 : 0.45})`;

              if (!optObj || backgroundColorFromOption === 'initial')
                btnBackground = selected ? 'btn-info' : 'btn-dark';

              return (
                <button
                  className={`btn btn-radius-25 btn-sm ${
                    optObj ? '' : selected ? 'btn-info' : 'btn-dark'
                  } me-2 px-3 mb-2`}
                  type="button"
                  key={rawOption}
                  style={{
                    borderRadius: '24px',
                    backgroundColor: backgroundColorFromOption,
                    color: '#fff',
                  }}
                  onClick={() => {
                    const newOption = onClick(rawOption);

                    if (schemaProps?.resetOnChange) {
                      this.props.rootOnChange({
                        [this.props.name]: newOption,
                      });
                    } else {
                      this.props.onChange(newOption);
                    }
                  }}>
                  {selected && <i className="fas fa-check me-1" />}
                  {optObj ? option.label || option.value : option}
                </button>
              );
            })}
        </div>
      </LabelAndInput>
    );
  }
}

export class NgCustomFormsRenderer extends Component {
  state = {
    showComponent: false,
    propsFromParent: {},
  };

  hideComponent = () => {
    this.setState({
      showComponent: false,
    });
  };

  render() {
    const { showComponent, propsFromParent } = this.state;
    const schema = this.props.rawSchema;
    const props = schema?.props || {};

    const Component = Forms[schema.type];

    const LauncherComponent = React.createElement(props.componentLauncher, {
      value: this.props.value,
      onChange: this.props.onChange,
      openComponent: (propsFromParent) => {
        this.setState({
          showComponent: true,
          propsFromParent: {
            ...(propsFromParent || {}),
            ...(props.componentsProps || {}),
          },
        });
      },
    });

    return (
      <>
        {!showComponent && LauncherComponent}
        {showComponent && (
          <Component
            onChange={this.props.onChange}
            onConfirm={(value) => {
              this.props.onChange(value);
              this.hideComponent();
            }}
            value={this.props.value}
            hide={this.hideComponent}
            {...(propsFromParent || {})}
          />
        )}
      </>
    );
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

  if (ngOptions.spread && !_props.readOnly) return _props.children;

  const margin =
    _props.margin ||
    props.margin ||
    _props.rawSchema?.props?.margin ||
    (_props.readOnly ? 'mb-0' : 'mb-3');

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
    const schema = this.props.schema;
    const props = schema.props || {};
    const readOnly = this.props.readOnly;

    const { defaultValue } = props;

    return (
      <LabelAndInput {...this.props}>
        {readOnly ? (
          <ReadOnlyField value={this.props.value || defaultValue || '{}'} pre={true} />
        ) : (
          <Suspense fallback={<div>Loading</div>}>
            <CodeInput
              {...this.props.rawSchema?.props}
              value={this.props.value}
              onChange={(e) => this.props.onChange(e)}
              style={{ width: '100%' }}
            />
          </Suspense>
        )}
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
          value={JSON.stringify(
            this.props.value || this.props.rawSchema?.props?.defaultValue,
            null,
            2
          )}
          onChange={(e) => {
            try {
              this.props.onChange(JSON.parse(e));
            } catch (ex) {}
          }}
          style={{ width: '100%', ...(this.props.style || {}) }}
        />
      </Suspense>
    );
  }
}

export class NgStringRenderer extends Component {
  state = {
    touched: false,
  };

  render() {
    const schema = this.props.schema;
    const props = schema.props || {};
    const readOnly = this.props.readOnly;

    // avoid to have both value and defaultValue props
    const { defaultValue, ...inputProps } = props;

    return (
      <LabelAndInput {...this.props}>
        {readOnly ? (
          <ReadOnlyField value={this.props.value || defaultValue || 'Not specified'} />
        ) : (
          <>
            <input
              type="text"
              className="form-control"
              placeholder={props.placeholder}
              title={props.help}
              value={
                this.state.touched ? this.props.value || '' : this.props.value || defaultValue || ''
              }
              onChange={(e) => {
                this.props.onChange(e.target.value);

                if (!this.state.touched) this.setState({ touched: true });
              }}
              {...inputProps}
            />
            {props.subTitle && <span style={{ fontStyle: 'italic' }}>{props.subTitle}</span>}
          </>
        )}
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
  state = {
    touched: false,
  };
  render() {
    const schema = this.props.schema;
    const props = schema.props || {};
    const readOnly = this.props.readOnly;

    // avoid to have both value and defaultValue props
    const { defaultValue, ...inputProps } = props;

    return (
      <LabelAndInput {...this.props}>
        {readOnly && <ReadOnlyField value={this.props.value || defaultValue} />}
        {!readOnly && (
          <input
            type="number"
            className="form-control"
            placeholder={props.placeholder}
            title={props.help}
            value={this.state.touched ? this.props.value : this.props.value || defaultValue}
            onChange={(e) => {
              this.props.onChange(~~e.target.value);
              if (!this.state.touched) this.setState({ touched: true });
            }}
            {...inputProps}
          />
        )}
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
    const readOnly = this.props.readOnly;

    const value =
      this.props.value === null || this.props.value === undefined
        ? props.defaultValue
        : this.props.value;

    return (
      <LabelAndInput {...this.props}>
        {readOnly ? (
          <ReadOnlyField value={value ? 'true' : 'false'} />
        ) : (
          <>
            {value && <OnSwitch onChange={this.toggleOff} />}
            {!value && <OffSwitch onChange={this.toggleOn} />}
          </>
        )}
      </LabelAndInput>
    );
  }
}

export class NgBoxBooleanRenderer extends Component {
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
    const readOnly = this.props.readOnly;

    const value =
      this.props.value === null || this.props.value === undefined
        ? props.defaultValue
        : this.props.value;
    const label =
      this.props.label || props.label || this.props.rawSchema?.label || this.props.name || '...';
    const description =
      this.props.description || props.description || this.props.rawSchema?.description || '...';

    return (
      <div
        className="d-flex"
        style={{
          outline: 'rgb(65, 65, 62) solid 1px',
          padding: '5px',
          margin: '5px 0px',
          width: '100%',
          borderRadius: '4px',
        }}>
        <div className="d-flex justify-content-between flex-column" style={{ flex: 1 }}>
          <div
            style={{
              color: 'rgb(249, 176, 0)',
              fontWeight: 'bold',
              marginLeft: '5px',
              marginTop: '7px',
              marginBottom: '10px',
            }}>
            {label}
          </div>
          <div className="me-1" style={{ marginLeft: '5px', marginBottom: '10px' }}>
            <p>{description}</p>
            {readOnly ? (
              <ReadOnlyField value={value ? 'true' : 'false'} />
            ) : (
              <div className="d-flex align-items-center">
                {value && <OnSwitch onChange={this.toggleOff} style={{ margin: 0 }} />}
                {!value && <OffSwitch onChange={this.toggleOn} style={{ margin: 0 }} />}
                <p className="m-0 ms-2">{value ? 'On' : 'Off'}</p>
              </div>
            )}
          </div>
        </div>
      </div>
    );
  }
}

export class NgArrayRenderer extends Component {
  canShowActions(path) {
    const breadcrumbAsArray = this.props.breadcrumb || [];
    const pathAsArray = path || this.props.path || [];

    if (this.props.breadcrumb === undefined) return true;

    if (path) return isEqual(pathAsArray, breadcrumbAsArray);

    return (
      pathAsArray.length >= breadcrumbAsArray.length &&
      (pathAsArray.join('-').startsWith(pathAsArray.join('-')) ||
        pathAsArray.join('-').startsWith(pathAsArray.join('-')))
    );
  }

  isAnObject = (v) => typeof v === 'object' && v !== null && !Array.isArray(v);

  defaultValues = (current) => ({
    number: () => 0,
    boolean: () => false,
    bool: () => false,
    array: () => [],
    select: () => current?.props?.options[0] || '',
    form: () => ({
      ...this.generateDefaultValue(current.schema),
    }),
    object: () => {},
    json: () => {},
  });

  generateDefaultValue = (obj) => {
    return Object.entries(obj).reduce((acc, current) => {
      const type = current[1] ? current[1].type : undefined;
      const value = this.defaultValues(current[1])[type];
      return {
        ...acc,
        [current[0]]: value ? value() : '',
      };
    }, {});
  };

  render() {
    const schema = this.props.schema;
    const props = schema.props || {};
    const readOnly = this.props.readOnly;
    const ItemRenderer = schema.itemRenderer || this.props.rawSchema.itemRenderer;

    const showActions = this.canShowActions();

    if (readOnly && Array.isArray(this.props.value) && this.props.value.length === 0) return null;

    return (
      <LabelAndInput {...this.props}>
        <div
          style={{
            display: 'flex',
            flexDirection: 'column',
            width: '100%',
          }}>
          {Array.isArray(this.props.value) &&
            this.props.value.map((value, idx) => {
              const path = [...this.props.path, String(idx)];
              const showItem = this.canShowActions(path);

              return (
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    width: '100%',
                    outline: showItem ? 'rgb(65, 65, 62) solid 1px' : 'none',
                    padding: showItem ? '6px' : 0,
                    marginBottom: showItem ? '6px' : 0,
                  }}
                  key={path}>
                  {!ItemRenderer &&
                    (readOnly ? (
                      <ReadOnlyField value={value} />
                    ) : (
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
                    ))}
                  {ItemRenderer && (
                    <ItemRenderer
                      embedded
                      fromArray
                      readOnly={readOnly}
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
                  {showActions && !readOnly && (
                    <button
                      type="button"
                      className="btn btn-sm btn-danger"
                      style={{ width: 42, marginLeft: 5 }}
                      onClick={(e) => {
                        const newArray = this.props.value ? [...this.props.value] : [];
                        newArray.splice(idx, 1);
                        this.props.onChange(newArray);
                      }}>
                      <i className="fas fa-trash" />
                    </button>
                  )}
                </div>
              );
            })}
          {showActions && !readOnly && (
            <button
              type="button"
              className="btn btn-sm btn-info float-end"
              style={{ width: 42, marginTop: 5 }}
              onClick={() => {
                let newArr = [...(this.props.value || [])];

                if (schema.of) return this.props.onChange([...newArr, '']);
                else {
                  const newArray = [...newArr, this.generateDefaultValue(schema)];
                  this.props.onChange(newArray);
                }
              }}>
              <i className="fas fa-plus-circle" />
            </button>
          )}
        </div>
      </LabelAndInput>
    );
  }
}

export class NgObjectRenderer extends Component {
  render() {
    const schema = this.props.schema;
    const props = schema.props || {};
    const readOnly = this.props.readOnly;
    const ItemRenderer = schema.itemRenderer || this.props.rawSchema.itemRenderer;

    if (readOnly && Object.entries(this.props.value || {}).length === 0) return null;

    return (
      <LabelAndInput {...this.props}>
        {readOnly ? (
          <ReadOnlyField
            value={Object.entries(this.props.value || {}).map((entry) => {
              return (
                <>
                  {`${entry[0]} - ${entry[1]}`}
                  <br />
                </>
              );
            })}
          />
        ) : (
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
        )}
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
    const readOnly = this.props.readOnly;

    return (
      <LabelAndInput {...this.props}>
        {readOnly && <ReadOnlyField value={this.props.value} />}
        {!readOnly && (
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
        )}
      </LabelAndInput>
    );
  }
}
