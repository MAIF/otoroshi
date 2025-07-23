import React, { forwardRef, useLayoutEffect, useRef } from "react"
import { Panel } from "@xyflow/react";
import NodeTrashButton from './NodeTrashButton';
import Handles from "./Handles";

export const GroupNode = forwardRef((props, ref) => {
    const { selected, position, data } = props

    useLayoutEffect(() => {
        const height = props.data.height
        if (height) {
            const sourceEl = document.querySelector(`[data-id="${props.id}"]`);
            setTimeout(() => {
                sourceEl.style.height = height()
            }, 150)
        }
    }, [])

    return <>
        <Handles {...props} />

        {data.nodeRenderer && data.nodeRenderer(props)}

        <Panel className="m-0 node-one-output" position={position}>
            {data.label} {data.name}
        </Panel>

        <NodeTrashButton {...props} />
    </>
})