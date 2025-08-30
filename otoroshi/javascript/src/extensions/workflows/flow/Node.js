import React, { useEffect, useLayoutEffect, useState } from 'react';

import Handles from './Handles';
import NodeTrashButton from './NodeTrashButton';
import { getNodeFromKind } from '../models/Functions';

export function Node(props) {
  const { data } = props;

  useLayoutEffect(() => {
    const sourceEl = document.querySelector(`[data-id="${props.id}"]`);

    if (data.operators) sourceEl?.classList.add('operator');
  });

  let label = data.label || data.item?.label || data.icon;
  let name = data.display_name || data.name;

  if (data.content?.function) {
    const functionData = getNodeFromKind(data.content.function);
    if (functionData) {
      label = functionData.icon;
      name = functionData.display_name;
    }
  }

  return (
    <>
      <Handles {...props} />

      <button
        className="d-flex-center m-0 node"
        onDoubleClick={(e) => {
          e.stopPropagation();
          data.functions.onDoubleClick(props);
        }}
      >
        <div className="node-one-output d-flex-center">
          {data.operators ? <i className="fas fa-wrench" /> : <i className={label} />} {name}
        </div>

        {data.nodeRenderer && data.nodeRenderer(props)}

        {props.id !== 'returned-node' && <NodeTrashButton {...props} />}

        <div className="node-description">{data.information.description}</div>
      </button>
    </>
  );
}
