import React, { Component } from 'react';
import _ from 'lodash';
import classNames from 'classnames';
import { Popover } from 'antd';

export const formatPercentage = (value, decimal = 2) => {
  return parseFloat(value).toFixed(decimal) + ' %';
};

export class Uptime extends Component {
  render() {
    const test = this.props.health.dates.map((h) => {
      const availability = h.status.length
        ? h.status
            .filter((s) => s.health === 'GREEN' || s.health === 'YELLOW')
            .reduce((acc, curr) => acc + curr.percentage, 0)
            .toFixed(2)
        : `unknown`;
      return { date: h.dateAsString, availability, status: h.status };
    });

    const avg = this.props.health.dates
      .filter((d) => !this.props.stopTheCountUnknownStatus || d.status.length)
      .reduce((avg, value, _, { length }) => {
        return (
          avg +
          value.status
            .filter((s) => s.health === 'GREEN' || s.health === 'YELLOW')
            .reduce((acc, curr) => acc + curr.percentage, 0) /
            length
        );
      }, 0);
    return (
      <div className={`health-container ${this.props.className}`}>
        <div
          className="container--header"
          style={{
            display: 'flex',
            justifyContent: 'flex-end',
          }}>
          <div className="uptime-avg">{formatPercentage(avg)}</div>
        </div>
        <div className="flex-status">
          {test.map((t, idx) => {
            const clazz = {
              green: t.availability !== 'unknown' && t.availability >= 100,
              'light-green':
                t.availability !== 'unknown' && t.availability >= 99 && t.availability < 100,
              orange: t.availability !== 'unknown' && t.availability >= 95 && t.availability < 99,
              red: t.availability !== 'unknown' && t.availability < 95,
              gray: t.availability === 'unknown',
            };

            return (
              <Popover
                key={idx}
                placement="bottom"
                title={t.date}
                content={
                  !t.status.length ? (
                    <span>UNKNOWN</span>
                  ) : (
                    <div className="d-flex flex-column">
                      {t.status.map((s, i) => (
                        <div className="status-info">
                          <div className={`dot ${s.health.toLowerCase()}`} />
                          <div className={`info`}>{`${formatPercentage(s.percentage)}`}</div>
                        </div>
                      ))}
                    </div>
                  )
                }>
                <div key={idx} className={classNames('status', clazz)}></div>
              </Popover>
            );
          })}
        </div>
      </div>
    );
  }
}
