import React, { PureComponent } from 'react';
import { Radar, RadarChart, PolarGrid, PolarAngleAxis, PolarRadiusAxis, ResponsiveContainer, Text } from 'recharts';
import { caclculateRuleGroup } from './util';

export default class RulesRadarchart extends PureComponent {

  normalizeReservoir = (value, limit) => {
    if (value > limit)
      return 1;
    return value / limit
  }

  calculate = (routes, globalThresholds) => {
    return routes.reduce((acc, route, i) => {
      return [
        { subject: 'Architecture', value: acc[0].value + caclculateRuleGroup(route.rulesConfig, "architecture") },
        { subject: 'Design', value: acc[1].value + caclculateRuleGroup(route.rulesConfig, 'design') },
        { subject: 'Usage', value: acc[2].value + caclculateRuleGroup(route.rulesConfig, 'usage') },
        { subject: 'Log retention', value: acc[3].value + caclculateRuleGroup(route.rulesConfig, 'log') },
        { subject: 'Plugins instance', value: acc[4].value + 1 - this.normalizeReservoir(globalThresholds[i].pluginsReservoir, route.rulesConfig.thresholds.plugins.poor) },
        { subject: 'Produced data', value: acc[5].value + 1 - this.normalizeReservoir(globalThresholds[i].dataOutReservoir, route.rulesConfig.thresholds.dataOut.poor) },
        { subject: 'Produced headers', value: acc[6].value + 1 - this.normalizeReservoir(globalThresholds[i].headersOutReservoir, route.rulesConfig.thresholds.headersOut.poor) },
      ]
    }, [
      { subject: 'Architecture', value: 0 },
      { subject: 'Design', value: 0 },
      { subject: 'Usage', value: 0 },
      { subject: 'Log retention', value: 0 },
      { subject: 'Plugins instance', value: 0 },
      { subject: 'Produced data', value: 0 },
      { subject: 'Produced headers', value: 0 },
    ])
  }

  renderPolarAngleAxis = props => {
    const newPoint = this.movePointAtAngle([props.x, props.y], props.payload.index * 90, 10);
    const texts = props.payload.value.split(" ");

    return texts
      .map((text, i) => <Text
        key={text}
        {...props}
        verticalAnchor="middle"
        x={props.payload.index % 2 !== 0 ? newPoint[0] : props.x}
        y={(props.payload.index % 2 === 0 ? newPoint[1] : props.y) + (i * 20)}
      >
        {text}
      </Text>
      );
  }

  movePointAtAngle = (point, angle, distance) => [
    point[0] + (Math.sin(angle) * distance),
    point[1] - (Math.cos(angle) * distance)
  ];

  render() {
    const values = this.props.groups.reduce((acc, item) => {
      const values = this.calculate(item.routes, item.globalThresholds);
      return [
        { subject: 'Architecture', value: acc[0].value + values[0].value },
        { subject: 'Design', value: acc[1].value + values[1].value },
        { subject: 'Usage', value: acc[2].value + values[2].value },
        { subject: 'Log retention', value: acc[3].value + values[3].value },
        { subject: 'Plugins instance', value: acc[4].value + values[4].value },
        { subject: 'Produced data', value: acc[5].value + values[5].value },
        { subject: 'Produced headers', value: acc[6].value + values[6].value }
      ]
    }, [
      { subject: 'Architecture', value: 0 },
      { subject: 'Design', value: 0 },
      { subject: 'Usage', value: 0 },
      { subject: 'Log retention', value: 0 },
      { subject: 'Plugins instance', value: 0 },
      { subject: 'Produced data', value: 0 },
      { subject: 'Produced headers', value: 0 }
    ]);
    
    const numberOfRoutes = this.props.groups.reduce((acc, group) => acc + group.routes.length, 0);

    const data = [
      { subject: 'Architecture', value: values[0].value / numberOfRoutes / 1500, fullMark: 1, domain: [0, 1] },
      { subject: 'Design', value: values[1].value / numberOfRoutes / 2400, fullMark: 1, domain: [0, 1] },
      { subject: 'Usage', value: values[2].value / numberOfRoutes / 1500, fullMark: 1, domain: [0, 1] },
      { subject: 'Log retention', value: values[3].value / numberOfRoutes / 600, fullMark: 1, domain: [0, 1] },
      { subject: 'Plugins instance', value: values[4].value / numberOfRoutes, fullMark: 1, domain: [0, 1] },
      { subject: 'Produced data', value: values[5].value / numberOfRoutes, fullMark: 1, domain: [0, 1] },
      { subject: 'Produced headers', value: values[6].value / numberOfRoutes, fullMark: 1, domain: [0, 1] },
    ];

    console.log(this.props)

    console.log(data)

    return <div style={{
      flex: 1,
      maxWidth: 420,
      background: 'var(--bg-color_level2)',
      borderRadius: '.2rem',
      position: 'relative'
    }} className='p-3'>
      <ResponsiveContainer width="100%" height="100%">
        <RadarChart
          outerRadius="80%"
          cx="50%"
          cy="50%"
          data={data}
          fill="var(--color_level2)"
          fontSize={16}>
          <PolarGrid />
          <PolarAngleAxis dataKey="subject" tick={props => this.renderPolarAngleAxis(props)} />
          <PolarRadiusAxis angle={90} domain={[0, 1]} fontSize={0} />
          <Radar name="Mike" dataKey="value" stroke="#8884d8" fill="#f9b000" fillOpacity={0.6} />
        </RadarChart>
      </ResponsiveContainer>

      <div style={{
        position: 'absolute',
        top: 6,
        right: 6,
        borderRadius: '50%',
        background: 'rgba(249, 176, 0, 0.46)',
        color: '#fff',
        width: 32,
        height: 32,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center'
      }}>
        <i className='fas fa-chart-area' style={{ fontSize: 'initial' }} />
      </div>
    </div>
  }
}
