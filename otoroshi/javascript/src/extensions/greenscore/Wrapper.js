import React, { useEffect, useState } from 'react'

export default function Wrapper({ children, ...props }) {
  const [loading, setLoading] = useState(props.loading === undefined ? true : props.loading);

  useEffect(() => {
    // setTimeout(() => {
    //   setLoading(false)
    // }, 1000)
  }, [])

  if (!loading)
    return children

  return <div style={{ position: 'relative', ...children.props.style, overflow: 'hidden' }}>
    {children}
    {loading && <div style={{
      position: 'absolute',
      inset: '0',
      zIndex: 100
    }} className='animate-loader' />}
  </div>
}