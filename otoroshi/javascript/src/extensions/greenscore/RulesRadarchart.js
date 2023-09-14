import React, { PureComponent } from 'react';
import { Radar, RadarChart, PolarGrid, PolarAngleAxis, PolarRadiusAxis, ResponsiveContainer, Text } from 'recharts';

export default class RulesRadarchart extends PureComponent {

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
    const { values, dynamic_score } = this.props;

    const data = [
      { subject: 'Architecture', value: values.find(v => v.section === "architecture")?.score.normalized_score || 0, fullMark: 1, domain: [0, 1] },
      { subject: 'Design', value: values.find(v => v.section === "design")?.score.normalized_score || 0, fullMark: 1, domain: [0, 1] },
      { subject: 'Usage', value: values.find(v => v.section === "usage")?.score.normalized_score || 0, fullMark: 1, domain: [0, 1] },
      { subject: 'Log retention', value: values.find(v => v.section === "log")?.score.normalized_score || 0, fullMark: 1, domain: [0, 1] },
      { subject: 'Plugins instance', value: dynamic_score.plugins_instance, fullMark: 1, domain: [0, 1] },
      { subject: 'Produced data', value: dynamic_score.produced_data, fullMark: 1, domain: [0, 1] },
      { subject: 'Produced headers', value: dynamic_score.produced_headers, fullMark: 1, domain: [0, 1] },
    ];

    return <div style={{
      flex: '1 1 50%',
      // maxWidth: 420,
      // maxWidth: '50%',
      background: 'var(--bg-color_level2)',
      borderRadius: '.2rem',
      position: 'relative'
    }} className='p-3'>
      <ResponsiveContainer height="100%" width="100%">
        <RadarChart
          outerRadius="70%"
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
