import React from 'react';
import { Button } from './Button';

export function SquareButton({ text, icon, level = "info", ...props }) {
  return <Button
    type={level}
    className="btn-sm d-flex align-items-center justify-content-center"
    style={{
      flexDirection: 'column',
      minWidth: '80px',
      minHeight: '80px',
      maxWidth: '80px',
      maxHeight: '80px',
      flex: 1,
      ...(props.style || {})
    }}
    {...props}>
    <div>
      <i className={`fas ${icon}`} />
    </div>
    <div>
      {text}
    </div>
  </Button>
}