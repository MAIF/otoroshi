import React, { Component } from 'react';
import Select from 'react-select';
import Creatable from 'react-select/lib/Creatable';
import { Help } from './Help';

export class ArrayInput extends Component {
  state = {
    loading: false,
    values: this.props.possibleValues || [],
  };

  componentWillReceiveProps(next) {
    if (next.possibleValues !== this.props.possibleValues) {
      this.setState({ values: next.possibleValues });
    }
    if (next.value !== this.props.value && !!this.props.valuesFrom) {
      this.updateValuesFrom();
    }
  }

  updateValuesFrom = () => {
    this.setState({ loading: true });
    fetch(this.props.valuesFrom, {
      method: 'GET',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
      },
    })
      .then((r) => r.json())
      .then((values) =>
        values.map((v) => {
          if (this.props.transformerMapping) {
            const value = v[this.props.transformerMapping.value];
            const label = v[this.props.transformerMapping.label];
            return { value, label };
          } else if (this.props.transformer) {
            return this.props.transformer(v);
          } else {
            return v;
          }
        })
      )
      .then((values) => this.setState({ values, loading: false }));
  };

  componentDidMount() {
    if (this.props.valuesFrom) {
      this.updateValuesFrom();
    } else if (this.props.values) {
      if (this.props.values.every((v) => v.values && v.label)) {
        this.setState({ values: this.props.values });
      } else {
        this.setState({ values: this.props.values.map((value) => ({ label: value, value })) });
      }
    }
  }

  changeValue = (e, idx) => {
    if (e && e.preventDefault) e.preventDefault();
    const newValues = [...this.props.value];
    newValues[idx] = e.target.value;
    this.props.onChange(newValues, e.target.value, idx);
  };

  addFirst = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    if (!this.props.value || this.props.value.length === 0) {
      const newValue = this.props.defaultValue || '';
      this.props.onChange([newValue], newValue, 0);
    }
  };

  addNext = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    const newValues = [...this.props.value, this.props.defaultValue || ''];
    this.props.onChange(newValues, this.props.defaultValue || '', newValues.length - 1);
  };

  remove = (e, idx) => {
    if (e && e.preventDefault) e.preventDefault();
    const newValues = [...this.props.value];
    newValues.splice(idx, 1);
    this.props.onChange(newValues, null, idx);
  };

  render() {
    const values = this.props.initTransform
      ? this.props.initTransform(this.props.value || [])
      : this.props.value || [];
    const Component = this.props.component;
    const AdditionalButton = this.props.additionalButton;

    if (Component) {
      return (
        <div>
          <div className="row mb-3">
            <label className="col-xs-12 col-sm-2 col-form-label">
              {this.props.label} <Help text={this.props.help} />
            </label>
            <div className="col-sm-10">
              {values.length === 0 && (
                <button
                  disabled={this.props.disabled}
                  type="button"
                  className="btn btn-primary"
                  onClick={this.addFirst}>
                  <i className="fas fa-plus-circle" />{' '}
                </button>
              )}
            </div>
          </div>
          <div className="row mb-3">
            {values.map((value, idx) => (
              <div className="col-12 mb-3" key={idx}>
                <div>
                  {/*<div className="input-groupp">*/}
                  {this.props.component && (
                    <Component idx={idx} itemValue={value} {...this.props} />
                  )}
                  <div
                    style={{
                      width: '100%',
                      display: 'flex',
                      justifyContent: 'flex-end',
                      paddingRight: 0,
                    }}>
                    <span className="input-group-btn">
                      <button
                        disabled={this.props.disabled}
                        type="button"
                        className="btn btn-danger"
                        onClick={(e) => this.remove(e, idx)}>
                        <i className="fas fa-trash" />
                      </button>
                      {idx === values.length - 1 && (
                        <button
                          disabled={this.props.disabled}
                          type="button"
                          className="btn btn-primary"
                          onClick={this.addNext}>
                          <i className="fas fa-plus-circle" />{' '}
                        </button>
                      )}
                    </span>
                  </div>
                  {/*</div>*/}
                </div>
              </div>
            ))}
          </div>
        </div>
      );
    }
    return (
      <div>
        {values.length === 0 && (
          <div className="row mb-3">
            <label
              htmlFor={`input-${this.props.label}`}
              className="col-xs-12 col-sm-2 col-form-label">
              {this.props.label} <Help text={this.props.help} />
            </label>
            <div className="col-sm-10">
              <button
                disabled={this.props.disabled}
                type="button"
                className="btn btn-primary"
                onClick={this.addFirst}>
                <i className="fas fa-plus-circle" />{' '}
              </button>
            </div>
          </div>
        )}
        {values.map((value, idx) => (
          <div className="row mb-3" key={idx}>
            {idx === 0 && (
              <label className="col-xs-12 col-sm-2 col-form-label">
                {this.props.label} <Help text={this.props.help} />
              </label>
            )}
            {idx > 0 && <label className="col-xs-12 col-sm-2 col-form-label">&nbsp;</label>}
            <div className="col-sm-10">
              <div className="input-group justify-content-between">
                {!this.state.values.length && !this.props.component && (
                  <>
                    {this.props.prefix && (
                      <span className="input-group-text">{this.props.prefix}</span>
                    )}
                    <input
                      disabled={this.props.disabled}
                      type={this.props.inputType || 'text'}
                      className="form-control"
                      id={`input-${this.props.label}`}
                      placeholder={this.props.placeholder}
                      value={value}
                      onChange={(e) => this.changeValue(e, idx)}
                    />
                    {this.props.suffix && (
                      <span className="input-group-text">{this.props.suffix}</span>
                    )}
                  </>
                )}
                {!!this.state.values.length && !this.props.creatable && !this.props.component && (
                  <div className="flex-grow-1">
                    <Select
                      name={`selector-${idx}`}
                      value={value}
                      isLoading={this.state.loading}
                      disabled={this.props.disabled}
                      placeholder={this.props.placeholder}
                      optionRenderer={this.props.optionRenderer}
                      options={this.state.values}
                      onChange={(e) => this.changeValue({ target: { value: e.value } }, idx)}
                    />
                  </div>
                )}
                {!!this.state.values.length && this.props.creatable && !this.props.component && (
                  <Creatable
                    name={`selector-${idx}`}
                    value={{ label: value, value }}
                    isLoading={this.state.loading}
                    disabled={this.props.disabled}
                    placeholder={this.props.placeholder}
                    optionRenderer={this.props.optionRenderer}
                    options={this.state.values}
                    onChange={(e) => this.changeValue({ target: { value: e.value } }, idx)}
                    promptTextCreator={(label) => `Create events filter "${label}"`}
                  />
                )}
                {this.props.component && <Component idx={idx} itemValue={value} {...this.props} />}
                <span className="input-group-btn">
                  <button
                    disabled={this.props.disabled}
                    type="button"
                    className="btn btn-danger"
                    onClick={(e) => this.remove(e, idx)}>
                    <i className="fas fa-trash" />
                  </button>
                  {this.props.additionalButton && (
                    <AdditionalButton idx={idx} itemValue={value} {...this.props} />
                  )}
                  {idx === values.length - 1 && (
                    <button
                      disabled={this.props.disabled}
                      type="button"
                      className="btn btn-primary"
                      onClick={this.addNext}>
                      <i className="fas fa-plus-circle" />{' '}
                    </button>
                  )}
                </span>
              </div>
            </div>
          </div>
        ))}
      </div>
    );
  }
}
