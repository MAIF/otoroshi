import React from 'react';

export function PillButton({
  rightEnabled,
  onChange,
  leftText,
  onLeftClick,
  rightText,
  onRightClick,
  style = {},
  className = "" }) {
  return (<div className={`d-flex justify-content-center ${className}`}>
    <div
      className='p-1'
      style={{
        borderRadius: '24px',
        backgroundColor: '#373735',
        position: 'relative',
        width: 'fit-content',
        ...style
      }}>
      <div className={`pill-cursor ${rightEnabled ? '' : 'pill-mode-right'}`} />
      <button
        className="pill-mode"
        type="button"
        onClick={() => onLeftClick ? onLeftClick() : onChange(true)}>
        {leftText}
      </button>
      <button
        className="pill-mode"
        type="button"
        onClick={() => onRightClick ? onRightClick() : onChange(false)}>
        {rightText}
      </button>
    </div>
  </div>)
}