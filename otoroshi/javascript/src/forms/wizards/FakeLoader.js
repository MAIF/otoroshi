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
        display: 'grid',
        gridTemplateColumns: '42px 1fr',
        minHeight: '42px',
        alignItems: 'center',
        justifyContent: 'flex-start',
        marginBottom: '6px'
      }} className="mt-3">
      {started && <Loader loading={loading} minLoaderTime={timeout}>
        <i className="fas fa-check fa-2x" style={{ color: '#f9b000' }} />
      </Loader>}
      {!started && <i className="fas fa-square fa-2x" />}
      <div
        style={{
          flex: 1,
          marginLeft: '12px',
          color: loading ? '#eee' : '#fff',
          fontWeight: loading ? 'normal' : 'bold',
        }}>
        <div>{text}</div>
      </div>
    </div>
  );
};