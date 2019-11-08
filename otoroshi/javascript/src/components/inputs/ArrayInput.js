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
    this.props.onChange(newValues, e.target.value, idx);
  };

  addFirst = e => {
    if (e && e.preventDefault) e.preventDefault();
    if (!this.props.value || this.props.value.length === 0) {
      const newValue = this.props.defaultValue || '';
      this.props.onChange([newValue], newValue, 0);
    }
  };

  addNext = e => {
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
          <div className="form-group">
            <label className="col-xs-12 col-sm-2 control-label">
              {this.props.label} <Help text={this.props.help} />
            </label>
            <div className="col-sm-10" style={{ marginBottom: 20 }}>
              {values.length === 0 && (
                <div className="col-sm-10">
                  <button
                    disabled={this.props.disabled}
                    type="button"
                    className="btn btn-primary"
                    onClick={this.addFirst}>
                    <i className="glyphicon glyphicon-plus-sign" />{' '}
                  </button>
                </div>
              )}
            </div>
          </div>
          <div className="form-group" style={{ marginLeft: 0 }}>
            {values.map((value, idx) => (
              <div className="form-group" key={idx}>
                <div className="col-sm-12">
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
                    <span className="input-group-btnn">
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
                {!this.props.valuesFrom && !this.props.component && (
                  <div className="input-group" style={{ width: '100%' }}>
                    {this.props.prefix && (
                      <div className="input-group-addon">{this.props.prefix}</div>
                    )}
                    <input
                      disabled={this.props.disabled}
                      type="text"
                      className="form-control"
                      id={`input-${this.props.label}`}
                      placeholder={this.props.placeholder}
                      value={value}
                      onChange={e => this.changeValue(e, idx)}
                      style={{ width: '100%' }}
                    />
                    {this.props.suffix && (
                      <div className="input-group-addon">{this.props.suffix}</div>
                    )}
                  </div>
                )}
                {this.props.valuesFrom && !this.props.component && (
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
                {this.props.component && <Component idx={idx} itemValue={value} {...this.props} />}
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
