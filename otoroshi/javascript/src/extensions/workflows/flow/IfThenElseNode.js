import React, { forwardRef, useEffect } from "react"
import { useNodes } from "@xyflow/react";
import { GroupNode } from "./GroupNode";

export const IfThenElseNode = forwardRef(
    (props, _) => {
        useEffect(() => {
            const parent = document.querySelector(`[data-id="${props.id}"]`);
            parent?.classList.add('react-flow__node-group')
        }, [])

        return <>
            <GroupNode {...props}>
                {/* <AddNodeWrapper  {...props} title="If" maxChildren={3} />
                <AddNodeWrapper  {...props} title="Then" maxChildren={3} />
                <AddNodeWrapper  {...props} title="Else" maxChildren={3} /> */}
            </GroupNode>
        </>
    }
)
