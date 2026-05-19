import React, { useState, useRef, useEffect } from 'react';

export const SIDEBAR_MODES = [
  {
    key: 'expanded',
    label: 'Expanded',
    description: 'Sidebar always open',
    icon: 'fa-table-columns',
  },
  {
    key: 'collapsed',
    label: 'Collapsed',
    description: 'Sidebar always closed',
    icon: 'fa-bars',
  },
  {
    key: 'hover',
    label: 'Expand on hover',
    description: 'Open the sidebar on hover',
    icon: 'fa-arrow-pointer',
  },
];

export default function SidebarModeToggle({ mode, opened, onChange }) {
  const [menuOpen, setMenuOpen] = useState(false);
  const containerRef = useRef(null);

  const currentMode = SIDEBAR_MODES.find((m) => m.key === mode) || SIDEBAR_MODES[0];

  useEffect(() => {
    if (!menuOpen) return undefined;
    const handleClickOutside = (e) => {
      if (containerRef.current && !containerRef.current.contains(e.target)) {
        setMenuOpen(false);
      }
    };
    const handleKey = (e) => {
      if (e.key === 'Escape') setMenuOpen(false);
    };
    document.addEventListener('mousedown', handleClickOutside);
    document.addEventListener('keydown', handleKey);
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
      document.removeEventListener('keydown', handleKey);
    };
  }, [menuOpen]);

  const select = (key) => {
    setMenuOpen(false);
    if (key !== mode) onChange(key);
  };

  return (
    <div className="sidebar-mode-toggle" ref={containerRef}>
      {menuOpen && (
        <div className="sidebar-mode-menu" role="menu">
          <p className="sidebar-mode-menu-title">Sidebar control</p>
          {SIDEBAR_MODES.map((m) => (
            <button
              type="button"
              role="menuitemradio"
              aria-checked={m.key === mode}
              key={m.key}
              className={`sidebar-mode-menu-item ${m.key === mode ? 'is-active' : ''}`}
              onClick={() => select(m.key)}
            >
              <i className={`fas ${m.icon} sidebar-mode-menu-item-icon`} />
              <span className="sidebar-mode-menu-item-text">
                <span className="sidebar-mode-menu-item-label">{m.label}</span>
                <span className="sidebar-mode-menu-item-desc">{m.description}</span>
              </span>
              {m.key === mode && <i className="fas fa-check sidebar-mode-menu-item-check" />}
            </button>
          ))}
        </div>
      )}
      <button
        type="button"
        className="sidebar-mode-button"
        title="Sidebar control"
        aria-haspopup="true"
        aria-expanded={menuOpen}
        onClick={(e) => {
          e.stopPropagation();
          setMenuOpen((v) => !v);
        }}
      >
        <i className={`fas ${currentMode.icon} sidebar-mode-button-icon`} />
        {/* {opened && <span className="sidebar-mode-button-label">{currentMode.label}</span>} */}
        {/* {opened && <i className="fas fa-chevron-up sidebar-mode-button-caret" />} */}
      </button>
    </div>
  );
}
