import React from 'react';
import { BarChart, ResponsiveContainer, Bar, CartesianGrid, XAxis, YAxis } from 'recharts';

import { firstLetterUppercase } from '../../util';
import Wrapper from './Wrapper';

function Tag({ value }) {
  return (
    <div
      style={{
        position: 'absolute',
        top: '.75rem',
        left: '.75rem',
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
        display: 'block',
      }}>
      {value}
    </div>
  );
}

export function DynamicChart(props) {
  return (
    <Wrapper loading={props.loading}>
      <div
        className="text-center p-3 d-flex flex-column"
        style={{
          flex: 1,
          background: 'var(--bg-color_level2)',
          borderRadius: '.2rem',
          padding: '0 .5rem',
          position: 'relative',
        }}>
        <div style={{ maxHeight: 420, flex: 1 }}>
          <ResponsiveContainer width="100%" height="100%">
            <BarChart
              // layout='vertical'
              margin={{
                top: 75,
                bottom: 10,
                left: 20,
                right: 20,
              }}
              data={props.values.map(([key, value]) => ({
                name: firstLetterUppercase(key.replace(/_/g, ' ')),
                value: value * 100,
              }))}>
              <CartesianGrid strokeDasharray="2 2" />
              <XAxis dataKey="name" stroke="var(--text)" />
              <YAxis stroke="var(--text)" domain={[0, 1]} />
              <Bar
                dataKey="value"
                fill="var(--color-primary)"
                barSize={20}
                radius={[10, 10, 0, 0]}
              />
            </BarChart>
          </ResponsiveContainer>
        </div>
        <h3 style={{ color: 'var(--color_level2)', fontWeight: 100 }} className="m-0">
          {props.title}
        </h3>

        <div className="card-picto position-absolute">
          <i className="fas fa-bolt" style={{ fontSize: 'initial' }} />
        </div>
      </div>
    </Wrapper>
  );
}
