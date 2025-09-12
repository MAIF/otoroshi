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

  useLayoutEffect(() => {
    console.log(props.data.highlighted_live)
    const sourceEl = document.querySelector(`[data-id="${props.id}"]`);
    if (props.data.highlighted_live) {
      sourceEl.style.outline = '2px ridge #47FF0F'
    } else {
      sourceEl.style.outline = null
    }
  }, [props.data.highlighted_live])

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
