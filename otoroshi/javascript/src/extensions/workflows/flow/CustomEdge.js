import React, { useEffect, useMemo } from 'react';
import {
  BaseEdge,
  EdgeLabelRenderer,
  getSimpleBezierPath,
  useReactFlow,
} from '@xyflow/react';
// import { useSignalValue } from 'signals-react-safe';
// import { edgeHighlights } from '../WorkflowsDesigner';

const listeners = (id) => {
  const sourceEl = document.querySelector(`[data-id="${id}"]`);
  const labelEl = document.getElementById(`react-flow__edgelabel-renderer-data-${id}`);

  const edgeEl = document.querySelector(`.react-flow__edge[data-id="${id}"]`);

  const childrenEdge = document.querySelector(
    `.react-flow__edge[data-id="${id}"] .react-flow__edge-path`
  );

  const actions = [...(labelEl.getElementsByClassName('node-one-output-add') || [])];

  const actionOnEnter = () => onEnter();
  const actionOnLeave = () => onLeave();

  actions.forEach((action) => {
    action.addEventListener('mouseenter', actionOnEnter);
    action.addEventListener('mouseleave', actionOnLeave);
  });

  let timeoutId;

  const onEnter = () => {
    clearTimeout(timeoutId);
    labelEl?.classList.add('edge-content-hovered');
    childrenEdge?.classList.add('react-flow__edge-hover');
  };

  const onLeave = () => {
    timeoutId = setTimeout(() => {
      labelEl?.classList.remove('edge-content-hovered');
      childrenEdge?.classList.remove('react-flow__edge-hover');
    }, 1000);
  };

  sourceEl?.addEventListener('mouseenter', onEnter);
  sourceEl?.addEventListener('mouseleave', onLeave);

  edgeEl?.addEventListener('mouseenter', onEnter);
  edgeEl?.addEventListener('mouseleave', onLeave);

  return () => {
    sourceEl?.removeEventListener('mouseenter', onEnter);
    sourceEl?.removeEventListener('mouseleave', onLeave);
    edgeEl?.removeEventListener('mouseenter', onEnter);
    edgeEl?.removeEventListener('mouseleave', onLeave);

    actions.forEach((action) => {
      action.removeEventListener('mouseenter', actionOnEnter);
      action.removeEventListener('mouseleave', actionOnLeave);
    });

    clearTimeout(timeoutId);
  };
};

function bezierPathLength(d, steps = 10) {
  // Extract numbers from path string
  const nums = d.match(/-?\d+(\.\d+)?/g).map(Number);

  if (nums.length !== 8) {
    throw new Error("Expected path like Mx,y Cx1,y1 x2,y2 x3,y3");
  }

  const [x0, y0, x1, y1, x2, y2, x3, y3] = nums;

  function x(t) {
    return (
      Math.pow(1 - t, 3) * x0 +
      3 * Math.pow(1 - t, 2) * t * x1 +
      3 * (1 - t) * Math.pow(t, 2) * x2 +
      Math.pow(t, 3) * x3
    );
  }

  function y(t) {
    return (
      Math.pow(1 - t, 3) * y0 +
      3 * Math.pow(1 - t, 2) * t * y1 +
      3 * (1 - t) * Math.pow(t, 2) * y2 +
      Math.pow(t, 3) * y3
    );
  }

  let length = 0;
  let prevX = x(0);
  let prevY = y(0);

  for (let i = 1; i <= steps; i++) {
    const t = i / steps;
    const currX = x(t);
    const currY = y(t);
    const dx = currX - prevX;
    const dy = currY - prevY;
    length += Math.sqrt(dx * dx + dy * dy);
    prevX = currX;
    prevY = currY;
  }

  return length;
}


export function CustomEdge({ id, sourceX, sourceY, targetX, targetY, data }) {
  const { setEdges } = useReactFlow();
  const [edgePath, labelX, labelY] = getSimpleBezierPath({
    sourceX,
    sourceY,
    targetX,
    targetY,
  });

  useEffect(() => listeners(id), [id]);

  const { highlighted, error } = data || {}

  const length = useMemo(() => bezierPathLength(edgePath), [sourceX, sourceY, targetX, targetY])

  return (
    <>
      <BaseEdge
        id={id}
        path={edgePath}
        className={error ? 'edge--error' : highlighted ? 'animate-edge' : ''}
        style={{
          animationDelay: `${(highlighted || error) || .1}s`,
          strokeDasharray: highlighted || error ? length : 'initial',
          strokeDashoffset: highlighted || error ? length : 'initial',
        }}
      />
      <EdgeLabelRenderer>
        <div
          style={{
            position: 'absolute',
            transform: `translate(-50%, -120%) translate(${labelX}px,${labelY}px)`,
            pointerEvents: 'all'
          }}
          className="nodrag nopan d-flex-center gap-1 edge-label-renderer"
          id={`react-flow__edgelabel-renderer-data-${id}`}
        >
          <div
            className="node-one-output-add"
            onClick={(e) => {
              e.stopPropagation();
              setEdges((es) => es.filter((e) => e.id !== id));
            }}
          >
            <i className="fas fa-trash" />
          </div>
        </div>
      </EdgeLabelRenderer>
    </>
  );
}
