
import React from 'react'

export function Node({ children }) {
    return <button className='d-flex items-center justify-center m-0 p-3 node'>
        {children}
    </button>
}