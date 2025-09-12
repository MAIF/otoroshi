import React, { useEffect, useLayoutEffect, useRef } from 'react';
import { Panel } from '@xyflow/react';
import NodeTrashButton from './NodeTrashButton';
import Handles from './Handles';

export const GroupNode = (props) => {
  const { position, data } = props;

  useLayoutEffect(() => {
    const height = props.data.height;

    const sourceEl = document.querySelector(`[data-id="${props.id}"]`);
    if (height) {
      setTimeout(() => {
        sourceEl.style.height = height(props.data);
      }, 150);
    }
  }, [props.data]);


  // const highlighted = useSignalValue(nodeHighlights).get(props.id)

  const highlightRef = useRef(data.highlighted)

  useEffect(() => {
    highlightRef.current = data.highlighted
  }, [data.highlighted])

  const highlight = () => {
    if (highlightRef.current !== "END")
      document.querySelector(`[data-id="${props.id}"]`).classList.add('loading-gradient')
  }

  useLayoutEffect(() => {
    const sourceEl = document.querySelector(`[data-id="${props.id}"]`);

    if (data.highlighted) {
      if (data.highlighted === 'END') {
        sourceEl.classList.remove("loading-gradient")
      } else {
        setTimeout(highlight, (data.highlighted + 1.25) * 1000)
      }
    }
  }, [data.highlighted])

  return (
    <>
      <Handles {...props} />

      {data.nodeRenderer && data.nodeRenderer(props)}

      <Panel className="m-0 node-one-output" position={position}>
        <i className={data.label || data.icon} /> {data.display_name || data.name}
      </Panel>

      <NodeTrashButton {...props} />

      <div className="node-description">{props.data.information.description}</div>
    </>
  );
};
