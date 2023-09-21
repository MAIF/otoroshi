import React, { PureComponent } from 'react';
import { Radar, RadarChart, PolarGrid, PolarAngleAxis, PolarRadiusAxis, ResponsiveContainer, Text } from 'recharts';
import Wrapper from './Wrapper';

export default class RulesRadarchart extends PureComponent {

  degToRad = degrees => degrees * (Math.PI / 180);

  renderPolarAngleAxis = props => {
    const newPoint = this.movePointAtAngle([props.x, props.y], this.degToRad((360 / 12) * props.payload.index), 10);
    const texts = [props.payload.value] // props.payload.value.split(" ");

    return texts
      .map((text, i) => <Text
        key={text}
        {...props}
        verticalAnchor="middle"
        x={newPoint[0]}
        y={newPoint[1]}
      // x={props.payload.index % 2 !== 0 ? newPoint[0] : props.x}
      // y={(props.payload.index % 2 === 0 ? newPoint[1] : props.y) + (i * 20)}
      >
        {text}
      </Text>
      );
  }

  movePointAtAngle = (point, angle, distance) => [
    point[0] + (Math.cos(angle) * distance),
    point[1] + (Math.sin(angle) * distance)
  ];

  render() {
    const { values, dynamic_values } = this.props;

    const data = [
      { subject: 'Architecture', value: values.find(v => v.section === "architecture")?.score.scaling_score || 0, fullMark: 1, domain: [0, 1] },
      { subject: 'Design', value: values.find(v => v.section === "design")?.score.scaling_score || 0, fullMark: 1, domain: [0, 1] },
      { subject: 'Usage', value: values.find(v => v.section === "usage")?.score.scaling_score || 0, fullMark: 1, domain: [0, 1] },
      { subject: 'Log retention', value: values.find(v => v.section === "log")?.score.scaling_score || 0, fullMark: 1, domain: [0, 1] },
      { subject: 'Backend duration', value: dynamic_values.plugins_instance, fullMark: 1, domain: [0, 1] },
      { subject: 'Calls', value: dynamic_values.calls, fullMark: 1, domain: [0, 1] },
      { subject: 'Data in', value: dynamic_values.dataIn, fullMark: 1, domain: [0, 1] },
      { subject: 'Data out', value: dynamic_values.dataOut, fullMark: 1, domain: [0, 1] },
      { subject: 'Duration', value: dynamic_values.duration, fullMark: 1, domain: [0, 1] },
      { subject: 'Headers in', value: dynamic_values.headersIn, fullMark: 1, domain: [0, 1] },
      { subject: 'Headers out', value: dynamic_values.headersOut, fullMark: 1, domain: [0, 1] },
      { subject: 'Overhead', value: dynamic_values.overhead, fullMark: 1, domain: [0, 1] },
    ];

    return <Wrapper loading={this.props.loading}>
      <div style={{
        flex: '1 1 50%',
        // maxWidth: 420,
        // maxWidth: '50%',
        background: 'var(--bg-color_level2)',
        borderRadius: '.2rem',
        position: 'relative'
      }} className='p-3'>
        <ResponsiveContainer height="100%" width="100%">
          <RadarChart
            outerRadius="90%"
            innerRadius="10%"
            data={data}
            fill="var(--color_level2)"
            fontSize={15}>
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
    </Wrapper>
  }
}
