import React from 'react'

export default function Wrapper({ children, loading }) {
  if (!loading)
    return children

  return <div style={{ position: 'relative', ...(children.props?.style || {}), overflow: 'hidden' }}>
    {children}
    {loading && <div style={{
      position: 'absolute',
      inset: '0',
      zIndex: 100
    }} className='animate-loader' />}
  </div>
}