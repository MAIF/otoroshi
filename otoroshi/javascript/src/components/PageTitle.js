import React, { useEffect } from 'react';

// className='slide-in-blurred-top'
export default function PageTitle({ title, children, style = {}, className = 'ms-0 mb-3' }) {
  return (
    <div
      className={`page-header_title d-flex align-item-center justify-content-between ${className}`}
      style={style}>
      <h3 style={{ margin: 0, alignSelf: 'center' }}>{title}</h3>
      <div className="d-flex align-item-center justify-content-between">{children}</div>
    </div>
  );
}
