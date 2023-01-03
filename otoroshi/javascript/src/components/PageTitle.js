import React from 'react';

export default function PageTitle({ title, children, style = {} }) {
  return (
    <div
      className="page-header_title d-flex align-item-center justify-content-between ms-0 mb-3"
      style={style}>
      <h3 style={{ margin: 0, alignSelf: 'center' }}>
        {title}
      </h3>
      <div className="d-flex align-item-center justify-content-between">{children}</div>
    </div>
  );
}
