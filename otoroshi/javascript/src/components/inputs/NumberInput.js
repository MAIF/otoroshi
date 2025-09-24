import React, { Component } from 'react';
import { Help } from './Help';

export class NumberInput extends Component {
  onChange = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    const value = e.target.value;

    if (('' + value).length === 0) {
      this.props.onChange(0);
      return;
    }

    let oldValue = this.props.value;
    try {
      if (value.indexOf('.') > -1) {
        oldValue = parseFloat(value);
      } else {
        oldValue = parseInt(value, 10);
      }
      if (oldValue !== NaN) {
        this.props.onChange(oldValue);
      }
    } catch (e) {
      console.log('error while parsing number', e);
    }
  };

  render() {
    return (
      <div className="row mb-3">
        <label htmlFor={`input-${this.props.label}`} className="col-xs-12 col-sm-2 col-form-label">
          {this.props.label} <Help text={this.props.help} />
        </label>
        <div className="col-sm-10">
          {(this.props.prefix || this.props.suffix) && (
            <div className="input-group">
              {this.props.prefix && (
                <span
                  className="input-group-text"
                  onClick={
                    this.props.prefixCb ? () => this.props.prefixCb(this.props.value) : undefined
                  }
                  style={this.props.prefixStyle || {}}
                >
                  {this.props.prefix}
                </span>
              )}
              <input
                type="number"
                step={this.props.step}
                min={this.props.min}
                max={this.props.max}
                disabled={this.props.disabled}
                className="form-control"
                id={`input-${this.props.label}`}
                placeholder={this.props.placeholder}
                defaultValue={this.props.value}
                onChange={this.onChange}
              />
              {this.props.suffix && (
                <span
                  className="input-group-text"
                  onClick={
                    this.props.suffixCb ? () => this.props.suffixCb(this.props.value) : undefined
                  }
                  style={this.props.suffixStyle || {}}
                >
                  {this.props.suffix}
                </span>
              )}
            </div>
          )}
          {!(this.props.prefix || this.props.suffix) && (
            <input
              type="number"
              step={this.props.step}
              min={this.props.min}
              max={this.props.max}
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

export class VerticalNumberInput extends Component {
  onChange = (e) => {
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
      <div className="row mb-3">
        <div className="col-xs-12">
          <label htmlFor={`input-${this.props.label}`} className="col-form-label">
            {this.props.label} <Help text={this.props.help} />
          </label>
          <div>
            {(this.props.prefix || this.props.suffix) && (
              <div className="input-group">
                {this.props.prefix && <span className="input-group-text">{this.props.prefix}</span>}
                <input
                  type="number"
                  step={this.props.step}
                  min={this.props.min}
                  max={this.props.max}
                  disabled={this.props.disabled}
                  className="form-control"
                  id={`input-${this.props.label}`}
                  placeholder={this.props.placeholder}
                  value={this.props.value}
                  onChange={this.onChange}
                />
                {this.props.suffix && <span className="input-group-text">{this.props.suffix}</span>}
              </div>
            )}
            {!(this.props.prefix || this.props.suffix) && (
              <input
                type="number"
                step={this.props.step}
                min={this.props.min}
                max={this.props.max}
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
      </div>
    );
  }
}

export class NumberRangeInput extends Component {
  onChangeFrom = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    const value = e.target.value;
    if (value.indexOf('.') > -1) {
      this.props.onChangeFrom(parseFloat(value));
    } else {
      this.props.onChangeFrom(parseInt(value, 10));
    }
  };

  onChangeTo = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    const value = e.target.value;
    if (value.indexOf('.') > -1) {
      this.props.onChangeTo(parseFloat(value));
    } else {
      this.props.onChangeTo(parseInt(value, 10));
    }
  };

  render() {
    return (
      <div className="row mb-3">
        <label htmlFor={`input-${this.props.label}`} className="col-xs-12 col-sm-2 col-form-label">
          {this.props.label} <Help text={this.props.help} />
        </label>
        <div className="col-sm-10">
          {(this.props.prefixFrom || this.props.suffixFrom) && (
            <div className="input-group col-sm-6" style={{ float: 'inherit' }}>
              {this.props.prefixFrom && (
                <div className="input-group-text">{this.props.prefixFrom}</div>
              )}
              <input
                type="number"
                step={this.props.stepFrom}
                min={this.props.minFrom}
                max={this.props.maxFrom}
                disabled={this.props.disabled}
                className="form-control"
                id={`input-${this.props.labelFrom}`}
                placeholder={this.props.placeholderFrom}
                value={this.props.valueFrom}
                onChange={this.onChangeFrom}
              />
              {this.props.suffixFrom && (
                <div className="input-group-text">{this.props.suffixFrom}</div>
              )}
            </div>
          )}
          {(this.props.prefixTo || this.props.suffixTo) && (
            <div className="input-group col-sm-6" style={{ float: 'inherit' }}>
              {this.props.prefixTo && <div className="input-group-text">{this.props.prefixTo}</div>}
              <input
                type="number"
                step={this.props.stepTo}
                min={this.props.minTo}
                max={this.props.maxTo}
                disabled={this.props.disabled}
                className="form-control"
                id={`input-${this.props.labelTo}`}
                placeholder={this.props.placeholderTo}
                value={this.props.valueTo}
                onChange={this.onChangeTo}
              />
              {this.props.suffixTo && <div className="input-group-text">{this.props.suffixTo}</div>}
            </div>
          )}
          {!(this.props.prefixFrom || this.props.suffixFrom) && (
            <input
              type="number"
              step={this.props.stepFrom}
              min={this.props.minFrom}
              max={this.props.maxFrom}
              disabled={this.props.disabledFrom}
              className="form-control"
              id={`input-${this.props.labelFrom}`}
              placeholder={this.props.placeholderFrom}
              value={this.props.valueFrom}
              onChange={this.onChangeFrom}
            />
          )}
          {!(this.props.prefixTo || this.props.suffixTo) && (
            <input
              type="number"
              step={this.props.stepTo}
              min={this.props.minTo}
              max={this.props.maxTo}
              disabled={this.props.disabledTo}
              className="form-control"
              id={`input-${this.props.labelTo}`}
              placeholder={this.props.placeholderTo}
              value={this.props.valueTo}
              onChange={this.onChangeTo}
            />
          )}
        </div>
      </div>
    );
  }
}
