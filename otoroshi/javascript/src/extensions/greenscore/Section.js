import React from 'react';

export default function Section({ title, subTitle, children, full = true }) {
  return (
    <div
      style={{
        margin: full ? '5rem 0' : '1rem 0',
        width: '100%'
      }}
      className="d-flex flex-column justify-content-center"
    >
      <h1
        className="text-center"
        style={{
          textTransform: 'uppercase',
          color: 'var(--text)',
          fontSize: '2.25rem',
          lineHeight: '2.5rem',
          letterSpacing: '-0.025em',
        }}
      >
        {title}
      </h1>
      <h2
        className="text-center mb-4"
        style={{
          color: 'var(--color_level2)',
          fontSize: '1.125rem',
          lineHeight: '2rem',
        }}
      >
        {subTitle}
      </h2>

      {children}
    </div>
  );
}
