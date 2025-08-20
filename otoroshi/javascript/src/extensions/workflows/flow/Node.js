import React, { useEffect, useLayoutEffect } from 'react'

import Handles from './Handles'
import NodeTrashButton from './NodeTrashButton'

export function Node(props) {
    const { data } = props

    useLayoutEffect(() => {
        const sourceEl = document.querySelector(`[data-id="${props.id}"]`);

        if (data.operator)
            sourceEl?.classList.add('operator')
    })

    return (
        <>
            <Handles {...props} />

            <button
                className="d-flex-center m-0 node"
                onDoubleClick={e => {
                    e.stopPropagation()
                    data.functions.onDoubleClick(props)
                }}>
                <div className='node-one-output d-flex-center'>
                    {data.operator ? <i className='fas fa-wrench' /> : <i className={(data.label || data.item?.label || data.icon)} />} {data.display_name || data.name}
                </div>

                {data.nodeRenderer && data.nodeRenderer(props)}

                {props.id !== 'returned-node' && <NodeTrashButton {...props} />}

                <div className='node-description'>{props.data.description}</div>
            </button>
        </>
    );
}