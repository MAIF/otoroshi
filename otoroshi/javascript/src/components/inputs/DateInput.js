import React, { Component } from 'react';
import { Help } from './Help';

import moment from 'moment';

import { OtoDateTimePicker } from '../datepicker';

export class DateTimeInput extends Component {
  onChange = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    this.props.onChange(e);
  };

  render() {
    if (this.props.hide) {
      return null;
    }
    return (
      <div className="form__group mb-20 grid-template-col-xs-up__1fr-5fr">
        <label htmlFor={`input-${this.props.label}`}>
          {this.props.label} <Help text={this.props.help} />
        </label>
        <div style={{ display: 'flex' }}>
          <OtoDateTimePicker date={moment(this.props.value)} onChange={this.onChange} />
        </div>
      </div>
    );
  }
}
