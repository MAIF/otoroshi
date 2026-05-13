import React from 'react';

export function Badge({ text = "ALPHA" }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: '.5rem', marginBottom: 20 }}>
      <div style={{ padding: 5, borderRadius: 6, backgroundColor: 'rgb(249, 176, 0)', fontWeight: 'bolder', color: 'white', fontSize: 12, lineHeight: '0.8' }}>
        {text}
      </div>
    </div>
  )
}