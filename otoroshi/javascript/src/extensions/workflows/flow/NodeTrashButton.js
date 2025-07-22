import React from 'react'

export default function NodeTrashButton(props) {
    return <i className='fas fa-trash node-trash' onClick={e => {
        e.stopPropagation()
        props.onNodeDelete ? props.onNodeDelete() : props.data.functions.onNodeDelete(props.id)
    }} />
}