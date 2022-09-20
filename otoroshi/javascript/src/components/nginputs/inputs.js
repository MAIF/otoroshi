import React, { Component, Suspense } from 'react';
// import _ from 'lodash';
import Select from 'react-select';
import { OffSwitch, OnSwitch } from '../inputs/BooleanInput';

const CodeInput = React.lazy(() => Promise.resolve(require('../inputs/CodeInput')));

export class SingleLineCode extends Component {
  render() {
    return (
      <div>SingleLineCode</div>
    )
  }
}

function LabelAndInput(_props) {
  const schema = _props.schema || {};
  const props = schema.props || {};
  const label = _props.label || props.label || _props.name || _props.rawSchema?.label || '...';
  const ngOptions = _props.ngOptions || props.ngOptions || {}

  if (ngOptions.spread)
    return _props.children

  return (
    <div className="row mb-3">
      <label className="col-xs-12 col-sm-2 col-form-label">
        {label.replace(/_/g, ' ')}{' '}
        <i
          className="far fa-question-circle"
          data-toggle="tooltip"
          data-placement="top"
          title={_props.help}
          data-bs-original-title={_props.help}
          aria-label={_props.help}
        />
      </label>
      <div className="col-sm-10">{_props.children}</div>
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
      <LabelAndInput {...this.props}>
        <CodeInput
          value={JSON.stringify(this.props.value, null, 2)}
          onChange={(e) => {
            try {
              this.props.onChange(JSON.parse(e));
            } catch (ex) { }
          }}
          style={{ width: '100%' }}
        />
      </LabelAndInput>
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
          onChange={(e) => this.props.onChange(e.target.value)}
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
  render() {
    const schema = this.props.schema;
    const props = schema.props || {};
    const ItemRenderer = schema.itemRenderer || this.props.rawSchema.itemRenderer;
    return (
      <LabelAndInput {...this.props}>
        <div style={{ display: 'flex', flexDirection: 'column', width: '100%' }}>
          {this.props.value &&
            this.props.value.map((value, idx) => {
              return (
                <div
                  style={{
                    display: 'flex',
                    flexDirection: 'row',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    width: '100%',
                  }}>
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
                      path={[...this.props.path, String(idx)]}
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
                </div>
              );
            })}
          <button
            type="button"
            className="btn btn-sm btn-success float-end"
            style={{ width: 42, marginTop: 5 }}
            onClick={(e) => {
              const newArray = this.props.value ? [...this.props.value, ''] : [''];
              this.props.onChange(newArray);
            }}>
            <i className="fas fa-plus-circle" />
          </button>
        </div>
      </LabelAndInput>
    );
  }
}

export class NgObjectRenderer extends Component {
  // state = { values: this.props.value ? Object.keys(this.props.value).map(key => ({ key, value: this.props.value[key] })) : [] }

  render() {
    const schema = this.props.schema;
    const props = schema.props || {};
    const ItemRenderer = schema.itemRenderer || this.props.rawSchema.itemRenderer;
    return (
      <LabelAndInput {...this.props}>
        <div style={{ display: 'flex', flexDirection: 'column', width: '100%' }}>
          {this.props.value &&
            // this.state.values.map(raw => {
            //   const { key, value } = raw;
            Object.keys(this.props.value)
              .map((key) => [key, this.props.value[key]])
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
                      className="form-control"
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
                    {!ItemRenderer && (
                      <input
                        type="text"
                        className="form-control"
                        placeholder={props.placeholderValue}
                        title={props.help}
                        value={value}
                        onChange={(e) => {
                          const newObject = this.props.value ? { ...this.props.value } : {};
                          newObject[key] = e.target.value;
                          this.props.onChange(newObject);
                        }}
                        style={{ width: '50%' }}
                        {...props}
                      />
                    )}
                    {ItemRenderer && (
                      <ItemRenderer
                        embedded
                        flow={this.props.flow}
                        schema={this.props.schema}
                        value={value}
                        onChange={(e) => {
                          const newObject = this.props.value ? { ...this.props.value } : {};
                          newObject[key] = e;
                          this.props.onChange(newObject);
                        }}
                        {...props}
                      />
                    )}
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
            className="btn btn-sm btn-success float-end"
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

export class NgArraySelectRenderer extends Component {
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
            this.props.value.map((value, idx) => {
              return (
                <div
                  style={{
                    display: 'flex',
                    flexDirection: 'row',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    width: '100%',
                  }}>
                  <div style={{ width: '100%', flex: 1 }}>
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
                        const newArray = this.props.value ? [...this.props.value] : [];
                        newArray.splice(idx, 1, e.value);
                        this.props.onChange(newArray);
                      }}
                    />
                  </div>
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
                </div>
              );
            })}
          <button
            type="button"
            className="btn btn-sm btn-success float-end"
            style={{ width: 42, marginTop: 5 }}
            onClick={(e) => {
              const newArray = this.props.value ? [...this.props.value, ''] : [''];
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
            className="btn btn-sm btn-success float-end"
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
    if (props.optionsFrom) {
      this.setState({ loading: true }, () => {
        fetch(this.props.schema.props.optionsFrom, {
          method: 'GET',
          credentials: 'include',
        })
          .then((r) => r.json())
          .then((r) => {
            this.setState({ loading: false });
            this.setState({ options: this.applyTransformer(props, r) })
          })
          .catch((e) => {
            this.setState({ loading: false });
          });
      });
    }
  }

  applyTransformer = (props, r) => {
    if (props.optionsTransformer) {
      return props.optionsTransformer(r)
    } else {
      return r
    }
  }

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
          options={this.applyTransformer(props || this.props, this.state.options || props.options || this.props.options)}
          onChange={(e) => this.props.onChange(e?.value)}
        />
      </LabelAndInput>
    );
  }
}