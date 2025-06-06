
import React, { useEffect } from 'react'
import {
    BaseEdge,
    EdgeLabelRenderer,
    getStraightPath,
    useReactFlow,
} from '@xyflow/react';

const listeners = id => {
    const sourceEl = document.querySelector(`[data-id="${id}"]`);
    const labelEl = document.getElementById(`react-flow__edgelabel-renderer-data-${id}`);

    const edgeEl = document.querySelector(`.react-flow__edge[data-id="${id}"]`);

    const childrenEdge = document.querySelector(`.react-flow__edge[data-id="${id}"] .react-flow__edge-path`);

    const actions = [...(labelEl.getElementsByClassName('node-one-output-add') || [])]

    const actionOnEnter = () => onEnter()
    const actionOnLeave = () => onLeave()

    actions.forEach(action => {
        action.addEventListener('mouseenter', actionOnEnter)
        action.addEventListener('mouseleave', actionOnLeave)
    })

    let timeoutId;

    const onEnter = () => {
        clearTimeout(timeoutId);
        labelEl?.classList.add('edge-content-hovered');
        childrenEdge?.classList.add('react-flow__edge-hover')
    };

    const onLeave = () => {
        timeoutId = setTimeout(() => {
            labelEl?.classList.remove('edge-content-hovered')
            childrenEdge?.classList.remove('react-flow__edge-hover')
        }, 1000)
    }

    sourceEl?.addEventListener('mouseenter', onEnter);
    sourceEl?.addEventListener('mouseleave', onLeave);

    edgeEl?.addEventListener('mouseenter', onEnter);
    edgeEl?.addEventListener('mouseleave', onLeave);

    return () => {
        sourceEl?.removeEventListener('mouseenter', onEnter);
        sourceEl?.removeEventListener('mouseleave', onLeave);
        edgeEl?.removeEventListener('mouseenter', onEnter);
        edgeEl?.removeEventListener('mouseleave', onLeave);

        actions.forEach(action => {
            action.removeEventListener('mouseenter', actionOnEnter)
            action.removeEventListener('mouseleave', actionOnLeave)
        })

        clearTimeout(timeoutId);
    };
}

export function CustomEdge({ id, sourceX, sourceY, targetX, targetY }) {
    const { setEdges } = useReactFlow();
    const [edgePath, labelX, labelY] = getStraightPath({
        sourceX,
        sourceY,
        targetX,
        targetY,
    });

    useEffect(() => listeners(id), [id]);

    return (
        <>
            <BaseEdge id={id} path={edgePath} />
            <EdgeLabelRenderer>
                <div
                    style={{
                        position: 'absolute',
                        transform: `translate(-50%, -50%) translate(${labelX}px,${labelY}px)`,
                        pointerEvents: 'all',
                    }}
                    className="nodrag nopan d-flex-center gap-1 edge-label-renderer"
                    id={`react-flow__edgelabel-renderer-data-${id}`}
                >
                    <div className='node-one-output-add'
                        onClick={e => {
                            e.stopPropagation()

                        }}>
                        <i className='fas fa-plus' />
                    </div>
                    <div className='node-one-output-add' onClick={e => {
                        e.stopPropagation()
                        setEdges((es) => es.filter((e) => e.id !== id));
                    }}>
                        <i className='fas fa-trash' />
                    </div>
                </div>
            </EdgeLabelRenderer >
        </>
    );
}