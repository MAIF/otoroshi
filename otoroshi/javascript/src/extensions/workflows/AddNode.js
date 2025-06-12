import React from 'react'

export function AddNode(props) {
    return <div className='node-one-output-add node-add--large' onClick={e => {
        e.stopPropagation()
        props.data.functions.openNodesExplorer(props)
    }}>
        <i className='fas fa-plus' />
    </div>
}