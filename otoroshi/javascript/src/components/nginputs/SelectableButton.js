import React from 'react';
import { Button } from '../Button';

export function SelectableButton({ value, expected, title, desc, onChange }) {
  return (
    <Button
      type={value === expected ? 'primaryColor' : 'quiet'}
      className="py-3 d-flex align-items-center flex-column col-3"
      style={{
        gap: '12px',
        minHeight: '325px',
        maxWidth: '235px',
      }}
      onClick={() => onChange(value)}
      key={value}
    >
      <div style={{ flex: 0.2 }}>
        <h3 className="wizard-h3--small " style={{ margin: 0 }}>
          {title}
        </h3>
      </div>
      <div className="d-flex flex-column align-items-center" style={{ flex: 1 }}>
        <span className="d-flex align-items-center" style={{ textAlign: 'left' }}>
          {desc}
        </span>
      </div>
    </Button>
  );
}
