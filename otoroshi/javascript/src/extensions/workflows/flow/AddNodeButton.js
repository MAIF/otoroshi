import { Panel } from '@xyflow/react'
import React from 'react'

export function AddNodeButton(props) {
    return <div style={{ position: 'relative' }}>
        {props.title && <Panel className="m-0 px-2 py-1 node-group-label" position={props.position}>
            {props.title}
        </Panel>}
        <div className='node-one-output-add node-add--large' onClick={e => {
            e.stopPropagation()
            props.data.functions.openNodesExplorer(props)
        }}>
            <i className='fas fa-plus' />
        </div>
    </div>
}