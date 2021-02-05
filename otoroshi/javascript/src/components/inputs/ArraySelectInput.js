import React, { Component } from 'react';
import Select from 'react-select';
import { Scripts } from '../Scripts';
import { Separator } from '../Separator';
import { Help } from './Help';

export class ArraySelectInput extends Component {
  state = {
    loading: false,
    possibleValues: this.props.possibleValues || [],
  };

  componentDidMount() {
    if (this.props.valuesFrom) {
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
      .then((possibleValues) => this.setState({ possibleValues, loading: false }));
  };

  changeValue = (e, name) => {
    if (e && e.preventDefault) e.preventDefault();
    const newValues = { ...this.props.value, [name]: e.target.value };
    this.props.onChange(newValues);
  };

  changeKey = (e, oldName) => {
    if (e && e.preventDefault) e.preventDefault();
    const newValues = { ...this.props.value };
    const oldValue = newValues[oldName];
    delete newValues[oldName];
    newValues[e.value] = oldValue;
    this.props.onChange(newValues);
  };

  addFirst = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    if (!this.props.value || Object.keys(this.props.value).length === 0) {
      this.props.onChange(this.props.defaultValue || { '': '' });
    }
  };

  addNext = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    const newItem = this.props.defaultValue || { '': '' };
    const newValues = { ...this.props.value, ...newItem };
    this.props.onChange(newValues);
  };

  remove = (e, name) => {
    if (e && e.preventDefault) e.preventDefault();
    const newValues = { ...this.props.value };
    delete newValues[name];
    this.props.onChange(newValues);
  };

  renderOnEmptyValues = () => (
    <div className="form-group">
      <label htmlFor={`input-${this.props.label}`} className="col-xs-12 col-sm-2 control-label">
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
  );

  renderActions = (values, idx, value) => (
    <span className="input-group-btn" style={{ minWidth: 'fit-content' }}>
      <button
        disabled={this.props.disabled}
        type="button"
        className="btn btn-danger"
        onClick={(e) => this.remove(e, value[0])}>
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
  );

  render() {
    const values = Object.keys(this.props.value || {}).map((k) => [k, this.props.value[k]]);

    return (
      <div>
        <Separator title={this.props.title} />
        {values.length === 0 && this.renderOnEmptyValues()}

        {values.map((value, idx) => (
          <div className="form-group" key={idx}>
            {idx === 0 ? (
              <label className="col-xs-12 col-sm-2 control-label">
                {this.props.label} <Help text={this.props.help} />
              </label>
            ) : (
              <label className="col-xs-12 col-sm-2 control-label">&nbsp;</label>
            )}
            <div className="col-sm-10">
              <div style={{ display: 'flex' }}>
                <div style={{ flex: 1 }}>
                  <Select
                    name={`selector-${idx}`}
                    value={value[0]}
                    isLoading={this.state.loading}
                    disabled={this.props.disabled}
                    placeholder={this.props.placeholderKey}
                    optionRenderer={this.props.optionRenderer}
                    options={this.state.possibleValues}
                    onChange={(e) => this.changeKey(e, value[0])}
                  />
                </div>
                <input
                  disabled={this.props.disabled}
                  type={this.props.inputType || 'text'}
                  className="form-control"
                  id={`input-${this.props.label}`}
                  placeholder={this.props.placeholderValue}
                  value={value[1]}
                  onChange={(e) => this.changeValue(e, value[0])}
                  style={{ flex: 1 }}
                />
                {this.renderActions(values, idx, value)}
              </div>
            </div>
          </div>
        ))}
      </div>
    );
  }
}
