import React, { Component } from 'react';
import moment from 'moment';

import DatePicker from 'antd/lib/date-picker';
import LocaleProvider from 'antd/lib/locale-provider';
import enUS from 'antd/lib/locale-provider/en_US';
import 'antd/lib/date-picker/style/index.css';
import './datepicker.css';

export class OtoDatePicker extends Component {
  onChange = (value, dateString) => {
    const from = value[0];
    const to = value[1];
    if (
      from &&
      to &&
      this.props.updateDateRange &&
      (!this.props.from.isSame(from) || !this.props.to.isSame(to))
    ) {
      this.props.updateDateRange(from, to);
    }
  };

  render() {
    const { from, to } = this.props;
    const dateFormat = 'YYYY-MM-DD HH:mm:ss';
    return (
      <LocaleProvider locale={enUS}>
        <DatePicker.RangePicker
          defaultValue={[from, to]}
          showTime={{ format: 'HH:mm:ss' }}
          format={dateFormat}
          placeholder={['Start Time', 'End Time']}
          onChange={this.onChange}
          onOk={value => value}
        />
      </LocaleProvider>
    );
  }
}
