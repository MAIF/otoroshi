import React, { Component } from 'react';
import { Help } from './Help';

export class NumberInput extends Component {
  onChange = e => {
    if (e && e.preventDefault) e.preventDefault();
    const value = e.target.value;
    if (value.indexOf('.') > -1) {
      this.props.onChange(parseFloat(value));
    } else {
      this.props.onChange(parseInt(value, 10));
    }
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
                type="number"
                disabled={this.props.disabled}
                className="form-control"
                id={`input-${this.props.label}`}
                placeholder={this.props.placeholder}
                value={this.props.value}
                onChange={this.onChange}
              />
              {this.props.suffix && <div className="input-group-addon">{this.props.suffix}</div>}
            </div>
          )}
          {!(this.props.prefix || this.props.suffix) && (
            <input
              type="number"
              disabled={this.props.disabled}
              className="form-control"
              id={`input-${this.props.label}`}
              placeholder={this.props.placeholder}
              value={this.props.value}
              onChange={this.onChange}
            />
          )}
        </div>
      </div>
    );
  }
}
