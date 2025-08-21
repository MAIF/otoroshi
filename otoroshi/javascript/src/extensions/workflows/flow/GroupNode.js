import React, { useLayoutEffect, useRef } from "react"
import { Panel } from "@xyflow/react";
import NodeTrashButton from './NodeTrashButton';
import Handles from "./Handles";

export const GroupNode = (props) => {
    const { position, data } = props

    useLayoutEffect(() => {
        const height = props.data.height
        if (height) {
            const sourceEl = document.querySelector(`[data-id="${props.id}"]`);
            setTimeout(() => {
                sourceEl.style.height = height(props.data)
            }, 150)
        }
    }, [])

    return <>
        <Handles {...props} />

        {data.nodeRenderer && data.nodeRenderer(props)}

        <Panel className="m-0 node-one-output" position={position}>
            <i className={data.label || data.icon} /> {data.display_name || data.name}
        </Panel>

        <NodeTrashButton {...props} />

        <div className='node-description'>{props.data.description}</div>
    </>
}