
import React from 'react'

export function Node({ children, data, onClick }) {
    return <button className='d-flex items-center justify-center m-0 p-3 node' onClick={() => onClick(data)}>
        {children}
    </button>
}