import React from 'react';

import { Node } from './Node'
import { CustomEdge } from './CustomEdge'
import { ReactFlow, Background, Controls } from '@xyflow/react';
import '@xyflow/react/dist/style.css';

export function Flow({ nodes, onClick, edges, onNodesChange, onEdgesChange, onConnect }) {
    return <div style={{ height: 'calc(100vh - 52px)' }} onClick={onClick}>
        <ReactFlow
            nodes={nodes}
            onNodesChange={onNodesChange}
            edges={edges}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            fitView
            fitViewOptions={{
                padding: 1
            }}
            nodeTypes={{
                simple: Node
            }}
            edgeTypes={{
                customEdge: CustomEdge,
            }}
        >
            <Background />
            <Controls />
        </ReactFlow>
    </div>
}