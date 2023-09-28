import React, { PureComponent } from 'react';
import { AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend } from 'recharts';

import moment from 'moment';

export default class StackedBarChart extends PureComponent {

    render() {
        const mapValues = Object.entries(this.props.values || {});
        const data = (mapValues.length < 2 ? [...mapValues, mapValues[0]] : mapValues)
            .filter(f => f)
            .map(([date, section]) => {
                return {
                    name: moment(new Date(Number(date))).format("DD MMMM YY"),
                    rawDate: date,
                    ...section.reduce((acc, s) => ({ ...acc, [s.section]: s.score.score / s.length }), {})
                }
            })
            .sort((a, b) => a.rawDate - b.rawDate)

        console.log(this.props.values, data)

        return <div style={{
            flex: 1,
            // minHeight: 520,
            maxHeight: 320,
            background: 'var(--bg-color_level2)',
            borderRadius: '.2rem',
            padding: '1rem 2rem 62px'
        }} >
            <div className='d-flex justify-content-between' style={{ minHeight: 48 }}>
                <div style={{
                    color: '#fff',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    fontWeight: '500',
                    fontSize: '.75rem',
                    lineHeight: '1.2rem',
                    letterSpacing: '.125em',
                    textTransform: 'uppercase',
                    color: '#f9b000',
                    marginBottom: '10px',
                    display: 'block'
                }}>
                    static
                </div>
                <div style={{
                    borderRadius: '50%',
                    background: 'rgba(249, 176, 0, 0.46)',
                    color: '#fff',
                    width: 32,
                    height: 32,
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center'
                }}>
                    <i className='fas fa-chart-line' style={{ fontSize: 'initial' }} />
                </div>
            </div>
            <ResponsiveContainer height="100%" width="100%">
                <AreaChart
                    data={data}>
                    <CartesianGrid strokeDasharray="3 3" />
                    <XAxis dataKey="name" />
                    <YAxis domain={[0, 6000]} />
                    <Tooltip />
                    <Legend wrapperStyle={{ bottom: 0 }} />
                    <Area type="monotone" dataKey="architecture" stackId="1" stroke="#2F4858" fill="#2F4858" />
                    <Area type="monotone" dataKey="design" stackId="1" stroke="#006674" fill="#006674" />
                    <Area type="monotone" dataKey="usage" stackId="1" stroke="#008473" fill="#008473" />
                    <Area type="monotone" dataKey="log" stackId="1" stroke="#389D55" fill="#389D55" />
                </AreaChart>
            </ResponsiveContainer>
        </div>
    }
}
