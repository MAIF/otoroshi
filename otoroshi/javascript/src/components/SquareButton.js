import React from 'react';
import { Button } from './Button';

export function SquareButton({ text, icon, level = 'info', ...props }) {
  return (
    <Button
      type={level}
      className="btn-sm d-flex flex-column align-items-center justify-content-center square-button"
      style={{
        ...(props.style || {}),
      }}
      {...props}>
      <div>
        <i className={`mb-2 fas ${icon}`} />
      </div>
      <div>{text}</div>
    </Button>
  );
}
