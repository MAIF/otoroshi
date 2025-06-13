import React from 'react'

export default function NodeTrashButton(props) {
    return <i className='fas fa-trash node-trash' onClick={e => {
        e.stopPropagation()
        props.handleDeleteNode ? props.handleDeleteNode() : props.data.functions.handleDeleteNode(props.id)
    }} />
}