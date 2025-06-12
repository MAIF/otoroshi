import React from 'react';

import { Node } from './Node'
import { GroupNode } from './GroupNode'
import { CustomEdge } from './CustomEdge'
import { ReactFlow, Background, Controls, useReactFlow } from '@xyflow/react';
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
            nodeTypes={{
                simple: Node,
                group: GroupNode
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