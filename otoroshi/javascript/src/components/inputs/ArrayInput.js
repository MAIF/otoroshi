import React, { Component } from 'react';
import Select from 'react-select';
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
        .then(r => r.json())
        .then(values => values.map(this.props.transformer || (a => a)))
        .then(values => this.setState({ values, loading: false }));
    }
  }

  changeValue = (e, idx) => {
    if (e && e.preventDefault) e.preventDefault();
    const newValues = [...this.props.value];
    newValues[idx] = e.target.value;
    this.props.onChange(newValues);
  };

  addFirst = e => {
    if (e && e.preventDefault) e.preventDefault();
    if (!this.props.value || this.props.value.length === 0) {
      this.props.onChange([this.props.defaultValue || '']);
    }
  };

  addNext = e => {
    if (e && e.preventDefault) e.preventDefault();
    const newValues = [...this.props.value, this.props.defaultValue || ''];
    this.props.onChange(newValues);
  };

  remove = (e, idx) => {
    if (e && e.preventDefault) e.preventDefault();
    const newValues = [...this.props.value];
    newValues.splice(idx, 1);
    this.props.onChange(newValues);
  };

  render() {
    const values = this.props.value || [];
    return (
      <div>
        {values.length === 0 && (
          <div className="form-group">
            <label
              htmlFor={`input-${this.props.label}`}
              className="col-xs-12 col-sm-2 control-label">
              {this.props.label} <Help text={this.props.help} />
            </label>
            <div className="col-sm-10">
              <button
                disabled={this.props.disabled}
                type="button"
                className="btn btn-primary"
                onClick={this.addFirst}>
                <i className="glyphicon glyphicon-plus-sign" />{' '}
              </button>
            </div>
          </div>
        )}
        {values.map((value, idx) => (
          <div className="form-group" key={idx}>
            {idx === 0 && (
              <label className="col-xs-12 col-sm-2 control-label">
                {this.props.label} <Help text={this.props.help} />
              </label>
            )}
            {idx > 0 && <label className="col-xs-12 col-sm-2 control-label">&nbsp;</label>}
            <div className="col-sm-10">
              <div className="input-group">
                {!this.props.valuesFrom && (
                  <input
                    disabled={this.props.disabled}
                    type="text"
                    className="form-control"
                    id={`input-${this.props.label}`}
                    placeholder={this.props.placeholder}
                    value={value}
                    onChange={e => this.changeValue(e, idx)}
                  />
                )}
                {this.props.valuesFrom && (
                  <Select
                    name={`selector-${idx}`}
                    value={value}
                    isLoading={this.state.loading}
                    disabled={this.props.disabled}
                    placeholder={this.props.placeholder}
                    options={this.state.values}
                    onChange={e => this.changeValue({ target: { value: e.value } }, idx)}
                  />
                )}
                <span className="input-group-btn">
                  <button
                    disabled={this.props.disabled}
                    type="button"
                    className="btn btn-danger"
                    onClick={e => this.remove(e, idx)}>
                    <i className="glyphicon glyphicon-trash" />
                  </button>
                  {idx === values.length - 1 && (
                    <button
                      disabled={this.props.disabled}
                      type="button"
                      className="btn btn-primary"
                      onClick={this.addNext}>
                      <i className="glyphicon glyphicon-plus-sign" />{' '}
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
