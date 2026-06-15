import React from 'react';

export function PillButton({
  rightEnabled,
  onChange,
  leftText,
  onLeftClick,
  rightText,
  onRightClick,
  style = {},
  containerStyle = {},
  className = '',
  pillButtonStyle = {},
}) {
  return (
    <div
      className={`d-flex justify-content-center ${className}`}
      style={{
        maxWidth: 1000,
        ...containerStyle,
      }}
    >
      <div
        className="p-1"
        style={{
          borderRadius: '24px',
          // backgroundColor: 'var(--bg-color_level3)',
          border: '1px solid var(--input-border)',
          color: 'var(--color_level3)',
          position: 'relative',
          width: 'fit-content',
          ...style,
        }}
      >
        <div className={`pill-cursor ${rightEnabled ? '' : 'pill-mode-right'}`} />
        <button
          className="pill-mode"
          type="button"
          style={pillButtonStyle}
          onClick={() => (onLeftClick ? onLeftClick() : onChange(true))}
        >
          {leftText}
        </button>
        <button
          className="pill-mode"
          type="button"
          style={pillButtonStyle}
          onClick={() => (onRightClick ? onRightClick() : onChange(false))}
        >
          {rightText}
        </button>
      </div>
    </div>
  );
}
