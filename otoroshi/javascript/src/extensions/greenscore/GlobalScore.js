import React from 'react';
import Wrapper from './Wrapper';

import { getColorFromLetter } from './util';

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

export function GlobalScore(allProps) {
  const { tag = 'static', letter, rank, color, score, maxScore, unit, ...props } = allProps;

  function showDynamicRawScore() {
    return (
      <>
        <span
          style={{
            fontSize: '5rem',
            '--dynamic-raw-score-to': Math.round(score),
          }}
          className="dynamic-raw-score"></span>
        <span style={{ fontSize: '1rem', fontWeight: 'bold' }}>
          {`${maxScore ? `/${maxScore.toFixed(0)} ` : ''}`}
          <span style={{ fontWeight: 'normal' }}>{unit || '%'}</span>
        </span>
      </>
    );
  }

  function showNetScore() {
    return (
      <div style={{ position: 'relative' }}>
        <span
          style={{
            fontSize: `${5 - String(score).length * 0.1}rem`,
            '--net-score-to': Math.round(score),
          }}
          className="net-score"></span>
        <div
          style={{
            fontSize: '1rem',
            fontWeight: 'bold',
            position: 'absolute',
            bottom: '2rem',
            left: 0,
            right: 0,
            overflow: 'hidden',
            whiteSpace: 'nowrap',
          }}>{`/ ${maxScore}`}</div>
      </div>
    );
  }

  function showGlobalScore() {
    return (
      <>
        {letter !== '@' ? letter : '-'}{' '}
        <i
          className="fa fa-leaf scale-in-ver-top"
          style={{ color: color || getColorFromLetter(letter), fontSize: '5rem' }}
        />
      </>
    );
  }

  return (
    <Wrapper loading={props.loading}>
      <div
        className={`text-center p-3 ${allProps.className}`}
        style={{
          flex: 0.5,
          // maxWidth: 250,
          // minWidth: 250,
          background: 'var(--bg-color_level2)',
          borderRadius: '.2rem',
          padding: '0 .5rem',
          fontSize: '10rem',
          position: 'relative',
          display: 'flex',
          flexDirection: 'column',
          // boxShadow: props.dynamic && props.raw ? `inset ${color} 0 -4px 0px 0px` : 0
        }}>
        <div
          style={{
            display: 'flex',
            flexDirection: props.under ? 'column' : 'center',
            alignItems: props.under ? 'center' : 'baseline',
            justifyContent: 'center',
            flex: 1,
          }}>
          {props.dynamic
            ? props.raw
              ? showDynamicRawScore()
              : showGlobalScore()
            : props.raw
            ? showNetScore()
            : showGlobalScore()}
        </div>

        <h3
          style={{
            color: 'var(--text)',
            fontWeight: 100,
          }}
          className="m-0 mt-1">
          {props.title ? props.title : props.raw ? 'Raw Value' : 'Static Score'}
        </h3>

        <Tag value={tag} />

        <div
          style={{
            position: 'absolute',
            top: 6,
            right: 6,
            bottom: 6,
          }}
          className="d-flex flex-column justify-content-between">
          <div
            style={{
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              justifyContent: 'center',
              background: 'rgba(249, 176, 0, 0.46)',
              color: '#fff',
              width: 32,
              height: 32,
              borderRadius: '50%',
            }}>
            <i
              className={`fas fa-${props.dynamic ? 'bolt' : 'spa'}`}
              style={{ fontSize: 'initial' }}
            />
          </div>

          {props.dynamic && props.raw && color && (
            <i className="fa fa-leaf scale-in-ver-top" style={{ color, fontSize: '1.25rem' }} />
          )}
        </div>
      </div>
    </Wrapper>
  );
}
