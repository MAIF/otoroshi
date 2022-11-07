import React from 'react';

export function Button({
  type = 'info',
  style = {},
  onClick = () => { },
  text,
  children,
  className = '',
  disabled = false,
  ...props
}) {
  return <button
    type="button"
    className={`btn btn-${type} ${className}`}
    style={style}
    onClick={onClick}
    disabled={disabled}
    {...props}>
    {text || children}
  </button>
}