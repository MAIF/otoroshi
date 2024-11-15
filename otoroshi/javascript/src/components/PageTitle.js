import React from 'react';
import Thumbtack from './Thumbtack';

export default function PageTitle({
  title,
  children,
  style = {},
  className = 'mb-3',
  ...props
}) {
  return (
    <div
      className={`page-header_title d-flex align-item-center justify-content-between ${className}`}
      style={style}
    >
      <div className="d-flex ms-3">
        <h3 className="m-0 align-self-center">
          {title} <Thumbtack {...props} />
        </h3>
      </div>
      <div className="d-flex align-item-center justify-content-between me-3 py-1">{children}</div>
    </div>
  );
}
