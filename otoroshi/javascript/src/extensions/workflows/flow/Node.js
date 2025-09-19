import React, { useEffect, useLayoutEffect, useRef } from 'react';

import Handles from './Handles';
import NodeTrashButton from './NodeTrashButton';
import { getNodeFromKind } from '../models/Functions';
import { useStore } from '@xyflow/react';

export const getNodeStyles = zoom => {
  const factor = .5 / zoom
  const padding = factor / 2 < .25 ? .25 : factor / 2
  let offset = (-0.6 * factor) > -.5 ? -.5 : (-0.6 * factor)

  if (offset < -1)
    offset = -1

  return {
    '--loading-top': `${offset}rem`,
    '--loading-left': `${offset}rem`,
    '--loading-right': `${offset}rem`,
    '--loading-bottom': `${offset}rem`,
    '--loading-radius': `1rem`,
    '--loading-padding': `${Math.min(.4, padding)}rem`
  }
}

export function Node(props) {
  const { data } = props;

  useLayoutEffect(() => {
    const sourceEl = document.querySelector(`[data-id="${props.id}"]`);

    if (data.operators) sourceEl?.classList.add('operator');
  }, []);

  let label = data.label || data.item?.label || data.icon;
  let name = data.display_name || data.name;
  let nodeRenderer;

  if (data.content?.function) {
    const functionData = getNodeFromKind(data.content.function) || getNodeFromKind(data.content.function.substring(5));
    if (functionData) {
      label = functionData.icon;
      name = functionData.display_name || functionData.name;
      nodeRenderer = functionData.nodeRenderer
    }
  }

  const ref = useRef()

  const highlightRef = useRef(data.highlighted)
  highlightRef.current = data.highlighted

  const addClassList = () => {
    if (!highlightRef.current) {
      ref.current?.classList.remove("node--successfull'")
      ref.current?.classList.remove("loading-gradient")
    }
    else if (highlightRef.current === 'END') {
      ref.current?.classList.add('node--successfull')
      ref.current?.classList.remove("loading-gradient")
    }
    else {
      if (props.id === 'start') {
        ref.current?.classList.add('loading-gradient--start')
      }
      ref.current?.classList.add('loading-gradient')
    }
  }

  useLayoutEffect(() => {
    if (data.highlighted) {
      if (props.id === 'start') {
        addClassList()
      } else
        setTimeout(addClassList, (data.highlighted) * 1000)
    } else {
      addClassList()
    }
  }, [data.highlighted])

  const zoom = useStore((state) => state.transform[2]);
  const styles = getNodeStyles(zoom)

  return (
    <>
      <Handles {...props} />

      <button
        ref={ref}
        className="d-flex-center m-0 node"
        style={{
          animationDelay: `${data.highlighted_loading}s`,
          ...styles
        }}
        onDoubleClick={(e) => {
          e.stopPropagation();
          data.functions.onDoubleClick(props);
        }}
      >
        <div className="node-one-output d-flex-center">
          {data.operators ? <i className="fas fa-wrench" /> : <i className={label} />} {name} {data?.content?.breakpoint ? <span style={{ color: 'red' }}>(breakpoint <i className="fas fa-bug" />)</span> : ''}
        </div>

        {nodeRenderer && nodeRenderer(props)}

        {data.nodeRenderer && data.nodeRenderer(props)}

        {props.id !== 'returned-node' && props.id !== 'start' && <NodeTrashButton {...props} />}

        <div className="node-description">{data.information.description}</div>
      </button>
    </>
  );
}
