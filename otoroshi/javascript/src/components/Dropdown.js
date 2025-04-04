import React from 'react';

export function Dropdown({ children, className = '', style = {}, buttonStyle, h100 = true }) {
  return (
    <div className={`dropdown ${className}`} style={style}>
      <button
        className={`btn btn-sm toggle-form-buttons d-flex align-items-center dark-background ${h100 ? 'h-100' : ''}`}
        style={{
          backgroundColor: 'var(--bg-color_level2)',
          color: 'var(--color_level2)',
          ...(buttonStyle || {}),
        }}
        id="menu"
        data-bs-toggle="dropdown"
        data-bs-auto-close="true"
        aria-expanded="false"
      >
        <i className="fas fa-ellipsis-h" style={{ fontSize: '1.33333em' }} />
      </button>
      <ul
        className="dropdown-menu"
        aria-labelledby="menu"
        style={{
          backgroundColor: 'var(--bg-color_level2)',
          color: 'var(--color_level2)',
          border: '1px solid',
          borderColor: 'var(--bg-color_level2)',
          borderTop: 0,
          padding: '12px',
          zIndex: 4000,
        }}
        onClick={(e) => e.stopPropagation()}
      >
        <li
          className="d-flex flex-wrap"
          style={{
            gap: '8px',
            minWidth: '170px',
          }}
        >
          {children}
        </li>
      </ul>
    </div>
  );
}
