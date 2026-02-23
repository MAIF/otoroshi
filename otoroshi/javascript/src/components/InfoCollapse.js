import React, { useState } from 'react';

export default function InfoCollapse({ title, icon, children, defaultOpen = false }) {
  const [open, setOpen] = useState(defaultOpen);

  return (
    <div
      style={{
        border: '1px solid rgba(23, 162, 184, 0.3)',
        borderRadius: 4,
        backgroundColor: 'rgba(23, 162, 184, 0.08)',
        marginBottom: 10,
      }}
    >
      <div
        onClick={() => setOpen(!open)}
        role="button"
        style={{
          padding: '10px 15px',
          fontWeight: 'bold',
          color: '#17a2b8',
          cursor: 'pointer',
          display: 'flex',
          alignItems: 'center',
          gap: 8,
        }}
      >
        {icon ?? <i className="fas fa-lightbulb" style={{ color: '#17a2b8', fontSize: 16 }} />}
        <span style={{ flex: 1 }}>{title}</span>
        <i
          className={`fas fa-chevron-${open ? 'up' : 'down'}`}
          style={{ fontSize: 12, color: '#17a2b8' }}
        />
      </div>
      {open && (
        <div style={{ padding: '0 15px 10px 15px' }}>
          {children}
        </div>
      )}
    </div>
  );
}
