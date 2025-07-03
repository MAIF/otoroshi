import React, { forwardRef, useRef } from "react"
import { NodeResizer, Panel } from "@xyflow/react";
import NodeTrashButton from './NodeTrashButton';
import Handles from "./Handles";

export const GroupNode = forwardRef((props, ref) => {
    const { selected, position, data } = props
    const isFirst = data.isFirst

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