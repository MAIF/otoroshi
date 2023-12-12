import React from 'react';

export function PillButton({
  rightEnabled,
  onChange,
  leftText,
  onLeftClick,
  rightText,
  onRightClick,
  style = {},
  className = '',
  pillButtonStyle = {},
}) {
  return (
    <div className={`d-flex justify-content-center ${className}`}>
      <div
        className="p-1"
        style={{
          borderRadius: '24px',
          backgroundColor: 'var(--bg-color_level2)',
          color: 'var(--color_level2)',
          position: 'relative',
          width: 'fit-content',
          ...style,
        }}
      >
        <div className={`pill-cursor ${rightEnabled ? '' : 'pill-mode-right'}`} />
        <button
          className="pill-mode"
          type="button"
          style={rightEnabled ? { ...pillButtonStyle, color: '#FFF' } : { ...pillButtonStyle }}
          onClick={() => (onLeftClick ? onLeftClick() : onChange(true))}
        >
          {leftText}
        </button>
        <button
          className="pill-mode"
          type="button"
          style={!rightEnabled ? { ...pillButtonStyle, color: '#FFF' } : { ...pillButtonStyle }}
          onClick={() => (onRightClick ? onRightClick() : onChange(false))}
        >
          {rightText}
        </button>
      </div>
    </div>
  );
}
