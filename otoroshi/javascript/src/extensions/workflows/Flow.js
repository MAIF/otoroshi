import React from 'react';

import { Node } from './flow/Node';
import { GroupNode } from './flow/GroupNode';
import { CustomEdge } from './flow/CustomEdge';
import { ReactFlow, Background, Controls } from '@xyflow/react';
import '@xyflow/react/dist/style.css';

export function Flow({
  nodes,
  onClick,
  edges,
  onNodesChange,
  onEdgesChange,
  onConnect,
  onConnectEnd,
  autoLayout,
  onGroupNodeClick,
}) {
  return (
    <div style={{ height: 'calc(100vh - 52px)' }} onClick={onClick}>
      <ReactFlow
        nodes={nodes}
        onNodesChange={onNodesChange}
        edges={edges}
        onEdgesChange={onEdgesChange}
        onConnect={onConnect}
        onConnectEnd={onConnectEnd}
        fitView
        minZoom={0.1}
        maxZoom={2}
        connectionLineType="simplebezier"
        nodeTypes={{
          simple: Node,
          group: GroupNode,
        }}
        edgeTypes={{
          customEdge: CustomEdge,
        }}
        onNodeDoubleClick={(_, group) => onGroupNodeClick(group)}
        onClick={e => {
          e.preventDefault()
          e.stopPropagation()
        }}
      >
        <Background />
        <Controls orientation="horizontal" showInteractive={false}>
          <button
            className="react-flow__controls-button react-flow__controls-fitview"
            onClick={autoLayout}
          >
            <i className="fas fa-hat-wizard" />
          </button>
        </Controls>
      </ReactFlow>
    </div>
  );
}
