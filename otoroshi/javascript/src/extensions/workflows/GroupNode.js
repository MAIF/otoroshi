import React, { forwardRef } from "react"
import { Handle, NodeResizer, Panel, Position, useNodeConnections, useNodes } from "@xyflow/react";
import { Node } from './Node'
import { AddNode } from "./AddNode";
import { NODES } from './models/Functions';

export const GroupNode = forwardRef(
    (props, ref) => {
        const { selected, label, position, data, ...rest } = props

        // const internalNode = data.workflow.node
        // const internalNodeData = internalNode?.kind ? NODES[internalNode.kind.toLowerCase()](internalNode) : {}

        const isSelected = false
        const isFirst = data.isFirst
        const isLast = data.isLast

        const connections = useNodeConnections();

        const nodes = useNodes()
        const hasChild = nodes.find(node => node.parentId === props.id)

        // console.log(props.id, hasChild)

        return <>
            <NodeResizer
                color="#ff0071"
                isVisible={selected}
                minWidth={100}
                minHeight={30}
            />
            {!isFirst && <Handle type="source" position={Position.Left} />}
            <Panel className="m-0 p-2 node-group-label" position={position}>
                {data.label} {data.name}
            </Panel>
            <i className='fas fa-trash node-trash' onClick={e => {
                e.stopPropagation()
                props.handleDeleteNode ? props.handleDeleteNode() : data.functions.handleDeleteNode(props.id)
            }} />
            {!hasChild && <AddNode {...props} isInternalNode />}

            {!isLast && <Handle type="target" position={Position.Right} id="a" />}

            {connections.length < 2 && !isLast &&
                <div className='node-one-output-dot' onClick={e => {
                    e.stopPropagation()
                    data.functions.openNodesExplorer(props)
                }}>
                    <div className='node-one-output-bar'></div>
                    <div className='node-one-output-add'>
                        <i className='fas fa-plus' />
                    </div>
                </div>}
        </>
    }
)
