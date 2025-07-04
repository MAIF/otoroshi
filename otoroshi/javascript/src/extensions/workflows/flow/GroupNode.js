import React, { forwardRef, useLayoutEffect, useRef } from "react"
import { NodeResizer, Panel } from "@xyflow/react";
import NodeTrashButton from './NodeTrashButton';
import Handles from "./Handles";

export const GroupNode = forwardRef((props, ref) => {
    const { selected, position, data } = props
    const isFirst = data.isFirst

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
        <NodeResizer
            color="#ff0071"
            isVisible={selected}
            minWidth={200}
            minHeight={100}
        />

        <div className={`${isFirst ? 'node--first' : ''}`} />

        <Handles {...props} />

        {data.nodeRenderer && data.nodeRenderer(props)}

        <Panel className="m-0 node-one-output" position={position}>
            {data.label} {data.name}
        </Panel>

        <NodeTrashButton {...props} />
    </>
})