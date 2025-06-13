import React from 'react'

export const AddNode = (name) => {
    return {
        label: <i className='fas fa-plus' />,
        name: name,
        kind: 'addnode',
        type: 'AddNode'
    }
}