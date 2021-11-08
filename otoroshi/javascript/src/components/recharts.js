import React, { Component } from 'react';
import _ from 'lodash';
import moment from 'moment';

import {
  AreaChart,
  Area,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  PieChart,
  Pie,
  Cell,
} from 'recharts';

const RADIAN = Math.PI / 180;

export class Histogram extends Component {
  colors = [
    '#027cc3',
    '#95cf3d',
    '#ff8900',
    '#7cb5ec',
    '#8085c9',
    '#ffeb3b',
    '#8a2be2',
    '#deb887',
    '#d50200',
    '#a52a2a',
  ];

  formatTick = (v) => {
    if (v > 999999) {
      return (v / 1000000).toFixed(0) + ' M';
    }
    if (v > 999) {
      return (v / 1000).toFixed(0) + ' k';
    }
    return v;
  };

  render() {
    let data = [];
    let seriesName = [];

    // console.log(this.props.title, this.props.series);

    if (
      this.props.series &&
      this.props.series.map((s) => s.data.length).filter((v) => v > 0).length > 0
    ) {
      seriesName = this.props.series.map((s) => s.name);
      const values = [];
      const sizes = this.props.series.map((s) => s.data.length);
      const size = Math.max(...sizes);
      for (let i = 0; i < size; i++) {
        let finalItem = {};
        this.props.series.forEach((serie) => {
          const item = serie.data[i];
          if (item) {
            finalItem = {
              ...finalItem,
              ...{
                name: moment(item[0]).format('YYYY-MM-DD HH:mm'),
                [serie.name]: item[1],
              },
            };
          }
        });
        values.push(finalItem);
      }
      data = values;
    }

    return (
      <div
        className="sub-container__bg-color"
        style={{
          display: 'flex',
          flexDirection: 'column',
          justifyContent: 'center',
          alignItems: 'center',
          width: '100%',
        }}>
        <h4 style={{ marginTop: 10 }}>{this.props.title}</h4>
        <ResponsiveContainer height={this.props.height || 200}>
          <AreaChart data={data} margin={{ top: 10, right: 30, left: 0, bottom: 0 }}>
            <XAxis dataKey="name" hide={!!this.props.hideXAxis} />
            <YAxis tickFormatter={this.formatTick} />
            <CartesianGrid strokeDasharray="3 3" />
            <Tooltip />
            {_.sortBy(seriesName, (sn) => sn).map((sn, idx) => (
              <Area
                key={sn}
                type="monotone"
                name={sn}
                unit={this.props.unit}
                dataKey={sn}
                stroke={this.colors[idx]}
                fillOpacity={0.6}
                fill={this.colors[idx]}
              />
            ))}
          </AreaChart>
        </ResponsiveContainer>
      </div>
    );
  }
}

export class RoundChart extends Component {
  colors = [
    '#95cf3d',
    '#027cc3',
    '#ff8900',
    '#7cb5ec',
    '#8085c9',
    '#ffeb3b',
    '#8a2be2',
    '#deb887',
    '#d50200',
    '#a52a2a',
  ];

  renderCustomizedLabel = (props) => {
    const { x, y, cx, midAngle, innerRadius, outerRadius, percent, index } = props;
    return (
      <text
        x={x}
        y={y}
        fill="white"
        textAnchor={x > cx ? 'start' : 'end'}
        dominantBaseline="central"
        style={{ padding: 5 }}>
        {props.name.replace(/"/g, '')}: {(props.percent * 100).toFixed(0)}% ({props.value})
      </text>
    );
  };

  render() {
    let data = [];
    let seriesName = [];

    // console.log(this.props.title, this.props.series);

    if (this.props.series && this.props.series[0]) {
      seriesName = this.props.series.map((s) => s.name);
      data = this.props.series[0].data.map((i) => ({ name: i.name, value: i.y }));
    }

    return (
      <div
        className="sub-container__bg-color"
        style={{
          display: 'flex',
          flexDirection: 'column',
          justifyContent: 'center',
          alignItems: 'center',
        }}>
        <h4 style={{ marginTop: 10 }}>{this.props.title}</h4>
        <ResponsiveContainer height={this.props.size ? this.props.size + 100 : 200}>
          <PieChart>
            <Pie
              data={data}
              fill="#8884d8"
              outerRadius={this.props.size ? this.props.size / 2 : 100}
              dataKey="value"
              minAngle={5}
              label={this.renderCustomizedLabel}>
              {data.map((entry, index) => (
                <Cell key={entry.name} fill={this.colors[index % this.colors.length]} />
              ))}
            </Pie>
            <Tooltip />
          </PieChart>
        </ResponsiveContainer>
      </div>
    );
  }
}
