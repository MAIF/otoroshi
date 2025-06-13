import React, { forwardRef } from "react"
import { NodeResizer, Panel } from "@xyflow/react";
import NodeTrashButton from './NodeTrashButton';
import Handles from "./Handles";

export const GroupNode = forwardRef(
    (props, ref) => {
        const { selected, position, data } = props

        return <>
            <NodeResizer
                color="#ff0071"
                isVisible={selected}
                minWidth={100}
                minHeight={30}
            />

            <Handles {...props} />

            <Panel className="m-0 p-2 node-group-label" position={position}>
                {data.label} {data.name}
            </Panel>

            <NodeTrashButton {...props} />
        </>
    }
)
