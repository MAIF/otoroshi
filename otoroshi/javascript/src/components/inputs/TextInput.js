import React, { Component } from 'react';
import { Help } from './Help';

export class TextInput extends Component {
  onChange = e => {
    if (e && e.preventDefault) e.preventDefault();
    this.props.onChange(e.target.value);
  };

  render() {
    return (
      <div className="form-group">
        <label htmlFor={`input-${this.props.label}`} className="col-xs-12 col-sm-2 control-label">
          {this.props.label} <Help text={this.props.help} />
        </label>
        <div className="col-sm-10">
          {(this.props.prefix || this.props.suffix) && (
            <div className="input-group">
              {this.props.prefix && <div className="input-group-addon">{this.props.prefix}</div>}
              <input
                type={this.props.type || 'text'}
                className="form-control"
                disabled={this.props.disabled}
                id={`input-${this.props.label}`}
                placeholder={this.props.placeholder}
                value={this.props.value || ''}
                onChange={this.onChange}
              />
              {this.props.suffix && <div className="input-group-addon">{this.props.suffix}</div>}
            </div>
          )}
          {!(this.props.prefix || this.props.suffix) && (
            <input
              type={this.props.type || 'text'}
              className="form-control"
              disabled={this.props.disabled}
              id={`input-${this.props.label}`}
              placeholder={this.props.placeholder}
              value={this.props.value || ''}
              onChange={this.onChange}
            />
          )}
        </div>
      </div>
    );
  }
}

export class TextareaInput extends Component {
  onChange = e => {
    if (e && e.preventDefault) e.preventDefault();
    this.props.onChange(e.target.value);
  };

  render() {
    return (
      <div className="form-group">
        <label htmlFor={`input-${this.props.label}`} className="col-xs-12 col-sm-2 control-label">
          {this.props.label} <Help text={this.props.help} />
        </label>
        <div className="col-sm-10">
          <textarea
            className="form-control"
            disabled={this.props.disabled}
            id={`input-${this.props.label}`}
            placeholder={this.props.placeholder}
            value={this.props.value || ''}
            onChange={this.onChange}
            rows={this.props.rows || 3}
          />
        </div>
      </div>
    );
  }
}

export class RangeTextInput extends Component {
  onChangeFrom = e => {
    if (e && e.preventDefault) e.preventDefault();
    this.props.onChangeFrom(e.target.value);
  };
  onChangeTo = e => {
    if (e && e.preventDefault) e.preventDefault();
    this.props.onChangeTo(e.target.value);
  };

  render() {
    return (
      <div className="form-group">
        <label htmlFor={`input-${this.props.label}`} className="col-xs-12 col-sm-2 control-label">
          {this.props.label} <Help text={this.props.help} />
        </label>
        <div className="col-sm-10" style={{ display: 'flex' }}>
          {(this.props.prefixFrom || this.props.suffixFrom) && (
            <div className="input-group col-sm-6">
              {this.props.prefixFrom && (
                <div className="input-group-addon">{this.props.prefixFrom}</div>
              )}
              <input
                type={this.props.typeFrom || 'text'}
                className="form-control"
                disabled={this.props.disabled}
                id={`input-${this.props.label}`}
                placeholder={this.props.placeholderFrom}
                value={this.props.valueFrom || ''}
                onChange={this.onChangeFrom}
              />
              {this.props.suffixFrom && (
                <div className="input-group-addon">{this.props.suffixFrom}</div>
              )}
            </div>
          )}
          {(this.props.prefixTo || this.props.suffixTo) && (
            <div className="input-group col-sm-6">
              {this.props.prefixTo && (
                <div className="input-group-addon">{this.props.prefixTo}</div>
              )}
              <input
                type={this.props.typeTo || 'text'}
                className="form-control"
                disabled={this.props.disabled}
                id={`input-${this.props.label}`}
                placeholder={this.props.placeholderTo}
                value={this.props.valueTo || ''}
                onChange={this.onChangeTo}
              />
              {this.props.suffixTo && (
                <div className="input-group-addon">{this.props.suffixTo}</div>
              )}
            </div>
          )}
          {!(this.props.prefixFrom || this.props.suffixFrom) && (
            <div style={{ width: '50%' }}>
              <input
                type={this.props.typeFrom || 'text'}
                className="form-control col-sm-6"
                disabled={this.props.disabled}
                id={`input-${this.props.label}`}
                placeholder={this.props.placeholderFrom}
                value={this.props.valueFrom || ''}
                onChange={this.onChangeFrom}
              />
            </div>
          )}
          {!(this.props.prefixTo || this.props.suffixTo) && (
            <div style={{ width: '50%' }}>
              <input
                type={this.props.typeTo || 'text'}
                className="form-control col-sm-6"
                disabled={this.props.disabled}
                id={`input-${this.props.label}`}
                placeholder={this.props.placeholderTo}
                value={this.props.valueTo || ''}
                onChange={this.onChangeTo}
              />
            </div>
          )}
        </div>
      </div>
    );
  }
}

export class VerticalTextInput extends Component {
  onChange = e => {
    if (e && e.preventDefault) e.preventDefault();
    this.props.onChange(e.target.value);
  };

  render() {
    return (
      <div className="form-group">
        <div className="col-xs-12">
          <label htmlFor={`input-${this.props.label}`} className="control-label">
            {this.props.label} <Help text={this.props.help} />
          </label>
          <div>
            {(this.props.prefix || this.props.suffix) && (
              <div className="input-group">
                {this.props.prefix && <div className="input-group-addon">{this.props.prefix}</div>}
                <input
                  type={this.props.type || 'text'}
                  className="form-control"
                  disabled={this.props.disabled}
                  id={`input-${this.props.label}`}
                  placeholder={this.props.placeholder}
                  value={this.props.value || ''}
                  onChange={this.onChange}
                />
                {this.props.suffix && <div className="input-group-addon">{this.props.suffix}</div>}
              </div>
            )}
            {!(this.props.prefix || this.props.suffix) && (
              <input
                type={this.props.type || 'text'}
                className="form-control"
                disabled={this.props.disabled}
                id={`input-${this.props.label}`}
                placeholder={this.props.placeholder}
                value={this.props.value || ''}
                onChange={this.onChange}
              />
            )}
          </div>
        </div>
      </div>
    );
  }
}
