import React from "react";

export default function PageTitle({ title, children, style = {} }) {
  return <div className="page-header d-flex align-item-center justify-content-between ms-0 mb-3" style={style}>
    <h4 className="flex" style={{ margin: 0, alignSelf: 'center' }}>
      {title}
    </h4>
    <div className="d-flex align-item-center justify-content-between flex">
      {children}
    </div>
  </div>
}