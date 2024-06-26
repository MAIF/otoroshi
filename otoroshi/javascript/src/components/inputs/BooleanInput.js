import React, { Component } from 'react';
import { Help } from './Help';

export const OnSwitch = (props) => (
  <div className="content-switch-button-on" onClick={props.onChange} style={props?.style || {}}>
    <div className="switch-button-on" />
  </div>
);

export const OffSwitch = (props) => (
  <div className="content-switch-button-off" onClick={props.onChange} style={props?.style || {}}>
    <div className="switch-button-off" />
  </div>
);

export class BooleanInput extends Component {
  toggleOff = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    if (!this.props.disabled) this.props.onChange(false);
  };

  toggleOn = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    if (!this.props.disabled) this.props.onChange(true);
  };

  toggle = (value) => {
    if (!this.props.disabled) this.props.onChange(value);
  };

  render() {
    const value = !!this.props.value;

    if (this.props.flex)
      return (
        <div className="d-flex align-items-center flex">
          <label className="flex" style={{ marginTop: '10px' }}>
            {this.props.label} <Help text={this.props.help} />
          </label>
          <div className="">
            <div>
              {value && <OnSwitch onChange={this.toggleOff} />}
              {!value && <OffSwitch onChange={this.toggleOn} />}
            </div>
            <div>{this.props.after && <div className="float-end">{this.props.after()}</div>}</div>
          </div>
        </div>
      );

    return (
      <div>
        <div className="row mb-3">
          <label className="col-xs-12 col-sm-2 col-form-label">
            {this.props.label} <Help text={this.props.help} />
          </label>
          <div className="col-sm-10">
            <div className="row">
              <div className="col-sm-6">
                {value && <OnSwitch onChange={this.toggleOff} />}
                {!value && <OffSwitch onChange={this.toggleOn} />}
              </div>
              <div className="col-sm-6">
                {this.props.after && <div className="float-end">{this.props.after()}</div>}
              </div>
            </div>
          </div>
        </div>
      </div>
    );
  }
}

export class BiColumnBooleanInput extends Component {
  toggleOff = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    if (!this.props.disabled) this.props.onChange(false);
  };

  toggleOn = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    if (!this.props.disabled) this.props.onChange(true);
  };

  toggle = (value) => {
    if (!this.props.disabled) this.props.onChange(value);
  };

  render() {
    const value = !!this.props.value;
    if (this.props.hide) {
      return null;
    }

    return (
      <div>
        <div className="row mb-3">
          <label className="col-xs-12 col-sm-4 col-form-label">
            {this.props.label} <Help text={this.props.help} />
          </label>
          <div className="col-sm-8">
            <div className="row">
              <div className="col-sm-6">
                {value && <OnSwitch onChange={this.toggleOff} />}
                {!value && <OffSwitch onChange={this.toggleOn} />}
              </div>
              <div className="col-sm-6">
                {this.props.after && <div className="float-end">{this.props.after()}</div>}
              </div>
            </div>
          </div>
        </div>
      </div>
    );
  }
}

export class SimpleBooleanInput extends Component {
  toggleOff = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    console.log('off');
    if (!this.props.disabled) this.props.onChange(false);
  };

  toggleOn = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    console.log('on');
    if (!this.props.disabled) this.props.onChange(true);
  };

  toggle = (value) => {
    if (!this.props.disabled) this.props.onChange(value);
  };

  render() {
    const value = !!this.props.value;
    return (
      <div>
        {value && <OnSwitch onChange={this.toggleOff} />}
        {!value && <OffSwitch onChange={this.toggleOn} />}
      </div>
    );
  }
}
