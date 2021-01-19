import React, { Component } from 'react';
import Select from 'react-select';
import Creatable from 'react-select/lib/Creatable';
import { Help } from './Help';

export class ArrayInput extends Component {
  state = {
    loading: false,
    values: [],
  };

  componentDidMount() {
    if (this.props.valuesFrom) {
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
    const values = this.props.value || [];
    const Component = this.props.component;
    if (Component) {
      return (
        <div style={{ marginRight: 10 }}>
          <div className="form__group mb-10 grid-template-bp1--fifth">
            <label>
              {this.props.label} <Help text={this.props.help} />
            </label>
            <div className="" >
              {values.length === 0 && (
                <div>
                  <button
                    disabled={this.props.disabled}
                    type="button"
                    className="btn-info"
                    onClick={this.addFirst}>
                    <i className="fas fa-plus-circle" />{' '}
                  </button>
                </div>
              )}
            </div>
          </div>
          <div className="">
            {values.map((value, idx) => (
              <div className="" key={idx}>
                <div className="">
                  {/*<div className="input-groupp">*/}
                  {this.props.component && (
                    <Component idx={idx} itemValue={value} {...this.props} />
                  )}
                  <div
                    className="flex f-justify_end mb-10">
                    <span className="input-group-append">
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
                          className="btn-info ml-5"
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
          <div className="form__group mb-20 grid-template-bp1--fifth">
            <label
              htmlFor={`input-${this.props.label}`}
              className="">
              {this.props.label} <Help text={this.props.help} />
            </label>
            <div>
              <button
                disabled={this.props.disabled}
                type="button"
                className="btn-info"
                onClick={this.addFirst}>
                <i className="fas fa-plus-circle" />{' '}
              </button>
            </div>
          </div>
        )}
        {values.map((value, idx) => (
          <div className="form__group mb-20 grid-template-bp1--fifth grid-align-items_baseline" style={{alignItems: 'baseline'}} key={idx}>
            {idx === 0 && (
              <label className="">
                {this.props.label} <Help text={this.props.help} />
              </label>
            )}
            {idx > 0 && <label>&nbsp;</label>}
            <div>
              <div className="flex">
                {!this.state.values.length && !this.props.component && (
                  <div className="w-100 grid grid-template--1-auto" >
                    {this.props.prefix && (
                      <div className="input-group-addon">{this.props.prefix}</div>
                    )}
                    <input
                      disabled={this.props.disabled}
                      type={this.props.inputType || 'text'}
                      id={`input-${this.props.label}`}
                      placeholder={this.props.placeholder}
                      value={value}
                      onChange={(e) => this.changeValue(e, idx)}
                    />
                    {this.props.suffix && (
                      <div className="input-group-addon">{this.props.suffix}</div>
                    )}
                  </div>
                )}
                {!!this.state.values.length && !this.props.creatable && !this.props.component && (
                <div className="w-100" >
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
                <div className="w-100" >
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
                </div>
                )}
                {this.props.component && <Component idx={idx} itemValue={value} {...this.props} />}
                <span className="flex ml-5">
                  <button
                    disabled={this.props.disabled}
                    type="button"
                    className="btn-danger"
                    onClick={(e) => this.remove(e, idx)}>
                    <i className="fas fa-trash" />
                  </button>
                  {idx === values.length - 1 && (
                    <button
                      disabled={this.props.disabled}
                      type="button"
                      className="btn-info ml-5"
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
