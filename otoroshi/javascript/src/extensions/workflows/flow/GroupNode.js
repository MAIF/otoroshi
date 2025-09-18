import React, { useEffect, useLayoutEffect, useRef } from 'react';
import { Panel, useStore } from '@xyflow/react';
import NodeTrashButton from './NodeTrashButton';
import Handles from './Handles';
import { getNodeStyles } from './Node';

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

  const highlightRef = useRef(data.highlighted)

  useEffect(() => {
    highlightRef.current = data.highlighted
  }, [data.highlighted])

  const zoom = useStore((state) => state.transform[2]);
  const styles = getNodeStyles(zoom)

  useLayoutEffect(() => {
    const el = document.querySelector(`[data-id="${props.id}"]`)
    Object
      .entries(styles)
      .forEach(([key, value]) => el.style.setProperty(key, value))
  }, [zoom])

  const highlight = () => {
    if (highlightRef.current !== "END") {
      const el = document.querySelector(`[data-id="${props.id}"]`)
      el
        .classList
        .add('loading-gradient')
    }
  }

  useLayoutEffect(() => {
    const sourceEl = document.querySelector(`[data-id="${props.id}"]`);

    if (data.highlighted) {
      if (data.highlighted === 'END') {
        sourceEl.classList.remove("loading-gradient")
        sourceEl.classList.add('node--successfull')
      } else {
        setTimeout(highlight, ((data.highlighted)) * 1000)
      }
    } else {
      sourceEl.classList.remove("node--successfull")
      sourceEl.classList.remove("loading-gradient")
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
