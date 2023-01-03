import React from 'react';

export function Dropdown({ children, className = '', style = {}, buttonStyle }) {
  return (
    <div className={`dropdown ${className}`} style={style}>
      <button
        className="btn btn-sm toggle-form-buttons d-flex align-items-center dark-background"
        style={{
          backgroundColor: '#494948',
          color: '#fff',
          height: '100%',
          ...(buttonStyle || {}),
        }}
        id="menu"
        data-bs-toggle="dropdown"
        data-bs-auto-close="true"
        aria-expanded="false">
        <i className="fas fa-ellipsis-h" style={{ fontSize: '1.33333em' }} />
      </button>
      <ul
        className="dropdown-menu"
        aria-labelledby="menu"
        style={{
          background: 'rgb(73, 73, 72)',
          border: '1px solid #373735',
          borderTop: 0,
          padding: '12px',
          zIndex: 4000,
        }}
        onClick={(e) => e.stopPropagation()}>
        <li
          className="d-flex flex-wrap"
          style={{
            gap: '8px',
            minWidth: '170px',
          }}>
          {children}
        </li>
      </ul>
    </div>
  );
}
