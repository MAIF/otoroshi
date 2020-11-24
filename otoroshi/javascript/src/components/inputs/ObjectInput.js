import React, { Component } from 'react';
import { Help } from './Help';

export class ObjectInput extends Component {
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
    newValues[e.target.value] = oldValue;
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

  render() {
    const values = Object.keys(this.props.value || {}).map((k) => [k, this.props.value[k]]);
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
                <i className="fas fa-folder" />{' '}
              </button>
            </div>
          </div>
        )}
        {values.map((value, idx) => (
          <div className="form-group">
            {idx === 0 && (
              <label className="col-xs-12 col-sm-2 control-label">
                {this.props.label} <Help text={this.props.help} />
              </label>
            )}
            {idx > 0 && <label className="col-xs-12 col-sm-2 control-label">&nbsp;</label>}
            <div className="col-sm-10">
              <div className="input-group">
                <input
                  disabled={this.props.disabled}
                  type="text"
                  className="form-control"
                  style={{ width: '50%' }}
                  placeholder={this.props.placeholderKey}
                  value={value[0]}
                  onChange={(e) => this.changeKey(e, value[0])}
                />
                <input
                  disabled={this.props.disabled}
                  type="text"
                  className="form-control"
                  style={{ width: '50%' }}
                  placeholder={this.props.placeholderValue}
                  value={value[1]}
                  onChange={(e) => this.changeValue(e, value[0])}
                />
                <span className="input-group-btn">
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
              </div>
            </div>
          </div>
        ))}
      </div>
    );
  }
}

export class VerticalObjectInput extends Component {
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
    newValues[e.target.value] = oldValue;
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

  render() {
    const values = Object.keys(this.props.value || {}).map((k) => [k, this.props.value[k]]);
    return (
      <div>
        {values.length === 0 && (
          <div className="form-group">
            <div className="col-xs-12">
              <label htmlFor={`input-${this.props.label}`} className="control-label">
                {this.props.label} <Help text={this.props.help} />
              </label>
              <div>
                <button
                  disabled={this.props.disabled}
                  type="button"
                  className="btn btn-primary"
                  onClick={this.addFirst}>
                  <i className="fas fa-plus-circle" />{' '}
                </button>
              </div>
            </div>
          </div>
        )}
        {values.map((value, idx) => (
          <div className="form-group" style={{ marginBottom: 5 }}>
            <div className="col-xs-12">
              {idx === 0 && (
                <label className="control-label">
                  {this.props.label} <Help text={this.props.help} />
                </label>
              )}
              {idx > 0 && false && <label className="control-label">&nbsp;</label>}
              <div className="input-group">
                <input
                  disabled={this.props.disabled}
                  type="text"
                  className="form-control"
                  style={{ width: '50%' }}
                  placeholder={this.props.placeholderKey}
                  value={value[0]}
                  onChange={(e) => this.changeKey(e, value[0])}
                />
                <input
                  disabled={this.props.disabled}
                  type="text"
                  className="form-control"
                  style={{ width: '50%' }}
                  placeholder={this.props.placeholderValue}
                  value={value[1]}
                  onChange={(e) => this.changeValue(e, value[0])}
                />
                <span className="input-group-btn">
                  <button
                    disabled={this.props.disabled}
                    type="button"
                    className="btn btn-sm btn-danger"
                    style={{ marginRight: 0 }}
                    onClick={(e) => this.remove(e, value[0])}>
                    <i className="fas fa-trash" />
                  </button>
                </span>
              </div>
              {idx === values.length - 1 && (
                <div
                  style={{
                    display: 'flex',
                    width: '100%',
                    justifyContent: 'center',
                    alignItems: 'center',
                    marginTop: 5,
                  }}>
                  <button
                    disabled={this.props.disabled}
                    type="button"
                    className="btn btn-sm btn-block btn-primary"
                    style={{ marginRight: 0 }}
                    onClick={this.addNext}>
                    <i className="fas fa-plus-circle" />{' '}
                  </button>
                </div>
              )}
            </div>
          </div>
        ))}
      </div>
    );
  }
}
