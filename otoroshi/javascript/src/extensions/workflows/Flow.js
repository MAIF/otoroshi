import React from 'react';

import { Node } from './flow/Node'
import { GroupNode } from './flow/GroupNode'
import { IfThenElseNode } from './flow/IfThenElseNode'
import { CustomEdge } from './flow/CustomEdge'
import { AddNode } from './nodes/AddNode'
import { ReactFlow, Background, Controls } from '@xyflow/react';
import '@xyflow/react/dist/style.css';

export function Flow({ nodes, onClick, edges, onNodesChange, onEdgesChange, onConnect, onConnectEnd, onGroupNodeClick, setRfInstance }) {

    return <div style={{ height: 'calc(100vh - 52px)' }} onClick={onClick}>
        <ReactFlow
            nodes={nodes}
            onInit={setRfInstance}
            onNodesChange={onNodesChange}
            edges={edges}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            onConnectEnd={onConnectEnd}
            fitView
            fitViewOptions={{
                padding: .5
            }}
            connectionLineType='simplebezier'
            nodeTypes={{
                simple: Node,
                group: GroupNode,
                IfThenElse: IfThenElseNode,
                AddNode: AddNode
            }}
            edgeTypes={{
                customEdge: CustomEdge,
            }}
            onNodeDoubleClick={(_, group) => onGroupNodeClick(group.data)}
        >
            <Background />
            <Controls orientation='horizontal' showInteractive={false} />
        </ReactFlow>
    </div>
}