import React, { PureComponent } from 'react';
import {
  AreaChart,
  Area,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  Legend,
} from 'recharts';

import moment from 'moment';

export default class StackedBarChart extends PureComponent {
  render() {
    const mapValues = Object.entries(this.props.values || {});

    const data = (mapValues.length < 2 ? [...mapValues, mapValues[0]] : mapValues)
      .filter((f) => f)
      .map(([date, section]) => {
        const sections = section.reduce(
          (acc, s) => ({ ...acc, [s.section]: (acc[s.section] || 0) + s.score.score }),
          {}
        );
        const length = section.length / 4;

        return {
          name: moment(new Date(Number(date))).format('DD MMMM YY'),
          rawDate: date,
          ...Object.fromEntries(Object.entries(sections).map((s) => [s[0], s[1] / length])),
        };
      })
      .sort((a, b) => a.rawDate - b.rawDate);

    return (
      <div
        style={{
          flex: 1,
          // minHeight: 520,
          maxHeight: 320,
          background: 'var(--bg-color_level2)',
          borderRadius: '.2rem',
          padding: '1rem 2rem 62px',
        }}
      >
        <ResponsiveContainer height="100%" width="100%">
          <AreaChart data={data}>
            <CartesianGrid strokeDasharray="3 3" />
            <XAxis dataKey="name" />
            <YAxis domain={[0, 6000]} />
            <Tooltip />
            <Legend wrapperStyle={{ bottom: 0 }} />
            <Area
              type="monotone"
              dataKey="architecture"
              stackId="1"
              stroke="#2F4858"
              fill="#2F4858"
            />
            <Area type="monotone" dataKey="design" stackId="1" stroke="#006674" fill="#006674" />
            <Area type="monotone" dataKey="usage" stackId="1" stroke="#008473" fill="#008473" />
            <Area type="monotone" dataKey="log" stackId="1" stroke="#389D55" fill="#389D55" />
          </AreaChart>
        </ResponsiveContainer>
      </div>
    );
  }
}
