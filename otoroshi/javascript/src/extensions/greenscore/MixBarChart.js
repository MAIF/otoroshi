import React, { PureComponent } from 'react';
import { Radar, RadarChart, PolarGrid, PolarAngleAxis, PolarRadiusAxis, ResponsiveContainer } from 'recharts';
import { caclculateRuleGroup } from './util';

export default class MixBarChart extends PureComponent {

  calculate = routes => {
    return routes.reduce((acc, route) => {
      return [
        { subject: 'Architecture', value: acc[0].value + caclculateRuleGroup(route.rulesConfig, "architecture") },
        { subject: 'Design', value: acc[1].value + caclculateRuleGroup(route.rulesConfig, 'design') },
        { subject: 'Usage', value: acc[2].value + caclculateRuleGroup(route.rulesConfig, 'usage') },
        { subject: 'Log retention', value: acc[3].value + caclculateRuleGroup(route.rulesConfig, 'log') }
      ]
    }, [
      { subject: 'Architecture', value: 0 },
      { subject: 'Design', value: 0 },
      { subject: 'Usage', value: 0 },
      { subject: 'Log retention', value: 0 },
    ])
  }

  render() {
    const values = this.props.groups.reduce((acc, item) => {
      const values = this.calculate(item.routes);
      return [
        { subject: 'Architecture', value: acc[0].value + values[0].value },
        { subject: 'Design', value: acc[1].value + values[1].value },
        { subject: 'Usage', value: acc[2].value + values[2].value },
        { subject: 'Log retention', value: acc[3].value + values[3].value }
      ]
    }, [
      { subject: 'Architecture', value: 0 },
      { subject: 'Design', value: 0 },
      { subject: 'Usage', value: 0 },
      { subject: 'Log retention', value: 0 }
    ]);

    const data = [
      { subject: 'Architecture', value: values[0].value / this.props.groups.length },
      { subject: 'Design', value: values[1].value / this.props.groups.length },
      { subject: 'Usage', value: values[2].value / this.props.groups.length },
      { subject: 'Log retention', value: values[3].value / this.props.groups.length }
    ]

    console.log(data, [
      { subject: 'Architecture', value: data[0].value / 1500 },
      { subject: 'Design', value: data[1].value / 2400 },
      { subject: 'Usage', value: data[2].value / 1500 },
      { subject: 'Log retention', value: data[3].value / 600 }
    ])

    return <div style={{ flex: 1, height: "300px", background: 'var(--bg-color_level2)', borderRadius: '.2rem' }}>
      <ResponsiveContainer width="100%" height="100%">
        <RadarChart
          outerRadius="80%"
          cx="50%"
          cy="50%"
          data={[
            { subject: 'Architecture', value: data[0].value / 1500, fullMark: 1, domain: [0, 1] },
            { subject: 'Design', value: Math.max(data[1].value / 2400, 1), fullMark: 1, domain: [0, 1] },
            { subject: 'Usage', value: data[2].value / 1500, fullMark: 1, domain: [0, 1] },
            { subject: 'Log retention', value: data[3].value / 600, fullMark: 1, domain: [0, 1] }
          ]}
          fill="var(--color_level2)"
          fontSize={16}>
          <PolarGrid />
          <PolarAngleAxis dataKey="subject" />
          <PolarRadiusAxis angle={90} domain={[0, 1]} fontSize={0} />
          <Radar name="Mike" dataKey="value" stroke="#8884d8" fill="#f9b000" fillOpacity={0.6} />
        </RadarChart>
      </ResponsiveContainer>
    </div>
  }
}
