import React from 'react';

export function Row({
  title,
  children,
  className = 'col-sm-10',
  containerClassName = 'mb-3',
  style = {},
}) {
  return (
    <div className={`row ${containerClassName}`} style={style}>
      <label className="col-xs-12 col-sm-2 col-form-label" style={{ textAlign: 'right' }}>
        {title}
      </label>
      <div className={className}>{children}</div>
    </div>
  );
}
