import React, { useState, useEffect } from 'react';
import Loader from '../../components/Loader';

export function FakeLoader({ text, timeout, started }) {
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (started) {
      const timeout = setTimeout(() => setLoading(false), timeout);
      return () => timeout;
    }
  }, [started]);

  return (
    <div
      style={{
        gridTemplateColumns: '42px 1fr',
        minHeight: '42px',
        backgroundColor: 'var(--bg-color_level2)',
        borderRadius: '5px',
      }}
      className="d-grid align-items-center justify-content-start mt-3 mb-2 p-3"
    >
      {started && (
        <Loader loading={loading} minLoaderTime={timeout}>
          <i className="fas fa-check fa-lg" style={{ color: 'var(--color-primary)' }} />
        </Loader>
      )}
      {!started && <i className="fas fa-square fa-lg" />}
      <div
        style={{
          color: loading ? '#eee' : '#fff',
          fontWeight: loading ? 'normal' : 'bold',
        }}
      >
        <div>{text}</div>
      </div>
    </div>
  );
}
