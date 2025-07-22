import React, { useEffect, useLayoutEffect } from 'react'

import Handles from './Handles'
import NodeTrashButton from './NodeTrashButton'
// import { NodeResizer } from '@xyflow/react'

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

            {/* {data.kind !== 'start' && !data.operator && <NodeResizer
                color="#ff0071"
                isVisible={props.selected}
                minWidth={200}
                minHeight={100}
            />} */}

            <button
                className="d-flex-center m-0 node"
                onDoubleClick={e => {
                    e.stopPropagation()
                    data.functions.onDoubleClick(props)
                }}>
                <div className='node-one-output d-flex-center'>
                    {data.operator ? <i className='fas fa-wrench' /> : (data.label || data.item?.label)} {data.name}
                </div>

                {data.nodeRenderer && data.nodeRenderer(props)}

                {data.kind !== 'returned' && <NodeTrashButton {...props} />}
            </button>
        </>
    );
}